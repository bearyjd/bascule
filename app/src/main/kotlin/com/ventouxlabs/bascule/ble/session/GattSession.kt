@file:Suppress("CyclomaticComplexMethod", "LongMethod", "TooManyFunctions", "ReturnCount")

package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.HandshakeContext
import com.ventouxlabs.bascule.ble.decoders.HandshakeDirective
import com.ventouxlabs.bascule.ble.decoders.ScaleDecoder
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The BLE state machine of 00-design.md §2.1: connect, discover, run the
 * ADR-007 handshake, subscribe, consume indications, persist at EMITTED, tear
 * down exactly once.
 *
 * Pure control — it holds no protocol knowledge. It executes the [GattOp]s a
 * [ScaleDecoder] asks for against a [GattTransport] and feeds inbound bytes
 * back in, which is what makes every failure edge reproducible in a JVM test
 * against a fake transport.
 *
 * WP-06: `DISARMED` through `DISCOVERING`, plus teardown discipline (E1, E2,
 * E3, E4, E12, E15). WP-07 (this package) adds `DISCOVERING` → `SUBSCRIBED`:
 * the Current Time opening write (00-design.md §4.4), the UDS register/consent
 * handshake (E6, E19), and subscribing to the decoder's measurement
 * characteristics once consent is granted. WP-10 adds `MEASURING` → `EMITTED`.
 * Until that lands, a session that reaches `SUBSCRIBED` successfully still
 * reports [SessionOutcome.Missed] with [MissReason.NO_MEASUREMENT] — the same
 * outcome the earlier stub reported, so nothing downstream has to change shape
 * as later work packages land. See docs/prp/02-ci-notes.md.
 */
class GattSession(
    private val transport: GattTransport,
    val decoder: ScaleDecoder,
    private val consentStore: ConsentStore,
    private val deviceAddress: String,
    private val diagnostics: DiagnosticsCounters,
    private val purpose: ScaleSessionPurpose = ScaleSessionPurpose.REGISTER_NEW,
    private val stopAfterHandshake: Boolean = false,
    /** Injected so the Current Time write is deterministic in a JVM test. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Assembles the handshake inputs the decoder needs: the credential stored
     * for this scale if there is one, and a fresh consent code to register with
     * if there is not.
     */
    fun handshakeContext(): HandshakeContext = HandshakeContext(
        storedCredential = consentStore.credentialFor(deviceAddress),
        freshConsentCode = consentStore.newConsentCode(),
        permitsRegistration = purpose.permitsRegistration,
    )

    /**
     * Persists the mapping the scale just assigned, so the next session sends
     * Consent directly instead of registering a second user slot (ADR-007).
     */
    fun rememberCredential(credential: ScaleCredential) {
        consentStore.save(deviceAddress, credential)
    }

    suspend fun run(): SessionOutcome = coroutineScope {
        // One subscription for the whole session lifetime, forwarded into a
        // Channel and consumed sequentially. Two hazards this closes: (1) a
        // fresh `transport.events.first {}` per wait-step would re-read the
        // fake's whole replay buffer on every call and could match a stale
        // event from an earlier step; (2) a late event from an already-closed
        // attempt (a post-close 133 callback, say) could otherwise sit in the
        // channel and be misread as the *next* attempt's outcome — see
        // [drainStaleEvents], called after every mid-retry close().
        //
        // Depends on `transport.events` replaying at least the in-flight
        // script to a subscriber that starts collecting after emission — see
        // the contract note on [GattTransport.events].
        val events = Channel<TransportEvent>(Channel.UNLIMITED)
        val forwarder = launch { transport.events.collect { events.trySend(it) } }
        try {
            withTimeoutOrNull(SessionBudget.HARD_SESSION_CEILING) { connectAndDiscover(events) }
                ?: SessionOutcome.Missed(MissReason.NO_MEASUREMENT)
        } finally {
            forwarder.cancel()
            // 00-design.md §8.10: every terminal path calls close() exactly
            // once. E1/E2's mid-retry close() calls (§2.3) are additional and
            // mandatory before those retries, not a substitute for this one.
            transport.close()
        }
    }

    private suspend fun connectAndDiscover(events: Channel<TransportEvent>): SessionOutcome {
        var ladderInProgress = MissReason.CONNECT_TIMEOUT
        val phaseResult = withTimeoutOrNull(SessionBudget.CONNECT_PHASE_BUDGET) {
            connectWithRetries(events) { ladderInProgress = it }
        } ?: ConnectPhaseResult.Failed(ladderInProgress)

        return when (phaseResult) {
            is ConnectPhaseResult.Failed -> SessionOutcome.Missed(phaseResult.reason)
            ConnectPhaseResult.AdapterOff -> SessionOutcome.Missed(MissReason.ADAPTER_OFF)
            ConnectPhaseResult.Connected -> discover(events)
        }
    }

    private suspend fun connectWithRetries(
        events: Channel<TransportEvent>,
        onLadderEntered: (MissReason) -> Unit,
    ): ConnectPhaseResult {
        var timeoutRetries = 0
        var status133Retries = 0
        var contentionRetries = 0

        while (true) {
            transport.connect()
            val outcome = withTimeoutOrNull(SessionBudget.CONNECT_ATTEMPT_TIMEOUT) {
                receiveConnectOutcome(events)
            }

            when {
                outcome is ConnectAttempt.AdapterOff -> return ConnectPhaseResult.AdapterOff

                outcome is ConnectAttempt.Connected -> return ConnectPhaseResult.Connected

                outcome is ConnectAttempt.Failed && outcome.status == STATUS_GATT_ERROR -> {
                    // E2: full teardown before any retry — reusing the transport
                    // after 133 is the classic Android leak. §2.3 specifies
                    // disconnect() -> close() -> null the ref; this abstraction
                    // has no "ref" to null, but disconnect() then close() is
                    // still both calls, in order.
                    onLadderEntered(MissReason.GATT_ERROR)
                    transport.disconnect()
                    transport.close()
                    if (status133Retries >= SessionBudget.STATUS_133_MAX_RETRIES) {
                        return ConnectPhaseResult.Failed(MissReason.GATT_ERROR)
                    }
                    delay(SessionBudget.STATUS_133_RETRY_DELAYS[status133Retries])
                    // Drained only after a real suspension (the delay above),
                    // not before: draining before any suspension point can run
                    // before the forwarder coroutine has relayed disconnect()'s
                    // own event out of the SharedFlow, in which case there is
                    // nothing yet to find and the stale event corrupts the next
                    // attempt's classification instead.
                    drainStaleEvents(events)
                    status133Retries++
                }

                outcome is ConnectAttempt.Failed && outcome.status in CONTENTION_STATUSES -> {
                    // E3: deliberately non-aggressive — one retry, no close() first (ADR-003).
                    onLadderEntered(MissReason.CONTENTION)
                    if (contentionRetries >= SessionBudget.CONTENTION_MAX_RETRIES) {
                        return ConnectPhaseResult.Failed(MissReason.CONTENTION)
                    }
                    contentionRetries++
                    delay(SessionBudget.CONTENTION_RETRY_DELAY)
                }

                else -> {
                    // E1 (no event within the attempt timeout) and any unclassified
                    // failure status share the same recovery: close, wait, retry once.
                    // TODO(WP-09): status 5/15 (GATT_INSUFFICIENT_AUTHENTICATION/
                    //  _ENCRYPTION) falls here today and reports as a connect
                    //  timeout; once bonding lands it must route to BONDING (E5)
                    //  instead.
                    onLadderEntered(MissReason.CONNECT_TIMEOUT)
                    transport.close()
                    if (timeoutRetries >= SessionBudget.CONNECT_TIMEOUT_MAX_RETRIES) {
                        return ConnectPhaseResult.Failed(MissReason.CONNECT_TIMEOUT)
                    }
                    timeoutRetries++
                    delay(SessionBudget.CONNECT_TIMEOUT_RETRY_DELAY)
                    drainStaleEvents(events)
                }
            }
        }
    }

    private suspend fun receiveConnectOutcome(events: Channel<TransportEvent>): ConnectAttempt {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return ConnectAttempt.AdapterOff
                is TransportEvent.ConnectionStateChanged ->
                    return if (event.connected) {
                        connectedOrImmediateDrop(events)
                    } else {
                        ConnectAttempt.Failed(event.status)
                    }
                else -> continue // not relevant to the connect wait step
            }
        }
    }

    /**
     * E3's second shape (`00-design.md` §2.3): "`CONNECTED` then immediate
     * disconnect with status 8/19/22" — Atlas contention that only reveals
     * itself after the connect callback fires. A scripted fake emits both
     * events back-to-back with no delay, so if the drop is genuinely
     * "immediate" it is already queued behind `CONNECTED` by the time this
     * runs; treat that queued drop as the whole attempt's outcome rather than
     * reporting `Connected` and letting discovery misclassify it as E4
     * (`01-plan.md`'s `device_busy.scale` fixture is exactly this shape).
     */
    private fun connectedOrImmediateDrop(events: Channel<TransportEvent>): ConnectAttempt {
        val queued = events.tryReceive().getOrNull()
        return if (queued is TransportEvent.ConnectionStateChanged && !queued.connected) {
            ConnectAttempt.Failed(queued.status)
        } else {
            ConnectAttempt.Connected
        }
    }

    /**
     * A callback from an attempt this session already closed (a post-close 133,
     * say) can still be sitting in the channel when the next `connect()` is
     * about to run. Discard it — the next wait-step must only ever see events
     * from the attempt it is actually waiting on.
     *
     * [TransportEvent.AdapterOff] is the one thing never discarded here: it is
     * global session state, not an artifact of any particular attempt or
     * write, so draining it away would let a real adapter-off go unnoticed and
     * have the session misclassify the resulting failure as a plain timeout
     * instead of `Missed(ADAPTER_OFF)`. Put back rather than dropped, and the
     * drain stops there — anything behind it in the channel is necessarily
     * older than the adapter-off and moot regardless.
     */
    private fun drainStaleEvents(events: Channel<TransportEvent>) {
        val adapterOff = generateSequence { events.tryReceive().getOrNull() }
            .firstOrNull { it is TransportEvent.AdapterOff }
            ?: return
        // events is Channel.UNLIMITED (see run()), so trySend here cannot fail
        // on capacity — it exists to put back what tryReceive just took, not
        // to enqueue new work. Everything drained before it is discarded as
        // intended; anything still behind it in the channel is necessarily
        // older than the adapter-off and moot regardless, so this stops here.
        check(events.trySend(adapterOff).isSuccess) { "unreachable: UNLIMITED channel send failed" }
    }

    private suspend fun discover(events: Channel<TransportEvent>): SessionOutcome {
        transport.discoverServices()
        val outcome = withTimeoutOrNull(SessionBudget.DISCOVERY_TIMEOUT) {
            receiveDiscoveryOutcome(events)
        }

        return when {
            outcome == null || outcome is DiscoveryAttempt.Missing -> {
                // E4: no onServicesDiscovered within 5s, or the required
                // service is absent — this is a statement about the device,
                // so (and only so) it counts toward incompatibleStreak.
                diagnostics.increment(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
                SessionOutcome.Incompatible
            }

            outcome is DiscoveryAttempt.AdapterOff -> SessionOutcome.Missed(MissReason.ADAPTER_OFF)

            outcome is DiscoveryAttempt.Failed ->
                // A non-zero discovery status is a transport failure, not a
                // "wrong device" signal — must not count toward
                // incompatibleStreak, or a radio hiccup against the real scale
                // eventually reads as "Scale not recognised".
                SessionOutcome.Missed(MissReason.DISCOVERY_FAILED)

            else -> {
                diagnostics.reset(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
                val discoveredServices = (outcome as DiscoveryAttempt.Discovered).services
                runOpeningSequence(events, discoveredServices)?.let { return it }
                handshake(events, discoveredServices)
            }
        }
    }

    private suspend fun receiveDiscoveryOutcome(events: Channel<TransportEvent>): DiscoveryAttempt {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return DiscoveryAttempt.AdapterOff
                is TransportEvent.ServicesDiscovered -> return when {
                    event.status != 0 -> DiscoveryAttempt.Failed(event.status)
                    event.services.containsAll(decoder.requiredServices) -> DiscoveryAttempt.Discovered(event.services)
                    else -> DiscoveryAttempt.Missing
                }
                else -> continue // not relevant to the discovery wait step
            }
        }
    }

    /**
     * 00-design.md §4.4: writes Current Time before Register/Consent, when the
     * decoder has something to send. Best-effort — waits for each write's
     * transport-level completion (real GATT operations must be serialized) but
     * never fails the session over a rejected/timed-out write; see
     * [ScaleDecoder.openingSequence]'s KDoc. Returns non-null only to short
     * circuit the whole session on adapter-off, which is not specific to this
     * write and must not be swallowed just because this step is best-effort.
     */
    private suspend fun runOpeningSequence(
        events: Channel<TransportEvent>,
        discovered: DiscoveredServices,
    ): SessionOutcome? {
        for (op in decoder.openingSequence(discovered, clock())) {
            if (op !is GattOp.Write) continue
            issueHandshakeWrite(events, op)
            val adapterOff = withTimeoutOrNull(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT) {
                awaitWriteComplete(events, op.char)
            } == WriteOutcome.AdapterOff
            if (adapterOff) return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
        }
        return null
    }

    private suspend fun awaitWriteComplete(events: Channel<TransportEvent>, char: UUID): WriteOutcome {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return WriteOutcome.AdapterOff
                is TransportEvent.WriteComplete -> if (event.char == char) return WriteOutcome.Completed
                else -> continue
            }
        }
    }

    private enum class WriteOutcome { Completed, AdapterOff }

    /**
     * Drives `beginHandshake`/`onHandshakeEvent` (ADR-007, RISK-1) — one step
     * per acknowledging indication, gated on `DecodeEvent.ConsentResult(success
     * = true)` rather than an undifferentiated ack (E6). Subscribes to the
     * decoder's measurement characteristics only once `Complete` is reached.
     *
     * A mid-handshake disconnect that is *not* adapter-off (no dedicated edge
     * names this — E8 is MEASURING-only) is deliberately left to E6's own ack
     * ladder to catch: `awaitNonWaitDirective` ignores it and keeps waiting,
     * so the outstanding write's timeout eventually fires and the session ends
     * cleanly as `HandshakeFailed` rather than hanging. This is an explicit
     * scope decision for WP-07, not an oversight — flag if it needs its own
     * edge before WP-08.
     */
    private suspend fun handshake(events: Channel<TransportEvent>, discovered: DiscoveredServices): SessionOutcome {
        var directive = decoder.beginHandshake(discovered, handshakeContext())
        prepareHandshakeResponseChannel(events, directive)?.let { return it }
        while (true) {
            when (val current = directive) {
                is HandshakeDirective.Send -> {
                    val write = current.op as? GattOp.Write
                        ?: return SessionOutcome.HandshakeFailed("handshake directive was not a Write")
                    issueHandshakeWrite(events, write)
                    when (val step = awaitHandshakeStep(events, write, current.expectAckWithin)) {
                        HandshakeStep.AdapterOff -> return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
                        is HandshakeStep.Directive -> directive = step.directive
                    }
                }

                HandshakeDirective.Wait ->
                    return SessionOutcome.HandshakeFailed("beginHandshake returned Wait with nothing sent")

                is HandshakeDirective.Complete -> {
                    current.credential?.let(::rememberCredential)
                    if (stopAfterHandshake) return SessionOutcome.Completed(emptyList())
                    return subscribeAndMeasure(events)
                }

                is HandshakeDirective.Abort -> {
                    if (current.registrationRejected) {
                        diagnostics.increment(DiagnosticsCounterKey.REGISTRATION_REJECTED)
                    }
                    return SessionOutcome.HandshakeFailed(current.reason)
                }
            }
        }
    }

    private suspend fun prepareHandshakeResponseChannel(
        events: Channel<TransportEvent>,
        directive: HandshakeDirective,
    ): SessionOutcome? {
        // User Control Point responses are indications. Enabling its CCCD must
        // complete before the first Register/Consent write or real hardware
        // has no channel on which to return the acknowledgement (the JVM fake
        // can emit without a subscription, which previously hid this gap).
        val responseChar = (directive as? HandshakeDirective.Send)?.op?.let { it as? GattOp.Write }?.char
        if (responseChar == null) return null
        transport.enableIndications(responseChar)
        return when (awaitSubscription(events, responseChar)) {
            SubscriptionOutcome.Enabled -> null
            SubscriptionOutcome.AdapterOff -> SessionOutcome.Missed(MissReason.ADAPTER_OFF)
            SubscriptionOutcome.Failed -> SessionOutcome.HandshakeFailed(
                "could not enable User Control Point indications",
            )
        }
    }

    private suspend fun awaitSubscription(
        events: Channel<TransportEvent>,
        char: UUID,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>? = null,
    ): SubscriptionOutcome =
        withTimeoutOrNull(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT) {
            while (true) {
                when (val event = events.receive()) {
                    is TransportEvent.AdapterOff -> return@withTimeoutOrNull SubscriptionOutcome.AdapterOff
                    is TransportEvent.SubscriptionEnabled -> if (event.char == char) {
                        return@withTimeoutOrNull if (event.status == 0) {
                            SubscriptionOutcome.Enabled
                        } else {
                            SubscriptionOutcome.Failed
                        }
                    }
                    is TransportEvent.CharacteristicChanged -> deferredFrames?.add(event)
                    else -> continue
                }
            }
            @Suppress("UNREACHABLE_CODE")
            SubscriptionOutcome.Failed
        } ?: SubscriptionOutcome.Failed

    private enum class SubscriptionOutcome { Enabled, Failed, AdapterOff }

    private suspend fun subscribeAndMeasure(events: Channel<TransportEvent>): SessionOutcome {
        val deferredFrames = mutableListOf<TransportEvent.CharacteristicChanged>()
        for (characteristic in decoder.measurementCharacteristics) {
            transport.enableIndications(characteristic)
            when (awaitSubscription(events, characteristic, deferredFrames)) {
                SubscriptionOutcome.Enabled -> Unit
                SubscriptionOutcome.AdapterOff -> return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
                SubscriptionOutcome.Failed -> return SessionOutcome.HandshakeFailed(
                    "could not enable a measurement indication",
                )
            }
        }
        for (frame in deferredFrames) {
            val decoded = decoder.onNotification(frame.char, frame.value)
            if (decoded is DecodeEvent.Stable) return finishEmission(events, decoded.reading)
        }
        return awaitMeasurement(events)
    }

    private suspend fun awaitMeasurement(events: Channel<TransportEvent>): SessionOutcome {
        var malformed = 0
        val first = withTimeoutOrNull(SessionBudget.FIRST_INDICATION_TIMEOUT) {
            while (true) {
                when (val event = events.receive()) {
                    is TransportEvent.AdapterOff -> return@withTimeoutOrNull MeasureStep.AdapterOff
                    is TransportEvent.ConnectionStateChanged -> if (!event.connected) {
                        return@withTimeoutOrNull MeasureStep.Dropped
                    }
                    is TransportEvent.CharacteristicChanged -> {
                        when (val decoded = decoder.onNotification(event.char, event.value)) {
                            is DecodeEvent.Stable -> return@withTimeoutOrNull MeasureStep.Reading(decoded.reading)
                            is DecodeEvent.Malformed -> malformed++
                            DecodeEvent.SessionComplete -> {
                                val flushed = decoder.flush()
                                if (flushed is DecodeEvent.Stable) {
                                    return@withTimeoutOrNull MeasureStep.Reading(flushed.reading)
                                }
                            }
                            else -> Unit
                        }
                        return@withTimeoutOrNull MeasureStep.Pending
                    }
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            MeasureStep.Pending
        } ?: return if (malformed > 0) SessionOutcome.DecodeFailure(malformed)
        else SessionOutcome.Missed(MissReason.NO_MEASUREMENT)

        return when (first) {
            MeasureStep.AdapterOff -> SessionOutcome.Missed(MissReason.ADAPTER_OFF)
            MeasureStep.Dropped -> SessionOutcome.Missed(MissReason.DROPPED)
            is MeasureStep.Reading -> finishEmission(events, first.reading)
            MeasureStep.Pending -> {
                val paired = withTimeoutOrNull(SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW) {
                    while (true) {
                        when (val event = events.receive()) {
                            is TransportEvent.AdapterOff -> return@withTimeoutOrNull MeasureStep.AdapterOff
                            is TransportEvent.ConnectionStateChanged -> if (!event.connected) {
                                return@withTimeoutOrNull MeasureStep.Dropped
                            }
                            is TransportEvent.CharacteristicChanged -> when (
                                val decoded = decoder.onNotification(event.char, event.value)
                            ) {
                                is DecodeEvent.Stable -> return@withTimeoutOrNull MeasureStep.Reading(decoded.reading)
                                is DecodeEvent.Malformed -> malformed++
                                else -> Unit
                            }
                            else -> Unit
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    MeasureStep.Pending
                } ?: decoder.flush().let { flushed ->
                    if (flushed is DecodeEvent.Stable) MeasureStep.Reading(flushed.reading) else MeasureStep.Pending
                }
                when (paired) {
                    is MeasureStep.Reading -> finishEmission(events, paired.reading)
                    MeasureStep.AdapterOff -> SessionOutcome.Missed(MissReason.ADAPTER_OFF)
                    MeasureStep.Dropped -> SessionOutcome.Missed(MissReason.DROPPED)
                    MeasureStep.Pending -> if (malformed > 0) SessionOutcome.DecodeFailure(malformed)
                    else SessionOutcome.Missed(MissReason.NO_MEASUREMENT)
                }
            }
        }
    }

    private suspend fun finishEmission(
        events: Channel<TransportEvent>,
        reading: com.ventouxlabs.bascule.ble.ScaleReading,
    ): SessionOutcome {
        withTimeoutOrNull(SessionBudget.POST_EMISSION_IDLE) {
            while (true) {
                when (val event = events.receive()) {
                    is TransportEvent.AdapterOff -> return@withTimeoutOrNull
                    is TransportEvent.ConnectionStateChanged -> if (!event.connected) return@withTimeoutOrNull
                    is TransportEvent.CharacteristicChanged -> {
                        if (decoder.onNotification(event.char, event.value) is DecodeEvent.Stable) {
                            diagnostics.increment(DiagnosticsCounterKey.DUPLICATE_STABLE_SUPPRESSED)
                        }
                    }
                    else -> Unit
                }
            }
        }
        return SessionOutcome.Completed(listOf(reading))
    }

    private sealed interface MeasureStep {
        data object Pending : MeasureStep
        data object AdapterOff : MeasureStep
        data object Dropped : MeasureStep
        data class Reading(val reading: com.ventouxlabs.bascule.ble.ScaleReading) : MeasureStep
    }

    /**
     * The wire protocol carries no correlation ID: a Register/Consent response
     * is identified only by its opcode, so a response to a *superseded* write
     * is byte-for-byte indistinguishable from a fresh one once decoded.
     * Draining before every handshake write (including reissues) discards
     * anything *already sitting* in the channel from a step this write
     * supersedes — e.g. a duplicate response the fake (or scale) emitted twice
     * for the same write. `yield()` first because a response already emitted
     * may not have been relayed into `events` yet — see `run()`'s own KDoc on
     * the same hazard.
     *
     * This does NOT close the harder case where the stale response hasn't
     * arrived yet at drain time and only shows up during the *next* write's
     * wait — draining can't discard what isn't in the channel. When state
     * cycles back to the same `HandshakeState` subtype (`AwaitingConsent
     * (registered=false)` → re-register → `AwaitingConsent(registered=true)`),
     * that case is closed at the decoder level instead: see
     * `BeurerDecoder.HandshakeState.AwaitingConsent.staleResponseBudget`,
     * which absorbs only as many same-type refusals as could possibly be
     * stale (bounded by `SessionBudget.HANDSHAKE_ACK_MAX_RETRIES`) before
     * treating the next one as genuine.
     */
    private suspend fun issueHandshakeWrite(events: Channel<TransportEvent>, op: GattOp.Write) {
        yield()
        drainStaleEvents(events)
        transport.write(op.char, op.bytes)
    }

    /**
     * Waits for the outstanding write's ack, re-issuing on timeout up to E6's
     * retry cap. Every decoded event is fed through the decoder until it
     * returns something other than [HandshakeDirective.Wait] — an unrelated
     * indication comes back as `Wait` from the decoder itself, so this loop
     * naturally keeps waiting rather than misreading it as the step's answer.
     */
    private suspend fun awaitHandshakeStep(
        events: Channel<TransportEvent>,
        write: GattOp.Write,
        ackTimeout: Duration,
    ): HandshakeStep {
        var retries = 0
        while (true) {
            val step = withTimeoutOrNull(ackTimeout) { awaitNonWaitDirective(events) }
            when {
                step == null -> {
                    // E6: no ack within timeout — re-issue the same write, max 2 retries.
                    if (retries >= SessionBudget.HANDSHAKE_ACK_MAX_RETRIES) {
                        return HandshakeStep.Directive(HandshakeDirective.Abort(ackExhaustedReason()))
                    }
                    retries++
                    issueHandshakeWrite(events, write)
                }

                else -> return step
            }
        }
    }

    /**
     * [decoder.handshakeSawUnverifiableResponse] distinguishes two E6-exhaustion
     * causes that would otherwise share one misleading message: genuinely no
     * ack ever arriving, versus a response arriving that could not be trusted
     * (see `BeurerDecoder.HandshakeState.AwaitingConsent.staleResponseBudget`).
     * Only the former is accurately "no ack".
     */
    private fun ackExhaustedReason(): String {
        val maxRetries = SessionBudget.HANDSHAKE_ACK_MAX_RETRIES
        return if (decoder.handshakeSawUnverifiableResponse) {
            "no verifiable ack after $maxRetries retries " +
                "(a response arrived but could not be attributed to this write)"
        } else {
            "no ack after $maxRetries retries"
        }
    }

    private suspend fun awaitNonWaitDirective(events: Channel<TransportEvent>): HandshakeStep {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return HandshakeStep.AdapterOff
                is TransportEvent.CharacteristicChanged -> {
                    // TODO(WP-10): a measurement indication cannot reach here on
                    //  real hardware (indications aren't enabled until consent
                    //  is granted), but if that ever changes, decoding it here
                    //  primes the correlator with a frame this session-phase
                    //  will never pair or flush.
                    val decoded = decoder.onNotification(event.char, event.value)
                    val next = decoder.onHandshakeEvent(decoded)
                    if (next !is HandshakeDirective.Wait) return HandshakeStep.Directive(next)
                }
                else -> continue // WriteComplete and other transport plumbing, not relevant here
            }
        }
    }

    private sealed interface HandshakeStep {
        data object AdapterOff : HandshakeStep
        data class Directive(val directive: HandshakeDirective) : HandshakeStep
    }

    private sealed interface ConnectAttempt {
        data object Connected : ConnectAttempt
        data class Failed(val status: Int) : ConnectAttempt
        data object AdapterOff : ConnectAttempt
    }

    private sealed interface DiscoveryAttempt {
        data class Discovered(val services: DiscoveredServices) : DiscoveryAttempt
        data object Missing : DiscoveryAttempt
        data class Failed(val status: Int) : DiscoveryAttempt
        data object AdapterOff : DiscoveryAttempt
    }

    private sealed interface ConnectPhaseResult {
        data object Connected : ConnectPhaseResult
        data class Failed(val reason: MissReason) : ConnectPhaseResult
        data object AdapterOff : ConnectPhaseResult
    }

    private companion object {
        /** Android's catch-all `GATT_ERROR` (E2). */
        const val STATUS_GATT_ERROR = 133

        /** Busy / already-connected / contention statuses (E3, `00-design.md` §2.3). */
        val CONTENTION_STATUSES = setOf(8, 19, 22)
    }
}
