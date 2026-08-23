package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.ScaleReading

/** Terminal result of one GATT session (00-design.md §2.1, §2.3). */
sealed interface SessionOutcome {
    data class Completed(val readings: List<ScaleReading>) : SessionOutcome
    data class Missed(val reason: MissReason) : SessionOutcome
    data object Incompatible : SessionOutcome
    data class HandshakeFailed(val detail: String) : SessionOutcome
    data class DecodeFailure(val malformedCount: Int) : SessionOutcome
}

enum class MissReason {
    CONNECT_TIMEOUT,
    CONTENTION,
    DROPPED,
    QUOTA,
    NO_MEASUREMENT,
    BOND_FAILED,
    ADAPTER_OFF,

    /** E2 exhausted its retry ladder — distinct from a plain connect timeout for HW-17. */
    GATT_ERROR,

    /** `onServicesDiscovered` reported a non-zero status — a transport failure, not "wrong device". */
    DISCOVERY_FAILED,
}
