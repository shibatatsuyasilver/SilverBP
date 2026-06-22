package com.silverbp.android.recognition

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverbp.android.di.ServiceLocator
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the selected on-device model variant as a **foreground** WorkManager
 * job, so a multi-GB download survives app-backgrounding / the screen leaving /
 * the process being reclaimed. WorkManager persists the request across process
 * death and re-runs us; [ModelDownloader] resumes from the `.part` file via an
 * HTTP Range header, so a kill mid-download continues rather than restarting.
 *
 * Progress + final state are published through [ServiceLocator.modelLoadStatus]
 * exactly as the old inline path did, so the UI (NutritionScreen banner, etc.)
 * is unchanged.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(-1)

    override suspend fun doWork(): Result {
        val status = ServiceLocator.modelLoadStatus
        val variant = resolveVariant()
        val hfTokenRef = inputData.getString(KEY_HF_TOKEN_REF)
        var hfToken: String? = null
        var clearTokenRef = false
        val sha = inputData.getString(KEY_SHA256)?.takeIf { it.isNotBlank() } ?: variant.sha256

        return try {
            hfToken = ModelDownloadTokenStore.resolve(applicationContext, hfTokenRef)
            setForeground(foregroundInfo(-1))
            status.set(ModelLoadPhase.Downloading(0f, variant.id))
            val downloader = ModelDownloader(applicationContext)
            var lastPct = -1
            downloader.download(variant, sha, hfToken).collect { p ->
                status.set(ModelLoadPhase.Downloading(p.fraction, variant.id))
                val pct = (p.fraction * 100f).toInt().coerceIn(0, 100)
                // Only refresh the foreground notification on a whole-percent
                // change so we don't spam NotificationManager every 64 KB.
                if (pct != lastPct) {
                    lastPct = pct
                    runCatching { setForeground(foregroundInfo(pct)) }
                }
            }
            // Download complete — load the engine into memory (Loading → Ready).
            ModelBootstrap.preloadVariant(applicationContext, variant)
            ServiceLocator.userSettings.setModelDownloaded(true)
            clearTokenRef = true
            Result.success()
        } catch (e: IOException) {
            // Network blip / interrupted stream — keep the .part file and let
            // WorkManager retry; the next run resumes via the Range header.
            Log.w(TAG, "[ModelDownload] network error, will retry: ${e.message}")
            status.set(ModelLoadPhase.Downloading(0f, variant.id))
            Result.retry()
        } catch (e: Throwable) {
            Log.w(TAG, "[ModelDownload] failed: ${e.message}", e)
            status.set(ModelLoadPhase.Failed(e.message ?: "download failed"))
            clearTokenRef = true
            Result.failure()
        } finally {
            if (clearTokenRef) ModelDownloadTokenStore.clear(applicationContext, hfTokenRef)
            hfToken = null
        }
    }

    private fun resolveVariant(): ModelVariant {
        val id = inputData.getString(KEY_VARIANT_ID)
        return if (id != null) ModelCatalog.byId(id) else ModelCatalog.default
    }

    private fun foregroundInfo(pct: Int): ForegroundInfo {
        val notif = ModelDownloadNotification.build(applicationContext, pct)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                ModelDownloadNotification.NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(ModelDownloadNotification.NOTIF_ID, notif)
        }
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.model.download"
        private const val KEY_VARIANT_ID = "variant_id"
        private const val KEY_HF_TOKEN_REF = "hf_token_ref"
        private const val KEY_SHA256 = "sha256"
        private const val TAG = "ModelDownloadWorker"

        /**
         * Enqueue (or keep) the foreground download for [variantId]. KEEP so a
         * repeat trigger never restarts an already-running download; the unique
         * work survives process death and resumes automatically.
         *
         * [allowMetered] relaxes the network constraint from UNMETERED (Wi-Fi /
         * unmetered only — the default) to CONNECTED (any network, incl. mobile
         * data). The UI sets it true only after an explicit per-download
         * confirmation, gated behind the "allow download over mobile data"
         * setting.
         */
        fun enqueue(
            context: Context,
            variantId: String,
            hfToken: String? = null,
            sha256: String? = null,
            allowMetered: Boolean = false,
        ) {
            val appContext = context.applicationContext
            val hfTokenRef = ModelDownloadTokenStore.put(appContext, hfToken)
            val networkType = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            val data = Data.Builder()
                .putString(KEY_VARIANT_ID, variantId)
                .apply {
                    hfTokenRef?.let { putString(KEY_HF_TOKEN_REF, it) }
                    sha256?.takeIf { it.isNotBlank() }?.let { putString(KEY_SHA256, it) }
                }
                .build()
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(data)
                .build()
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
