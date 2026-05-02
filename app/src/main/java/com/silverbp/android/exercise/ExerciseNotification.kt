package com.silverbp.android.exercise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.silverbp.android.R

/**
 * Foreground notification for an active exercise session. Created once via
 * [createChannel] (Application onCreate); rebuilt every second by the service
 * so the body shows live distance + duration.
 */
object ExerciseNotification {

    const val CHANNEL_ID = "exercise_tracking"
    const val NOTIF_ID = 4242

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.exercise_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ctx.getString(R.string.exercise_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun build(ctx: Context, live: SessionLive?): Notification {
        val title = ctx.getString(R.string.exercise_recording_title)
        val body = if (live != null) {
            ctx.getString(
                R.string.exercise_recording_body,
                ExerciseMath.formatDistance(live.accumulatedDistanceMeters),
                ExerciseMath.formatDuration(live.activeDurationMillis),
            )
        } else {
            ctx.getString(R.string.exercise_recording_starting)
        }

        val stopIntent = Intent(ctx, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            ctx, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_media_pause,
                ctx.getString(R.string.exercise_stop),
                stopPi,
            )
            .build()
    }
}
