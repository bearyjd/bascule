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
 * the Current Time opening write (00-design.md §4.4), subscribing to the UCP
 * and the decoder's measurement characteristics, then the UDS register/consent
 * handshake (E6, E19) — see [handshake] for why the measurement CCCDs precede
 * it. WP-10 adds `MEASURING` → `EMITTED`.
 * Until that lands, a session that reaches `SUBSCRIBED` successfully still
 * reports [SessionOutcome.Missed] with [MissReason.NO_MEASUREMENT] — the same
 * outcome the earlier stub reported, so nothing downstream has to change shape
 * as later work packages land. See docs/prp/02-ci-notes.md.
 */
// 29 small, single-purpose members, one per protocol step. Merging any of them
// to reach the threshold of 20 would hide a distinct BLE failure edge inside a
// larger function, in the one area validated against physical hardware.
@Suppress("TooManyFunctions")
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
    /**
     * A sink rather than `android.util.Log` directly: this class runs in the
     * plain JVM lane, where `Log` is unmocked and throws. The worker wires it
     * to logcat. Until this existed the session was a black box from outside —
     * a weigh-in that reached the radio and produced nothing was
     * indistinguishable from one the scale never answered.
     */
    private val log: (String) -> Unit = {},
) {

    /**
     * E8 allows exactly one reconnect per session. Per-session state on the
     * session object, the same way [ScaleDecoder] holds its own handshake
     * state: a `GattSession` is single-use, so this is never reset.
     */
    private var reconnectAttempts = 0

    init {
        // The two are independent parameters kept consistent by hand, and only
        // registration has a reason to stop once the handshake lands — a
        // MEASUREMENT session that stops there can never produce a reading.
        require(!stopAfterHandshake || purpose.permitsRegistration) {
            "stopAfterHandshake is only meaningful for a registration session, not $purpose"
        }
    }

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
        // [ConnectLadder.drainStaleEvents], called after every mid-retry close().
        //
        // Depends on `transport.events` replaying at least the in-flight
        // script to a subscriber that starts collecting after emission — see
        // the contract note on [GattTransport.events].
        val events = Channel<TransportEvent>(Channel.UNLIMITED)
        val forwarder = launch { transport.events.collect { events.trySend(it) } }
        try {
            withTimeoutOrNull(SessionBudget.HARD_SESSION_CEILING) { connectAndDiscover(events) }
                ?: measurement.outcomeAtCeiling()
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
            ladder.connectWithRetries(events) { ladderInProgress = it }
        } ?: ConnectPhaseResult.Failed(ladderInProgress)

        return when (phaseResult) {
            is ConnectPhaseResult.Failed -> SessionOutcome.Missed(phaseResult.reason)
            ConnectPhaseResult.AdapterOff -> SessionOutcome.Missed(MissReason.ADAPTER_OFF)
            ConnectPhaseResult.Connected -> discover(events)
        }
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
                is TransportEvent.ConnectionStateChanged,
                is TransportEvent.CharacteristicChanged,
                is TransportEvent.WriteComplete,
                is TransportEvent.SubscriptionEnabled,
                is TransportEvent.MtuChanged,
                is TransportEvent.BondStateChanged,
                -> continue // not relevant to the discovery wait step
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
            var outcome = withTimeoutOrNull(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT) {
                awaitWriteComplete(events, op.char)
            }
            if (outcome == WriteOutcome.Bonding || (outcome == null && pairingObserved)) {
                // The stack is holding this write behind pairing. Once bonded it
                // retries the write itself, so wait for that completion afresh.
                when (awaitBond(events)) {
                    BondWait.Bonded -> outcome = withTimeoutOrNull(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT) {
                        awaitWriteComplete(events, op.char)
                    }
                    BondWait.Refused, BondWait.TimedOut -> return SessionOutcome.PairingRequired
                    BondWait.Dropped -> return reconnectOnce(events)
                    BondWait.AdapterOff -> return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
                }
            }
            if (outcome == WriteOutcome.AdapterOff) return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
            // Tolerated, but no longer silent: an unanswered write here is the
            // first sign of a scale that is not talking to *this* phone, and
            // the subscribe that follows will time out the same way.
            if (outcome == null) {
                log("opening write to ${op.char} unanswered after ${SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT}")
            }
        }
        return null
    }

    private suspend fun awaitWriteComplete(events: Channel<TransportEvent>, char: UUID): WriteOutcome {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return WriteOutcome.AdapterOff
                is TransportEvent.WriteComplete -> if (event.char == char) {
                    log("write to ${event.char} completed, status ${event.status}")
                    return WriteOutcome.Completed
                }
                is TransportEvent.BondStateChanged -> if (event.state == BOND_BONDING) {
                    pairingObserved = true
                    return WriteOutcome.Bonding
                }
                is TransportEvent.ConnectionStateChanged,
                is TransportEvent.ServicesDiscovered,
                is TransportEvent.CharacteristicChanged,
                is TransportEvent.SubscriptionEnabled,
                is TransportEvent.MtuChanged,
                -> continue // not relevant to this write's completion
            }
        }
    }

    private enum class WriteOutcome { Completed, AdapterOff, Bonding }

    /**
     * E5 (`00-design.md` §2.3): the BF720 requires an encrypted link for its
     * writes. On a phone it has never met, the first write makes the Android
     * stack start pairing and hold the operation until the user accepts the
     * system's "Pair with BF720?" request — which takes as long as a human
     * takes, not the 2 s a write is otherwise allowed. Found on a fresh Pixel:
     * every session failed "could not enable User Control Point indications"
     * exactly 2 s after the pairing request appeared, while the original phone,
     * bonded since the first hardware session in August, never saw it.
     *
     * Set by whichever wait first sees `BOND_BONDING`, and consulted on a
     * timeout so a pairing that started before the wait began is still
     * recognised as one.
     */
    private var pairingObserved = false

    /** E1/E2/E3, extracted whole: see [ConnectLadder]. */
    private val ladder = ConnectLadder(transport)

    /** E7/E17/E8's listening half, extracted whole: see [MeasurementPhase]. */
    private val measurement = MeasurementPhase(decoder, log, onDropped = ::reconnectOnce)

    private enum class BondWait { Bonded, Refused, TimedOut, Dropped, AdapterOff }

    private suspend fun awaitBond(
        events: Channel<TransportEvent>,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>? = null,
    ): BondWait {
        log("scale requested pairing; waiting up to ${SessionBudget.BOND_WAIT} for the user to accept")
        val outcome = withTimeoutOrNull(SessionBudget.BOND_WAIT) {
            receiveBondOutcome(events, deferredFrames)
        } ?: BondWait.TimedOut
        log("pairing wait ended: $outcome")
        // A bond that landed is consumed: a later timeout is a plain timeout
        // again, not a second 30 s wait for a pairing that already happened.
        if (outcome == BondWait.Bonded) pairingObserved = false
        return outcome
    }

    /** A function, not a lambda, for the reason [receiveSubscriptionOutcome] gives. */
    private suspend fun receiveBondOutcome(
        events: Channel<TransportEvent>,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>?,
    ): BondWait {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return BondWait.AdapterOff
                is TransportEvent.ConnectionStateChanged -> if (!event.connected) return BondWait.Dropped
                is TransportEvent.BondStateChanged -> when (event.state) {
                    BOND_BONDED -> return BondWait.Bonded
                    BOND_NONE -> return BondWait.Refused
                }
                // Kept for the phase that can still use it, never decoded here.
                is TransportEvent.CharacteristicChanged -> deferredFrames?.add(event)
                is TransportEvent.ServicesDiscovered,
                is TransportEvent.WriteComplete,
                is TransportEvent.SubscriptionEnabled,
                is TransportEvent.MtuChanged,
                -> Unit // the held operation completes after the bond, not during it
            }
        }
    }

    /**
     * Drives `beginHandshake`/`onHandshakeEvent` (ADR-007, RISK-1) — one step
     * per acknowledging indication, gated on `DecodeEvent.ConsentResult(success
     * = true)` rather than an undifferentiated ack (E6).
     *
     * Every CCCD is enabled *before* the first UCP write, the measurement ones
     * included. Found on the BF720 (`03-hardware-validation.md`, "Consented
     * reads, 2026-09-19"): a weigh-in taken while no phone was connected is
     * stored under the user the scale recognised and delivered exactly once,
     * on that user's next Consent — the Weight and Body Composition
     * indications land ~1 s after the write, *before* the consent response,
     * and are cleared as they go. With the measurement CCCDs still off at
     * that moment the scale's one delivery was lost, which is the likely
     * cause of the 2026-09-16 `MISSED` weigh-in.
     * Only *listening* stays gated on consent: a lost consent is still E6's
     * `HandshakeFailed`, never a measurement window of silence.
     *
     * A mid-handshake disconnect that is *not* adapter-off (no dedicated edge
     * names this — E8 is MEASURING-only) is deliberately left to E6's own ack
     * ladder to catch: `awaitNonWaitDirective` ignores it and keeps waiting,
     * so the outstanding write's timeout eventually fires and the session ends
     * cleanly as `HandshakeFailed` rather than hanging. This is an explicit
     * scope decision for WP-07, not an oversight — flag if it needs its own
     * edge before WP-08.
     */
    // Seven sequential protocol steps, each with its own distinct failure exit.
    @Suppress("ReturnCount")
    private suspend fun handshake(events: Channel<TransportEvent>, discovered: DiscoveredServices): SessionOutcome {
        var directive = decoder.beginHandshake(discovered, handshakeContext())
        val deferredFrames = mutableListOf<TransportEvent.CharacteristicChanged>()
        // Not `is Send`: a decoder whose first directive is `Complete` still
        // needs its measurement CCCDs, or it would listen deaf.
        if (directive !is HandshakeDirective.Abort && directive !is HandshakeDirective.Wait) {
            prepareHandshakeResponseChannel(events, directive)?.let { return it }
            subscribeMeasurementCharacteristics(events, deferredFrames)?.let { return it }
        }
        while (true) {
            when (val current = directive) {
                is HandshakeDirective.Send -> {
                    val write = current.op as? GattOp.Write
                        ?: return SessionOutcome.HandshakeFailed("handshake directive was not a Write")
                    issueHandshakeWrite(events, write, deferredFrames)
                    when (val step = awaitHandshakeStep(events, write, current.expectAckWithin, deferredFrames)) {
                        HandshakeStep.AdapterOff -> {
                            logDroppedFrames(deferredFrames)
                            return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
                        }
                        is HandshakeStep.Directive -> directive = step.directive
                    }
                }

                HandshakeDirective.Wait ->
                    return SessionOutcome.HandshakeFailed("beginHandshake returned Wait with nothing sent")

                is HandshakeDirective.Complete -> return completeHandshake(events, current, deferredFrames)

                is HandshakeDirective.Abort -> {
                    if (current.registrationRejected) {
                        diagnostics.increment(DiagnosticsCounterKey.REGISTRATION_REJECTED)
                    }
                    log("handshake aborted: ${current.reason}")
                    logDroppedFrames(deferredFrames)
                    return SessionOutcome.HandshakeFailed(current.reason)
                }
            }
        }
    }

    /** Log only — whether to flush a stored weigh-in the handshake then failed under is a separate decision. */
    private fun logDroppedFrames(deferredFrames: List<TransportEvent.CharacteristicChanged>) {
        if (deferredFrames.isEmpty()) return
        log("handshake failed; ${deferredFrames.size} measurement frame(s) delivered mid-handshake, dropped")
    }

    private suspend fun completeHandshake(
        events: Channel<TransportEvent>,
        complete: HandshakeDirective.Complete,
        deferredFrames: List<TransportEvent.CharacteristicChanged>,
    ): SessionOutcome {
        complete.credential?.let(::rememberCredential)
        if (!stopAfterHandshake) {
            log("handshake complete (consented); listening for measurement")
            return measureAfterHandshake(events, deferredFrames)
        }
        // A just-registered user has no weigh-in stored against them, so a
        // frame here is worth a line in the log, not a behaviour.
        if (deferredFrames.isNotEmpty()) {
            log("registration complete; ${deferredFrames.size} measurement frame(s) arrived mid-handshake, ignored")
        }
        return SessionOutcome.Completed(null)
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
            SubscriptionOutcome.PairingRequired -> SessionOutcome.PairingRequired
            SubscriptionOutcome.Dropped -> reconnectOnce(events)
            SubscriptionOutcome.Bonding -> error("Bonding is resolved inside awaitSubscription")
            SubscriptionOutcome.Failed -> SessionOutcome.HandshakeFailed(
                "could not enable User Control Point indications",
            )
        }
    }

    /**
     * After the UCP CCCD and before the first UCP write — see [handshake] for
     * the hardware reason. Null once every measurement CCCD is enabled.
     */
    private suspend fun subscribeMeasurementCharacteristics(
        events: Channel<TransportEvent>,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>,
    ): SessionOutcome? {
        for (characteristic in decoder.measurementCharacteristics) {
            transport.enableIndications(characteristic)
            when (awaitSubscription(events, characteristic, deferredFrames)) {
                SubscriptionOutcome.Enabled -> Unit
                SubscriptionOutcome.AdapterOff -> return SessionOutcome.Missed(MissReason.ADAPTER_OFF)
                SubscriptionOutcome.PairingRequired -> return SessionOutcome.PairingRequired
                SubscriptionOutcome.Dropped -> return reconnectOnce(events)
                SubscriptionOutcome.Bonding -> error("Bonding is resolved inside awaitSubscription")
                SubscriptionOutcome.Failed -> return SessionOutcome.HandshakeFailed(
                    "could not enable a measurement indication",
                )
            }
        }
        return null
    }

    private suspend fun awaitSubscription(
        events: Channel<TransportEvent>,
        char: UUID,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>? = null,
    ): SubscriptionOutcome {
        val first = withTimeoutOrNull(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT) {
            receiveSubscriptionOutcome(events, char, deferredFrames)
        }
        if (first == SubscriptionOutcome.Bonding || (first == null && pairingObserved)) {
            // Same shape as the opening write: the CCCD write is held behind
            // pairing and completes on its own once the link is encrypted.
            return when (awaitBond(events, deferredFrames)) {
                BondWait.Bonded -> withTimeoutOrNull(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT) {
                    receiveSubscriptionOutcome(events, char, deferredFrames)
                } ?: SubscriptionOutcome.Failed.also { log("subscription to $char unanswered even after bonding") }
                BondWait.Refused, BondWait.TimedOut -> SubscriptionOutcome.PairingRequired
                BondWait.Dropped -> SubscriptionOutcome.Dropped
                BondWait.AdapterOff -> SubscriptionOutcome.AdapterOff
            }
        }
        return first ?: SubscriptionOutcome.Failed.also {
            log("subscription to $char unanswered after ${SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT}")
        }
    }

    /**
     * Split out of [awaitSubscription] rather than living inside its
     * `withTimeoutOrNull` lambda, because a *lambda* ending in `while (true)`
     * still needs a trailing typed value that the compiler then proves
     * unreachable. Suppressing that warning would also suppress the one that
     * matters: if anyone later adds a `break`, the trailing value becomes live
     * and its non-null return silently skips the `?:` timeout branch. A
     * function body ending in `while (true)` needs no such value, so the hazard
     * cannot be reintroduced here.
     */
    private suspend fun receiveSubscriptionOutcome(
        events: Channel<TransportEvent>,
        char: UUID,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>?,
    ): SubscriptionOutcome {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return SubscriptionOutcome.AdapterOff
                is TransportEvent.SubscriptionEnabled ->
                    if (event.char == char) return subscriptionOutcome(event.status)
                is TransportEvent.CharacteristicChanged -> deferredFrames?.add(event)
                is TransportEvent.BondStateChanged -> if (event.state == BOND_BONDING) {
                    pairingObserved = true
                    return SubscriptionOutcome.Bonding
                }
                is TransportEvent.ConnectionStateChanged,
                is TransportEvent.ServicesDiscovered,
                is TransportEvent.WriteComplete,
                is TransportEvent.MtuChanged,
                -> continue // not relevant to this subscription
            }
        }
    }

    private fun subscriptionOutcome(status: Int): SubscriptionOutcome {
        if (status != 0) log("subscription refused, status $status")
        return if (status == 0) SubscriptionOutcome.Enabled else SubscriptionOutcome.Failed
    }

    /**
     * [Bonding] is internal to [awaitSubscription], which resolves it into one
     * of the others before any caller sees it.
     */
    private enum class SubscriptionOutcome { Enabled, Failed, AdapterOff, Bonding, PairingRequired, Dropped }

    /**
     * [deferredFrames] first: the scale's stored weigh-in, if it had one, was
     * delivered during the handshake and is decoded only now, so the correlator
     * sees it exactly once and in the one phase that can emit it. Every frame
     * is fed, not just up to the first `Stable`, so the correlator's own
     * duplicate/drop counters stay right (as `finishEmission` does). Then the
     * ordinary wait — except that a deferred weight still awaiting its
     * body-composition pair is owed E17's short window, exactly as a live one
     * that has just arrived, not E7's long one.
     */
    private suspend fun measureAfterHandshake(
        events: Channel<TransportEvent>,
        deferredFrames: List<TransportEvent.CharacteristicChanged>,
    ): SessionOutcome {
        var stable: DecodeEvent.Stable? = null
        for (frame in deferredFrames) {
            val decoded = decoder.onNotification(frame.char, frame.value)
            log("deferred frame on ${frame.char} (${frame.value.size} bytes) -> ${decoded::class.simpleName}")
            if (stable == null && decoded is DecodeEvent.Stable) stable = decoded
        }
        stable?.let { return measurement.finishEmission(events, it.reading) }
        // Settling in to listen, possibly for minutes: drop to a low-duty
        // interval so the wait costs the scale's batteries as little as possible.
        transport.requestLowPower()
        val pairPending = deferredFrames.isNotEmpty() && decoder.hasPendingCorrelation
        if (pairPending) {
            log("deferred weight awaiting its pair for ${SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW}")
        } else {
            log("subscribed; listening for a weigh-in for up to ${SessionBudget.FIRST_INDICATION_TIMEOUT}")
        }
        return measurement.run(events, startInCorrelationWindow = pairPending)
    }

    /**
     * E8 (`00-design.md` §2.3): a disconnect while `MEASURING` gets up to
     * [SessionBudget.RECONNECT_MAX_ATTEMPTS] reconnects, each inside
     * [SessionBudget.RECONNECT_ONCE_WINDOW], before the session gives up. The
     * design's original "exactly one" assumed the drop meant the scale was
     * powering down; on the BF720 it is the scale's own idle timer ending a
     * link it will happily re-accept seconds later, and a session that listens
     * for minutes has to ride through that or the timer becomes a capture gap.
     *
     * The whole post-connect sequence runs again, not just the wait loop:
     * neither the CCCD subscriptions nor the User Data Service consent survive
     * the link for a non-bonded device, so a session that merely reconnected
     * and kept listening would sit silent until its budget expired. The decoder
     * instance is deliberately *not* reset — a weight already buffered for
     * correlation stays buffered, so a reconnect can still pair it and a failed
     * reconnect still flushes it.
     *
     * This relies on [ScaleDecoder.beginHandshake] being safe to call a second
     * time on the same instance: [decoder] is not reset, so the reconnected
     * leg's call to it lands on a decoder whose handshake state already
     * progressed once. [com.ventouxlabs.bascule.ble.decoders.BeurerDecoder]'s
     * implementation unconditionally overwrites its handshake state rather
     * than checking it first, so this holds today — but it is now a real
     * precondition of this class's correctness, not an incidental property of
     * a decoder that happened to only ever be handshake'd once before.
     *
     * Malformed frames counted before the drop are not carried into the second
     * leg's outcome: the reconnected leg reports what *it* saw. That loses an
     * E11 count in a rare path, which is a diagnostics detail, where carrying
     * it would mean a healthy post-reconnect weigh-in could still report
     * `DecodeFailure`.
     */
    private suspend fun reconnectOnce(events: Channel<TransportEvent>): SessionOutcome {
        if (reconnectAttempts >= SessionBudget.RECONNECT_MAX_ATTEMPTS) {
            return measurement.flushOrElse(SessionOutcome.Missed(MissReason.DROPPED))
        }
        reconnectAttempts++
        // Same teardown-before-retry discipline as E1/E2: the BluetoothGatt
        // behind a dropped link is dead, and reusing it is the classic Android
        // leak. Drained only after a real suspension — see [ConnectLadder.drainStaleEvents].
        transport.close()
        yield()
        ladder.drainStaleEvents(events)
        transport.connect()
        val attempt = withTimeoutOrNull(SessionBudget.RECONNECT_ONCE_WINDOW) { ladder.receiveConnectOutcome(events) }
        if (attempt !is ConnectAttempt.Connected) {
            return measurement.flushOrElse(SessionOutcome.Missed(MissReason.DROPPED))
        }
        // The second leg terminates through the same paths as the first, so it
        // flushes a still-buffered weight on its own way out.
        return discover(events)
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
    private suspend fun issueHandshakeWrite(
        events: Channel<TransportEvent>,
        op: GattOp.Write,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>? = null,
    ) {
        yield()
        drainStaleHandshakeEvents(events, deferredFrames)
        transport.write(op.char, op.bytes)
    }

    /**
     * [ConnectLadder.drainStaleEvents], minus one class of event. A measurement
     * frame is never stale in the correlation-ID sense the drain exists for —
     * it is the scale's stored weigh-in, whichever write it happened to land
     * behind — so one sitting in the channel at the instant the ack timer
     * fired is kept for [deferredFrames] rather than discarded with the rest.
     * Adapter-off is put back and stops the drain, exactly as the ladder's does.
     * A null [deferredFrames] (the opening write, before any consent) keeps the
     * ladder's behaviour in full.
     */
    private fun drainStaleHandshakeEvents(
        events: Channel<TransportEvent>,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>?,
    ) {
        while (true) {
            val event = events.tryReceive().getOrNull() ?: return
            when {
                event is TransportEvent.AdapterOff -> {
                    check(events.trySend(event).isSuccess) { "unreachable: UNLIMITED channel send failed" }
                    return
                }
                event is TransportEvent.CharacteristicChanged && event.char in decoder.measurementCharacteristics ->
                    deferredFrames?.add(event)
                else -> Unit // superseded by the write about to go out
            }
        }
    }

    /**
     * Waits for the outstanding write's ack, re-issuing on timeout up to E6's
     * retry cap. Every decoded event is fed through the decoder until it
     * returns something other than [HandshakeDirective.Wait] — an unrelated
     * indication comes back as `Wait` from the decoder itself, so this loop
     * naturally keeps waiting rather than misreading it as the step's answer.
     * A measurement frame is not fed through at all: it goes to
     * [deferredFrames] and neither satisfies nor extends the ack timer.
     */
    private suspend fun awaitHandshakeStep(
        events: Channel<TransportEvent>,
        write: GattOp.Write,
        ackTimeout: Duration,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>,
    ): HandshakeStep {
        var retries = 0
        while (true) {
            val step = withTimeoutOrNull(ackTimeout) { awaitNonWaitDirective(events, deferredFrames) }
            when {
                step == null -> {
                    // E6: no ack within timeout — re-issue the same write, max 2 retries.
                    if (retries >= SessionBudget.HANDSHAKE_ACK_MAX_RETRIES) {
                        return HandshakeStep.Directive(HandshakeDirective.Abort(ackExhaustedReason()))
                    }
                    retries++
                    issueHandshakeWrite(events, write, deferredFrames)
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

    private suspend fun awaitNonWaitDirective(
        events: Channel<TransportEvent>,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>,
    ): HandshakeStep {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return HandshakeStep.AdapterOff
                is TransportEvent.CharacteristicChanged ->
                    advanceHandshake(event, deferredFrames)?.let { return HandshakeStep.Directive(it) }
                is TransportEvent.ConnectionStateChanged,
                is TransportEvent.ServicesDiscovered,
                is TransportEvent.WriteComplete,
                is TransportEvent.SubscriptionEnabled,
                is TransportEvent.MtuChanged,
                is TransportEvent.BondStateChanged,
                -> continue // WriteComplete and other transport plumbing, not relevant here
            }
        }
    }

    /**
     * Null when the frame moved the handshake nowhere. Measurement frames can
     * and do arrive here: they are the scale's stored weigh-in, delivered on
     * the Consent write and ahead of its response (see [handshake]). Deferred,
     * not decoded — decoding now would hand the correlator a `Stable` that
     * this phase answers with `Wait` and drops. The correlator sees them once
     * the handshake is complete. Everything else is a UCP response and goes
     * through the decoder as before.
     */
    private fun advanceHandshake(
        event: TransportEvent.CharacteristicChanged,
        deferredFrames: MutableList<TransportEvent.CharacteristicChanged>,
    ): HandshakeDirective? {
        if (event.char in decoder.measurementCharacteristics) {
            deferredFrames += event
            return null
        }
        val next = decoder.onHandshakeEvent(decoder.onNotification(event.char, event.value))
        return next.takeUnless { it is HandshakeDirective.Wait }
    }

    private sealed interface HandshakeStep {
        data object AdapterOff : HandshakeStep
        data class Directive(val directive: HandshakeDirective) : HandshakeStep
    }

    private sealed interface DiscoveryAttempt {
        data class Discovered(val services: DiscoveredServices) : DiscoveryAttempt
        data object Missing : DiscoveryAttempt
        data class Failed(val status: Int) : DiscoveryAttempt
        data object AdapterOff : DiscoveryAttempt
    }

    private companion object {
        /** Mirror `BluetoothDevice.BOND_*` — kept local so this class needs no Android import in the JVM lane. */
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12

    }
}
