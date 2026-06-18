package com.silverbp.android.recognition

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadNotificationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun createChannel_usesLiveVisibleSilentDefaults() {
        ModelDownloadNotification.createChannel(context)

        val manager = context.getSystemService<NotificationManager>()
        val channel = manager?.getNotificationChannel(ModelDownloadNotification.CHANNEL_ID)

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
    }

    @Test
    fun buildDeterminateNotification_isPublicOngoingProgressNotification() {
        val notification = ModelDownloadNotification.build(context, pct = 42)

        assertEquals(ModelDownloadNotification.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, notification.priority)
        assertEquals(NotificationCompat.CATEGORY_PROGRESS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(42, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }
}
