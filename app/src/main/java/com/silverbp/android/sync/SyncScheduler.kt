package com.silverbp.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager façade for background LAN sync, mirroring
 * [com.silverbp.android.backup.auto.AutoBackupScheduler]:
 *  - [enqueue] / [reconcile] register a single periodic [LanSyncWorker]
 *    (reconcile uses KEEP so a cold start never disturbs a healthy schedule —
 *    WorkManager's DB lives in `no_backup/` and is wiped by force-stop).
 *  - [runNow] is the one-shot for a manual "sync now" action.
 *  - [cancel] tears the schedule down (e.g. when the last peer is unpaired).
 */
class SyncScheduler(private val appContext: Context) {

    private val wm get() = WorkManager.getInstance(appContext)

    fun enqueue() {
        wm.enqueueUniquePeriodicWork(
            LanSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRequest(),
        )
    }

    fun reconcile() {
        wm.enqueueUniquePeriodicWork(
            LanSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest(),
        )
    }

    fun cancel() {
        wm.cancelUniqueWork(LanSyncWorker.UNIQUE_NAME)
    }

    fun runNow() {
        wm.enqueueUniqueWork(
            "${LanSyncWorker.UNIQUE_NAME}.once",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LanSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun buildRequest() =
        PeriodicWorkRequestBuilder<LanSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    companion object {
        /** Periodic cadence for opportunistic background re-sync attempts. */
        private const val INTERVAL_HOURS = 6L
        private const val INITIAL_BACKOFF_SECONDS = 30L
    }
}
