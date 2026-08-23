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

    /** The E6 gate that exists in prose only until this test enforces it (O-11 item 1). */
    @Test
    fun doesNotSubscribeBeforeConsentIsGranted() = runTest {
        val transport = consentingScale { opcode ->
            if (opcode == SigWeightProfile.UCP_REGISTER_NEW_USER) Bf720Capture.registrationSuccess() else null
        }

        session(transport).run()

        assertTrue(
            "no measurement characteristic may be subscribed without a granted consent",
            transport.subscribedCharacteristics.isEmpty(),
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
     * carries no correlation ID, so a late response to a *superseded* write is
     * byte-identical to a fresh one. Drives the exact sequence that exposes it
     * — a stale stored credential's Consent write times out (E6), reissues,
     * and only then does the *original* write's late refusal arrive, kicking
     * off re-registration and a brand new Consent write. Without draining the
     * channel before every handshake write, that late refusal (or the
     * reissue's own now-orphaned response) would still be sitting there and
     * get misread as the answer to the new Consent step, which shares
     * `AwaitingConsent`'s state type with the one it actually answered.
     */
    @Test
    fun lateResponseToASupersededConsentWriteDoesNotMisfireTheNextStep() = runTest {
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
                            // Writes #1 (original) and #2 (E6's reissue) get no
                            // automatic response; #1's refusal is pushed
                            // manually, late, below. Write #3 (post-re-
                            // registration) succeeds normally.
                            if (consentAttempts <= 2) emptyList() else listOf(char to Bf720Capture.consentSuccess())
                        }
                        else -> emptyList()
                    }
                }
            },
        )
        val deferred = async { session(transport, consentStore).run() }

        runCurrent() // consent write #1, for the stale stored credential
        advanceTimeBy(3_000)
        runCurrent() // E6 fires: consent write #2 (the reissue) goes out

        // Write #1's answer finally arrives — late, after #2 is already
        // outstanding. A refusal, since the stored credential really is stale.
        transport.indicate(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.consentFailure())
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
