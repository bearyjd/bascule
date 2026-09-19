package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `00-design.md` §4.4 (Current Time), ADR-007 (RISK-1: UDS register/consent
 * handshake), §2.3 E6/E19 — WP-07. The decoder half of this conversation
 * (`BeurerDecoder`'s three-branch state machine, the UCP decode) landed in
 * WP-00 and is covered by `BeurerHandshakeTest`; this class covers the session
 * that drives it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionHandshakeTest {

    private val discovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to
                setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
            SigWeightProfile.CURRENT_TIME_SERVICE to setOf(SigWeightProfile.CURRENT_TIME),
        ),
    )

    private fun session(
        transport: FakeGattTransport,
        consentStore: InMemoryConsentStore = InMemoryConsentStore(),
        diagnostics: InMemoryDiagnosticsCounters = InMemoryDiagnosticsCounters(),
        stopAfterHandshake: Boolean = false,
    ) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = consentStore,
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = diagnostics,
        stopAfterHandshake = stopAfterHandshake,
        clock = { Bf720Capture.expectedTimestampMillis },
    )

    /** A scale this phone has already registered with, so the handshake goes straight to Consent. */
    private fun storedCredential(): InMemoryConsentStore = InMemoryConsentStore().apply {
        save(DEVICE_ADDRESS, ScaleCredential(scaleIndex = Bf720Capture.EXPECTED_USER_INDEX, consentCode = 0x1234))
    }

    /**
     * A scale whose Consent writes are never answered automatically: the test
     * scripts the stored weigh-in and the consent response by hand, so it
     * controls exactly when each lands. Register is answered as usual.
     */
    private fun scaleAnsweringConsentByHand(): FakeGattTransport = consentingScale { opcode ->
        if (opcode == SigWeightProfile.UCP_REGISTER_NEW_USER) Bf720Capture.registrationSuccess() else null
    }

    /**
     * Delivers a weigh-in the BF720 took while no phone was connected, the way
     * the scale does (`03-hardware-validation.md`, "Consented reads,
     * 2026-09-19"): stored under the user it recognised and indicated exactly
     * once, ~1 s after that user's Consent write and *before* the consent
     * response — and, like any indication, only on a CCCD that is already
     * enabled. A frame indicated while its CCCD is off is simply gone, which
     * this models by checking the subscription table at delivery time. Call
     * with the Consent write already out; the consent response is the caller's.
     */
    private fun TestScope.deliverStoredWeighIn(
        transport: FakeGattTransport,
        frames: List<Pair<UUID, ByteArray>> = STORED_WEIGH_IN,
    ) {
        advanceTimeBy(STORED_DELIVERY_DELAY_MILLIS)
        indicateOnEnabledCccds(transport, frames)
        runCurrent()
    }

    private fun indicateOnEnabledCccds(transport: FakeGattTransport, frames: List<Pair<UUID, ByteArray>>) {
        for ((char, value) in frames) {
            if (char in transport.subscribedCharacteristics) transport.indicate(char, value)
        }
    }

    /** A scale that answers Register+Consent and consents. */
    private fun consentingScale(onUcpWrite: (Int?) -> ByteArray?): FakeGattTransport =
        FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    onUcpWrite(bytes.firstOrNull()?.toInt())?.let { listOf(char to it) } ?: emptyList()
                }
            },
        )

    private fun happyPathScale(): FakeGattTransport = consentingScale { opcode ->
        when (opcode) {
            SigWeightProfile.UCP_REGISTER_NEW_USER -> Bf720Capture.registrationSuccess()
            SigWeightProfile.UCP_CONSENT -> Bf720Capture.consentSuccess()
            else -> null
        }
    }

    @Test
    fun writesCurrentTimeBeforeRegisterOrConsent() = runTest {
        val transport = happyPathScale()

        session(transport).run()

        val ucpWriteIndex = transport.writesPerformed.indexOfFirst { it.first == SigWeightProfile.USER_CONTROL_POINT }
        val ctsWriteIndex = transport.writesPerformed.indexOfFirst { it.first == SigWeightProfile.CURRENT_TIME }
        val order = transport.writesPerformed.map { it.first }
        assertTrue("Current Time was never written", ctsWriteIndex >= 0)
        assertTrue(
            "Current Time must be written before the first UCP write, got $order",
            ctsWriteIndex < ucpWriteIndex,
        )
    }

    /**
     * Best-effort per `ScaleDecoder.openingSequence`'s KDoc: a CTS write that
     * never completes must not block or fail the session. Uses
     * `suppressWriteCompleteFor` rather than a slow scale, so this proves the
     * *timeout* path specifically rather than merely that a fast write works.
     */
    @Test
    fun currentTimeWriteNeverCompletingDoesNotBlockOrFailTheSession() = runTest {
        val transport = FakeGattTransport(
            discovered = discovered,
            suppressWriteCompleteFor = setOf(SigWeightProfile.CURRENT_TIME),
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

        val outcome = session(transport).run()

        assertFalse(
            "a CTS timeout must never surface as a handshake failure",
            outcome is SessionOutcome.HandshakeFailed,
        )
        assertTrue(SigWeightProfile.WEIGHT_MEASUREMENT in transport.subscribedCharacteristics)
    }

    @Test
    fun registersWhenNoCredentialIsStored() = runTest {
        val transport = happyPathScale()

        session(transport, InMemoryConsentStore()).run()

        assertTrue(
            "no stored credential must register",
            transport.writesPerformed.any {
                it.first == SigWeightProfile.USER_CONTROL_POINT &&
                    it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_REGISTER_NEW_USER
            },
        )
    }

    @Test
    fun sendsConsentDirectlyWhenACredentialIsStored() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save(DEVICE_ADDRESS, ScaleCredential(scaleIndex = Bf720Capture.EXPECTED_USER_INDEX, consentCode = 0x1234))
        }
        val transport = happyPathScale()

        session(transport, consentStore).run()

        assertFalse(
            "a stored credential must not trigger a fresh registration",
            transport.writesPerformed.any {
                it.first == SigWeightProfile.USER_CONTROL_POINT &&
                    it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_REGISTER_NEW_USER
            },
        )
        assertTrue(
            "a stored credential must go straight to Consent",
            transport.writesPerformed.any {
                it.first == SigWeightProfile.USER_CONTROL_POINT &&
                    it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_CONSENT
            },
        )
    }

    @Test
    fun rejectedStoredCredentialFallsBackToRegistering() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save(DEVICE_ADDRESS, ScaleCredential(scaleIndex = 5, consentCode = 0x9999))
        }
        var consentAttempts = 0
        val transport = consentingScale { opcode ->
            when (opcode) {
                SigWeightProfile.UCP_CONSENT -> {
                    consentAttempts++
                    if (consentAttempts == 1) Bf720Capture.consentFailure() else Bf720Capture.consentSuccess()
                }
                SigWeightProfile.UCP_REGISTER_NEW_USER -> Bf720Capture.registrationSuccess()
                else -> null
            }
        }

        val outcome = session(transport, consentStore).run()

        assertTrue("expected a completed handshake, got $outcome", outcome !is SessionOutcome.HandshakeFailed)
        assertTrue(
            "a stale stored credential must fall back to registering",
            transport.writesPerformed.any {
                it.first == SigWeightProfile.USER_CONTROL_POINT &&
                    it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_REGISTER_NEW_USER
            },
        )
        assertEquals(2, consentAttempts)
    }

    /** The one that stops every weigh-in burning a profile slot (O-08). */
    @Test
    fun assignedScaleIndexIsPersistedToConsentStore() = runTest {
        val consentStore = InMemoryConsentStore()
        val transport = happyPathScale()

        session(transport, consentStore).run()

        assertEquals(
            Bf720Capture.EXPECTED_USER_INDEX,
            consentStore.credentialFor(DEVICE_ADDRESS)?.scaleIndex,
        )
    }

    @Test
    fun userControlPointIndicationsAreEnabledBeforeTheFirstHandshakeWrite() = runTest {
        val transport = happyPathScale()

        session(transport).run()

        val subscription = transport.callOrder.indexOf("subscribe:${SigWeightProfile.USER_CONTROL_POINT}")
        val firstWrite = transport.callOrder.indexOf("write:${SigWeightProfile.USER_CONTROL_POINT}")
        assertTrue("the scale cannot acknowledge a write before its indication CCCD is enabled", subscription >= 0)
        assertTrue("UCP subscription must complete before Register/Consent", subscription < firstWrite)
    }

    /**
     * The scale's one delivery of a stored weigh-in lands ~1 s after the Consent
     * write, so the measurement CCCDs must already be enabled by then — after
     * the UCP CCCD (which has to precede the first UCP write) and before that
     * first write goes out. Order pinned on the transport, not membership.
     */
    @Test
    fun measurementIndicationsAreEnabledAfterTheUcpCccdAndBeforeTheFirstHandshakeWrite() = runTest {
        val transport = happyPathScale()

        session(transport).run()

        val order = transport.callOrder
        val ucpSubscription = order.indexOf("subscribe:${SigWeightProfile.USER_CONTROL_POINT}")
        val firstWrite = order.indexOf("write:${SigWeightProfile.USER_CONTROL_POINT}")
        for (char in listOf(SigWeightProfile.WEIGHT_MEASUREMENT, SigWeightProfile.BODY_COMPOSITION_MEASUREMENT)) {
            val subscription = order.indexOf("subscribe:$char")
            assertTrue("$char was never subscribed, got $order", subscription >= 0)
            assertTrue("$char must be subscribed after the UCP CCCD, got $order", subscription > ucpSubscription)
            assertTrue("$char must be subscribed before the first UCP write, got $order", subscription < firstWrite)
        }
    }

    /**
     * The one that pins the whole change: a weigh-in taken while no phone was
     * connected reaches the phone on the next consent. Before this, the frames
     * arrived while the session was still waiting for the consent response and
     * were decoded on the spot — the decoder answered `Wait`, the `Stable`
     * reading was dropped, and the session sat out the measurement window for a
     * weigh-in it had already been handed.
     */
    @Test
    fun aWeighInStoredOnTheScaleIsDeliveredOnConsentAndCaptured() = runTest {
        val transport = scaleAnsweringConsentByHand()
        val deferred = async { session(transport, storedCredential()).run() }

        runCurrent() // the Consent write is out
        deliverStoredWeighIn(transport)
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceUntilIdle()

        val outcome = deferred.await()
        val reading = requireNotNull((outcome as? SessionOutcome.Completed)?.reading) {
            "the stored weigh-in must become this session's reading, got $outcome"
        }
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, reading.userIndex)
        assertEquals(
            "the body-composition half must be paired, not flushed weight-only",
            Bf720Capture.EXPECTED_BODY_FAT_PCT,
            reading.bodyFatPct ?: 0.0,
            TOLERANCE,
        )
        assertTrue(
            "captured from the deferred frames, never by waiting out the measurement window",
            currentTime < SessionBudget.FIRST_INDICATION_TIMEOUT.inWholeMilliseconds,
        )
    }

    /**
     * A stored-measurement frame arriving mid-handshake is not the ack the
     * write is waiting for, and must neither satisfy nor extend E6: the write
     * is reissued on the same 3 s timer as if nothing had arrived, and the
     * frames deferred across that reissue still become the reading. The
     * frames land ~1 s in, as on hardware, so "reissued at exactly 3 s" also
     * rules out a timer that restarts on the frame (which would fire at 4 s).
     */
    @Test
    fun aStoredMeasurementFrameIsNotAnAckAndDoesNotConsumeAnE6Retry() = runTest {
        val transport = scaleAnsweringConsentByHand()
        val deferred = async { session(transport, storedCredential()).run() }

        runCurrent()
        assertEquals(1, ucpWriteCount(transport))
        // Consent #1's response is lost; only the stored weigh-in arrives.
        deliverStoredWeighIn(transport)

        advanceTimeBy(SessionBudget.HANDSHAKE_ACK_TIMEOUT.inWholeMilliseconds - STORED_DELIVERY_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(
            "the frames must not read as the ack — the write is still outstanding at 2999 ms",
            1,
            ucpWriteCount(transport),
        )

        advanceTimeBy(1)
        runCurrent()
        assertEquals("E6 reissues at 3000 ms, exactly as if no frame had arrived", 2, ucpWriteCount(transport))

        // The reissue is answered; nothing stored is left to deliver.
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceUntilIdle()
        val outcome = deferred.await()
        val reading = requireNotNull((outcome as? SessionOutcome.Completed)?.reading) {
            "frames deferred across the reissue must still be delivered after the ack, got $outcome"
        }
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
    }

    /**
     * The narrow race the drain before a reissue used to lose: a frame that is
     * *in the channel* — indicated, but not yet received — at the instant the
     * ack timer fires. `drainStaleEvents` exists to discard responses to the
     * write being superseded; a measurement frame is never stale in that
     * sense, and the session's own drain now keeps it.
     */
    @Test
    fun aStoredMeasurementFrameLandingAsTheAckTimerFiresIsKeptNotDrained() = runTest {
        val transport = scaleAnsweringConsentByHand()
        val deferred = async { session(transport, storedCredential()).run() }

        runCurrent()
        // Clock at the timer's instant, the timeout task pending but not yet
        // run; the frames go in ahead of it in the same instant.
        advanceTimeBy(SessionBudget.HANDSHAKE_ACK_TIMEOUT.inWholeMilliseconds)
        assertEquals("precondition: the reissue must not have gone out yet", 1, ucpWriteCount(transport))
        indicateOnEnabledCccds(transport, STORED_WEIGH_IN)
        runCurrent()
        assertEquals("the reissue must have fired with the frames in the channel", 2, ucpWriteCount(transport))

        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceUntilIdle()
        val outcome = deferred.await()
        val reading = requireNotNull((outcome as? SessionOutcome.Completed)?.reading) {
            "a frame in the channel at the timer's instant must not be drained away, got $outcome"
        }
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
    }

    /**
     * Only the weight half arrives during the handshake and its body-composition
     * pair follows just after the consent response — the session must pair
     * them, and end well inside the correlation window plus post-emission idle,
     * never sit out the first-indication wait.
     */
    @Test
    fun aDeferredWeightFramePairsWithTheBodyCompositionThatFollowsTheConsentResponse() = runTest {
        val transport = scaleAnsweringConsentByHand()
        val deferred = async { session(transport, storedCredential()).run() }

        runCurrent()
        deliverStoredWeighIn(transport, STORED_WEIGH_IN.take(1))
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceTimeBy(PAIR_AFTER_CONSENT_MILLIS)
        transport.indicate(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, Bf720Capture.BODY_COMPOSITION_MEASUREMENT)
        advanceUntilIdle()

        val outcome = deferred.await()
        val reading = requireNotNull((outcome as? SessionOutcome.Completed)?.reading) { "got $outcome" }
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertEquals(
            "the late body-composition half must be paired with the deferred weight",
            Bf720Capture.EXPECTED_BODY_FAT_PCT,
            reading.bodyFatPct ?: 0.0,
            TOLERANCE,
        )
        assertTrue(
            "ended at ${currentTime}ms: the pair landed, so only the post-emission idle should remain",
            currentTime <= STORED_DELIVERY_DELAY_MILLIS + PAIR_AFTER_CONSENT_MILLIS +
                SessionBudget.POST_EMISSION_IDLE.inWholeMilliseconds,
        )
    }

    /**
     * E17 for a deferred weight: its pair is owed the 4 s correlation window,
     * measured from the weight the session already holds — not E7's long
     * first-indication wait, which is for a weigh-in that has not started.
     */
    @Test
    fun aDeferredWeightFrameWhosePairNeverComesIsFlushedAtTheCorrelationBudget() = runTest {
        val transport = scaleAnsweringConsentByHand()
        val deferred = async { session(transport, storedCredential()).run() }

        runCurrent()
        deliverStoredWeighIn(transport, STORED_WEIGH_IN.take(1))
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceUntilIdle()

        val outcome = deferred.await()
        val reading = requireNotNull((outcome as? SessionOutcome.Completed)?.reading) { "got $outcome" }
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertNull("no body-composition frame ever arrived", reading.bodyFatPct)
        assertEquals(
            "flushed at the correlation budget, not the first-indication timeout",
            STORED_DELIVERY_DELAY_MILLIS + SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW.inWholeMilliseconds,
            currentTime,
        )
    }

    /**
     * A registration-only session stops at the handshake with no reading
     * (`ScaleSessionContractTest.aRegistrationOnlySessionCompletesWithNoReading`),
     * and that holds even if frames arrive during it — a just-registered user
     * has nothing stored against them, so a frame here is a log line, not a
     * reading.
     */
    @Test
    fun aRegistrationOnlySessionStillCompletesWithNoReadingWhenFramesArriveDuringTheHandshake() = runTest {
        val transport = scaleAnsweringConsentByHand()
        val deferred = async { session(transport, stopAfterHandshake = true).run() }

        runCurrent() // Register answered on the spot; the Consent write is out
        deliverStoredWeighIn(transport)
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue("expected Completed, got $outcome", outcome is SessionOutcome.Completed)
        assertNull("registration stops at the handshake", (outcome as SessionOutcome.Completed).reading)
    }

    /**
     * The E6 gate (O-11 item 1), restated for where it now lives. Enabling the
     * measurement CCCDs early is what lets a stored weigh-in through; *listening*
     * is still gated on `ConsentResult(success = true)`. A lost consent must
     * therefore surface as the handshake failure it is, inside E6's own ack
     * ladder — never as a measurement window of silence.
     */
    @Test
    fun aLostConsentIsAHandshakeFailureNotAMeasurementWindowOfSilence() = runTest {
        val transport = consentingScale { opcode ->
            if (opcode == SigWeightProfile.UCP_REGISTER_NEW_USER) Bf720Capture.registrationSuccess() else null
        }

        val outcome = session(transport).run()

        assertTrue("expected HandshakeFailed, got $outcome", outcome is SessionOutcome.HandshakeFailed)
        assertTrue(
            "the session must end inside E6's ack ladder, never reach the measurement wait",
            currentTime < SessionBudget.FIRST_INDICATION_TIMEOUT.inWholeMilliseconds,
        )
    }

    @Test
    fun refusedRegistrationYieldsHandshakeFailedAndCounts() = runTest {
        val diagnostics = InMemoryDiagnosticsCounters()
        val transport = consentingScale { opcode ->
            if (opcode == SigWeightProfile.UCP_REGISTER_NEW_USER) Bf720Capture.registrationFailure() else null
        }

        val outcome = session(transport, diagnostics = diagnostics).run()

        assertTrue("expected HandshakeFailed, got $outcome", outcome is SessionOutcome.HandshakeFailed)
        assertEquals(1, diagnostics.value(DiagnosticsCounterKey.REGISTRATION_REJECTED))
    }

    @Test
    fun missingAckReissuesWriteAfterThreeSeconds() = runTest {
        val transport = FakeGattTransport(discovered = discovered) // never answers any write
        val deferred = async { session(transport).run() }

        runCurrent()
        assertEquals(1, ucpWriteCount(transport))

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals("reissue fired before the 3s ack timeout elapsed", 1, ucpWriteCount(transport))

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, ucpWriteCount(transport))

        advanceUntilIdle()
        deferred.await()
    }

    @Test
    fun reissuesAtMostTwiceThenTearsDown() = runTest {
        val transport = FakeGattTransport(discovered = discovered) // never answers any write

        val outcome = session(transport).run()

        assertTrue("expected HandshakeFailed, got $outcome", outcome is SessionOutcome.HandshakeFailed)
        assertEquals("initial write + 2 retries = 3 total", 3, ucpWriteCount(transport))
    }

    /** A reissue whose retry (not the original) lands still completes normally. */
    @Test
    fun reissuedRegisterThatLandsCompletesTheHandshake() = runTest {
        var registerAttempts = 0
        val transport = consentingScale { opcode ->
            when (opcode) {
                SigWeightProfile.UCP_REGISTER_NEW_USER -> {
                    registerAttempts++
                    if (registerAttempts == 1) null else Bf720Capture.registrationSuccess()
                }
                SigWeightProfile.UCP_CONSENT -> Bf720Capture.consentSuccess()
                else -> null
            }
        }

        session(transport).run()

        assertEquals("one silent attempt, one reissue that lands", 2, registerAttempts)
        assertEquals(
            "no double subscription from the reissue",
            1,
            transport.callOrder.count { it == "subscribe:${SigWeightProfile.WEIGHT_MEASUREMENT}" },
        )
        assertTrue(SigWeightProfile.WEIGHT_MEASUREMENT in transport.subscribedCharacteristics)
    }

    /**
     * Regression test for the hazard found in review: the UCP wire protocol
     * carries no correlation ID, so a response to a *superseded* write is
     * byte-identical to a fresh one. Draining the event channel before each
     * write cannot close this — "late" means "not yet arrived", so nothing is
     * there to drain — so the actual fix lives in `BeurerDecoder`: once a
     * refused stored credential has driven a re-registration this session, a
     * bounded budget (`staleResponseBudget`, at most as many writes as could
     * possibly have been superseded) absorbs same-type refusals instead of
     * treating every one as fatal, with the next one past the budget treated
     * as genuine.
     *
     * Drives the exact two-refusal sequence that exposes the hazard: consent
     * write #1 (stale stored credential) times out and reissues as #2; #1's
     * late refusal then arrives, kicking off re-registration and consent
     * write #3; #2's own now-orphaned late refusal arrives *while #3 is
     * outstanding* — type-matching #3's wait exactly, since both are
     * `AwaitingConsent(registered=true)`. Only then does #3's real answer
     * land. If the decoder treated either late refusal as answering #3, the
     * handshake would fail here.
     */
    @Test
    fun lateResponseToASupersededConsentWriteDoesNotMisfireTheNextStep() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save(DEVICE_ADDRESS, ScaleCredential(scaleIndex = 5, consentCode = 0x9999))
        }
        val transport = FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    when (bytes.firstOrNull()?.toInt()) {
                        SigWeightProfile.UCP_REGISTER_NEW_USER -> listOf(char to Bf720Capture.registrationSuccess())
                        // No consent write ever gets an automatic response —
                        // every answer, including the real one, is pushed
                        // manually below so the test controls exactly when
                        // each lands relative to the session's current wait.
                        SigWeightProfile.UCP_CONSENT -> emptyList()
                        else -> emptyList()
                    }
                }
            },
        )
        val deferred = async { session(transport, consentStore).run() }

        runCurrent() // consent write #1, for the stale stored credential
        advanceTimeBy(3_000)
        runCurrent() // E6 fires: consent write #2 (the reissue) goes out

        // Pin the precondition this test exists to construct: two consent
        // writes genuinely in flight, not one. Without this, a retuned
        // ack-timeout or a shifted advanceTimeBy boundary could silently stop
        // the reissue from firing and this test would keep passing for the
        // wrong reason — testing only a single-write race, not the two-write
        // one the KDoc above describes.
        assertEquals("the E6 reissue must have gone out before the late refusals below", 2, ucpWriteCount(transport))

        // Write #1's late refusal arrives now, while #2 is outstanding. Drives
        // re-registration; register auto-succeeds; consent write #3 goes out
        // and the session starts waiting on it — all without a time advance,
        // so the wait for #3 is still open when the next indicate lands.
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentFailure())
        runCurrent()

        // Write #2's late refusal — the reissue's own now-orphaned response —
        // arrives while #3 is outstanding. Byte-identical to a genuine new
        // refusal of #3; must not misfire an Abort.
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentFailure())
        runCurrent()

        // #3's real answer finally arrives.
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentSuccess())
        advanceUntilIdle()

        val outcome = deferred.await()

        assertTrue("expected a completed handshake, got $outcome", outcome !is SessionOutcome.HandshakeFailed)
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, consentStore.credentialFor(DEVICE_ADDRESS)?.scaleIndex)
        assertEquals(
            "exactly one subscribe, no matter how many stale UCP responses arrived",
            1,
            transport.callOrder.count { it == "subscribe:${SigWeightProfile.WEIGHT_MEASUREMENT}" },
        )
    }

    /**
     * Companion to [lateResponseToASupersededConsentWriteDoesNotMisfireTheNextStep]:
     * proves the other half of the stale-response budget's trade-off — when
     * consent is *genuinely* still refused after re-registration, not stale,
     * the budget (`HANDSHAKE_ACK_MAX_RETRIES`, exactly the number of writes
     * that could possibly have been superseded) absorbs only that many
     * refusals before aborting on the very next one, with the accurate
     * decoder-native reason. It must NOT take E6's own multi-retry ladder to
     * get there — that would mean the budget silently became unbounded again.
     */
    @Test
    fun consentGenuinelyStillRefusedAbortsAssoonAsTheStaleResponseBudgetIsExhausted() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save(DEVICE_ADDRESS, ScaleCredential(scaleIndex = 5, consentCode = 0x9999))
        }
        val transport = FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    when (bytes.firstOrNull()?.toInt()) {
                        SigWeightProfile.UCP_REGISTER_NEW_USER -> listOf(char to Bf720Capture.registrationSuccess())
                        // Every consent write is refused, forever — including
                        // after re-registration. No stale-response ambiguity
                        // here; this is the "genuinely still refused" case the
                        // budget must not absorb indefinitely.
                        SigWeightProfile.UCP_CONSENT -> listOf(char to Bf720Capture.consentFailure())
                        else -> emptyList()
                    }
                }
            },
        )

        val outcome = session(transport, consentStore).run()

        assertTrue("must terminate via HandshakeFailed, not hang", outcome is SessionOutcome.HandshakeFailed)
        val detail = (outcome as SessionOutcome.HandshakeFailed).detail
        assertEquals(
            "budget-exhaustion must abort with the decoder's own accurate reason, not a generic E6 message",
            "scale refused consent for a just-registered user",
            detail,
        )
        assertEquals(
            "exactly budget(2)+1 post-registration consent writes: 2 absorbed, the 3rd aborts on arrival — " +
                "any more means the budget didn't bound anything",
            1 + 1 + (SessionBudget.HANDSHAKE_ACK_MAX_RETRIES + 1),
            ucpWriteCount(transport),
        )
    }

    /**
     * The other side of [consentGenuinelyStillRefusedAbortsAssoonAsTheStaleResponseBudgetIsExhausted]:
     * one stale-looking refusal is absorbed post-registration, and then the
     * scale genuinely stops responding altogether — no more refusals, no
     * acks, nothing. There is no way to distinguish this from more stale
     * responses that simply never arrive, so this case is E6's own ack ladder
     * to resolve, not the budget's. Pins [handshakeSawUnverifiableResponse]'s
     * purpose: the eventual abort must say a response *did* arrive and
     * couldn't be trusted, not falsely claim total silence.
     */
    @Test
    fun oneAbsorbedRefusalThenTotalSilenceAbortsViaE6WithTheUnverifiableAckMessage() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save(DEVICE_ADDRESS, ScaleCredential(scaleIndex = 5, consentCode = 0x9999))
        }
        var consentAttempts = 0
        val transport = FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    when (bytes.firstOrNull()?.toInt()) {
                        SigWeightProfile.UCP_REGISTER_NEW_USER -> listOf(char to Bf720Capture.registrationSuccess())
                        SigWeightProfile.UCP_CONSENT -> {
                            consentAttempts++
                            // Attempt #1 (pre-registration, stale credential):
                            // always refused, driving re-registration. Attempt
                            // #2 (the 1st post-registration write) gets one
                            // more refusal — still plausibly stale, so the
                            // budget absorbs it. Every attempt after that goes
                            // completely silent: no ack, no refusal, nothing.
                            if (consentAttempts <= 2) {
                                listOf(char to Bf720Capture.consentFailure())
                            } else {
                                emptyList()
                            }
                        }
                        else -> emptyList()
                    }
                }
            },
        )

        val outcome = session(transport, consentStore).run()

        assertTrue("must terminate via HandshakeFailed, not hang", outcome is SessionOutcome.HandshakeFailed)
        val detail = (outcome as SessionOutcome.HandshakeFailed).detail
        assertTrue(
            "reason must not claim no ack arrived when a refusal actually did: $detail",
            detail.contains("could not be attributed"),
        )
    }

    /**
     * A duplicate response to the same write must not be processed twice. The
     * decoder's own state machine is what makes this safe — once it has moved
     * past `AwaitingRegistration`, a second `RegistrationResult` routes to
     * `onConsentEvent`, which returns `Wait` for anything that isn't a
     * `ConsentResult` — so this proves the property end to end, not just that
     * the decoder alone would handle it.
     */
    @Test
    fun duplicateAckIsIdempotent() = runTest {
        val consentStore = InMemoryConsentStore()
        // consentingScale's single-response shape can't express a duplicate in
        // one write's response list, so this scripts FakeGattTransport directly.
        val transport = FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    when (bytes.firstOrNull()?.toInt()) {
                        SigWeightProfile.UCP_REGISTER_NEW_USER -> listOf(
                            char to Bf720Capture.registrationSuccess(),
                            char to Bf720Capture.registrationSuccess(), // duplicate
                        )
                        SigWeightProfile.UCP_CONSENT -> listOf(char to Bf720Capture.consentSuccess())
                        else -> emptyList()
                    }
                }
            },
        )

        session(transport, consentStore).run()

        assertEquals(
            "the duplicate must not trigger a second registration/consent round trip",
            1,
            transport.writesPerformed.count {
                it.first == SigWeightProfile.USER_CONTROL_POINT &&
                    it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_CONSENT
            },
        )
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, consentStore.credentialFor(DEVICE_ADDRESS)?.scaleIndex)
    }

    /** An indication unrelated to the outstanding write must not fail the handshake. */
    @Test
    fun unrelatedIndicationMidHandshakeIsAWaitNotAFailure() = runTest {
        val transport = FakeGattTransport(
            discovered = discovered,
            onWrite = { char, bytes ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    when (bytes.firstOrNull()?.toInt()) {
                        SigWeightProfile.UCP_REGISTER_NEW_USER -> listOf(
                            // Unrelated indication arriving mid-handshake, ahead of the real ack.
                            SigWeightProfile.WEIGHT_MEASUREMENT to Bf720Capture.WEIGHT_MEASUREMENT,
                            char to Bf720Capture.registrationSuccess(),
                        )
                        SigWeightProfile.UCP_CONSENT -> listOf(char to Bf720Capture.consentSuccess())
                        else -> emptyList()
                    }
                }
            },
        )

        val outcome = session(transport).run()

        assertFalse(
            "an unrelated indication must not be mistaken for a handshake failure",
            outcome is SessionOutcome.HandshakeFailed,
        )
        assertTrue(SigWeightProfile.WEIGHT_MEASUREMENT in transport.subscribedCharacteristics)
    }

    /**
     * §8.8's "opcode and length only" obligation is not yet fully wired for
     * E6 — the detail string here is a fixed literal, not an opcode/length
     * report (tracked as a residue in `01-plan.md`'s WP-07 amendment). This
     * test asserts the property that does hold today: whatever the detail
     * string says, it never contains a raw payload byte dump.
     */
    @Test
    fun handshakeFailureDetailNeverLeaksPayloadBytes() = runTest {
        val transport = FakeGattTransport(discovered = discovered) // never answers any write

        val outcome = session(transport).run()

        assertTrue(outcome is SessionOutcome.HandshakeFailed)
        val detail = (outcome as SessionOutcome.HandshakeFailed).detail
        assertEquals("no ack after ${SessionBudget.HANDSHAKE_ACK_MAX_RETRIES} retries", detail)
        assertFalse(
            "failure detail must never leak raw payload bytes (§8.8)",
            detail.contains("byteArrayOf") || Regex("0x[0-9a-fA-F]{2}").containsMatchIn(detail),
        )
    }

    @Test
    fun adapterOffMidHandshakeIsMissedNotHandshakeFailed() = runTest {
        val transport = FakeGattTransport(discovered = discovered) // never answers any write
        val deferred = async { session(transport).run() }

        // Current Time completes immediately (unsuppressed, per the default),
        // so by the time this settles the session is waiting on the first
        // Register/Consent write's ack.
        runCurrent()
        transport.emitAdapterOff()
        advanceUntilIdle()

        assertEquals(SessionOutcome.Missed(MissReason.ADAPTER_OFF), deferred.await())
    }

    @Test
    fun adapterOffDuringTheOpeningWriteIsMissedNotIncompatibleOrHandshakeFailed() = runTest {
        val transport = FakeGattTransport(
            discovered = discovered,
            suppressWriteCompleteFor = setOf(SigWeightProfile.CURRENT_TIME),
        )
        val deferred = async { session(transport).run() }

        runCurrent() // Current Time write goes out, suppressed, so it just waits
        transport.emitAdapterOff()
        advanceUntilIdle()

        assertEquals(SessionOutcome.Missed(MissReason.ADAPTER_OFF), deferred.await())
    }

    private fun ucpWriteCount(transport: FakeGattTransport): Int =
        transport.writesPerformed.count { it.first == SigWeightProfile.USER_CONTROL_POINT }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val TOLERANCE = 1e-6

        /** The stored pair, Weight first, as the BF720 indicates it. */
        val STORED_WEIGH_IN: List<Pair<UUID, ByteArray>> = listOf(
            SigWeightProfile.WEIGHT_MEASUREMENT to Bf720Capture.WEIGHT_MEASUREMENT,
            SigWeightProfile.BODY_COMPOSITION_MEASUREMENT to Bf720Capture.BODY_COMPOSITION_MEASUREMENT,
        )

        /** Observed: the stored pair lands ~1.15 s after the Consent write, ahead of its response. */
        const val STORED_DELIVERY_DELAY_MILLIS = 1_000L

        /** A body-composition half that trails the consent response; inside E17's window, not immediate. */
        const val PAIR_AFTER_CONSENT_MILLIS = 500L
    }
}
