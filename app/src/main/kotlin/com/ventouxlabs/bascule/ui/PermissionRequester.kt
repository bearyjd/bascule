package com.ventouxlabs.bascule.ui

import android.Manifest
import android.os.Build

/**
 * `00-design.md` §6.3's SDK-branched runtime permission matrix, as pure
 * decision logic — no `ActivityResultLauncher` dependency here, so this is
 * testable with plain JUnit by injecting [sdkInt] and [isGranted] rather than
 * needing a real (or Robolectric-faked) device. The Compose-side launcher
 * plumbing lives in `ConfigScreen.kt`, which is the one place that actually
 * needs an Activity.
 *
 * The platform rule this exists to enforce: on API 29/30,
 * `ACCESS_BACKGROUND_LOCATION` requested in the *same* dialog as
 * `ACCESS_FINE_LOCATION` is silently denied outright by the platform, no
 * matter what the user taps. It must go out in a second, separate dialog,
 * and only after fine location is already granted.
 */
class PermissionRequester(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val isGranted: (String) -> Boolean,
) {

    /**
     * Everything requestable in the first dialog: BLE (SDK-branched between
     * the API 31+ runtime permissions and the ≤30 location proxy) plus
     * notifications on API 33+. Never includes
     * [Manifest.permission.ACCESS_BACKGROUND_LOCATION] — that is
     * [secondDialogPermission]'s alone, so the two can never collide into one
     * request.
     */
    fun firstDialogPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (sdkInt >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.filterNot(isGranted)
    }

    /**
     * Only meaningful on API 29/30 — the manifest caps
     * `ACCESS_BACKGROUND_LOCATION` at `maxSdkVersion="30"` because API 31+
     * uses `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` instead, which carry no
     * background restriction of their own. Returns null before fine location
     * is granted (nothing to follow up on yet) and once background location
     * is already granted (nothing left to ask).
     */
    fun secondDialogPermission(): String? {
        if (sdkInt !in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) return null
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        if (isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return null
        return Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }

    /**
     * A scale app asking for location reads as suspicious without
     * explanation — required only where the location permission is actually
     * the mechanism (≤30); API 31+ asks for Bluetooth permissions by name,
     * which need no such justification.
     */
    fun needsLocationRationale(): Boolean = sdkInt < Build.VERSION_CODES.S

    /**
     * True only on API 30 (R) — the one version where the platform still
     * accepts a standalone `ACCESS_BACKGROUND_LOCATION` request into
     * `requestPermissions()` but the resulting dialog can no longer grant
     * "Allow all the time"; only the app's own system Settings screen can.
     * API 29 (Q) still grants it through the normal second-dialog flow, and
     * API 31+ never requests background location at all (`BLUETOOTH_SCAN`/
     * `BLUETOOTH_CONNECT` carry no such restriction).
     */
    fun backgroundLocationRequiresSettings(): Boolean = sdkInt == Build.VERSION_CODES.R
}
