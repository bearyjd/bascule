package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * PHASE 2 CONTRACT TESTS — EXPECTED RED.
 *
 * These drive a whole session against the fake transport and assert the
 * end-to-end property: real captured BF720 bytes in, one correctly attributed
 * [com.ventouxlabs.bascule.ble.ScaleReading] out.
 *
 * They fail today because [GattSession]'s run loop is Phase 3 work (WP-06,
 * WP-07, WP-10) — the decoder underneath them is implemented and green, see
 * `BeurerDecoderCaptureTest` and `BeurerHandshakeTest`. Failures here are
 * assertion failures against a session that reports no measurement, which is
 * exactly the unimplemented behaviour they are meant to hold open. See
 * docs/prp/02-ci-notes.md.
 */
class ScaleSessionContractTest {

    private val consentStore = InMemoryConsentStore()

    private val discovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to
                setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
            SigWeightProfile.CURRENT_TIME_SERVICE to setOf(SigWeightProfile.CURRENT_TIME),
        ),
    )

    /**
     * A BF720 that answers the User Control Point handshake and then, once
     * consented, indicates the captured weigh-in pair.
     */
    private fun scale(): FakeGattTransport =
        FakeGattTransport(
            discovered = discovered,
            onWrite = { char: UUID, bytes: ByteArray ->
                if (char != SigWeightProfile.USER_CONTROL_POINT) {
                    emptyList()
                } else {
                    when (bytes.firstOrNull()?.toInt()) {
                        SigWeightProfile.UCP_REGISTER_NEW_USER ->
                            listOf(char to Bf720Capture.registrationSuccess())

                        SigWeightProfile.UCP_CONSENT -> listOf(
                            char to Bf720Capture.consentSuccess(),
                            SigWeightProfile.WEIGHT_MEASUREMENT to Bf720Capture.WEIGHT_MEASUREMENT,
                            SigWeightProfile.BODY_COMPOSITION_MEASUREMENT to
                                Bf720Capture.BODY_COMPOSITION_MEASUREMENT,
                        )

                        else -> emptyList()
                    }
                }
            },
        )

    private fun session(transport: FakeGattTransport) = GattSession(
        transport = transport,
        decoder = BeurerDecoder(),
        consentStore = consentStore,
        deviceAddress = DEVICE_ADDRESS,
        diagnostics = InMemoryDiagnosticsCounters(),
    )

    @Test
    fun aWeighInProducesExactlyOneAttributedReading() = runTest {
        val outcome = session(scale()).run()

        assertTrue("expected Completed, got $outcome", outcome is SessionOutcome.Completed)
        val readings = (outcome as SessionOutcome.Completed).readings
        assertEquals("one physical weigh-in is one reading", 1, readings.size)

        val reading = readings.single()
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, reading.userIndex)
        assertEquals(Bf720Capture.EXPECTED_BODY_FAT_PCT, reading.bodyFatPct!!, TOLERANCE)
    }

    @Test
    fun aRegisteredScaleIndexIsPersistedForTheNextSession() = runTest {
        session(scale()).run()

        val stored = consentStore.credentialFor(DEVICE_ADDRESS)
        assertEquals(
            "without a persisted mapping every weigh-in registers a new user slot",
            Bf720Capture.EXPECTED_USER_INDEX,
            stored?.scaleIndex,
        )
    }

    @Test
    fun theSessionSubscribesOnlyAfterConsentIsGranted() = runTest {
        val transport = scale()
        session(transport).run()

        val consentIndex = transport.writesPerformed.indexOfFirst {
            it.first == SigWeightProfile.USER_CONTROL_POINT &&
                it.second.firstOrNull()?.toInt() == SigWeightProfile.UCP_CONSENT
        }
        assertTrue("the session never sent Consent", consentIndex >= 0)
        assertTrue(
            "measurement indications must be enabled, and only after consent",
            SigWeightProfile.WEIGHT_MEASUREMENT in transport.subscribedCharacteristics,
        )

        // Membership alone doesn't prove *order* — the Consent write is always
        // the last UCP write in a granted handshake, so its call-order position
        // must precede the subscribe call, not merely both have happened.
        val lastUcpWriteOrderIndex = transport.callOrder.indexOfLast {
            it == "write:${SigWeightProfile.USER_CONTROL_POINT}"
        }
        val subscribeOrderIndex = transport.callOrder.indexOfFirst {
            it == "subscribe:${SigWeightProfile.WEIGHT_MEASUREMENT}"
        }
        assertTrue(
            "subscribe must come after the granting Consent write, got ${transport.callOrder}",
            lastUcpWriteOrderIndex in 0 until subscribeOrderIndex,
        )
    }

    @Test
    fun everyTerminalPathClosesGattExactlyOnce() = runTest {
        val transport = scale()
        session(transport).run()

        assertEquals(1, transport.closeCallCount)
    }

    private companion object {
        const val DEVICE_ADDRESS = "E7:DB:51:F1:36:91"
        const val TOLERANCE = 1e-6
    }
}
