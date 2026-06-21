package com.silverbp.android.coach

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.silverbp.android.di.ServiceLocator
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Per-schedule WorkManager bookkeeping for medication / supplement reminders.
 *
 * Why one-time-self-rescheduling instead of [androidx.work.PeriodicWorkRequest]:
 * Periodic work has a 15-minute minimum and only repeats at a fixed interval.
 * "Mon/Wed/Fri at 08:00" is irregular by ISO-day — the canonical workaround is
 * a [androidx.work.OneTimeWorkRequest] with `setInitialDelay` to the next
 * matching instant, then re-enqueue from inside the worker after firing.
 *
 * Each schedule row owns a unique work name `silverbp.med.<scheduleId>`, so
 * editing/deleting cancels deterministically.
 */
object MedicationReminderScheduler {
    const val KEY_SCHEDULE_ID = "scheduleId"
    private const val UNIQUE_PREFIX = "silverbp.med."

    fun uniqueNameFor(scheduleId: String): String = "$UNIQUE_PREFIX$scheduleId"

    /**
     * Compute the millis-since-epoch of the next occurrence of (mask, hour,
     * minute) after [now]. Iterates up to 7 days ahead — guaranteed to
     * terminate. Returns null when [mask] is empty (caller treats as
     * "do not schedule").
     *
     * Loop iterates 0..7 inclusive: offset 0 covers "later today", offset 7
     * covers "this same weekday next week" when today's time already passed
     * and the mask only includes today.
     */
    fun nextFiringMillis(
        mask: Int,
        hour: Int,
        minute: Int,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): Long? {
        if (DayOfWeekMask.isEmpty(mask)) return null
        val target = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        for (offset in 0..7) {
            val candidate = now.toLocalDate().plusDays(offset.toLong())
                .atTime(target)
                .atZone(now.zone)
            if (!candidate.isAfter(now)) continue
            if (DayOfWeekMask.contains(mask, candidate.dayOfWeek)) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    /**
     * Enqueue (or replace) the OneTime worker for one schedule row. Reads
     * the row from the DB so callers don't have to pass the entity.
     */
    suspend fun scheduleOne(context: Context, scheduleId: String) {
        val s = ServiceLocator.database.medicationScheduleDao().findById(scheduleId)
            ?: run { cancelForSchedule(context, scheduleId); return }
        if (!s.enabled) {
            cancelForSchedule(context, scheduleId); return
        }
        val nextMillis = nextFiringMillis(s.daysOfWeekMask, s.hour, s.minute)
            ?: run { cancelForSchedule(context, scheduleId); return }
        val delay = (nextMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<MedicationReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(KEY_SCHEDULE_ID, scheduleId)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueNameFor(scheduleId), ExistingWorkPolicy.REPLACE, req)
    }

    /** Cold-start sweep: enqueue every enabled row. Called from app onCreate. */
    suspend fun scheduleAll(context: Context) {
        val rows = ServiceLocator.database.medicationScheduleDao().allEnabled()
        for (row in rows) scheduleOne(context, row.id)
    }

    fun cancelForSchedule(context: Context, scheduleId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueNameFor(scheduleId))
    }

    suspend fun cancelForMedication(context: Context, medicationId: String) {
        val rows = ServiceLocator.database.medicationScheduleDao().forMedication(medicationId)
        val wm = WorkManager.getInstance(context)
        for (row in rows) wm.cancelUniqueWork(uniqueNameFor(row.id))
    }

    /** Edit-save flow: cancel old workers and re-enqueue from current rows. */
    suspend fun rescheduleForMedication(context: Context, medicationId: String) {
        cancelForMedication(context, medicationId)
        val rows = ServiceLocator.database.medicationScheduleDao().forMedication(medicationId)
        for (row in rows) if (row.enabled) scheduleOne(context, row.id)
    }
}
