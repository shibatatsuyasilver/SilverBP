package com.silverbp.android.backup.auto

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.backup.RecoveryCodeStore
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream

/**
 * Runs the full auto-backup pipeline:
 *   1. resolve linked Google account + stored recovery code (fail fast if missing)
 *   2. obtain a fresh Drive access token (fail fast on revoked consent)
 *   3. encrypt + serialize the local snapshot via [com.silverbp.android.backup.BackupManager.export]
 *   4. upload the bytes to the user's Drive `appDataFolder`
 *   5. delete older backups beyond [KEEP_LAST]
 *   6. write success / error markers to DataStore for the UI status row
 *
 * Used both by [AutoBackupScheduler] periodic enqueues and by the "立即備份"
 * one-shot button — same code path either way.
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Defensive: in case SilverBpApplication hasn't run yet (rare under
        // WorkManager but cheap to guard).
        ServiceLocator.init(applicationContext)

        val settings = ServiceLocator.userSettings.flow.first()
        val linkedEmail = settings.googleAccountEmail
        val recoveryCode = RecoveryCodeStore.create(applicationContext).get()
        if (linkedEmail.isBlank() || recoveryCode == null) {
            val msg = when {
                recoveryCode == null -> "未設定恢復碼"
                else -> "未連結 Google 帳號"
            }
            ServiceLocator.userSettings.recordBackupFailure(msg, System.currentTimeMillis())
            return Result.failure()
        }

        return try {
            val token = when (val r = ServiceLocator.googleAuthClient.requestDriveToken(linkedEmail)) {
                is GoogleAuthClient.TokenResult.Granted -> r.accessToken
                is GoogleAuthClient.TokenResult.NeedsConsent,
                GoogleAuthClient.TokenResult.Cancelled -> {
                    ServiceLocator.userSettings.recordBackupFailure(
                        "Drive 權限已撤銷, 請重新連結",
                        System.currentTimeMillis(),
                    )
                    return Result.failure()
                }
            }

            val out = ByteArrayOutputStream()
            ServiceLocator.backupManager.export(out, recoveryCode)
            val bytes = out.toByteArray()

            val fileName = "SilverBP-Backup-${nowFileTimestamp()}.sbpbk"
            ServiceLocator.googleDriveBackupClient.upload(bytes, fileName, token)

            // Prune oldest entries beyond the retention cap. listBackups
            // returns newest-first, so anything past index [KEEP_LAST-1] is
            // stale.
            runCatching {
                val all = ServiceLocator.googleDriveBackupClient.listBackups(token)
                all.drop(KEEP_LAST).forEach { stale ->
                    ServiceLocator.googleDriveBackupClient.deleteFile(stale.id, token)
                }
            }.onFailure { Log.w(TAG, "retention prune failed (non-fatal)", it) }

            ServiceLocator.userSettings.recordBackupSuccess(System.currentTimeMillis())
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "auto-backup failed", t)
            val message = t.localizedMessage ?: t::class.java.simpleName
            ServiceLocator.userSettings.recordBackupFailure(message, System.currentTimeMillis())
            // WorkManager will exponentially back off until MAX_AUTO_RETRIES
            // attempts then mark the work failed; the UI will already show
            // the persisted error from recordBackupFailure.
            if (runAttemptCount < MAX_AUTO_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        /** Drive retention cap: keep this many newest .sbpbk files in appDataFolder. */
        const val KEEP_LAST = 5
        /** Unique work name so frequency changes replace prior schedules. */
        const val UNIQUE_NAME = "silverbp.auto_backup"
        private const val MAX_AUTO_RETRIES = 4
        private const val TAG = "AutoBackupWorker"
    }
}

private fun nowFileTimestamp(): String {
    val cal = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d-%02d%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}
