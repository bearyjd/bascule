package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/prp/01-plan.md` WP-10's measurement-phase edge cases, tested against
 * *current* [GattSession] behavior rather than the Phase 1 spec wording —
 * Phase 2/3 implementation diverged from at least one named edge (E8, see
 * `disconnectDuringMeasurementYieldsDroppedWithNoReconnectAttempt`). See
 * `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md` Task 1.
 *
 * Also discovered while writing these tests: `MeasurementCorrelator`'s
 * `MAX_EMISSIONS_PER_SESSION = 1` latch means `finishEmission`'s
 * `DUPLICATE_STABLE_SUPPRESSED` counter (added for a prior review finding)
 * can never actually fire — a second `Stable` decode is structurally
 * impossible once one has been emitted this session. See
 * `aSecondIndependentPairDuringPostEmissionIdleIsDroppedNotEmittedTwice`'s
 * own KDoc for detail. The correlator's own `unpairableFramesDropped`/
 * `duplicateFramesSuppressed` counters are what actually track this and
 * are not currently wired to `DiagnosticsCounters` at all — a real,
 * separate gap, out of scope for this test-only pass.
 *
 * Not covered here: a *live* test of [SessionBudget.HARD_SESSION_CEILING]
 * actually cutting a session off at 90s. Every individual phase (connect,
 * discovery, handshake ack ladder, measurement wait, correlation window) is
 * independently bounded well under 90s and returns a terminal outcome on its
 * own; constructing a live scenario that reaches the outer 90s wrapper
 * without terminating earlier via one of those bounded paths — short of
 * spinning a synchronous fake transport in a real, wall-clock infinite loop
 * with no virtual-time advancement — was not safely constructible in this
 * pass. The static arithmetic guarantee (every phase's worst case still fits
 * under the ceiling) is already covered by
 * [SessionBudgetTest.hardCeilingExceedsSumOfNonBondTimers].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionMeasureTest {

    private val discovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
        ),
    )

    /** A scale that has already granted consent — the handshake sends only Consent, never Register. */
    private fun consentedTransport(): FakeGattTransport = FakeGattTransport(
        discovered = discovered,
        onWrite = { char, bytes ->
            if (char == SigWeightProfile.USER_CONTROL_POINT &&
                bytes.firstOrNull()?.toInt() == SigWeightProfile.UCP_CONSENT
            ) {
                listOf(char to Bf720Capture.consentSuccess())
            } else {
                emptyList()
            }
        },
    )

    private fun session(
        transport: FakeGattTransport,
        diagnostics: InMemoryDiagnosticsCounters = InMemoryDiagnosticsCounters(),
    ) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = InMemoryConsentStore().apply {
            save(DEVICE_ADDRESS, ScaleCredential(Bf720Capture.EXPECTED_USER_INDEX, CONSENT_CODE))
        },
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = diagnostics,
        purpose = ScaleSessionPurpose.MEASUREMENT,
    )

    @Test
    fun noNotificationWithinFortyFiveSecondsYieldsNoMeasurement() = runTest {
        val outcome = session(consentedTransport()).run()

        assertEquals(SessionOutcome.Missed(MissReason.NO_MEASUREMENT), outcome)
    }

    @Test
    fun missingBodyCompositionFlushesWeightOnlyAfterFourSeconds() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue("expected Completed, got $outcome", outcome is SessionOutcome.Completed)
        val reading = (outcome as SessionOutcome.Completed).readings.single()
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertNull(
            "no body-composition frame arrived — a weight-only flush must not carry stale body-fat data",
            reading.bodyFatPct,
        )
    }

    /**
     * `MeasurementCorrelator.MAX_EMISSIONS_PER_SESSION = 1` is a permanent
     * one-shot latch (`MeasurementCorrelator.kt:166`): once a session emits
     * one `Stable` reading, a later frame can never decode as `Stable` again
     * — it becomes `Ignored`, and the correlator's own `unpairableFramesDropped`
     * counts it. `GattSession.finishEmission`'s `DUPLICATE_STABLE_SUPPRESSED`
     * check is therefore unreachable in this exact scenario, by design — this
     * test documents that (a genuinely independent second weigh-in landing in
     * the post-emission idle window is dropped, not double-counted or
     * double-emitted), not a gap in `finishEmission` itself.
     */
    @Test
    fun aSecondIndependentPairDuringPostEmissionIdleIsDroppedNotEmittedTwice() = runTest {
        val transport = consentedTransport()
        val diagnostics = InMemoryDiagnosticsCounters()
        val deferred = async { session(transport, diagnostics).run() }

        runCurrent()
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        runCurrent()
        // A second, independent weigh-in landing inside the post-emission idle window.
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue("expected Completed, got $outcome", outcome is SessionOutcome.Completed)
        assertEquals(
            "exactly one reading per session, per MeasurementCorrelator's one-shot latch",
            1,
            (outcome as SessionOutcome.Completed).readings.size,
        )
        assertEquals(
            "the correlator's own latch prevents a second Stable decode, so this path never fires",
            0,
            diagnostics.value(DiagnosticsCounterKey.DUPLICATE_STABLE_SUPPRESSED),
        )
    }

    @Test
    fun disconnectDuringMeasurementYieldsDroppedWithNoReconnectAttempt() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.dropConnection()
        advanceUntilIdle()

        assertEquals(SessionOutcome.Missed(MissReason.DROPPED), deferred.await())
        assertEquals("no reconnect was attempted after an in-measurement drop", 1, transport.connectCallCount)
    }

    @Test
    fun sessionTearsDownWithinPostEmissionIdleAfterASuccessfulReading() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        advanceUntilIdle()

        deferred.await()
        assertEquals(1, transport.closeCallCount)
    }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val CONSENT_CODE = 0x1234
        const val TOLERANCE = 1e-6
    }
}
