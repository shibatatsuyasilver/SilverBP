package com.silverbp.android.coach

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
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
        // Execution-time gate (mirrors WeightSyncWorker's import half): an
        // already-scheduled job — enqueued before this gate existed, or persisting
        // across an OS upgrade to Android 15+ — must NOT read Health Connect without
        // both the per-type read grant AND, on Android 15+, the background-read
        // grant. Benign no-op (success, never retry) so it doesn't loop.
        if (!bridge.hasSleepReadPermission() || !canRunBackgroundHealthConnectReads()) {
            Log.i(TAG, "[SleepBackfill] read or background-read permission not granted; skipping")
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

    /**
     * Mirror of WeightSyncWorker's background-read gate: Android 15+ (API 35)
     * requires the background-read permission for a WorkManager job to read
     * Health Connect at all — without it the read silently returns nothing.
     * Below API 35 no extra grant is needed.
     */
    private suspend fun canRunBackgroundHealthConnectReads(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_15_API) return true
        val granted = runCatching {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                return false
            }
            HealthConnectClient.getOrCreate(applicationContext)
                .permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        return granted.containsAll(ServiceLocator.healthConnectBridge.backgroundReadPermissions)
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.coach.sleep-backfill"
        private const val BACKFILL_DAYS = 14
        private const val TAG = "SleepBackfillWorker"

        /** Android 15 (API 35); at/above this a background HC read needs the extra grant. */
        private const val ANDROID_15_API = 35

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
