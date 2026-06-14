package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * Retry/compensation for the one-way Health Connect body-weight mirror.
 *
 * Mirrors [GlucoseSyncWorker] 1:1. The happy path mirrors each reading inline in
 * [com.silverbp.android.core.WeightRepository.upsert]; when that write fails
 * (Health Connect temporarily unavailable, permission not yet granted, transient
 * error) the row is left with `hcRecordId == null`. This worker re-attempts every
 * such row, stamping the returned record id back on success so it isn't retried
 * again.
 *
 * Idempotent end-to-end: the bridge writes with `clientRecordId = reading.id`,
 * so re-mirroring an already-present reading upserts rather than duplicates.
 *
 * Owner-only (roadmap §3-5 / §4-4): [com.silverbp.android.core.WeightRepository.
 * findUnmirrored] is already owner-filtered, so the retry set never picks up a
 * family member's rows (which stay `hcRecordId == null` by design).
 *
 * Self-no-op when the integration is off or the weight write permission is
 * missing. Enqueued on cold start alongside [BpSyncWorker] / [GlucoseSyncWorker]
 * (see [com.silverbp.android.SilverBpApplication]); WorkManager handles the
 * backoff between retries.
 */
class WeightSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (settings?.enableHealthConnect != true) return Result.success()

        val bridge = ServiceLocator.healthConnectWeightBridge
        if (!bridge.hasWritePermission()) {
            Log.i(TAG, "[WeightSync] weight write permission not granted; skipping")
            return Result.success()
        }

        val repo = ServiceLocator.weightRepository
        val dao = ServiceLocator.database.weightDao()
        // findUnmirrored() is owner-scoped in the repository (only the owner's
        // weight is ever mirrored); non-owner rows are hcRecordId-null by design
        // and must not be re-attempted here.
        val pending = runCatching { repo.findUnmirrored() }.getOrElse {
            Log.w(TAG, "[WeightSync] query failed; will retry", it)
            return Result.retry()
        }
        if (pending.isEmpty()) return Result.success()

        var mirrored = 0
        var anyFailure = false
        for (reading in pending) {
            val hcId = bridge.write(reading)
            if (hcId != null) {
                runCatching { dao.upsert(reading.copy(hcRecordId = hcId).toEntity()) }
                mirrored++
            } else {
                anyFailure = true
            }
        }
        Log.i(TAG, "[WeightSync] mirrored $mirrored/${pending.size}")
        // Leftover failures retry with WorkManager backoff; already-mirrored rows
        // are stamped so they won't be re-attempted.
        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.health.weight-sync"
        private const val TAG = "WeightSyncWorker"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<WeightSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
