package com.silverbp.android.billing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.di.ServiceLocator

/**
 * Periodic re-check of the live Play subscription so a cancellation / expiry /
 * cross-device purchase reconciles even if the user never re-opens a paywall.
 * Mirrors [com.silverbp.android.achievements.StepSyncWorker]: thin, idempotent,
 * retries on failure. The actual resolve + cache write lives in
 * [EntitlementManager.refresh].
 */
class EntitlementRevalidationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching { ServiceLocator.entitlementManager.refresh() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { t ->
                    Log.w(TAG, "[Entitlement] revalidation failed; will retry", t)
                    Result.retry()
                },
            )

    private companion object { const val TAG = "EntitlementRevalWorker" }
}
