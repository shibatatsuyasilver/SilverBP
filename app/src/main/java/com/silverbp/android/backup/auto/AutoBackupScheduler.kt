package com.silverbp.android.backup.auto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Thin façade over [WorkManager] for the auto-backup feature.
 *
 *  - [enqueue] is idempotent — pass the user's chosen frequency; `Off` cancels
 *    any existing periodic work, all other values UPDATE the unique periodic
 *    schedule so old jobs are auto-replaced when the user changes cadence.
 *  - [runNow] kicks the same [AutoBackupWorker] as a one-shot for the
 *    「立即備份」button. Uses a separate unique name so it doesn't disturb
 *    the periodic schedule.
 *
 * No constraints are attached (network / charging) per product decision —
 * if the user opts into a frequency, we try to back up at that cadence
 * regardless of conditions. WorkManager still defers in Doze, which is fine.
 */
class AutoBackupScheduler(private val appContext: Context) {

    private val wm get() = WorkManager.getInstance(appContext)

    fun enqueue(frequency: AutoBackupFrequency) {
        val intervalDays = frequency.intervalDays
        if (intervalDays == null) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays, TimeUnit.DAYS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        wm.cancelUniqueWork(AutoBackupWorker.UNIQUE_NAME)
    }

    /** Manual 「立即備份」 — runs the same Worker class as a one-shot. */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(
            "${AutoBackupWorker.UNIQUE_NAME}.once",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        private const val INITIAL_BACKOFF_SECONDS = 30L
    }
}
