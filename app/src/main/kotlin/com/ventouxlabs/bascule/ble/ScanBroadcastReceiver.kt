@file:Suppress("MaxLineLength")

package com.ventouxlabs.bascule.ble

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.ventouxlabs.bascule.ble.session.ScaleSessionWorker

class ScanBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScaleScanner.ACTION_SCAN) return
        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        }
        val address = results.firstOrNull()?.device?.address ?: return
        enqueueSession(context, address, System.currentTimeMillis())
    }

    companion object {
        fun enqueueSession(context: Context, address: String, seenAtMillis: Long) {
            val input = Data.Builder().putString(ScaleSessionWorker.KEY_ADDRESS, address)
                .putLong(ScaleSessionWorker.KEY_SEEN_AT, seenAtMillis).build()
            val request = OneTimeWorkRequestBuilder<ScaleSessionWorker>().setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ScaleSessionWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
