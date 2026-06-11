package com.silverbp.android.coach

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules the two periodic Coach workers and lets callers (Settings toggle,
 * Application init) cancel them when the feature is disabled.
 *
 *  - Daily: 24 h period, fires at the user's preferred time (default 07:00).
 *    The first run is aligned to the next matching weekday (per the user's
 *    [com.silverbp.android.settings.UserSettings.reminderDaysMask]); the worker
 *    re-checks the mask on each fire and no-ops on excluded days.
 *  - Weekly: 7 d period, fires Monday 07:00.
 *
 * Initial delay is computed from "now → next target time" so the first run
 * happens at the expected wall-clock moment, not 24 h after install.
 */
object CoachReminderScheduler {
    const val UNIQUE_DAILY = "silverbp.coach.daily"
    const val UNIQUE_WEEKLY = "silverbp.coach.weekly"

    // Owned prefs file: remembers the target (hour/minute/mask) the daily worker
    // was last anchored to. Lets us re-anchor (CANCEL_AND_REENQUEUE) only when the
    // target actually changed, and KEEP on the unconditional cold-start sweep so
    // we don't reset the periodic anchor every launch.
    private const val PREFS = "silverbp.coach.reminder_schedule"
    private const val KEY_DAILY_HOUR = "daily_hour"
    private const val KEY_DAILY_MINUTE = "daily_minute"
    private const val KEY_DAILY_MASK = "daily_mask"
    private const val UNSET = Int.MIN_VALUE

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Pure decision: which [ExistingPeriodicWorkPolicy] to use given the last
     * anchored target vs. the requested one. CANCEL_AND_REENQUEUE re-aligns the
     * schedule when the user picks a new time/mask; otherwise KEEP preserves the
     * existing anchor so the repeated cold-start sweep is a no-op (and the weekly
     * worker is never pushed out). [saved] is the persisted triple (or null when
     * nothing has been scheduled yet — first schedule, so re-enqueue).
     *
     * DST note: the anchor is wall-clock-correct at schedule time; WorkManager
     * then repeats on a fixed 24 h period, so across a DST boundary the fire can
     * drift ±1 h until the next time/mask change re-anchors it. Acceptable for a
     * reminder; a user-initiated change realigns it immediately.
     */
    fun policyFor(saved: Triple<Int, Int, Int>?, target: Triple<Int, Int, Int>): ExistingPeriodicWorkPolicy =
        if (saved == target) ExistingPeriodicWorkPolicy.KEEP
        else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE

    /**
     * Cold-start / toggle entry point. Reads the user's reminder prefs and
     * aligns the daily worker to the chosen time + weekday mask. Suspend so we
     * can pull the prefs; callers already run inside coroutines.
     */
    suspend fun scheduleAll(context: Context) {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        scheduleDaily(
            context,
            hour = s?.reminderHour ?: 7,
            minute = s?.reminderMinute ?: 0,
            mask = s?.reminderDaysMask ?: DayOfWeekMask.ALL,
        )
        scheduleWeekly(context)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_DAILY)
        wm.cancelUniqueWork(UNIQUE_WEEKLY)
        // Forget the anchor so the next schedule re-enqueues from scratch.
        prefs(context).edit()
            .remove(KEY_DAILY_HOUR).remove(KEY_DAILY_MINUTE).remove(KEY_DAILY_MASK)
            .apply()
    }

    fun scheduleDaily(context: Context, hour: Int = 7, minute: Int = 0, mask: Int = DayOfWeekMask.ALL) {
        // Empty mask = no firing days; cancel rather than schedule a worker that
        // would no-op every day.
        if (DayOfWeekMask.isEmpty(mask)) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_DAILY)
            prefs(context).edit()
                .remove(KEY_DAILY_HOUR).remove(KEY_DAILY_MINUTE).remove(KEY_DAILY_MASK)
                .apply()
            return
        }
        val p = prefs(context)
        val saved = p.getInt(KEY_DAILY_HOUR, UNSET).takeIf { it != UNSET }?.let { h ->
            Triple(h, p.getInt(KEY_DAILY_MINUTE, 0), p.getInt(KEY_DAILY_MASK, DayOfWeekMask.ALL))
        }
        val target = Triple(hour, minute, mask)
        // KEEP when the target is unchanged so the repeated cold-start sweep is a
        // no-op; CANCEL_AND_REENQUEUE re-anchors to the new wall-clock time/mask.
        val policy = policyFor(saved, target)
        val initialMillis = millisUntilNext(hour = hour, minute = minute, mask = mask)
        val req = PeriodicWorkRequestBuilder<DailyReminderWorker>(
            24, TimeUnit.HOURS,
        )
            .setInitialDelay(initialMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_DAILY, policy, req)
        p.edit()
            .putInt(KEY_DAILY_HOUR, hour)
            .putInt(KEY_DAILY_MINUTE, minute)
            .putInt(KEY_DAILY_MASK, mask)
            .apply()
    }

    fun scheduleWeekly(context: Context) {
        val initialMillis = millisUntilNextMondayAt(hour = 7, minute = 0)
        val req = PeriodicWorkRequestBuilder<WeeklyReportWorker>(
            7, TimeUnit.DAYS,
        )
            .setInitialDelay(initialMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        // Weekly target is fixed (Mon 07:00), so KEEP: the unconditional cold-start
        // sweep must not re-anchor it (CANCEL_AND_REENQUEUE every launch could
        // permanently delay the next Monday fire). The first call anchors it.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WEEKLY, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Millis from now until the next occurrence of [hour]:[minute] that lands on
     * a weekday in [mask]. Iterates 0..7 days ahead — offset 0 covers "later
     * today", offset 7 covers "same weekday next week". Caller guarantees
     * [mask] is non-empty, so the loop always finds a match.
     */
    private fun millisUntilNext(
        hour: Int,
        minute: Int,
        mask: Int = DayOfWeekMask.ALL,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = ZonedDateTime.now(zone)
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        for (offset in 0..7) {
            val candidate = now.toLocalDate().plusDays(offset.toLong()).atTime(time).atZone(zone)
            if (candidate.isAfter(now) && DayOfWeekMask.contains(mask, candidate.dayOfWeek)) {
                return java.time.Duration.between(now, candidate).toMillis()
            }
        }
        // Defensive fallback (mask should never be empty here): fire tomorrow.
        return java.time.Duration.between(now, now.with(time).plusDays(1)).toMillis()
    }

    private fun millisUntilNextMondayAt(hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.now(zone)
        val today = LocalDate.now(zone)
        val daysUntilMonday = ((DayOfWeek.MONDAY.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }
        val target = now.toLocalDate().plusDays(daysUntilMonday.toLong())
            .atTime(LocalTime.of(hour, minute)).atZone(zone)
        return java.time.Duration.between(now, target).toMillis()
    }
}
