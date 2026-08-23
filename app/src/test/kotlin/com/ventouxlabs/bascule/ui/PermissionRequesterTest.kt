package com.ventouxlabs.bascule.ui

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `00-design.md` §6.3's SDK-branched permission matrix, tested by injecting
 * [PermissionRequester]'s `sdkInt`/`isGranted` rather than needing Robolectric
 * — the whole point of keeping this logic free of `ActivityResultLauncher`.
 */
class PermissionRequesterTest {

    @Test
    fun requestsScanAndConnectOnApi31Plus() {
        val requester = PermissionRequester(sdkInt = Build.VERSION_CODES.S, isGranted = { false })

        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            requester.firstDialogPermissions(),
        )
    }

    @Test
    fun requestsFineLocationBelowApi31() {
        val requester = PermissionRequester(sdkInt = Build.VERSION_CODES.R, isGranted = { false })

        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), requester.firstDialogPermissions())
    }

    @Test
    fun requestsBackgroundLocationInASecondDialog() {
        val requester = PermissionRequester(
            sdkInt = Build.VERSION_CODES.R,
            isGranted = { it == Manifest.permission.ACCESS_FINE_LOCATION },
        )

        assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION, requester.secondDialogPermission())
    }

    @Test
    fun requestsPostNotificationsOnApi33Plus() {
        val requester = PermissionRequester(sdkInt = Build.VERSION_CODES.TIRAMISU, isGranted = { false })

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            requester.firstDialogPermissions(),
        )
    }

    @Test
    fun neverRequestsBothLocationPermissionsAtOnce() {
        // Below API 31, where fine location is even in play at all.
        val requester = PermissionRequester(sdkInt = Build.VERSION_CODES.R, isGranted = { false })

        assertFalse(
            "the first dialog must never include background location alongside fine location",
            Manifest.permission.ACCESS_BACKGROUND_LOCATION in requester.firstDialogPermissions(),
        )
        assertNull(
            "the second dialog must not fire until fine location is already granted",
            requester.secondDialogPermission(),
        )
    }

    @Test
    fun secondDialogIsNothingOnceBackgroundLocationIsAlreadyGranted() {
        val requester = PermissionRequester(sdkInt = Build.VERSION_CODES.R, isGranted = { true })

        assertNull(requester.secondDialogPermission())
    }

    @Test
    fun secondDialogIsNothingOnApi31PlusSinceBackgroundLocationIsNotUsedThere() {
        val requester = PermissionRequester(
            sdkInt = Build.VERSION_CODES.S,
            isGranted = { it == Manifest.permission.ACCESS_FINE_LOCATION },
        )

        assertNull(requester.secondDialogPermission())
    }

    @Test
    fun alreadyGrantedPermissionsAreNotRequestedAgain() {
        val requester = PermissionRequester(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            isGranted = { it == Manifest.permission.BLUETOOTH_SCAN },
        )

        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
            requester.firstDialogPermissions(),
        )
    }

    @Test
    fun needsLocationRationaleBelowApi31Only() {
        assertTrue(PermissionRequester(sdkInt = Build.VERSION_CODES.R, isGranted = { false }).needsLocationRationale())
        assertFalse(PermissionRequester(sdkInt = Build.VERSION_CODES.S, isGranted = { false }).needsLocationRationale())
    }
}
