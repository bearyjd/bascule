package com.ventouxlabs.bascule.model

/** Fields VitalForge can be told about (00-design.md §4.3). */
enum class ReadingField {
    WEIGHT,
    BODY_FAT_PCT,
    BODY_WATER_PCT,
    MUSCLE_PCT,
    BONE_MASS_KG,
    BMI,
    BMR,
    AMR,
    CAPTURED_AT,
}

/**
 * Contract version is configured, never sniffed: probing v2 against a v1 server
 * would burn a real reading on an endpoint that might partially accept it
 * (00-design.md §4.3).
 */
enum class ContractVersion(val wire: Int, val supportedFields: Set<ReadingField>) {
    V1_WEIGHT_ONLY(1, setOf(ReadingField.WEIGHT)),
    V2_BODY_COMP(2, ReadingField.entries.toSet()),
}

