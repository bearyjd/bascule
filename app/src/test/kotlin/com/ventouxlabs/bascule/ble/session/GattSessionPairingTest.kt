package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E5/E5b (`00-design.md` §2.3), found for real on a phone the scale had never
 * met: the BF720 demands an encrypted link for its writes, so the first write
 * makes Android start pairing and pop a "Pair with BF720?" request, holding
 * the operation until a human answers. The session used to time that
 * operation out at 2 s and report "could not enable User Control Point
 * indications" — every session, forever, on any fresh install. The phone the
 * app was developed on never showed it because it had been bonded since the
 * first hardware session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionPairingTest {

    private val discovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to
                setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
            SigWeightProfile.CURRENT_TIME_SERVICE to setOf(SigWeightProfile.CURRENT_TIME),
        ),
    )

    private fun scale(
        suppressSubscriptionFor: Set<UUID> = emptySet(),
        suppressWriteCompleteFor: Set<UUID> = emptySet(),
    ) = FakeGattTransport(
        discovered = discovered,
        suppressSubscriptionFor = suppressSubscriptionFor,
        suppressWriteCompleteFor = suppressWriteCompleteFor,
        onWrite = { char, bytes ->
            if (char != SigWeightProfile.USER_CONTROL_POINT) {
                emptyList()
            } else {
                when (bytes.firstOrNull()?.toInt()) {
                    SigWeightProfile.UCP_REGISTER_NEW_USER -> listOf(char to Bf720Capture.registrationSuccess())
                    SigWeightProfile.UCP_CONSENT -> listOf(char to Bf720Capture.consentSuccess())
                    else -> emptyList()
                }
            }
        },
    )

    /** `stopAfterHandshake` so a successful handshake is a clean terminal outcome. */
    private fun session(transport: FakeGattTransport) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = InMemoryConsentStore(),
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = InMemoryDiagnosticsCounters(),
        stopAfterHandshake = true,
        clock = { Bf720Capture.expectedTimestampMillis },
    )

    /**
     * The exact shape seen on hardware: the User Control Point CCCD write is
     * held behind pairing. The session must wait for the human, not time out.
     */
    @Test
    fun pairingRequestedDuringTheHandshakeSubscriptionIsWaitedOutAndTheSessionProceeds() = runTest {
        val transport = scale(suppressSubscriptionFor = setOf(SigWeightProfile.USER_CONTROL_POINT))
        val deferred = async { session(transport).run() }
        runCurrent()

        transport.bondStateChanged(BOND_BONDING)
        runCurrent()
        advanceTimeBy(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT.inWholeMilliseconds + 1_000)
        assertTrue("must wait for the bond rather than fail at the write timeout", deferred.isActive)

        transport.bondStateChanged(BOND_BONDED)
        runCurrent()
        transport.completeSubscription(SigWeightProfile.USER_CONTROL_POINT)
        advanceUntilIdle()

        assertEquals(SessionOutcome.Completed(null), deferred.await())
    }

    /**
     * Pairing can also start on the opening Current Time write, before any
     * subscription. The discriminator is that the handshake must not have
     * begun while the bond is pending — the old code moved on after 2 s.
     */
    @Test
    fun pairingRequestedDuringTheOpeningWriteHoldsTheHandshakeUntilBonded() = runTest {
        val transport = scale(suppressWriteCompleteFor = setOf(SigWeightProfile.CURRENT_TIME))
        val deferred = async { session(transport).run() }
        runCurrent()

        transport.bondStateChanged(BOND_BONDING)
        runCurrent()
        advanceTimeBy(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT.inWholeMilliseconds + 1_000)
        assertFalse(
            "the handshake must not start while pairing is pending",
            transport.callOrder.any { it == "subscribe:${SigWeightProfile.USER_CONTROL_POINT}" },
        )
        assertTrue(deferred.isActive)

        transport.bondStateChanged(BOND_BONDED)
        advanceUntilIdle()

        assertEquals(SessionOutcome.Completed(null), deferred.await())
    }

    /** E5b: the user tapped "Cancel". Needs words, not a retry. */
    @Test
    fun aRefusedPairingEndsTheSessionAsPairingRequired() = runTest {
        val transport = scale(suppressSubscriptionFor = setOf(SigWeightProfile.USER_CONTROL_POINT))
        val deferred = async { session(transport).run() }
        runCurrent()

        transport.bondStateChanged(BOND_BONDING)
        runCurrent()
        transport.bondStateChanged(BOND_NONE)
        advanceUntilIdle()

        assertEquals(SessionOutcome.PairingRequired, deferred.await())
    }

    /** E5b: nobody answered. The wait is the design's 30 s, then the same outcome. */
    @Test
    fun anUnansweredPairingRequestEndsAsPairingRequiredAfterTheBondWait() = runTest {
        val transport = scale(suppressSubscriptionFor = setOf(SigWeightProfile.USER_CONTROL_POINT))
        val deferred = async { session(transport).run() }
        runCurrent()

        transport.bondStateChanged(BOND_BONDING)
        runCurrent()
        advanceTimeBy(SessionBudget.BOND_WAIT.inWholeMilliseconds - 1)
        assertTrue("the bond wait must still be open", deferred.isActive)
        advanceUntilIdle()

        assertEquals(SessionOutcome.PairingRequired, deferred.await())
    }

    /**
     * Regression guard for the reset: once a bond has landed, a later plain
     * timeout must be a plain timeout again, not a second 30 s pairing wait.
     */
    @Test
    fun aLaterTimeoutAfterASuccessfulBondDoesNotWaitForPairingAgain() = runTest {
        val transport = scale(
            suppressWriteCompleteFor = setOf(SigWeightProfile.CURRENT_TIME),
            suppressSubscriptionFor = setOf(SigWeightProfile.USER_CONTROL_POINT),
        )
        val deferred = async { session(transport).run() }
        runCurrent()
        transport.bondStateChanged(BOND_BONDING)
        runCurrent()
        transport.bondStateChanged(BOND_BONDED)
        runCurrent()

        // Current Time never completes (tolerated), then the UCP subscribe is
        // withheld for good: with no pairing pending that is an ordinary
        // failure inside the 2 s window, not another bond wait.
        advanceTimeBy(SessionBudget.OPENING_WRITE_COMPLETE_TIMEOUT.inWholeMilliseconds * 2 + 1_000)

        assertFalse("must have failed the ordinary way, not re-entered the bond wait", deferred.isActive)
        assertTrue(deferred.await() is SessionOutcome.HandshakeFailed)
    }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val BOND_NONE = 10
        const val BOND_BONDING = 11
        const val BOND_BONDED = 12
    }
}
