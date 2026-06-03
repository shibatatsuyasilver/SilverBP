package com.silverbp.android.coach

import android.content.Context
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
    }

    fun scheduleDaily(context: Context, hour: Int = 7, minute: Int = 0, mask: Int = DayOfWeekMask.ALL) {
        // Empty mask = no firing days; cancel rather than schedule a worker that
        // would no-op every day.
        if (DayOfWeekMask.isEmpty(mask)) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_DAILY)
            return
        }
        val initialMillis = millisUntilNext(hour = hour, minute = minute, mask = mask)
        val req = PeriodicWorkRequestBuilder<DailyReminderWorker>(
            24, TimeUnit.HOURS,
        )
            .setInitialDelay(initialMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_DAILY, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun scheduleWeekly(context: Context) {
        val initialMillis = millisUntilNextMondayAt(hour = 7, minute = 0)
        val req = PeriodicWorkRequestBuilder<WeeklyReportWorker>(
            7, TimeUnit.DAYS,
        )
            .setInitialDelay(initialMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WEEKLY, ExistingPeriodicWorkPolicy.UPDATE, req)
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
