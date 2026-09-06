package com.ventouxlabs.bascule.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.diagnostics.AttentionTransition
import com.ventouxlabs.bascule.diagnostics.CaptureAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureAttentionNotifierTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun notifier() = CaptureAttentionNotifier(app).also {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun postingShowsOneNotificationWithTheTitle() {
        notifier().apply(AttentionTransition.Post(CaptureAttention("Pair your scale", "Accept the request.")))

        assertEquals(1, manager.allNotifications.size)
        val shown = manager.getNotification(CaptureAttentionNotifier.NOTIFICATION_ID)
        assertEquals("Pair your scale", shadowOf(shown).contentTitle)
    }

    /** One slot, never a stack: a second post replaces the first. */
    @Test
    fun aSecondPostReplacesRatherThanStacks() {
        val n = notifier()
        n.apply(AttentionTransition.Post(CaptureAttention("First", "a")))
        n.apply(AttentionTransition.Post(CaptureAttention("Second", "b")))

        assertEquals(1, manager.allNotifications.size)
        assertEquals("Second", shadowOf(manager.getNotification(CaptureAttentionNotifier.NOTIFICATION_ID)).contentTitle)
    }

    @Test
    fun clearingRemovesIt() {
        val n = notifier()
        n.apply(AttentionTransition.Post(CaptureAttention("Pair your scale", "x")))

        n.apply(AttentionTransition.Clear)

        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun noneLeavesWhateverIsThereAlone() {
        val n = notifier()
        n.apply(AttentionTransition.Post(CaptureAttention("Pair your scale", "x")))

        n.apply(AttentionTransition.None)

        assertEquals(1, manager.allNotifications.size)
    }

    /** Declined permission must be a silent no-op — this runs inside a worker's `finally`. */
    @Test
    fun withoutPermissionItPostsNothingAndDoesNotThrow() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        CaptureAttentionNotifier(app).apply(AttentionTransition.Post(CaptureAttention("t", "b")))

        assertTrue(manager.allNotifications.isEmpty())
    }
}
