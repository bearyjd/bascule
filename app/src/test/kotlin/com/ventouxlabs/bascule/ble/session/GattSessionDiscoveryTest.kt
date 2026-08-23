package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.fake.DiscoverOutcome
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** `00-design.md` §2.1/§2.3 E4 — service discovery timeout and dispatch mismatch. */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionDiscoveryTest {

    private val allServicesDiscovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to
                setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
        ),
    )

    private val missingBodyCompositionService = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
        ),
    )

    private fun session(
        transport: FakeGattTransport,
        diagnostics: InMemoryDiagnosticsCounters = InMemoryDiagnosticsCounters(),
    ) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = InMemoryConsentStore(),
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = diagnostics,
    )

    @Test
    fun discoveryTimeoutAtFiveSeconds() = runTest {
        val transport = FakeGattTransport(discoverOutcome = DiscoverOutcome.Timeout)

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Incompatible, outcome)
        assertEquals("discovery timeout must not exceed its 5s budget", 5_000L, currentTime)
    }

    @Test
    fun missingRequiredServiceYieldsIncompatible() = runTest {
        val transport = FakeGattTransport(discovered = missingBodyCompositionService)

        val outcome = session(transport).run()

        assertEquals(SessionOutcome.Incompatible, outcome)
    }

    /**
     * A non-zero discovery status is a transport failure, not a statement about
     * the device — it must not count toward `incompatibleStreak`, or a radio
     * hiccup against the real scale eventually reads as "Scale not recognised".
     */
    @Test
    fun nonZeroDiscoveryStatusIsMissedNotIncompatible() = runTest {
        val diagnostics = InMemoryDiagnosticsCounters()
        val transport = FakeGattTransport(discoverOutcome = DiscoverOutcome.Failure(STATUS_DISCOVERY_FAILED))

        val outcome = session(transport, diagnostics).run()

        assertEquals(SessionOutcome.Missed(MissReason.DISCOVERY_FAILED), outcome)
        assertEquals(
            "a transport-level discovery failure must not count toward incompatibleStreak",
            0,
            diagnostics.value(DiagnosticsCounterKey.INCOMPATIBLE_STREAK),
        )
    }

    /**
     * `incompatibleStreak` (01-plan.md §2.1) is a session-external counter: it is
     * WP-06's job to increment/reset it correctly, not to act on it — actually
     * suspending scan arming at the threshold is ConfigScreen/ScaleScanner
     * consumer behaviour (WP-08+), which does not exist yet.
     */
    @Test
    fun thirdConsecutiveIncompatibleReachesTheSuspendThreshold() = runTest {
        val diagnostics = InMemoryDiagnosticsCounters()

        repeat(SessionBudget.INCOMPATIBLE_STREAK_SUSPEND_THRESHOLD) {
            val transport = FakeGattTransport(discovered = missingBodyCompositionService)
            session(transport, diagnostics).run()
        }

        assertEquals(
            SessionBudget.INCOMPATIBLE_STREAK_SUSPEND_THRESHOLD,
            diagnostics.value(DiagnosticsCounterKey.INCOMPATIBLE_STREAK),
        )
    }

    @Test
    fun aSuccessfulDiscoveryResetsTheIncompatibleStreak() = runTest {
        val diagnostics = InMemoryDiagnosticsCounters()
        session(FakeGattTransport(discovered = missingBodyCompositionService), diagnostics).run()
        session(FakeGattTransport(discovered = missingBodyCompositionService), diagnostics).run()
        assertEquals(2, diagnostics.value(DiagnosticsCounterKey.INCOMPATIBLE_STREAK))

        session(FakeGattTransport(discovered = allServicesDiscovered), diagnostics).run()

        assertEquals(0, diagnostics.value(DiagnosticsCounterKey.INCOMPATIBLE_STREAK))
    }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val STATUS_DISCOVERY_FAILED = 133
    }
}
