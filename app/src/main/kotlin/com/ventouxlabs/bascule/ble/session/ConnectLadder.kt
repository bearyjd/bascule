package com.ventouxlabs.bascule.ble.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The connect phase of a [GattSession]: E1 (timeout), E2 (status 133) and E3
 * (contention) retry ladders, the connect-outcome wait, and the stale-event
 * drain every retry boundary needs.
 *
 * Extracted from [GattSession] unchanged in behaviour once that file passed
 * 1000 lines. It is the one part of the session that touches only the
 * transport and the event channel — no decoder, no consent, no diagnostics —
 * which is what makes it a clean seam. `GattSession` still owns the phase's
 * budget (`CONNECT_PHASE_BUDGET`) and turns a [ConnectPhaseResult] into a
 * [SessionOutcome]; this class only knows how to get connected or say why not.
 */
internal class ConnectLadder(private val transport: GattTransport) {

    suspend fun connectWithRetries(
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
                    // E3: deliberately non-aggressive — one retry (ADR-003 fixes
                    // the retry *count*, not the teardown).
                    onLadderEntered(MissReason.CONTENTION)
                    if (contentionRetries >= SessionBudget.CONTENTION_MAX_RETRIES) {
                        return ConnectPhaseResult.Failed(MissReason.CONTENTION)
                    }
                    contentionRetries++
                    // Same teardown-before-retry discipline as E1/E2 (§2.3,
                    // §8.10). Looping back to connect() without it leaves the
                    // previous BluetoothGatt client registered — the per-app
                    // client table is finite, and exhausting it produces
                    // permanent status-133 until the process is killed.
                    transport.close()
                    delay(SessionBudget.CONTENTION_RETRY_DELAY)
                    drainStaleEvents(events)
                }

                else -> {
                    // E1 (no event within the attempt timeout) and any unclassified
                    // failure status share the same recovery: close, wait, retry once.
                    // TODO(WP-09): status 5/15 (GATT_INSUFFICIENT_AUTHENTICATION/
                    //  _ENCRYPTION) falls here today and reports as a connect
                    //  timeout; once bonding lands it must route to BONDING (E5)
                    //  instead.
                    val reason = disconnectReason(outcome)
                    onLadderEntered(reason)
                    transport.close()
                    if (timeoutRetries >= SessionBudget.CONNECT_TIMEOUT_MAX_RETRIES) {
                        return ConnectPhaseResult.Failed(reason)
                    }
                    timeoutRetries++
                    delay(SessionBudget.CONNECT_TIMEOUT_RETRY_DELAY)
                    drainStaleEvents(events)
                }
            }
        }
    }

    /**
     * A disconnect carrying `GATT_SUCCESS` is the peer closing the link
     * deliberately, not a radio failure that never completed — reporting it as
     * `CONNECT_TIMEOUT` hides a scale that accepted the connection and then
     * hung up. The recovery is the same (E1's close-wait-retry-once ladder);
     * only the reason it is reported under differs.
     */
    private fun disconnectReason(outcome: ConnectAttempt?): MissReason =
        if (outcome is ConnectAttempt.Failed && outcome.status == STATUS_GATT_SUCCESS) {
            MissReason.GRACEFUL_DISCONNECT
        } else {
            MissReason.CONNECT_TIMEOUT
        }

    suspend fun receiveConnectOutcome(events: Channel<TransportEvent>): ConnectAttempt {
        while (true) {
            when (val event = events.receive()) {
                is TransportEvent.AdapterOff -> return ConnectAttempt.AdapterOff
                is TransportEvent.ConnectionStateChanged ->
                    return if (event.connected) {
                        connectedOrImmediateDrop(events)
                    } else {
                        ConnectAttempt.Failed(event.status)
                    }
                // Exhaustive rather than `else`, at every dispatch on
                // TransportEvent in this class: these loops exit only on an
                // explicit return or their enclosing timeout, so a subtype that
                // fell through a catch-all would not raise an error — it would
                // hang the step until its budget expired. A ninth event must be
                // a compile failure here, not a silent stall.
                is TransportEvent.ServicesDiscovered,
                is TransportEvent.CharacteristicChanged,
                is TransportEvent.WriteComplete,
                is TransportEvent.SubscriptionEnabled,
                is TransportEvent.MtuChanged,
                is TransportEvent.BondStateChanged,
                -> continue // not relevant to the connect wait step
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
     *
     * [TransportEvent.AdapterOff] has to be classified here too, not just the
     * drop: the peek consumes whatever is queued, so discarding an adapter-off
     * left the session reporting `Connected` and then running discovery's full
     * 5 s against a dead adapter, ending as `Incompatible` — a statement about
     * the *device*, which also feeds `INCOMPATIBLE_STREAK` — instead of
     * `Missed(ADAPTER_OFF)`. Anything else is stale by construction at an
     * attempt boundary and discarded, as in [drainStaleEvents].
     */
    private fun connectedOrImmediateDrop(events: Channel<TransportEvent>): ConnectAttempt = when (
        val queued = events.tryReceive().getOrNull()
    ) {
        is TransportEvent.AdapterOff -> ConnectAttempt.AdapterOff
        is TransportEvent.ConnectionStateChanged ->
            if (queued.connected) ConnectAttempt.Connected else ConnectAttempt.Failed(queued.status)
        else -> ConnectAttempt.Connected
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
    fun drainStaleEvents(events: Channel<TransportEvent>) {
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

    private companion object {
        /** Android's catch-all `GATT_ERROR` (E2). */
        const val STATUS_GATT_ERROR = 133

        /** `BluetoothGatt.GATT_SUCCESS` — on a *disconnect*, a graceful close. */
        const val STATUS_GATT_SUCCESS = 0

        /** Busy / already-connected / contention statuses (E3, `00-design.md` §2.3). */
        val CONTENTION_STATUSES = setOf(8, 19, 22)
    }
}

internal sealed interface ConnectAttempt {
    data object Connected : ConnectAttempt
    data class Failed(val status: Int) : ConnectAttempt
    data object AdapterOff : ConnectAttempt
}

internal sealed interface ConnectPhaseResult {
    data object Connected : ConnectPhaseResult
    data class Failed(val reason: MissReason) : ConnectPhaseResult
    data object AdapterOff : ConnectPhaseResult
}

