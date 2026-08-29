package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.fake.ConnectOutcome
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/prp/01-plan.md` WP-10's measurement-phase edge cases, tested against
 * *current* [GattSession] behavior rather than the Phase 1 spec wording —
 * Phase 2/3 implementation diverged from at least one named edge (E8, see
 * `disconnectDuringMeasurementReconnectsOnce`). See
 * `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md` Task 1.
 *
 * That E8 divergence has since been closed: the session now makes exactly one
 * reconnect attempt within [SessionBudget.RECONNECT_ONCE_WINDOW] and re-runs
 * the whole post-connect sequence, because neither the CCCD subscriptions nor
 * the UDS consent survive the link. The test that pinned the old
 * no-reconnect behaviour is replaced by
 * `disconnectDuringMeasurementReconnectsOnce` and
 * `aFailedReconnectGivesUpWithDropped`.
 *
 * Also discovered while writing these tests: `MeasurementCorrelator`'s
 * `MAX_EMISSIONS_PER_SESSION = 1` latch means a second `Stable` decode is
 * structurally impossible once one has been emitted this session, which made
 * `finishEmission`'s `DUPLICATE_STABLE_SUPPRESSED` counter unreachable. That
 * branch has since been deleted. The correlator's own
 * `unpairableFramesDropped`/`duplicateFramesSuppressed` counters are what
 * actually track this and are still not wired to `DiagnosticsCounters` — a
 * real, separate gap.
 *
 * [SessionBudget.HARD_SESSION_CEILING] *is* covered live, by
 * [anEmittedReadingSurvivesTheCeilingFiringDuringPostEmissionIdle]: no single
 * phase can reach 90s alone, but a drop late in the first measurement window
 * plus the one permitted reconnect's own 45s window does. The static
 * arithmetic guarantee is covered separately by
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
    private fun consentedTransport(
        connectOutcomes: List<ConnectOutcome> = listOf(ConnectOutcome.Success),
    ): FakeGattTransport = FakeGattTransport(
        connectOutcomes = connectOutcomes,
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
        val reading = requireNotNull((outcome as SessionOutcome.Completed).reading)
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertNull(
            "no body-composition frame arrived — a weight-only flush must not carry stale body-fat data",
            reading.bodyFatPct,
        )
    }

    /**
     * `MeasurementCorrelator.MAX_EMISSIONS_PER_SESSION = 1` is a permanent
     * one-shot latch: once a session emits one `Stable` reading, no later frame
     * can decode as `Stable` again — every emit path clears the buffered weight
     * first and the closed correlation blocks a new one from being buffered, so
     * a later frame becomes `Ignored` and the correlator's own
     * `unpairableFramesDropped` counts it. That is what made
     * `finishEmission`'s `DUPLICATE_STABLE_SUPPRESSED` branch unreachable in
     * *every* scenario rather than only this one, and why it is now deleted.
     * This test pins the observable behaviour that remains: a genuinely
     * independent second weigh-in landing in the post-emission idle window is
     * dropped, not double-emitted.
     */
    @Test
    fun aSecondIndependentPairDuringPostEmissionIdleIsDroppedNotEmittedTwice() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

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
        assertNotNull(
            "exactly one reading per session, per MeasurementCorrelator's one-shot latch",
            (outcome as SessionOutcome.Completed).reading,
        )
    }

    @Test
    fun disconnectDuringMeasurementReconnectsOnce() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.dropConnection()
        runCurrent()

        // Asserted on the transport, not just on the outcome: a "reconnect"
        // that only re-opened the link would pass an outcome-only check and
        // then receive nothing from a real scale, whose CCCDs and UDS consent
        // did not survive the drop. The second subscribe is the proof.
        assertEquals("E8 allows exactly one reconnect attempt", 2, transport.connectCallCount)
        assertEquals(
            "the reconnected leg must re-subscribe, not just reconnect",
            2,
            transport.callOrder.count { it == "subscribe:${SigWeightProfile.WEIGHT_MEASUREMENT}" },
        )

        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue("expected Completed after a recovered drop, got $outcome", outcome is SessionOutcome.Completed)
        assertEquals(
            Bf720Capture.EXPECTED_WEIGHT_KG,
            requireNotNull((outcome as SessionOutcome.Completed).reading).weightKg,
            TOLERANCE,
        )
    }

    @Test
    fun aFailedReconnectGivesUpWithDropped() = runTest {
        val transport = consentedTransport(listOf(ConnectOutcome.Success, ConnectOutcome.Timeout))
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.dropConnection()

        // Pins the 5 s window to behaviour: advanceUntilIdle() alone would pass
        // just as happily if RECONNECT_ONCE_WINDOW were 500 s.
        advanceTimeBy(SessionBudget.RECONNECT_ONCE_WINDOW.inWholeMilliseconds - 1)
        assertTrue("the reconnect window must still be open", deferred.isActive)
        advanceUntilIdle()

        assertEquals(SessionOutcome.Missed(MissReason.DROPPED), deferred.await())
        assertEquals("exactly one reconnect, then give up", 2, transport.connectCallCount)
    }

    /**
     * The reconnected leg re-runs the handshake, and a scale that refuses the
     * Consent it just granted must not cause a *second* user slot to be
     * allocated: `ScaleSessionPurpose.MEASUREMENT` carries
     * `permitsRegistration = false`, which is what stops `BeurerDecoder`'s
     * stale-credential recovery from re-registering here. E19 / ADR-007 —
     * burning scale slots is not recoverable from the app side.
     */
    @Test
    fun aRefusedConsentOnTheReconnectedLegNeverRegistersASecondSlot() = runTest {
        var consentWrites = 0
        val transport = FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                val opcode = bytes.firstOrNull()?.toInt()
                if (char == SigWeightProfile.USER_CONTROL_POINT && opcode == SigWeightProfile.UCP_CONSENT) {
                    consentWrites++
                    // Granted on the first leg, refused on the reconnected one.
                    val response = if (consentWrites == 1) {
                        Bf720Capture.consentSuccess()
                    } else {
                        Bf720Capture.consentFailure()
                    }
                    listOf(char to response)
                } else {
                    emptyList()
                }
            },
        )
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.dropConnection()
        advanceUntilIdle()

        deferred.await()
        assertTrue("the reconnect must actually have re-run the handshake", consentWrites >= 2)
        assertEquals(
            "a MEASUREMENT session may never allocate a scale slot",
            0,
            transport.writesPerformed.count {
                it.first == SigWeightProfile.USER_CONTROL_POINT &&
                    it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_REGISTER_NEW_USER
            },
        )
    }

    /**
     * E17 vs E8. A weight the correlator has already decoded and attributed is
     * a real measurement, not the "partial data" E8 discards — so a drop inside
     * the body-composition correlation window must still persist it as a
     * weight-only reading once the one permitted reconnect has failed. Before
     * this, `flush()` was reachable only from the correlation window's own
     * timeout and the buffered weight was dropped on the floor.
     */
    @Test
    fun aDropInsideTheCorrelationWindowStillPersistsTheBufferedWeight() = runTest {
        val transport = consentedTransport(listOf(ConnectOutcome.Success, ConnectOutcome.Timeout))
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        runCurrent()
        transport.dropConnection()
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue(
            "a decoded weight must not be discarded on a drop, got $outcome",
            outcome is SessionOutcome.Completed,
        )
        val reading = requireNotNull((outcome as SessionOutcome.Completed).reading)
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertNull("no body-composition frame ever arrived", reading.bodyFatPct)
    }

    /**
     * E7 vs E17. A Body Composition frame arriving before its Weight frame is a
     * routine ordering, not a fault: the correlator holds it as an orphan and
     * reports `Ignored`. The session used to treat *any* first frame as "a
     * weigh-in has started" and hand over to the 4 s correlation window, so the
     * real weight — which the BF720 does not report as stable until 8-15 s in —
     * arrived after the session had already given up.
     */
    @Test
    fun anOrphanBodyCompositionFrameDoesNotCollapseTheFortyFiveSecondBudget() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        advanceTimeBy(ORPHAN_TO_WEIGHT_GAP_MILLIS)
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue("the weight arrived well inside 45s, got $outcome", outcome is SessionOutcome.Completed)
        val reading = requireNotNull((outcome as SessionOutcome.Completed).reading)
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertEquals(
            "the orphan frame must still be paired, not merely survived",
            Bf720Capture.EXPECTED_BODY_FAT_PCT,
            reading.bodyFatPct ?: 0.0,
            TOLERANCE,
        )
    }

    /** The same budget guarantee for a malformed frame, which is a fault rather than an ordering. */
    @Test
    fun aMalformedFrameDoesNotCollapseTheFortyFiveSecondBudget() = runTest {
        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, byteArrayOf(0x0e))
        advanceTimeBy(ORPHAN_TO_WEIGHT_GAP_MILLIS)
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue("one bad frame must not end the wait, got $outcome", outcome is SessionOutcome.Completed)
        assertEquals(
            Bf720Capture.EXPECTED_WEIGHT_KG,
            requireNotNull((outcome as SessionOutcome.Completed).reading).weightKg,
            TOLERANCE,
        )
    }

    /**
     * C3. [SessionBudget.HARD_SESSION_CEILING] wraps the whole session including
     * [SessionBudget.POST_EMISSION_IDLE], so it can fire *after* a reading has
     * already been decoded, attributed and emitted. `flush()` cannot recover it
     * at that point — [com.ventouxlabs.bascule.ble.decoders.MeasurementCorrelator]
     * consumed `pendingWeight` during the emission — so before the fix the
     * ceiling silently turned a successful weigh-in into
     * `Missed(NO_MEASUREMENT)`.
     *
     * Reaching past 80 s takes two measurement windows: the weight lands late in
     * the first, the link drops, and the one permitted reconnect opens a second
     * 45 s window in which the body-composition frame completes the pair.
     */
    @Test
    fun anEmittedReadingSurvivesTheCeilingFiringDuringPostEmissionIdle() = runTest {
        // Late inside the first window, so the reconnected leg's own window still
        // reaches past the ceiling minus the post-emission idle timer.
        val dropAtMillis = SessionBudget.FIRST_INDICATION_TIMEOUT.inWholeMilliseconds - LATE_IN_WINDOW_MARGIN_MILLIS
        val emitAtMillis =
            (SessionBudget.HARD_SESSION_CEILING - SessionBudget.POST_EMISSION_IDLE / 2).inWholeMilliseconds
        assertTrue(
            "precondition: the reconnected leg's window must still be open at ${emitAtMillis}ms",
            dropAtMillis + SessionBudget.FIRST_INDICATION_TIMEOUT.inWholeMilliseconds > emitAtMillis,
        )

        val transport = consentedTransport()
        val deferred = async { session(transport).run() }

        runCurrent()
        advanceTimeBy(dropAtMillis)
        transport.indicate(SigWeightProfile.WEIGHT_MEASUREMENT, Bf720Capture.WEIGHT_MEASUREMENT)
        transport.dropConnection()
        runCurrent()
        assertEquals("the drop must be recovered by the one permitted reconnect", 2, transport.connectCallCount)

        advanceTimeBy(emitAtMillis - currentTime)
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        assertEquals(
            "the ceiling, not the measurement window, must be what ended the session",
            SessionBudget.HARD_SESSION_CEILING.inWholeMilliseconds,
            currentTime,
        )
        assertTrue(
            "an already-emitted reading must survive the ceiling, got $outcome",
            outcome is SessionOutcome.Completed,
        )
        val reading = requireNotNull((outcome as SessionOutcome.Completed).reading)
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertEquals(
            "the paired body-composition data must survive with it",
            Bf720Capture.EXPECTED_BODY_FAT_PCT,
            reading.bodyFatPct ?: 0.0,
            TOLERANCE,
        )
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

        /**
         * Comfortably past [SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW]
         * and comfortably inside [SessionBudget.FIRST_INDICATION_TIMEOUT], so
         * the assertion is specifically that the *first* window is still open.
         */
        const val ORPHAN_TO_WEIGHT_GAP_MILLIS = 10_000L

        /** How far short of a measurement window's end a frame counts as "late inside it". */
        const val LATE_IN_WINDOW_MARGIN_MILLIS = 2_000L
    }
}
