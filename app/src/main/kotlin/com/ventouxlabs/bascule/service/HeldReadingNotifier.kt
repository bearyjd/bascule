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

/**
 * WP-26's `HELD_CONFIRM` notification: one per held reading, carrying "Yes,
 * that's me" / "Not me" so the question can be answered without opening the
 * app. The text deliberately omits the weight (`01-plan.md` §8.8): body
 * composition is not lock-screen content.
 */
class HeldReadingNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    /** Silently a no-op without POST_NOTIFICATIONS; the History row still offers both actions. */
    @SuppressLint("MissingPermission")
    fun post(readingId: String) {
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
                appContext.getString(R.string.held_reading_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = appContext.getString(R.string.held_reading_body)
        nm.notify(
            notificationId(readingId),
            NotificationCompat.Builder(appContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(appContext.getString(R.string.held_reading_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .addAction(0, appContext.getString(R.string.held_reading_confirm), action(readingId, confirm = true))
                .addAction(0, appContext.getString(R.string.held_reading_decline), action(readingId, confirm = false))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(readingId: String) {
        manager?.cancel(notificationId(readingId))
    }

    private fun action(readingId: String, confirm: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            notificationId(readingId) * 2 + if (confirm) 1 else 0,
            Intent(appContext, HeldReadingActionReceiver::class.java)
                .setAction(if (confirm) ACTION_CONFIRM else ACTION_DECLINE)
                .putExtra(EXTRA_READING_ID, readingId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL = "held_reading"
        const val ACTION_CONFIRM = "com.ventouxlabs.bascule.action.HELD_CONFIRM"
        const val ACTION_DECLINE = "com.ventouxlabs.bascule.action.HELD_DECLINE"
        const val EXTRA_READING_ID = "reading_id"

        /** Per reading, so two held weigh-ins do not overwrite each other. Masked to keep `* 2` in range. */
        fun notificationId(readingId: String): Int = readingId.hashCode() and ID_MASK

        private const val ID_MASK = 0x3FFFFFFF
    }
}
