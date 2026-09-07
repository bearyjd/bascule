package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.ScaleDecoder
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The listening half of a [GattSession]: E7's first-indication wait, E17's
 * body-composition correlation window, the emission and post-emission idle,
 * and the flush-on-every-exit rule that keeps a decoded weight from being lost
 * to a dropped link.
 *
 * Extracted from [GattSession] unchanged in behaviour once that file passed
 * 1000 lines. It owns the decoder for the duration of the measurement phase
 * and the one piece of state the hard ceiling needs — the reading already
 * emitted — but not the link: a drop mid-listen is handed back through
 * [onDropped], because reconnecting re-runs discovery and the handshake, which
 * are the session's business.
 */
internal class MeasurementPhase(
    private val decoder: ScaleDecoder,
    private val log: (String) -> Unit,
    private val onDropped: suspend (Channel<TransportEvent>) -> SessionOutcome,
) {

    /**
     * Set the moment [MeasurementCorrelator][com.ventouxlabs.bascule.ble.decoders.MeasurementCorrelator]
     * produces a reading, before [finishEmission]'s idle wait. Once that has
     * happened the decoder has nothing left to flush, so the reading exists
     * only here — see [outcomeAtCeiling].
     */
    private var emittedReading: com.ventouxlabs.bascule.ble.ScaleReading? = null

    /**
     * One event loop, used for both measurement windows. They differ only in
     * budget and in whether a frame that left the decoder holding a weight for
     * correlation ends the wait: the first window stops there so the caller can
     * open the shorter correlation window, the second keeps listening until
     * something stable arrives.
     *
     * Returns null on timeout. [onMalformed] is a callback rather than a return
     * value because the count accumulates across both windows.
     */
    private suspend fun awaitMeasureStep(
        events: Channel<TransportEvent>,
        budget: Duration,
        stopAfterFirstFrame: Boolean,
        onMalformed: () -> Unit,
    ): MeasureStep? = withTimeoutOrNull(budget) {
        receiveMeasureStep(events, stopAfterFirstFrame, onMalformed)
    }

    /** See [receiveSubscriptionOutcome] for why this is a function, not a lambda. */
    private suspend fun receiveMeasureStep(
        events: Channel<TransportEvent>,
        stopAfterFirstFrame: Boolean,
        onMalformed: () -> Unit,
    ): MeasureStep {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return MeasureStep.AdapterOff
                is TransportEvent.ConnectionStateChanged -> if (!event.connected) return MeasureStep.Dropped
                is TransportEvent.CharacteristicChanged -> {
                    decodeMeasurementFrame(event, onMalformed)?.let { return it }
                    // E7/E17: hand over to the much shorter correlation window
                    // only once the decoder is actually holding a weight
                    // awaiting its body-composition pair. Ending the 45 s wait
                    // on any other frame — an unknown characteristic, a
                    // malformed one, or a body-composition frame that merely
                    // arrived before its weight, which is a routine ordering
                    // and not a fault — would leave 4 s for a weigh-in the
                    // BF720 does not report as stable until 8-15 s in.
                    if (stopAfterFirstFrame && decoder.hasPendingCorrelation) return MeasureStep.Pending
                }
                is TransportEvent.ServicesDiscovered,
                is TransportEvent.WriteComplete,
                is TransportEvent.SubscriptionEnabled,
                is TransportEvent.MtuChanged,
                is TransportEvent.BondStateChanged,
                -> Unit // transport plumbing; keeps waiting on the same budget
            }
        }
    }

    /** Non-null only when this frame is itself the end of the wait. */
    private fun decodeMeasurementFrame(
        event: TransportEvent.CharacteristicChanged,
        onMalformed: () -> Unit,
    ): MeasureStep? {
        val decoded = decoder.onNotification(event.char, event.value)
        // Every frame, not just the interesting ones: "the scale sent nothing"
        // and "the scale sent something we did not understand" are different
        // diagnoses, and this is the only place that can tell them apart.
        log("frame on ${event.char} (${event.value.size} bytes) -> ${decoded::class.simpleName}")
        return classifyDecoded(decoded, onMalformed)
    }

    private fun classifyDecoded(decoded: DecodeEvent, onMalformed: () -> Unit): MeasureStep? = when (decoded) {
        is DecodeEvent.Stable -> MeasureStep.Reading(decoded.reading)

        is DecodeEvent.Malformed -> {
            onMalformed()
            null
        }

        DecodeEvent.SessionComplete -> readingFromFlush()?.let { MeasureStep.Reading(it) }

        // A frame the decoder consumed without completing anything: buffered
        // for correlation, an unknown characteristic (E11), a live weight the
        // BF720 never sends, or a handshake ack arriving after consent.
        DecodeEvent.Ignored,
        is DecodeEvent.Live,
        is DecodeEvent.RegistrationResult,
        is DecodeEvent.ConsentResult,
        -> null
    }

    /**
     * E7 + E17. The 45 s wait for a first frame, then — and only once the
     * decoder is holding a weight for correlation — the short window for its
     * body-composition pair.
     */
    suspend fun run(events: Channel<TransportEvent>): SessionOutcome {
        var malformed = 0
        val onMalformed: () -> Unit = { malformed++ }

        val first = awaitMeasureStep(
            events,
            SessionBudget.FIRST_INDICATION_TIMEOUT,
            stopAfterFirstFrame = true,
            onMalformed,
        ) ?: return flushOrElse(noMeasurement(malformed))

        if (first !is MeasureStep.Pending) return settle(events, first) { noMeasurement(malformed) }

        val paired = awaitMeasureStep(
            events,
            SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW,
            stopAfterFirstFrame = false,
            onMalformed,
        ) ?: return flushOrElse(noMeasurement(malformed))

        return settle(events, paired) { noMeasurement(malformed) }
    }

    private suspend fun settle(
        events: Channel<TransportEvent>,
        step: MeasureStep,
        fallback: () -> SessionOutcome,
    ): SessionOutcome = when (step) {
        is MeasureStep.Reading -> {
            log("stable reading decoded: ${step.reading.weightKg} kg, user ${step.reading.userIndex}")
            finishEmission(events, step.reading)
        }
        MeasureStep.AdapterOff -> flushOrElse(SessionOutcome.Missed(MissReason.ADAPTER_OFF))
        MeasureStep.Dropped -> onDropped(events)
        MeasureStep.Pending -> flushOrElse(fallback())
    }

    /**
     * E17, and the reason [flush] is reachable from *every* terminal path out
     * of the measurement phase rather than only from the correlation window's
     * own timeout. A weight the decoder has already decoded and attributed is a
     * real measurement; losing it because the link dropped 1 s into the pairing
     * window inverts E8's "partial data is discarded" — which is about an
     * *unstable* weight, not a complete one waiting on an optional companion
     * frame (`02-interface-revision.md` §3: persist the weight-only row).
     */
    fun flushOrElse(fallback: SessionOutcome): SessionOutcome =
        readingFromFlush()?.let { SessionOutcome.Completed(it) } ?: fallback

    /**
     * [SessionBudget.HARD_SESSION_CEILING] wraps the *whole* session, teardown
     * included, so it can fire after a reading has already been emitted — the
     * post-emission idle timer is 10 s and the ceiling does not stop for it.
     * [readingFromFlush] cannot recover that reading: the correlator consumed
     * its buffered weight during the emission and has nothing left to release,
     * so the ceiling would report `NO_MEASUREMENT` for a weigh-in that
     * succeeded. [emittedReading] is where it survives.
     *
     * The ceiling itself deliberately still applies once a reading exists: it
     * is an unconditional teardown bound, and cutting the idle wait short costs
     * nothing now that the result cannot be lost with it.
     *
     * Falling back to [flushOrElse] keeps E17 intact for the earlier case — the
     * ceiling firing mid-correlation-window, where the decoder *is* still
     * holding a weight. Every phase before that holds nothing, and the fallback
     * passes through untouched.
     */
    fun outcomeAtCeiling(): SessionOutcome =
        emittedReading?.let { SessionOutcome.Completed(it) }
            ?: flushOrElse(SessionOutcome.Missed(MissReason.NO_MEASUREMENT))

    private fun readingFromFlush(): com.ventouxlabs.bascule.ble.ScaleReading? =
        (decoder.flush() as? DecodeEvent.Stable)?.reading

    private fun noMeasurement(malformed: Int): SessionOutcome =
        if (malformed > 0) SessionOutcome.DecodeFailure(malformed) else SessionOutcome.Missed(MissReason.NO_MEASUREMENT)

    suspend fun finishEmission(
        events: Channel<TransportEvent>,
        reading: com.ventouxlabs.bascule.ble.ScaleReading,
    ): SessionOutcome {
        // Recorded before the idle wait, not after it: HARD_SESSION_CEILING can
        // fire inside that wait, and by then the decoder has nothing left to
        // flush. See [outcomeAtCeiling].
        emittedReading = reading
        withTimeoutOrNull(SessionBudget.POST_EMISSION_IDLE) {
            while (true) {
                when (val event = events.receive()) {
                    is TransportEvent.AdapterOff -> return@withTimeoutOrNull
                    is TransportEvent.ConnectionStateChanged -> if (!event.connected) return@withTimeoutOrNull
                    is TransportEvent.CharacteristicChanged -> {
                        // Still fed to the decoder so the correlator's own
                        // drop/duplicate counters stay accurate. It can never
                        // decode `Stable` again — `MAX_EMISSIONS_PER_SESSION`
                        // is a permanent one-shot latch and every emit path
                        // clears the buffered weight first.
                        decoder.onNotification(event.char, event.value)
                    }
                    is TransportEvent.ServicesDiscovered,
                    is TransportEvent.WriteComplete,
                    is TransportEvent.SubscriptionEnabled,
                    is TransportEvent.MtuChanged,
                    is TransportEvent.BondStateChanged,
                    -> Unit // transport plumbing; keeps the idle timer running
                }
            }
        }
        return SessionOutcome.Completed(reading)
    }

    private sealed interface MeasureStep {
        data object Pending : MeasureStep
        data object AdapterOff : MeasureStep
        data object Dropped : MeasureStep
        data class Reading(val reading: com.ventouxlabs.bascule.ble.ScaleReading) : MeasureStep
    }
}
