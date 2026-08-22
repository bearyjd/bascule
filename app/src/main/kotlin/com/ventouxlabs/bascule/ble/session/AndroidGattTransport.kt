package com.ventouxlabs.bascule.ble.session

/**
 * Thin adapter over BluetoothGatt + BluetoothGattCallback. It carries no logic
 * by design: anything it decided for itself would be untested when a fake
 * transport substitutes for it (01-plan.md §3.1).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-04.
 */
object AndroidGattTransport {
    const val PLANNED_IN = "WP-04"
}
