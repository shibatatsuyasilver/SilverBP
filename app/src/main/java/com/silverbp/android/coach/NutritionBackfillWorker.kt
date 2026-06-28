package com.silverbp.android.coach

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.health.HealthConnectBridge
import com.silverbp.android.health.classifySodium
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Mirrors [SleepBackfillWorker] but for sodium intake. Writes one
 * [DietCheckEntity] per day, with `sodiumLevelRaw` derived via
 * [classifySodium] (low <1500mg / mid 1500–2300 / high >2300).
 *
 * `vegServings` is left at 0 because Health Connect doesn't expose a
 * vegetable-servings field; users still log that manually via the diet
 * sub-route. We never overwrite a user-edited "manual" row.
 */
class NutritionBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull() ?: return Result.success()
        if (!s.enableCoach || !s.dietTrackingEnabled) return Result.success()

        val bridge = HealthConnectBridge(applicationContext)
        // Execution-time gate (mirrors WeightSyncWorker's import half): an
        // already-scheduled job — enqueued before this gate existed, or persisting
        // across an OS upgrade to Android 15+ — must NOT read Health Connect without
        // both the per-type read grant AND, on Android 15+, the background-read
        // grant. Benign no-op (success, never retry) so it doesn't loop.
        if (!bridge.hasNutritionReadPermission() || !canRunBackgroundHealthConnectReads()) {
            Log.i(TAG, "[NutritionBackfill] read or background-read permission not granted; skipping")
            return Result.success()
        }

        return runCatching {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.minusDays(BACKFILL_DAYS.toLong() - 1)
            val rows = bridge.queryNutrition(from, today, zone) ?: return Result.success()
            val now = System.currentTimeMillis()
            val coachRepo = ServiceLocator.coachRepository
            for (entry in rows) {
                val existing = coachRepo.dietForDay(entry.dayStartMillis)
                // Don't trample manually-logged sodium values. Only overwrite
                // when there's no row yet, or the existing row is from HC.
                if (existing != null && existing.sourceRaw == "manual") continue
                coachRepo.upsertDiet(
                    DietCheckEntity(
                        dayStart = entry.dayStartMillis,
                        sodiumLevelRaw = classifySodium(entry.sodiumMg),
                        vegServings = existing?.vegServings ?: 0,
                        sourceRaw = "hc",
                        updatedAt = now,
                    )
                )
            }
            Log.i(TAG, "[NutritionBackfill] wrote ${rows.size} day(s)")
            Result.success()
        }.getOrElse { t ->
            Log.w(TAG, "[NutritionBackfill] failed; will retry", t)
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
        const val UNIQUE_NAME = "silverbp.coach.nutrition-backfill"
        private const val BACKFILL_DAYS = 14
        private const val TAG = "NutritionBackfillWorker"

        /** Android 15 (API 35); at/above this a background HC read needs the extra grant. */
        private const val ANDROID_15_API = 35

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<NutritionBackfillWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
