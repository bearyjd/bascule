package com.ventouxlabs.bascule.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HeldReadingNotifierTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))

    private fun notifier() = HeldReadingNotifier(app).also {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun heldConfirmNotificationCarriesBothActions() {
        notifier().post("r1")

        val shown = manager.getNotification(HeldReadingNotifier.notificationId("r1"))
        assertEquals(listOf("Yes, that's me", "Not me"), shown.actions.map { it.title.toString() })
    }

    @Test
    fun noNotificationTextContainsAWeightValue() {
        notifier().post("r1")

        val shadow = shadowOf(manager.getNotification(HeldReadingNotifier.notificationId("r1")))
        val text = "${shadow.contentTitle} ${shadow.contentText}"
        assertFalse(Regex("""\d""").containsMatchIn(text))
    }

    @Test
    fun twoHeldReadingsDoNotOverwriteEachOther() {
        val n = notifier()
        n.post("r1")
        n.post("r2")

        assertEquals(2, manager.allNotifications.size)
    }

    @Test
    fun cancelRemovesIt() {
        val n = notifier()
        n.post("r1")

        n.cancel("r1")

        assertTrue(manager.allNotifications.isEmpty())
    }

    @Test
    fun withoutPostNotificationsPermissionNothingIsPosted() {
        HeldReadingNotifier(app).post("r1")

        assertTrue(manager.allNotifications.isEmpty())
    }
}
