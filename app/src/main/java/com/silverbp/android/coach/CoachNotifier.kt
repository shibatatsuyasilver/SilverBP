package com.silverbp.android.coach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.silverbp.android.MainActivity
import com.silverbp.android.R
import com.silverbp.android.achievements.MedalNotifier
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.ui.nav.Routes
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.absoluteValue

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
    const val CHANNEL_MED_REMINDER = "coach_med_reminder"

    private const val NOTIF_ID_DAILY = 9300
    private const val NOTIF_ID_WEEKLY = 9301
    private const val NOTIF_ID_ALERT = 9302
    /** Per-schedule notification IDs derive from this base. */
    private const val NOTIF_ID_MED_BASE = 9400

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
        if (mgr.getNotificationChannel(CHANNEL_MED_REMINDER) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MED_REMINDER,
                    context.getString(R.string.medication_reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.medication_reminder_channel_desc)
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

    /**
     * @param ownerMedication true when the medication belongs to the device
     *   owner — keeps the original copy. false addresses a family member by
     *   [memberName] in the title (v18; see strings_member_coach.xml).
     * @param memberName the owning member's display name; the resolved "Me"
     *   fallback is already substituted by the caller, so it is never blank
     *   when [ownerMedication] is false.
     */
    fun postMedicationReminder(
        context: Context,
        med: MedicationEntity,
        schedule: MedicationScheduleEntity,
        ownerMedication: Boolean = true,
        memberName: String = "",
    ) {
        if (!MedalNotifier.hasPostPermission(context)) return
        val mgr = context.getSystemService<NotificationManager>() ?: return
        val timeStr = "%02d:%02d".format(schedule.hour, schedule.minute)
        val title = if (ownerMedication) {
            context.getString(
                R.string.medication_reminder_notification_title,
                med.name,
            )
        } else {
            context.getString(
                R.string.medication_reminder_notification_title_member,
                memberName,
                med.name,
            )
        }
        val body = if (med.dose.isNotBlank()) {
            context.getString(
                R.string.medication_reminder_notification_body_with_dose,
                med.dose,
                timeStr,
            )
        } else {
            context.getString(
                R.string.medication_reminder_notification_body,
                timeStr,
            )
        }
        // Hash → 0..999 keeps multiple concurrent reminders distinct without
        // exhausting notification ID space; collisions are acceptable (one
        // reminder visually replaces another in the rare overlap case).
        val notifId = NOTIF_ID_MED_BASE +
            (schedule.id.hashCode().rem(1000).absoluteValue)
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        // PendingIntents are keyed by (requestCode + Intent.filterEquals), and
        // notifId can collide across schedules. A per-(schedule, day) data URI
        // keeps FLAG_UPDATE_CURRENT from overwriting one schedule's extras
        // with another's — and today's dayStart with tomorrow's after midnight.
        val uniqueData = Uri.Builder()
            .scheme("silverbp")
            .authority("med-reminder")
            .appendPath(schedule.id)
            .appendPath(dayStart.toString())
            .build()
        val takenIntent = Intent(context, MedicationActionReceiver::class.java).apply {
            action = MedicationActionReceiver.ACTION_MARK_TAKEN
            data = uniqueData
            putExtra(MedicationActionReceiver.EXTRA_MEDICATION_ID, med.id)
            putExtra(MedicationActionReceiver.EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(MedicationActionReceiver.EXTRA_DAY_START, dayStart)
            putExtra(MedicationActionReceiver.EXTRA_SCHEDULED_HOUR, schedule.hour)
            putExtra(MedicationActionReceiver.EXTRA_SCHEDULED_MINUTE, schedule.minute)
            putExtra(MedicationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val takenPi = PendingIntent.getBroadcast(
            context,
            notifId,
            takenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Unlike the other coach notifications (plain deepLink), the body tap
        // carries the same extras as the "mark taken" action so MainActivity
        // records the dose before navigating to the log screen.
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data = uniqueData
            putExtra(EXTRA_COACH_ROUTE, Routes.COACH_LOG_MEDICATION)
            putExtra(MedicationActionReceiver.EXTRA_MEDICATION_ID, med.id)
            putExtra(MedicationActionReceiver.EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(MedicationActionReceiver.EXTRA_DAY_START, dayStart)
            putExtra(MedicationActionReceiver.EXTRA_SCHEDULED_HOUR, schedule.hour)
            putExtra(MedicationActionReceiver.EXTRA_SCHEDULED_MINUTE, schedule.minute)
            putExtra(MedicationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_MED_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentPi)
            .addAction(
                android.R.drawable.checkbox_on_background,
                context.getString(R.string.medication_action_taken),
                takenPi,
            )
            .build()
        mgr.notify(notifId, notif)
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
