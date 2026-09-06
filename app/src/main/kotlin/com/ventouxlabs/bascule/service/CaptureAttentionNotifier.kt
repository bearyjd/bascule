package com.ventouxlabs.bascule.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ventouxlabs.bascule.MainActivity
import com.ventouxlabs.bascule.R
import com.ventouxlabs.bascule.diagnostics.AttentionTransition

/**
 * The Android half of [com.ventouxlabs.bascule.diagnostics.attentionTransition]:
 * one notification slot, posted or cleared, never stacked. Tapping it opens
 * the app on the Scale tab's "Last attempt" line, which says the same thing
 * in more detail.
 */
class CaptureAttentionNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun apply(transition: AttentionTransition) {
        when (transition) {
            is AttentionTransition.Post -> post(transition.attention.title, transition.attention.body)
            AttentionTransition.Clear -> manager?.cancel(NOTIFICATION_ID)
            AttentionTransition.None -> Unit
        }
    }

    /**
     * Silently a no-op without POST_NOTIFICATIONS: the worker already runs a
     * foreground service the same permission gates, so a user who declined it
     * has declined this too, and a throw here would be inside a `finally`.
     */
    @SuppressLint("MissingPermission")
    private fun post(title: String, body: String) {
        val nm = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                appContext.getString(R.string.capture_attention_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "capture_attention"
        const val NOTIFICATION_ID = 721
    }
}
