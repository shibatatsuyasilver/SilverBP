package com.silverbp.android.backup.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.silverbp.android.MainActivity
import com.silverbp.android.R
import com.silverbp.android.achievements.MedalNotifier

/**
 * One-shot status notification for auto-backup failures.
 *
 * [AutoBackupWorker] already records the error to DataStore for the
 * BackupScreen status row, but a user who never reopens that screen would never
 * learn their daily backup has been failing — so we also surface the failure
 * here. Only terminal / fast-fail outcomes notify; mid-retry attempts stay
 * silent to avoid spamming.
 *
 * Channel is created once via [createChannel] in Application.onCreate. We share
 * [MedalNotifier.hasPostPermission] so a denied POST_NOTIFICATIONS no-ops
 * instead of crashing, matching CoachNotifier / MedalNotifier.
 */
object BackupNotification {

    const val CHANNEL_ID = "backup_status"
    private const val NOTIF_ID = 5253

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.backup_notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.backup_notif_channel_desc)
                setShowBadge(true)
            }
        )
    }

    /** Post (or replace) the single "auto-backup failed" notification. */
    fun showFailure(ctx: Context, message: String) {
        if (!MedalNotifier.hasPostPermission(ctx)) return
        val mgr = ctx.getSystemService<NotificationManager>() ?: return
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ctx.getString(R.string.backup_notif_failure_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openAppPendingIntent(ctx))
            .build()
        mgr.notify(NOTIF_ID, notif)
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
