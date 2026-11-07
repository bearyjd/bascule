package com.ventouxlabs.bascule.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Thin adapter over the platform's battery-optimisation exemption. The
 * decision of when to show the prompt lives in [ScaleViewModel]; this only
 * talks to `PowerManager` and builds the `Intent`s.
 */
class BatteryExemption(private val context: Context) {

    fun isExempt(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The one-tap system dialog for this package, then the generic settings
     * list if nothing handles it. Catches instead of `resolveActivity`, which
     * answers null for system intents under API 30 package visibility.
     */
    fun launchRequest(launcher: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        try {
            launcher.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            launcher.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
