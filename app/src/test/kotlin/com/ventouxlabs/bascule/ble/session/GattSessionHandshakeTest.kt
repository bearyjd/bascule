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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = consentStore,
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = diagnostics,
        clock = { Bf720Capture.expectedTimestampMillis },
    )

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

    /** The E6 gate that exists in prose only until this test enforces it (O-11 item 1). */
    @Test
    fun doesNotSubscribeBeforeConsentIsGranted() = runTest {
        val transport = consentingScale { opcode ->
            if (opcode == SigWeightProfile.UCP_REGISTER_NEW_USER) Bf720Capture.registrationSuccess() else null
        }

        session(transport).run()

        assertTrue(
            "the UCP indication is required to receive consent, but measurement characteristics stay gated",
            transport.subscribedCharacteristics.keys == setOf(SigWeightProfile.USER_CONTROL_POINT),
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
    }
}
