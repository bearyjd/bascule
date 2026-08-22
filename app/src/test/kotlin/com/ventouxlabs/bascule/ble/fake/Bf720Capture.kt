package com.ventouxlabs.bascule.ble.fake

import java.util.Calendar
import java.util.TimeZone

/**
 * Ground truth from the live BF720 capture of 2026-08-22
 * (docs/prp/03-hardware-validation.md §5). These are real bytes off real
 * hardware, not synthesized frames — every expectation below was independently
 * corroborated in that document (the scale's own BMI matches weight/height², the
 * timestamp matches the Current Time written moments earlier, and fat mass
 * subtracted from weight leaves a lean mass consistent with the reported soft
 * lean mass plus a plausible bone remainder).
 */
object Bf720Capture {

    /** NOTIFY 2a9d, 15 bytes. */
    val WEIGHT_MEASUREMENT = byteArrayOf(
        0x0e, 0xf4.toByte(), 0x46, 0xea.toByte(), 0x07, 0x08, 0x16, 0x10,
        0x33, 0x01, 0x02, 0x3a, 0x01, 0xa4.toByte(), 0x06,
    )

    /** NOTIFY 2a9c, 14 bytes. */
    val BODY_COMPOSITION_MEASUREMENT = byteArrayOf(
        0x98.toByte(), 0x03, 0xa6.toByte(), 0x01, 0x7a, 0x1a, 0x30, 0x01,
        0x58, 0x26, 0xe0.toByte(), 0x1c, 0x12, 0x11,
    )

    const val EXPECTED_WEIGHT_KG = 90.82
    const val EXPECTED_USER_INDEX = 2
    const val EXPECTED_BMI = 31.4
    const val EXPECTED_HEIGHT_M = 1.700
    const val EXPECTED_BODY_FAT_PCT = 42.2
    const val EXPECTED_BASAL_METABOLISM_KJ = 6778.0
    const val EXPECTED_MUSCLE_PCT = 30.4
    const val EXPECTED_SOFT_LEAN_MASS_KG = 49.08
    const val EXPECTED_BODY_WATER_MASS_KG = 36.96
    const val EXPECTED_IMPEDANCE_OHMS = 437.0

    /** 2026-08-22 16:51:01, in the JVM default zone as the decoder reads it. */
    val expectedTimestampMillis: Long = Calendar.getInstance(TimeZone.getDefault()).apply {
        clear()
        set(2026, Calendar.AUGUST, 22, 16, 51, 1)
    }.timeInMillis

    /**
     * A second household member's Weight Measurement, derived from the captured
     * frame by substituting the user ID and the raw weight. **Synthesized, not
     * captured** — the probe exercised one user once — and used only to drive the
     * two-weigh-ins-in-one-session correlation case (O-03).
     */
    fun weightMeasurementForUser(userIndex: Int, rawWeight: Int): ByteArray =
        WEIGHT_MEASUREMENT.copyOf().also {
            it[RAW_WEIGHT_OFFSET] = (rawWeight and BYTE_MASK).toByte()
            it[RAW_WEIGHT_OFFSET + 1] = ((rawWeight shr BYTE_BITS) and BYTE_MASK).toByte()
            it[USER_ID_OFFSET] = userIndex.toByte()
        }

    /** A User Control Point response to Register New User: success, index 2. */
    fun registrationSuccess(scaleIndex: Int = EXPECTED_USER_INDEX) =
        byteArrayOf(0x20, 0x01, 0x01, scaleIndex.toByte())

    fun registrationFailure() = byteArrayOf(0x20, 0x01, 0x02)

    fun consentSuccess() = byteArrayOf(0x20, 0x02, 0x01)

    fun consentFailure() = byteArrayOf(0x20, 0x02, 0x02)

    /** Offsets into [WEIGHT_MEASUREMENT] for flags `0x0e` (timestamp + user ID + BMI/height). */
    private const val RAW_WEIGHT_OFFSET = 1
    private const val USER_ID_OFFSET = 10
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
}
