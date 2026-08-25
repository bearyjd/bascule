@file:Suppress("MaxLineLength")

package com.ventouxlabs.bascule.ble

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer
import com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer

/**
 * [enqueuerFactory] defaults to the real [WorkManagerScaleSessionEnqueuer] —
 * Android instantiates this receiver via a no-arg reflective constructor, so
 * production behavior is unchanged. A test constructs it directly with a
 * fake instead.
 */
class ScanBroadcastReceiver(
    private val enqueuerFactory: (Context) -> ScaleSessionEnqueuer = { WorkManagerScaleSessionEnqueuer(it) },
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScaleScanner.ACTION_SCAN) return
        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        }
        val address = results.firstOrNull()?.device?.address ?: return
        enqueuerFactory(context).enqueue(address, System.currentTimeMillis())
    }
}
