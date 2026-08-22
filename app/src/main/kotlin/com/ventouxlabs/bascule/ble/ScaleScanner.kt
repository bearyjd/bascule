package com.ventouxlabs.bascule.ble

/**
 * Arms and disarms the PendingIntent ScanFilter background scan — the wake
 * trigger, not the decode path (ADR-001 consequence 2).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-08, whose scan-registration half
 * is a PHONE-bucket item: a standard AVD has no BLE stack, so
 * `startScan(filters, settings, pendingIntent)` cannot be exercised in CI at all
 * (01-plan.md §0).
 */
class ScaleScanner {
    fun arm(): Nothing = TODO("WP-08: ScanFilter + PendingIntent scan registration")
    fun disarm(): Nothing = TODO("WP-08: scan teardown")
}
