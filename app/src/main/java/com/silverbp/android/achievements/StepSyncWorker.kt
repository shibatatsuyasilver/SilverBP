package com.silverbp.android.achievements

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first

class StepSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (settings?.enableHealthConnect != true) return Result.success()

        return runCatching { ServiceLocator.achievementStore.refresh() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { t ->
                    Log.w(TAG, "[StepSync] refresh failed; will retry", t)
                    Result.retry()
                },
            )
    }

    private companion object { const val TAG = "StepSyncWorker" }
}
