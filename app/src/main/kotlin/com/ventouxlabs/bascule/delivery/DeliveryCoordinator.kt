package com.ventouxlabs.bascule.delivery

/**
 * Dedup pass and delivery status transitions. Holds no HTTP.
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-21.
 */
object DeliveryCoordinator {
    const val PLANNED_IN = "WP-21"

    /** ADR-005: only TRANSIENT failures age out, and only from retryEpochMillis. */
    const val EXPIRY_MILLIS = 14L * 24 * 60 * 60 * 1000

    /** 00-design.md §3.4 backoff ladder: 30 s doubling to a 15 min cap. */
    const val BACKOFF_BASE_MILLIS = 30_000L
    const val BACKOFF_CAP_MILLIS = 900_000L
}
