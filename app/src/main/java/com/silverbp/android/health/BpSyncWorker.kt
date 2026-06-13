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
import kotlinx.coroutines.flow.first

/**
 * Retry/compensation for the one-way Health Connect blood-pressure mirror.
 *
 * The happy path mirrors each reading inline in [com.silverbp.android.core.
 * BpRepository.upsert]. When that write fails (Health Connect temporarily
 * unavailable, permission not yet granted, transient error) the row is left
 * with `hcRecordId == null`. This worker re-attempts every such row, stamping
 * the returned record id back on success so it isn't retried again.
 *
 * Idempotent end-to-end: the bridge writes with `clientRecordId = reading.id`,
 * so re-mirroring an already-present reading upserts rather than duplicates.
 *
 * Self-no-op when the integration is off or the BP write permission is missing,
 * mirroring [com.silverbp.android.coach.SleepBackfillWorker]. Enqueued on cold
 * start (see [com.silverbp.android.SilverBpApplication]); WorkManager handles
 * the backoff between retries.
 */
class BpSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (settings?.enableHealthConnect != true) return Result.success()

        val bridge = ServiceLocator.healthConnectBpBridge
        if (!bridge.hasWritePermission()) {
            Log.i(TAG, "[BpSync] BP write permission not granted; skipping")
            return Result.success()
        }

        val dao = ServiceLocator.database.bpDao()
        // Only the owner's readings are ever mirrored (roadmap §3-5); scope the
        // retry set to the owner so non-owner rows (hcRecordId null by design)
        // aren't repeatedly re-attempted.
        val ownerId = runCatching { ServiceLocator.memberRepository.ownerId() }.getOrElse {
            Log.w(TAG, "[BpSync] owner lookup failed; will retry", it)
            return Result.retry()
        }
        val pending = runCatching { dao.findUnmirrored(ownerId) }.getOrElse {
            Log.w(TAG, "[BpSync] query failed; will retry", it)
            return Result.retry()
        }
        if (pending.isEmpty()) return Result.success()

        var mirrored = 0
        var anyFailure = false
        for (entity in pending) {
            val hcId = bridge.write(entity.toDomain())
            if (hcId != null) {
                runCatching { dao.update(entity.copy(hcRecordId = hcId)) }
                mirrored++
            } else {
                anyFailure = true
            }
        }
        Log.i(TAG, "[BpSync] mirrored $mirrored/${pending.size}")
        // Leftover failures retry with WorkManager backoff; already-mirrored rows
        // are stamped so they won't be re-attempted.
        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.health.bp-sync"
        private const val TAG = "BpSyncWorker"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<BpSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
