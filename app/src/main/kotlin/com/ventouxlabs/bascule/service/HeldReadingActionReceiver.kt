package com.ventouxlabs.bascule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the two actions on [HeldReadingNotifier]'s notification. */
class HeldReadingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(HeldReadingNotifier.EXTRA_READING_ID) ?: return
        val confirm = when (intent.action) {
            HeldReadingNotifier.ACTION_CONFIRM -> true
            HeldReadingNotifier.ACTION_DECLINE -> false
            else -> return
        }
        val app = context.applicationContext as BasculeApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val confirmed = resolveHeldReading(app.database.readingDao(), id, confirm, System.currentTimeMillis())
                if (confirmed) app.deliveryTrigger.triggerImmediateDrain()
                HeldReadingNotifier(context).cancel(id)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The same transitions as History's confirm/decline buttons (ADR-006): confirm
 * re-enters PENDING with a fresh retry epoch, decline is terminal. Only a row
 * still held is touched, so a stale tap after the History screen already
 * answered is a no-op. Returns true when a row moved to PENDING.
 */
suspend fun resolveHeldReading(dao: ReadingDao, id: String, confirm: Boolean, nowMillis: Long): Boolean {
    val reading = dao.byId(id)?.takeIf { it.status == ReadingStatus.HELD_CONFIRM } ?: return false
    dao.update(
        if (confirm) {
            reading.copy(
                status = ReadingStatus.PENDING,
                attemptCount = 0,
                retryEpochMillis = nowMillis,
                lastError = null,
                lastErrorClass = null,
                nextAttemptMillis = null,
            )
        } else {
            reading.copy(status = ReadingStatus.DECLINED)
        },
    )
    return confirm
}
