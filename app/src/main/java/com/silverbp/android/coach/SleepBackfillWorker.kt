package com.silverbp.android.coach

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.health.HealthConnectBridge
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * One-shot worker that pulls the last 14 days of sleep duration from Health
 * Connect and writes them into [com.silverbp.android.core.db.SleepLogEntity].
 *
 * Idempotent: each row is keyed by `dayStart` (epoch millis at local midnight),
 * so re-running just refreshes the values. Only runs when both
 * [com.silverbp.android.settings.UserSettings.sleepTrackingEnabled] is true
 * and the HC permission is granted; otherwise no-op.
 *
 * Triggered by:
 *  - User flips the Settings toggle ON (enqueued as a OneTimeWorkRequest)
 *  - App cold-start when the toggle is already ON (handled in
 *    [com.silverbp.android.SilverBpApplication])
 */
class SleepBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull() ?: return Result.success()
        if (!s.enableCoach || !s.sleepTrackingEnabled) return Result.success()

        val bridge = HealthConnectBridge(applicationContext)
        if (!bridge.hasSleepReadPermission()) {
            Log.i(TAG, "[SleepBackfill] permission not granted; skipping")
            return Result.success()
        }

        return runCatching {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.minusDays(BACKFILL_DAYS.toLong() - 1)
            val rows = bridge.querySleep(from, today, zone) ?: return Result.success()
            val now = System.currentTimeMillis()
            val coachRepo = ServiceLocator.coachRepository
            for (entry in rows) {
                coachRepo.upsertSleep(
                    SleepLogEntity(
                        dayStart = entry.dayStartMillis,
                        durationMin = entry.durationMin,
                        sourceRaw = "hc",
                        updatedAt = now,
                    )
                )
            }
            Log.i(TAG, "[SleepBackfill] wrote ${rows.size} day(s)")
            Result.success()
        }.getOrElse { t ->
            Log.w(TAG, "[SleepBackfill] failed; will retry", t)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.coach.sleep-backfill"
        private const val BACKFILL_DAYS = 14
        private const val TAG = "SleepBackfillWorker"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SleepBackfillWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
