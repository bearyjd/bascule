package com.ventouxlabs.bascule.ble.decoders

import java.util.Locale
import java.util.UUID

/**
 * Bluetooth SIG "Weight Profile" constants for the Beurer BF720.
 *
 * Provenance (ADR-002 convention). Primary source: the Bluetooth SIG
 * specifications — Weight Scale Service 1.0 / Weight Measurement (0x2A9D),
 * Body Composition Service 1.0 / Body Composition Measurement (0x2A9C), User
 * Data Service 1.0 / User Control Point (0x2A9F), and the Assigned Numbers
 * document for the 16-bit UUID allocations and the Bluetooth Base UUID.
 * Cross-checked against openScale's `StandardWeightProfileHandler.kt` and
 * `StandardBeurerSanitasHandler.kt` (GPL-3.0, github.com/oliexdev/openScale);
 * reimplemented from protocol understanding, no source copied.
 *
 * Confirmed on the physical BF720 (MAC E7:DB:51:F1:36:91) on 2026-08-22 — see
 * docs/prp/03-hardware-validation.md for the raw capture these values decode.
 *
 * This supersedes 00-design.md §9's symbolic table, which anticipated a
 * proprietary Beurer opcode protocol (`BEURER_SERVICE_UUID`, `INIT_SEQUENCE`,
 * `OPCODE_*`, `WEIGHT_SCALE_FACTOR`). ADR-007 established that this unit speaks
 * the standard profile instead; those symbols have no referent and are not
 * implemented. See docs/prp/02-interface-revision.md §4.
 */
internal object SigWeightProfile {

    /** Bluetooth Base UUID: `0000xxxx-0000-1000-8000-00805F9B34FB`. */
    fun assigned(uuid16: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "%08x-0000-1000-8000-00805f9b34fb", uuid16))

    val CURRENT_TIME_SERVICE: UUID = assigned(0x1805)
    val BODY_COMPOSITION_SERVICE: UUID = assigned(0x181B)
    val USER_DATA_SERVICE: UUID = assigned(0x181C)
    val WEIGHT_SCALE_SERVICE: UUID = assigned(0x181D)

    val CURRENT_TIME: UUID = assigned(0x2A2B)
    val BODY_COMPOSITION_MEASUREMENT: UUID = assigned(0x2A9C)
    val WEIGHT_MEASUREMENT: UUID = assigned(0x2A9D)
    val USER_CONTROL_POINT: UUID = assigned(0x2A9F)

    /**
     * User Control Point op codes (User Data Service 1.0 §4.10). The BF720 gates
     * all measurement indications behind a successful Register-then-Consent
     * exchange over this characteristic — connect and subscribe alone yields
     * nothing (ADR-007).
     */
    const val UCP_REGISTER_NEW_USER = 0x01
    const val UCP_CONSENT = 0x02
    const val UCP_DELETE_USER_DATA = 0x03

    /** Op code of a User Control Point response indication. */
    const val UCP_RESPONSE_CODE = 0x20

    /** Response value meaning the request succeeded. */
    const val UCP_RESPONSE_SUCCESS = 0x01

    /** User indices are 8-bit (User Data Service 1.0 §4.9). */
    const val SCALE_INDEX_MIN = 0
    const val SCALE_INDEX_MAX = 255

    /** Consent codes are 16-bit (User Data Service 1.0 §4.10.2). */
    const val CONSENT_CODE_MIN = 0
    const val CONSENT_CODE_MAX = 0xFFFF

    val SCALE_INDEX_RANGE = SCALE_INDEX_MIN..SCALE_INDEX_MAX
    val CONSENT_CODE_RANGE = CONSENT_CODE_MIN..CONSENT_CODE_MAX

    /**
     * Weight Measurement resolution. The SIG format fixes these per unit system:
     * 0.005 kg in SI, 0.01 lb in Imperial. The BF720's Weight Scale Feature
     * (0x2A9E = b7 00 00 00) reports a *display* resolution of 0.01 kg, which is
     * independent of the wire resolution.
     */
    const val WEIGHT_KG_PER_LSB = 0.005
    const val WEIGHT_LB_PER_LSB = 0.01

    /** Body Composition mass fields use the same resolutions as weight. */
    const val MASS_KG_PER_LSB = 0.005
    const val MASS_LB_PER_LSB = 0.01

    const val HEIGHT_METRES_PER_LSB = 0.001
    const val HEIGHT_INCHES_PER_LSB = 0.1

    const val BMI_PER_LSB = 0.1
    const val PERCENT_PER_LSB = 0.1
    const val IMPEDANCE_OHMS_PER_LSB = 0.1

    /** Basal Metabolism is reported in kilojoules (Body Composition Service 1.0). */
    const val KJ_PER_KCAL = 4.184
}
