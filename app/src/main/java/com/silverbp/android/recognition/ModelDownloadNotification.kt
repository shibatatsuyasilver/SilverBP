package com.silverbp.android.recognition

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.silverbp.android.MainActivity
import com.silverbp.android.R

/**
 * Foreground notification for the on-device model download. Lets the multi-GB
 * download run as a foreground service so it survives app-backgrounding / the
 * screen leaving (see [ModelDownloadWorker]).
 *
 * Channel is created once via [createChannel] in Application.onCreate; the
 * worker rebuilds the notification as the download progresses.
 */
object ModelDownloadNotification {

    const val CHANNEL_ID = "model_download"
    const val NOTIF_ID = 5252

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ctx.getString(R.string.model_download_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Build the ongoing progress notification. [pct] in 0..100, or a negative
     * value for an indeterminate bar (e.g. before the first byte / while the
     * server hasn't reported a content length).
     */
    fun build(ctx: Context, pct: Int): Notification {
        val indeterminate = pct < 0
        val text = if (indeterminate) {
            ctx.getString(R.string.model_download_notif_text)
        } else {
            ctx.getString(R.string.model_download_notif_text_pct, pct)
        }
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.model_download_notif_title))
            .setContentText(text)
            .setProgress(100, pct.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent(ctx))
            .build()
    }

    private fun openAppPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
