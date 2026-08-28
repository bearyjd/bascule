package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.fake.ConnectOutcome
import com.ventouxlabs.bascule.ble.fake.DiscoverOutcome
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `00-design.md` §8.10 and §2.3 E12/E15 — teardown discipline. */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionTeardownTest {

    private fun session(transport: FakeGattTransport) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = InMemoryConsentStore(),
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = InMemoryDiagnosticsCounters(),
    )

    @Test
    fun adapterOffTearsDownWithoutRetry() = runTest {
        val transport = FakeGattTransport(connectOutcomes = listOf(ConnectOutcome.Timeout))
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.emitAdapterOff()
        advanceUntilIdle()

        assertEquals(SessionOutcome.Missed(MissReason.ADAPTER_OFF), deferred.await())
        assertEquals("no retry after adapter-off", 1, transport.connectCallCount)
        assertEquals(1, transport.closeCallCount)
    }

    @Test
    fun cancellationClosesGattExactlyOnce() = runTest {
        val transport = FakeGattTransport(connectOutcomes = listOf(ConnectOutcome.Timeout))
        val job = launch { session(transport).run() }

        runCurrent()
        job.cancelAndJoin()

        assertEquals(1, transport.closeCallCount)
    }

    /**
     * Parameterised across the terminal paths that involve **no** mid-retry
     * close: E1, E2 and E3 are all excluded here because `gatt.close()` before
     * a retry is mandatory and additional for those edges (§2.3, §8.10) —
     * covered by `GattSessionConnectTest.status133ClosesGattBeforeRetrying` and
     * [contentionClosesBeforeItsRetry] instead, which assert ordering
     * rather than a total of one.
     */
    @Test
    fun everyTerminalPathClosesGattExactlyOnce() = runTest {
        val scenarios = listOf(
            "successful connect, incompatible discovery" to FakeGattTransport(
                discovered = DiscoveredServices(emptyMap()),
            ),
            "discovery timeout" to FakeGattTransport(discoverOutcome = DiscoverOutcome.Timeout),
        )

        for ((label, transport) in scenarios) {
            session(transport).run()
            assertEquals("$label: closeCallCount", 1, transport.closeCallCount)
        }
    }

    /**
     * E1 closes before every failed attempt, including the one that exhausts
     * the ladder (the check happens after `close()`, since the last attempt
     * only turns out to be terminal in hindsight) — two mid-retry closes for
     * two attempts, plus the terminal close from `run()`'s own `finally`.
     * Nothing else in this changeset pins the count on an *exhausted* E1/E2
     * path; `status133ClosesGattBeforeRetrying` only proves ordering on a
     * script that recovers on the second attempt.
     */
    @Test
    fun connectTimeoutExhaustionClosesOnceForEveryAttemptPlusTheTerminalClose() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.Timeout, ConnectOutcome.Timeout),
        )

        session(transport).run()

        assertEquals(2, transport.connectCallCount)
        assertEquals(3, transport.closeCallCount)
    }

    /**
     * E2 closes before *every* 133, including the attempt that exhausts the
     * ladder — four mid-retry closes for four attempts, plus the terminal
     * close. That the mid-retry close happens unconditionally (before the
     * exhaustion check) is deliberate: §2.3 requires it before any retry, and
     * the final attempt only turns out to be terminal in hindsight.
     */
    @Test
    fun status133ExhaustionClosesOnceForEveryAttemptPlusTheTerminalClose() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = List(4) { ConnectOutcome.Failure(STATUS_GATT_ERROR) },
        )

        session(transport).run()

        assertEquals(4, transport.connectCallCount)
        assertEquals(5, transport.closeCallCount)
    }

    /**
     * E3 closes before its retry too — without that, the retry's `connect()`
     * orphaned the previous `BluetoothGatt` client registration, and the
     * per-app client table is finite. Its close sits *after* the exhaustion
     * check rather than before it (unlike E1/E2), because the close exists to
     * stop a retry leaking and the attempt that ends the phase makes no retry:
     * one close for the one retry, plus the terminal close.
     */
    @Test
    fun contentionClosesBeforeItsRetry() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.Failure(STATUS_BUSY), ConnectOutcome.Failure(STATUS_BUSY)),
        )

        session(transport).run()

        assertEquals(2, transport.connectCallCount)
        assertEquals(2, transport.closeCallCount)
        assertTrue(
            "close() must happen before the retrying connect(), got ${transport.callOrder}",
            transport.callOrder.indexOf("close") < transport.callOrder.lastIndexOf("connect"),
        )
    }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val STATUS_BUSY = 8
        const val STATUS_GATT_ERROR = 133
    }
}
