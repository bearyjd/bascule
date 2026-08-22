package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.ScaleReading

/**
 * What a decoder made of one inbound frame.
 *
 * Revised in Phase 2 (docs/prp/02-interface-revision.md §2). 00-design.md §2.6's
 * single `InitAcknowledged` case is replaced by [RegistrationResult] and
 * [ConsentResult]: ADR-007 established that the handshake is a two-step User
 * Data Service exchange whose acks carry different payloads and drive different
 * follow-on actions, so one undifferentiated ack cannot represent it.
 */
sealed interface DecodeEvent {
    /** Known frame, nothing to report — includes frames buffered for correlation. */
    data object Ignored : DecodeEvent

    /**
     * Response to a Register New User write on the User Control Point.
     * [scaleIndex] is the index the scale assigned, present only on success.
     */
    data class RegistrationResult(val scaleIndex: Int?, val success: Boolean) : DecodeEvent

    /** Response to a Consent write on the User Control Point. */
    data class ConsentResult(val success: Boolean) : DecodeEvent

    /**
     * Unstable intermediate weight — UI only, never persisted. The BF720 does
     * not produce these (docs/prp/02-interface-revision.md §5); the case remains
     * on the interface for decoders of scales that stream live weight.
     */
    data class Live(val weightKg: Double) : DecodeEvent

    /** One complete, attributable reading. At most one per physical weigh-in. */
    data class Stable(val reading: ScaleReading) : DecodeEvent

    data class Malformed(val reason: String, val opcode: Int?, val length: Int) : DecodeEvent

    /** The scale signalled end of transmission. */
    data object SessionComplete : DecodeEvent
}
