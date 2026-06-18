package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.di.ServiceLocator
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Retry/compensation for the one-way Health Connect exercise-session mirror.
 *
 * The happy path mirrors each saved workout inline in
 * [com.silverbp.android.exercise.ExerciseRepository.upsert]. When that write
 * fails, the row is left with `hcRecordId == null`; this worker re-attempts
 * those rows and stamps the returned record id so they are not retried again.
 *
 * Idempotent end-to-end: the bridge writes with `clientRecordId = session.id`,
 * so re-mirroring an already-present workout upserts rather than duplicates.
 */
class ExerciseSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (settings?.enableHealthConnect != true) return Result.success()

        val bridge = ServiceLocator.healthConnectExerciseBridge
        if (!bridge.hasPermissions()) {
            Log.i(TAG, "[ExerciseSync] exercise write permission not granted; skipping")
            return Result.success()
        }

        val dao = ServiceLocator.database.exerciseDao()
        val pending = runCatching { dao.findUnmirrored() }.getOrElse {
            Log.w(TAG, "[ExerciseSync] query failed; will retry", it)
            return Result.retry()
        }
        if (pending.isEmpty()) return Result.success()

        var mirrored = 0
        var anyFailure = false
        for (entity in pending) {
            val pointsResult = runCatching { dao.pointsFor(entity.id).map { it.toDomain() } }
            if (pointsResult.isFailure) {
                Log.w(
                    TAG,
                    "[ExerciseSync] route query failed for ${entity.id}; will retry",
                    pointsResult.exceptionOrNull(),
                )
                anyFailure = true
                continue
            }
            val points = pointsResult.getOrThrow()
            val hcId = bridge.write(entity.toDomain(), points)
            if (hcId != null) {
                runCatching {
                    dao.updateSession(
                        entity.copy(
                            hcRecordId = hcId,
                            updatedAt = Instant.now().toEpochMilli(),
                        ),
                    )
                }
                mirrored++
            } else {
                anyFailure = true
            }
        }
        Log.i(TAG, "[ExerciseSync] mirrored $mirrored/${pending.size}")
        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.health.exercise-sync"
        private const val TAG = "ExerciseSyncWorker"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<ExerciseSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
