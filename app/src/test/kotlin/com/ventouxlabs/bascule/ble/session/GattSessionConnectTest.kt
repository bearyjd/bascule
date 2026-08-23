package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.fake.ConnectOutcome
import com.ventouxlabs.bascule.ble.fake.DiscoverOutcome
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `00-design.md` §2.1/§2.3 E1, E2, E3 — the connect phase's retry ladders. */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionConnectTest {

    private fun session(transport: FakeGattTransport) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = InMemoryConsentStore(),
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = InMemoryDiagnosticsCounters(),
    )

    @Test
    fun connectTimeoutRetriesExactlyOnce() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.Timeout, ConnectOutcome.Timeout),
        )

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Missed(MissReason.CONNECT_TIMEOUT), outcome)
        assertEquals("2 attempts total (E1)", 2, transport.connectCallCount)
    }

    /**
     * E2's own ladder (3.5s) and a full E1 pair (17.5s) each individually fit
     * inside 20s ([SessionBudgetTest.connectLadderFitsWithinConnectPhaseBudget]
     * checks exactly that), but nothing stops consecutive attempts from hitting
     * *different* edges before either one's own retry cap trips — three 133s
     * (3.5s) then a fresh E1 pair (17.5s) sums to 21s. Without the connect-phase
     * wrapper this script would run past budget; with it, the phase is cut off
     * at exactly 20s. See
     * [SessionBudgetTest.individualLaddersFitButCanCombinePastTheBudget].
     */
    @Test
    fun connectPhaseNeverExceedsTwentySeconds() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(
                ConnectOutcome.Failure(GATT_ERROR),
                ConnectOutcome.Failure(GATT_ERROR),
                ConnectOutcome.Failure(GATT_ERROR),
                ConnectOutcome.Timeout,
                ConnectOutcome.Timeout,
            ),
        )

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Missed(MissReason.CONNECT_TIMEOUT), outcome)
        assertEquals(
            "the wrapper — not either ladder's own cap — must be what ends this phase",
            20_000L,
            currentTime,
        )
    }

    @Test
    fun status133ExhaustionYieldsGattErrorNotConnectTimeout() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = List(4) { ConnectOutcome.Failure(GATT_ERROR) },
        )

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Missed(MissReason.GATT_ERROR), outcome)
        assertEquals(4, transport.connectCallCount)
    }

    /**
     * E3's second detection path: `CONNECTED` fires, then the scale drops the
     * link immediately — Atlas contention that only reveals itself after the
     * connect callback (`00-design.md` §2.3 E3, `01-plan.md`'s
     * `device_busy.scale` fixture). Must be classified as contention, not
     * routed into discovery where it would misreport as `Incompatible`.
     */
    @Test
    fun connectedThenImmediateDropIsTreatedAsContention() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(
                ConnectOutcome.ConnectThenDrop(STATUS_BUSY),
                ConnectOutcome.ConnectThenDrop(STATUS_BUSY),
            ),
        )

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Missed(MissReason.CONTENTION), outcome)
        assertEquals(2, transport.connectCallCount)
    }

    @Test
    fun connectedThenImmediateDropRecoversOnRetry() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.ConnectThenDrop(STATUS_BUSY), ConnectOutcome.Success),
        )

        val outcome = session(transport).run()

        assertTrue(
            "a recovered connect-then-drop must proceed to discovery, not stay Missed(CONTENTION)",
            outcome is SessionOutcome.Incompatible,
        )
    }

    @Test
    fun status133ClosesGattBeforeRetrying() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.Failure(GATT_ERROR), ConnectOutcome.Success),
            discoverOutcome = DiscoverOutcome.Success,
        )

        session(transport).run()

        val firstClose = transport.callOrder.indexOf("close")
        val secondConnect = transport.callOrder.lastIndexOf("connect")
        assertTrue("no close() recorded before the retry", firstClose >= 0)
        assertTrue(
            "close() must happen before the retrying connect(), got ${transport.callOrder}",
            firstClose < secondConnect,
        )
    }

    @Test
    fun status133RetriesAtFiveHundredOneAndTwoSeconds() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(
                ConnectOutcome.Failure(GATT_ERROR),
                ConnectOutcome.Failure(GATT_ERROR),
                ConnectOutcome.Failure(GATT_ERROR),
                ConnectOutcome.Success,
            ),
        )
        val deferred = async { session(transport).run() }

        runCurrent()
        assertEquals(1, transport.connectCallCount)

        // Pin the lower bound too: a retry must not fire before its delay
        // elapses, or a ladder shortened to e.g. 100ms would pass this test.
        advanceTimeBy(499)
        runCurrent()
        assertEquals("retry fired before its 500ms delay elapsed", 1, transport.connectCallCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, transport.connectCallCount)

        advanceTimeBy(999)
        runCurrent()
        assertEquals("retry fired before its 1s delay elapsed", 2, transport.connectCallCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, transport.connectCallCount)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("retry fired before its 2s delay elapsed", 3, transport.connectCallCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, transport.connectCallCount)

        deferred.await()
    }

    @Test
    fun busyStatusYieldsAfterOneRetry() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.Failure(STATUS_BUSY), ConnectOutcome.Success),
        )

        session(transport).run()

        assertEquals("exactly one retry (E3)", 2, transport.connectCallCount)
    }

    @Test
    fun contentionOutcomeIsMissedContention() = runTest {
        val transport = FakeGattTransport(
            connectOutcomes = listOf(ConnectOutcome.Failure(STATUS_BUSY), ConnectOutcome.Failure(STATUS_BUSY)),
        )

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Missed(MissReason.CONTENTION), outcome)
        assertEquals(2, transport.connectCallCount)
    }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val GATT_ERROR = 133
        const val STATUS_BUSY = 8
    }
}
