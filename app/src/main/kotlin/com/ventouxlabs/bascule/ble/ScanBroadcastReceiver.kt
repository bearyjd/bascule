package com.ventouxlabs.bascule.ble

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer
import com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer

/**
 * [enqueuerFactory] and [activeAddressProvider] default to the real
 * [WorkManagerScaleSessionEnqueuer] and the app's active profile — Android
 * instantiates this receiver via a no-arg reflective constructor, so production
 * behavior is unchanged. A test constructs it directly with fakes instead.
 */
class ScanBroadcastReceiver(
    private val enqueuerFactory: (Context) -> ScaleSessionEnqueuer = { WorkManagerScaleSessionEnqueuer(it) },
    private val activeAddressProvider: (Context) -> String? = ::activeProfileAddress,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScaleScanner.ACTION_SCAN) return
        val addresses = scanResults(intent).mapNotNull { it.device?.address }
        // A batched delivery routinely carries several results, and the scale's
        // own is not necessarily first. When the active address is unknown, fall
        // back to the leading result — ScaleSessionWorker re-checks it anyway.
        val active = activeAddressProvider(context)
        val address = when (active) {
            null -> addresses.firstOrNull()
            else -> addresses.firstOrNull { it.equals(active, ignoreCase = true) }
        } ?: return
        enqueuerFactory(context).enqueue(address, System.currentTimeMillis())
    }

    private fun scanResults(intent: Intent): List<ScanResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            ).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        }
}

private fun activeProfileAddress(context: Context): String? =
    (context.applicationContext as? BasculeApplication)
        ?.scaleProfileStore?.activeProfile?.value?.deviceAddress
