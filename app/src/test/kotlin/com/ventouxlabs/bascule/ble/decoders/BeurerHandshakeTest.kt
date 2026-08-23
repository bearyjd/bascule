package com.ventouxlabs.bascule.ble.decoders

import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.session.DecodeEvent
import com.ventouxlabs.bascule.ble.session.DiscoveredServices
import com.ventouxlabs.bascule.ble.session.GattOp
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADR-007 User Data Service handshake, which 00-design.md §2.6 modelled as a
 * fixed `initSequence(): List<GattOp>`.
 *
 * These tests exist to pin the property a list cannot express: whether the first
 * write is Register or Consent depends on what is already stored for this scale,
 * and a rejected stored credential must fall back to registering again.
 */
class BeurerHandshakeTest {

    private val decoder = BeurerDecoder(clock = { 0L })

    private val discovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to
                setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
        ),
    )

    private fun context(stored: ScaleCredential? = null) =
        HandshakeContext(storedCredential = stored, freshConsentCode = CONSENT_CODE)

    private fun writeOf(directive: HandshakeDirective): GattOp.Write {
        assertTrue("expected a Send directive, got $directive", directive is HandshakeDirective.Send)
        val op = (directive as HandshakeDirective.Send).op
        assertTrue("expected a Write op, got $op", op is GattOp.Write)
        return op as GattOp.Write
    }

    private fun ucp(bytes: ByteArray) =
        decoder.onNotification(SigWeightProfile.USER_CONTROL_POINT, bytes)

    @Test
    fun firstEverSessionRegistersANewUser() {
        val write = writeOf(decoder.beginHandshake(discovered, context()))

        assertEquals(SigWeightProfile.USER_CONTROL_POINT, write.char)
        assertArrayEquals(
            "Register New User is opcode 0x01 followed by the consent code, little-endian",
            byteArrayOf(0x01, 0x34, 0x12),
            write.bytes,
        )
    }

    @Test
    fun returningSessionSendsConsentDirectlyWithoutRegistering() {
        val stored = ScaleCredential(scaleIndex = 2, consentCode = CONSENT_CODE)
        val write = writeOf(decoder.beginHandshake(discovered, context(stored)))

        assertArrayEquals(
            "Consent is opcode 0x02, scale index, then the consent code",
            byteArrayOf(0x02, 0x02, 0x34, 0x12),
            write.bytes,
        )
    }

    @Test
    fun registrationSuccessIsFollowedByConsentForTheAssignedIndex() {
        decoder.beginHandshake(discovered, context())

        val event = ucp(Bf720Capture.registrationSuccess(scaleIndex = 2))
        assertEquals(DecodeEvent.RegistrationResult(scaleIndex = 2, success = true), event)

        val write = writeOf(decoder.onHandshakeEvent(event))
        assertArrayEquals(byteArrayOf(0x02, 0x02, 0x34, 0x12), write.bytes)
    }

    @Test
    fun consentSuccessCompletesTheHandshakeAndYieldsTheCredentialToPersist() {
        decoder.beginHandshake(discovered, context())
        decoder.onHandshakeEvent(ucp(Bf720Capture.registrationSuccess()))

        val event = ucp(Bf720Capture.consentSuccess())
        assertEquals(DecodeEvent.ConsentResult(success = true), event)

        val directive = decoder.onHandshakeEvent(event)
        assertTrue(directive is HandshakeDirective.Complete)
        assertEquals(
            "a session that registered must hand back the mapping to persist",
            ScaleCredential(Bf720Capture.EXPECTED_USER_INDEX, CONSENT_CODE),
            (directive as HandshakeDirective.Complete).credential,
        )
    }

    @Test
    fun returningSessionCompletesWithNothingNewToPersist() {
        val stored = ScaleCredential(scaleIndex = 2, consentCode = CONSENT_CODE)
        decoder.beginHandshake(discovered, context(stored))

        val directive = decoder.onHandshakeEvent(ucp(Bf720Capture.consentSuccess()))
        assertTrue(directive is HandshakeDirective.Complete)
        assertNull((directive as HandshakeDirective.Complete).credential)
    }

    @Test
    fun rejectedStoredCredentialFallsBackToRegisteringAgain() {
        val stale = ScaleCredential(scaleIndex = 7, consentCode = 0x0001)
        decoder.beginHandshake(discovered, context(stale))

        val directive = decoder.onHandshakeEvent(ucp(Bf720Capture.consentFailure()))

        // The branch a fixed initSequence could not express: the scale's user
        // slot was deleted or reassigned, so re-register rather than give up.
        val write = writeOf(directive)
        assertArrayEquals(byteArrayOf(0x01, 0x34, 0x12), write.bytes)
    }

    @Test
    fun consentRefusedForAFreshlyRegisteredUserAborts() {
        decoder.beginHandshake(discovered, context())
        decoder.onHandshakeEvent(ucp(Bf720Capture.registrationSuccess()))

        val directive = decoder.onHandshakeEvent(ucp(Bf720Capture.consentFailure()))
        assertTrue("re-registering in a loop would burn the connection window", directive is HandshakeDirective.Abort)
    }

    /**
     * Once a *stale stored credential's* Consent has already been refused and
     * recovered from by re-registering this session, a second same-shaped
     * refusal must not abort. The UCP protocol carries no correlation ID, so
     * this refusal is byte-identical to either a genuine new refusal or a
     * stale leftover from the write it superseded — the decoder cannot tell
     * which, and `GattSession` (which only drains what's *already* arrived)
     * cannot help either, since a not-yet-arrived response can't be drained.
     * Contrast with [consentRefusedForAFreshlyRegisteredUserAborts]: that
     * case has no prior consent write in the session to be stale, so it can
     * still abort fast and accurately.
     */
    @Test
    fun consentRefusedAfterAStaleCredentialAlreadyReregisteredWaitsInsteadOfAborting() {
        val stale = ScaleCredential(scaleIndex = 7, consentCode = 0x0001)
        decoder.beginHandshake(discovered, context(stale))
        decoder.onHandshakeEvent(ucp(Bf720Capture.consentFailure())) // stale credential refused -> re-register
        decoder.onHandshakeEvent(ucp(Bf720Capture.registrationSuccess())) // re-registration succeeds -> consent again

        val directive = decoder.onHandshakeEvent(ucp(Bf720Capture.consentFailure()))
        assertEquals(
            "an unverifiable refusal must not abort a handshake that could still land",
            HandshakeDirective.Wait,
            directive,
        )
    }

    @Test
    fun refusedRegistrationAborts() {
        decoder.beginHandshake(discovered, context())

        val event = ucp(Bf720Capture.registrationFailure())
        assertEquals(DecodeEvent.RegistrationResult(scaleIndex = null, success = false), event)
        assertTrue(decoder.onHandshakeEvent(event) is HandshakeDirective.Abort)
    }

    @Test
    fun missingControlPointAbortsBeforeAnyWrite() {
        val withoutUds = DiscoveredServices(
            mapOf(SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT)),
        )

        val directive = decoder.beginHandshake(withoutUds, context())
        assertTrue(directive is HandshakeDirective.Abort)
    }

    @Test
    fun measurementFrameDuringHandshakeDoesNotAdvanceIt() {
        decoder.beginHandshake(discovered, context())

        val directive = decoder.onHandshakeEvent(
            decoder.onNotification(
                SigWeightProfile.WEIGHT_MEASUREMENT,
                Bf720Capture.WEIGHT_MEASUREMENT,
            ),
        )
        assertEquals(HandshakeDirective.Wait, directive)
    }

    @Test
    fun duplicateConsentAckIsIdempotent() {
        val stored = ScaleCredential(scaleIndex = 2, consentCode = CONSENT_CODE)
        decoder.beginHandshake(discovered, context(stored))
        decoder.onHandshakeEvent(ucp(Bf720Capture.consentSuccess()))

        val second = decoder.onHandshakeEvent(ucp(Bf720Capture.consentSuccess()))
        assertEquals(
            "a repeated ack must not re-issue a write",
            HandshakeDirective.Wait,
            second,
        )
    }

    private companion object {
        const val CONSENT_CODE = 0x1234
    }
}
