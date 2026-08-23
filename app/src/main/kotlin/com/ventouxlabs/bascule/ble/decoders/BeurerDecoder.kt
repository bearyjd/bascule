package com.ventouxlabs.bascule.ble.decoders

import com.ventouxlabs.bascule.ble.session.DecodeEvent
import com.ventouxlabs.bascule.ble.session.DiscoveredServices
import com.ventouxlabs.bascule.ble.session.GattOp
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.ble.session.SessionBudget
import java.util.Calendar
import java.util.UUID
import kotlin.time.Duration

/**
 * Beurer/Sanitas family decoder for the BF720, which speaks the standard
 * Bluetooth SIG Weight Profile rather than a proprietary opcode protocol
 * (ADR-007).
 *
 * Per-session stateful, performs no I/O.
 */
class BeurerDecoder(
    private val clock: () -> Long = System::currentTimeMillis,
) : ScaleDecoder {

    override val id: String = DECODER_ID

    override val requiredServices: Set<UUID> = setOf(
        SigWeightProfile.WEIGHT_SCALE_SERVICE,
        SigWeightProfile.BODY_COMPOSITION_SERVICE,
        SigWeightProfile.USER_DATA_SERVICE,
    )

    override val measurementCharacteristics: Set<UUID> = setOf(
        SigWeightProfile.WEIGHT_MEASUREMENT,
        SigWeightProfile.BODY_COMPOSITION_MEASUREMENT,
    )

    private val correlator = MeasurementCorrelator(DECODER_ID, clock)

    private var handshake: HandshakeState = HandshakeState.NotStarted
    private var context: HandshakeContext? = null

    override var handshakeSawUnverifiableResponse: Boolean = false
        private set

    var malformedCount: Int = 0
        private set

    /**
     * The advertised service UUID is the primary signal; the name only
     * disqualifies a device when one is actually advertised. Requiring both
     * would be stricter than 00-design.md §10 A2 assumes — a scan record often
     * omits the local name from the primary advertisement, and a name-and-UUID
     * conjunction would then never match the scale at all.
     */
    override fun matches(advertisedName: String?, serviceUuids: Set<UUID>): Boolean =
        SigWeightProfile.WEIGHT_SCALE_SERVICE in serviceUuids &&
            (advertisedName == null || advertisedName.startsWith(ADVERTISED_NAME_PREFIX))

    /**
     * Confirmed on the physical BF720: the probe's Current Time write produced
     * a frame timestamp matching to the second (docs/prp/03-hardware-validation.md
     * §5, 00-design.md §4.4). Skipped, not failed, if the device doesn't expose
     * `0x1805`/`2A2B` — best-effort per [ScaleDecoder.openingSequence]'s KDoc.
     */
    override fun openingSequence(discovered: DiscoveredServices, nowMillis: Long): List<GattOp> {
        val hasCurrentTime = discovered.hasCharacteristic(
            SigWeightProfile.CURRENT_TIME_SERVICE,
            SigWeightProfile.CURRENT_TIME,
        )
        return if (hasCurrentTime) listOf(currentTimeWrite(nowMillis)) else emptyList()
    }

    override fun beginHandshake(
        discovered: DiscoveredServices,
        context: HandshakeContext,
    ): HandshakeDirective {
        val hasControlPoint = discovered.hasCharacteristic(
            SigWeightProfile.USER_DATA_SERVICE,
            SigWeightProfile.USER_CONTROL_POINT,
        )
        if (!hasControlPoint) {
            return HandshakeDirective.Abort("User Control Point 0x2A9F absent")
        }
        this.context = context

        val stored = context.storedCredential
        return if (stored == null) {
            handshake = HandshakeState.AwaitingRegistration(context.freshConsentCode)
            HandshakeDirective.Send(registerWrite(context.freshConsentCode), ACK_TIMEOUT)
        } else {
            handshake = HandshakeState.AwaitingConsent(stored, registered = false)
            HandshakeDirective.Send(consentWrite(stored), ACK_TIMEOUT)
        }
    }

    override fun onHandshakeEvent(event: DecodeEvent): HandshakeDirective =
        when (val state = handshake) {
            is HandshakeState.AwaitingRegistration -> onRegistrationEvent(state, event)
            is HandshakeState.AwaitingConsent -> onConsentEvent(state, event)
            else -> HandshakeDirective.Wait
        }

    private fun onRegistrationEvent(
        state: HandshakeState.AwaitingRegistration,
        event: DecodeEvent,
    ): HandshakeDirective {
        if (event !is DecodeEvent.RegistrationResult) return HandshakeDirective.Wait
        val index = event.scaleIndex
        if (!event.success || index == null) {
            return HandshakeDirective.Abort("scale refused Register New User", registrationRejected = true)
        }
        val credential = ScaleCredential(index, state.consentCode)
        handshake = HandshakeState.AwaitingConsent(
            credential,
            registered = true,
            staleResponseBudget = state.staleResponseBudgetOnSuccess,
        )
        return HandshakeDirective.Send(consentWrite(credential), ACK_TIMEOUT)
    }

    private fun onConsentEvent(
        state: HandshakeState.AwaitingConsent,
        event: DecodeEvent,
    ): HandshakeDirective {
        if (event !is DecodeEvent.ConsentResult) return HandshakeDirective.Wait
        if (event.success) {
            handshake = HandshakeState.Consented
            return HandshakeDirective.Complete(state.credential.takeIf { state.registered })
        }
        if (state.registered) {
            if (state.staleResponseBudget > 0) {
                handshakeSawUnverifiableResponse = true
                handshake = state.copy(staleResponseBudget = state.staleResponseBudget - 1)
                return HandshakeDirective.Wait
            }
            return HandshakeDirective.Abort("scale refused consent for a just-registered user")
        }
        // A stored credential the scale no longer honours — its user slot was
        // deleted or reassigned. Registering again is the only recovery, and it
        // is the branch a fixed initSequence could not express (ADR-007).
        val freshCode = context?.freshConsentCode
            ?: return HandshakeDirective.Abort("no consent code available to re-register")
        // Up to HANDSHAKE_ACK_MAX_RETRIES consent writes for the *stale*
        // credential may already be outstanding (the original plus E6's own
        // reissues) when this refusal is processed — the wire protocol has no
        // correlation ID, so any of their responses could still arrive after
        // this point and would otherwise misread as an answer to the new
        // registration's consent step. That count is exactly bounded (it can
        // never exceed E6's own retry cap), so the budget below absorbs every
        // physically-possible stale response and nothing more: the next
        // refusal past it is guaranteed to be a genuine answer to the current
        // write, not a leftover, and aborts immediately and accurately.
        handshake = HandshakeState.AwaitingRegistration(
            freshCode,
            staleResponseBudgetOnSuccess = SessionBudget.HANDSHAKE_ACK_MAX_RETRIES,
        )
        return HandshakeDirective.Send(registerWrite(freshCode), ACK_TIMEOUT)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray): DecodeEvent =
        when (characteristic) {
            SigWeightProfile.USER_CONTROL_POINT -> decodeControlPoint(value)
            SigWeightProfile.WEIGHT_MEASUREMENT -> decodeWeight(value)
            SigWeightProfile.BODY_COMPOSITION_MEASUREMENT -> decodeBodyComposition(value)
            // Unknown characteristic: log-and-skip, forward compatibility (E11).
            else -> DecodeEvent.Ignored
        }

    private fun decodeControlPoint(value: ByteArray): DecodeEvent {
        if (value.size < UCP_RESPONSE_MIN_LENGTH) {
            return malformed("control point response too short", value.firstOrNull()?.toInt(), value.size)
        }
        val opcode = value[0].toInt() and BYTE_MASK
        if (opcode != SigWeightProfile.UCP_RESPONSE_CODE) return DecodeEvent.Ignored

        val requestOpcode = value[1].toInt() and BYTE_MASK
        val success = (value[2].toInt() and BYTE_MASK) == SigWeightProfile.UCP_RESPONSE_SUCCESS
        return when (requestOpcode) {
            SigWeightProfile.UCP_REGISTER_NEW_USER -> DecodeEvent.RegistrationResult(
                scaleIndex = if (success && value.size > UCP_INDEX_OFFSET) {
                    value[UCP_INDEX_OFFSET].toInt() and BYTE_MASK
                } else {
                    null
                },
                success = success,
            )

            SigWeightProfile.UCP_CONSENT -> DecodeEvent.ConsentResult(success)
            else -> DecodeEvent.Ignored
        }
    }

    private fun decodeWeight(value: ByteArray): DecodeEvent {
        if (value.size < WeightMeasurementParser.MIN_LENGTH) {
            return malformed("weight frame too short", null, value.size)
        }
        val parsed = WeightMeasurementParser.parse(value)
            ?: return malformed("weight frame truncated for its flags", null, value.size)
        return correlator.onWeight(parsed)
    }

    private fun decodeBodyComposition(value: ByteArray): DecodeEvent {
        if (value.size < BodyCompositionMeasurementParser.MIN_LENGTH) {
            return malformed("body composition frame too short", null, value.size)
        }
        val parsed = BodyCompositionMeasurementParser.parse(value)
            ?: return malformed("body composition frame truncated for its flags", null, value.size)
        return correlator.onBodyComposition(parsed)
    }

    override fun flush(): DecodeEvent? = correlator.flush()

    override fun teardownSequence(): List<GattOp> = emptyList()

    /** 00-design.md §2.3 E9 / §3.3 diagnostics. */
    val duplicateFramesSuppressed: Int get() = correlator.duplicateFramesSuppressed

    /** 00-design.md §2.3 E18: frames dropped because correlation had closed. */
    val unpairableFramesDropped: Int get() = correlator.unpairableFramesDropped

    private fun malformed(reason: String, opcode: Int?, length: Int): DecodeEvent {
        malformedCount++
        // Diagnostics carry opcode and length only, never payload bytes, because
        // those bytes are the user's body composition (00-design.md §8.8).
        return DecodeEvent.Malformed(reason, opcode, length)
    }

    private fun registerWrite(consentCode: Int): GattOp.Write = GattOp.Write(
        char = SigWeightProfile.USER_CONTROL_POINT,
        bytes = byteArrayOf(
            SigWeightProfile.UCP_REGISTER_NEW_USER.toByte(),
            (consentCode and BYTE_MASK).toByte(),
            ((consentCode shr BYTE_BITS) and BYTE_MASK).toByte(),
        ),
        expectAckWithin = ACK_TIMEOUT,
    )

    private fun consentWrite(credential: ScaleCredential): GattOp.Write = GattOp.Write(
        char = SigWeightProfile.USER_CONTROL_POINT,
        bytes = byteArrayOf(
            SigWeightProfile.UCP_CONSENT.toByte(),
            credential.scaleIndex.toByte(),
            (credential.consentCode and BYTE_MASK).toByte(),
            ((credential.consentCode shr BYTE_BITS) and BYTE_MASK).toByte(),
        ),
        expectAckWithin = ACK_TIMEOUT,
    )

    /**
     * Bluetooth SIG Current Time characteristic, standard 10-byte payload:
     * Exact Time 256 (year LE, month, day, hours, minutes, seconds) + day of
     * week (BLE convention: Monday=1..Sunday=7, 0=unknown) + Fractions256 +
     * Adjust Reason. Byte layout and field values confirmed against the probe
     * capture (docs/prp/03-hardware-validation.md §5) — this is a port of that
     * exact logic, not a fresh implementation.
     */
    private fun currentTimeWrite(nowMillis: Long): GattOp.Write {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val year = cal.get(Calendar.YEAR)
        val bleDayOfWeek = when (val calendarDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> BLE_SUNDAY
            else -> calendarDayOfWeek - 1
        }
        return GattOp.Write(
            char = SigWeightProfile.CURRENT_TIME,
            bytes = byteArrayOf(
                (year and BYTE_MASK).toByte(),
                ((year shr BYTE_BITS) and BYTE_MASK).toByte(),
                (cal.get(Calendar.MONTH) + 1).toByte(),
                cal.get(Calendar.DAY_OF_MONTH).toByte(),
                cal.get(Calendar.HOUR_OF_DAY).toByte(),
                cal.get(Calendar.MINUTE).toByte(),
                cal.get(Calendar.SECOND).toByte(),
                bleDayOfWeek.toByte(),
                0, // Fractions256 — sub-second precision not tracked
                0, // Adjust Reason: manual time update
            ),
            expectAckWithin = null, // a plain characteristic write, not a UCP-ack step
        )
    }

    private sealed interface HandshakeState {
        data object NotStarted : HandshakeState

        /**
         * [staleResponseBudgetOnSuccess] carries forward into the
         * [AwaitingConsent] this registration leads to, once it succeeds — see
         * that class's own KDoc. Zero for every path except re-registration
         * after a stale stored credential's Consent was refused.
         */
        data class AwaitingRegistration(
            val consentCode: Int,
            val staleResponseBudgetOnSuccess: Int = 0,
        ) : HandshakeState

        /**
         * [staleResponseBudget] is nonzero only after a stale stored
         * credential's Consent was refused and this session re-registered as
         * recovery. The UCP wire protocol carries no correlation ID, so a
         * refusal received here cannot be told apart from a stale response to
         * one of the (at most [SessionBudget.HANDSHAKE_ACK_MAX_RETRIES])
         * consent writes this new one supersedes. The budget is exact, not a
         * guess: that many refusals are absorbed as [HandshakeDirective.Wait]
         * and decremented; the next one is guaranteed to answer *this* write,
         * so it aborts immediately with an accurate reason rather than
         * deferring to E6's own ack timeout. A first-ever registration (no
         * stored credential) never sets this — there is no prior consent
         * write in that path to be stale, so a refusal there always aborts
         * fast.
         */
        data class AwaitingConsent(
            val credential: ScaleCredential,
            val registered: Boolean,
            val staleResponseBudget: Int = 0,
        ) : HandshakeState

        data object Consented : HandshakeState
    }

    companion object {
        const val DECODER_ID = "beurer-sanitas-sig"

        /** Advertised name observed on the BF720 (docs/prp/03-hardware-validation.md). */
        const val ADVERTISED_NAME_PREFIX = "BF"

        /** 00-design.md §2.5: init ack timeout, shared with `GattSession`'s own E6 ladder. */
        val ACK_TIMEOUT: Duration = SessionBudget.HANDSHAKE_ACK_TIMEOUT

        private const val BYTE_MASK = 0xFF
        private const val BYTE_BITS = 8
        private const val UCP_RESPONSE_MIN_LENGTH = 3
        private const val UCP_INDEX_OFFSET = 3

        /** BLE Day of Week convention: Monday=1..Sunday=7 (`Calendar.DAY_OF_WEEK` is Sunday=1..Saturday=7). */
        private const val BLE_SUNDAY = 7
    }
}
