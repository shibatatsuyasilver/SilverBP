package com.silverbp.android.coach

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
 *  - Daily: 24 h period, fires at the user's preferred hour (default 07:00).
 *  - Weekly: 7 d period, fires Monday 07:00.
 *
 * Initial delay is computed from "now → next target time" so the first run
 * happens at the expected wall-clock moment, not 24 h after install.
 */
object CoachReminderScheduler {
    const val UNIQUE_DAILY = "silverbp.coach.daily"
    const val UNIQUE_WEEKLY = "silverbp.coach.weekly"

    fun scheduleAll(context: Context, dailyHour: Int = 7) {
        scheduleDaily(context, dailyHour)
        scheduleWeekly(context)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_DAILY)
        wm.cancelUniqueWork(UNIQUE_WEEKLY)
    }

    fun scheduleDaily(context: Context, hour: Int = 7) {
        val initialMillis = millisUntilNext(hour = hour, minute = 0)
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

    private fun millisUntilNext(hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.now(zone)
        var target = now.with(LocalTime.of(hour.coerceIn(0, 23), minute))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return java.time.Duration.between(now, target).toMillis()
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
