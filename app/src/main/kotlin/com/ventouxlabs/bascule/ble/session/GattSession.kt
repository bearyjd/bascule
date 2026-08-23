package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.HandshakeContext
import com.ventouxlabs.bascule.ble.decoders.ScaleDecoder
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * WP-06 (this package): `DISARMED` through `DISCOVERING`, plus teardown
 * discipline (E1, E2, E3, E4, E12, E15). WP-07 adds the `HANDSHAKING` →
 * `SUBSCRIBED` transition; WP-10 adds `MEASURING` → `EMITTED`. Until those land,
 * a session that reaches `DISCOVERING` successfully still reports
 * [SessionOutcome.Missed] with [MissReason.NO_MEASUREMENT] — the same outcome
 * the Phase 2 stub reported, so nothing downstream has to change shape as later
 * work packages land. See docs/prp/02-ci-notes.md.
 */
class GattSession(
    private val transport: GattTransport,
    val decoder: ScaleDecoder,
    private val consentStore: ConsentStore,
    private val deviceAddress: String,
    private val diagnostics: DiagnosticsCounters,
) {

    /**
     * Assembles the handshake inputs the decoder needs: the credential stored
     * for this scale if there is one, and a fresh consent code to register with
     * if there is not.
     */
    fun handshakeContext(): HandshakeContext = HandshakeContext(
        storedCredential = consentStore.credentialFor(deviceAddress),
        freshConsentCode = consentStore.newConsentCode(),
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
            connectAndDiscover(events)
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
     */
    private fun drainStaleEvents(events: Channel<TransportEvent>) {
        while (events.tryReceive().isSuccess) {
            // discarded — see KDoc above
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
                // WP-06 stops at DISCOVERING; HANDSHAKING is WP-07.
                SessionOutcome.Missed(MissReason.NO_MEASUREMENT)
            }
        }
    }

    private suspend fun receiveDiscoveryOutcome(events: Channel<TransportEvent>): DiscoveryAttempt {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return DiscoveryAttempt.AdapterOff
                is TransportEvent.ServicesDiscovered -> return when {
                    event.status != 0 -> DiscoveryAttempt.Failed(event.status)
                    event.services.containsAll(decoder.requiredServices) -> DiscoveryAttempt.Discovered
                    else -> DiscoveryAttempt.Missing
                }
                else -> continue // not relevant to the discovery wait step
            }
        }
    }

    private sealed interface ConnectAttempt {
        data object Connected : ConnectAttempt
        data class Failed(val status: Int) : ConnectAttempt
        data object AdapterOff : ConnectAttempt
    }

    private sealed interface DiscoveryAttempt {
        data object Discovered : DiscoveryAttempt
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
