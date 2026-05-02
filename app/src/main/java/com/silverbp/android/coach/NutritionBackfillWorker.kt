package com.silverbp.android.coach

import android.content.Context
import android.util.Log
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
        if (!bridge.hasNutritionReadPermission()) {
            Log.i(TAG, "[NutritionBackfill] permission not granted; skipping")
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

    companion object {
        const val UNIQUE_NAME = "silverbp.coach.nutrition-backfill"
        private const val BACKFILL_DAYS = 14
        private const val TAG = "NutritionBackfillWorker"

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
