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
 *  - [reconcile] is the cold-start counterpart: it re-registers a schedule that
 *    a force-stop / OEM task-killer wiped, using KEEP so it never disturbs a
 *    healthy one. Call it from `Application.onCreate` like the other reconcilers.
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
        val intervalDays = frequency.intervalDays ?: run { cancel(); return }
        wm.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRequest(intervalDays),
        )
    }

    /**
     * Cold-start reconciliation: re-register the periodic schedule to match the
     * persisted [frequency] WITHOUT disturbing a healthy existing one. Uses KEEP
     * — a running daily/weekly job keeps its next-run anchor (re-applying
     * [enqueue]'s UPDATE on every launch could reset the period and starve a
     * user who opens the app more than once a day). Restores a schedule that a
     * force-stop / OEM task-killer cancelled — the WorkManager equivalent of
     * iOS re-registering its BGTask in `SilverBPApp.init()`. `Off` cancels.
     */
    fun reconcile(frequency: AutoBackupFrequency) {
        val intervalDays = frequency.intervalDays ?: run { cancel(); return }
        wm.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildRequest(intervalDays),
        )
    }

    /**
     * Build the periodic request anchored to the next local
     * [PREFERRED_HOUR]:[PREFERRED_MINUTE] (≈03:30) so backups land overnight
     * like iOS's BGTask 03:30 schedule. WorkManager still defers under Doze, so
     * the anchor is a best-effort preference, not a guaranteed firing time.
     */
    private fun buildRequest(intervalDays: Long) =
        PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays, TimeUnit.DAYS)
            .setInitialDelay(millisUntilNextPreferredHour(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    private fun millisUntilNextPreferredHour(): Long {
        val now = java.util.Calendar.getInstance()
        val next = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, PREFERRED_HOUR)
            set(java.util.Calendar.MINUTE, PREFERRED_MINUTE)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return next.timeInMillis - now.timeInMillis
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
        /** Preferred overnight anchor for periodic backups (local time, ≈iOS 03:30). */
        private const val PREFERRED_HOUR = 3
        private const val PREFERRED_MINUTE = 30
    }
}
