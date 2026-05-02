package com.silverbp.android.achievements

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.silverbp.android.MainActivity
import com.silverbp.android.R

/**
 * Wraps [NotificationManager] for medal-unlock notifications. Channel is
 * separate from `exercise_tracking` so the user can mute one without losing
 * the foreground notification for an active session.
 *
 * The runtime permission (`POST_NOTIFICATIONS`) is requested from the UI
 * layer (banner CTA) — mirroring the iOS first-unlock prompt — not here.
 */
object MedalNotifier {

    const val CHANNEL_ID = "medal_unlocked"
    private const val NOTIF_ID_BASE = 9100

    fun createChannel(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.medal_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.medal_notification_channel_desc)
            setShowBadge(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /** True if Android 13+ POST_NOTIFICATIONS is granted (or pre-13 baseline). */
    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Post one notification for a new unlock. Caller is expected to have
     * verified [hasPostPermission]; we still no-op safely if it wasn't.
     */
    fun postMedalUnlocked(context: Context, medal: MedalKind) {
        if (!hasPostPermission(context)) return
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val title = context.getString(R.string.medal_notification_title)
        val body = context.getString(
            R.string.medal_notification_body,
            context.getString(medal.displayNameRes),
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(launchPendingIntent(context, medal.ordinal))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        mgr.notify(NOTIF_ID_BASE + medal.ordinal, notif)
    }

    /** Post a single combined notification when several medals unlock together. */
    fun postMultipleMedalsUnlocked(context: Context, count: Int) {
        if (!hasPostPermission(context) || count <= 0) return
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(context.getString(R.string.medal_notification_title))
            .setContentText(context.getString(R.string.medal_notification_body_multi, count))
            .setAutoCancel(true)
            .setContentIntent(launchPendingIntent(context, /*requestCode*/ 0))
            .build()
        mgr.notify(NOTIF_ID_BASE - 1, notif)
    }
}
