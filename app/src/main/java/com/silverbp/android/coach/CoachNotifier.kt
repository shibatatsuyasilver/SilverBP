package com.silverbp.android.coach

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
import com.silverbp.android.ui.nav.Routes

/**
 * Three-way notifier for the Coach feature. Channels are split so the user
 * can mute the calm daily/weekly cadence without losing the high-importance
 * BP alert.
 *
 * Permission gate ([MedalNotifier.hasPostPermission]) is shared with medals;
 * we no-op if the user denied POST_NOTIFICATIONS rather than crash.
 */
object CoachNotifier {

    const val CHANNEL_DAILY = "coach_daily"
    const val CHANNEL_WEEKLY = "coach_weekly"
    const val CHANNEL_ALERT = "coach_alert"

    private const val NOTIF_ID_DAILY = 9300
    private const val NOTIF_ID_WEEKLY = 9301
    private const val NOTIF_ID_ALERT = 9302

    const val EXTRA_COACH_ROUTE = "coach_route"

    fun createChannels(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return

        if (mgr.getNotificationChannel(CHANNEL_DAILY) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DAILY,
                    context.getString(R.string.coach_channel_daily),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.coach_channel_daily_desc)
                    setShowBadge(true)
                }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_WEEKLY) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WEEKLY,
                    context.getString(R.string.coach_channel_weekly),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.coach_channel_weekly_desc)
                    setShowBadge(true)
                }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_ALERT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    context.getString(R.string.coach_channel_alert),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.coach_channel_alert_desc)
                    setShowBadge(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 200, 250)
                }
            )
        }
    }

    fun postDailyReminder(context: Context, body: String?) {
        if (!MedalNotifier.hasPostPermission(context)) return
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val notif = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.coach_notif_daily_title))
            .setContentText(body ?: context.getString(R.string.coach_notif_daily_body_default))
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, "coach", NOTIF_ID_DAILY))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        mgr.notify(NOTIF_ID_DAILY, notif)
    }

    fun postWeeklyReport(context: Context) {
        if (!MedalNotifier.hasPostPermission(context)) return
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val notif = NotificationCompat.Builder(context, CHANNEL_WEEKLY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.coach_notif_weekly_title))
            .setContentText(context.getString(R.string.coach_notif_weekly_body))
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, Routes.COACH_WEEKLY_REPORT, NOTIF_ID_WEEKLY))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        mgr.notify(NOTIF_ID_WEEKLY, notif)
    }

    fun postAnomaly(context: Context, severity: Severity) {
        if (!MedalNotifier.hasPostPermission(context)) return
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val titleRes = when (severity) {
            Severity.Critical -> R.string.coach_notif_alert_title_critical
            Severity.Caution -> R.string.coach_notif_alert_title_caution
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(R.string.coach_notif_alert_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(deepLink(context, "coach", NOTIF_ID_ALERT))
            .build()
        mgr.notify(NOTIF_ID_ALERT, notif)
    }

    private fun deepLink(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_COACH_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
