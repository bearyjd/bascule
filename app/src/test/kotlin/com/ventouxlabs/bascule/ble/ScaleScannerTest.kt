package com.ventouxlabs.bascule.ble

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Only [ScaleScanner.arm]'s pure early-return gates — whether it actually
 * registers a scan with the OS needs a real BLE radio (`docs/prp/01-plan.md`
 * §0: no emulator has one), so that half is `PHONE`-bucket, not covered
 * here. See `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md`
 * Files to Change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScaleScannerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun armReturnsFalseWhenAutomaticCaptureIsDisabled() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(
            listOf(profile(active = true)),
        )
        val scanner = ScaleScanner(context, config, profiles)

        assertEquals(false, scanner.arm())
    }

    @Test
    fun armReturnsFalseWhenThereIsNoActiveProfile() = runTest {
        val config = FakeConfigStore()
        config.saveAutomaticCaptureEnabled(true)
        val profiles = FakeScaleProfileStore(emptyList())
        val scanner = ScaleScanner(context, config, profiles)

        assertEquals(false, scanner.arm())
    }

    /**
     * pr-1-review-correctness.md H4. Every `arm()` reuses one PendingIntent
     * identity with a filter keyed to the *then*-active profile's address, so a
     * registration left standing from a previous profile can keep the scan
     * filtered on the old device — automatic capture then silently never fires
     * for the new one.
     */
    @Test
    fun reArmingLeavesExactlyOneScanRegistered() = runTest {
        val config = FakeConfigStore()
        config.saveAutomaticCaptureEnabled(true)
        val profiles = FakeScaleProfileStore(listOf(profile(active = true)))
        val scanner = ScaleScanner(context, config, profiles)
        val leScanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner

        scanner.arm()
        scanner.arm()

        assertEquals(1, shadowOf(leScanner).activeScans.size)
    }

    private fun profile(active: Boolean) = ScaleProfile(
        id = "p1",
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        scaleIndex = 1,
        consentCode = 1234,
        label = "Profile 1",
        registeredAtMillis = 0L,
        active = active,
    )
}
