package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.ScaleReading

/** Terminal result of one GATT session (00-design.md §2.1, §2.3). */
sealed interface SessionOutcome {
    /**
     * [reading] is null when the session completed without a measurement —
     * today only the registration path (`stopAfterHandshake = true`).
     *
     * A single nullable reading rather than a list because
     * `MAX_EMISSIONS_PER_SESSION = 1` (`02-interface-revision.md` §3) allows
     * exactly one emission per session: a list left "two readings from one
     * session" representable, so a caller iterating it would have silently
     * ingested both — the misattribution O-03 exists to prevent.
     */
    data class Completed(val reading: ScaleReading?) : SessionOutcome
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
