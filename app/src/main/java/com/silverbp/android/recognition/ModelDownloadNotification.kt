package com.silverbp.android.recognition

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
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

    const val CHANNEL_ID = "model_download_live"
    const val NOTIF_ID = 5252

    // Tints the island's left-slot small icon and the ProgressStyle bar.
    private val ACCENT = 0xFF0288D1.toInt()  // light-blue-700

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = ctx.getString(R.string.model_download_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
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
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setColor(ACCENT)
            .setColorized(false)
            .setContentTitle(ctx.getString(R.string.model_download_notif_title))
            .setContentText(text)
            .setProgress(100, pct.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent(ctx))

        // API 36+: ProgressStyle is the gate-keeper API that pulls the download
        // into the OEM Live-Update island (OriginOS Atomic Island, Pixel status
        // pill, Samsung Now Brief) — same mechanism the exercise notification
        // uses. No route map here, so unlike ExerciseNotification we keep
        // VISIBILITY_PUBLIC and need no setPublicVersion lock-screen variant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setShortCriticalText(
                if (indeterminate) ctx.getString(R.string.model_download_notif_short_indeterminate)
                else "$pct%",
            )
            applyProgressStyle(ctx, builder, pct, indeterminate)
        }

        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun applyProgressStyle(
        ctx: Context,
        builder: NotificationCompat.Builder,
        pct: Int,
        indeterminate: Boolean,
    ) {
        val style = NotificationCompat.ProgressStyle()
            .setProgressSegments(
                listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(ACCENT)),
            )
            .setProgress(pct.coerceIn(0, 100))
            .setProgressIndeterminate(indeterminate)
            .setProgressTrackerIcon(
                IconCompat.createWithResource(ctx, R.drawable.ic_notification_download),
            )
        builder.setStyle(style)
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
