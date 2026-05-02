package com.silverbp.android.achievements

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object StepSyncScheduler {
    const val UNIQUE_NAME = "silverbp.step-sync.periodic"
    const val UNIQUE_KICK = "silverbp.step-sync.kick"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val periodic = PeriodicWorkRequestBuilder<StepSyncWorker>(
            6, TimeUnit.HOURS,
            1, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val kick = OneTimeWorkRequestBuilder<StepSyncWorker>()
            .setConstraints(constraints)
            .build()

        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        wm.enqueueUniqueWork(UNIQUE_KICK, ExistingWorkPolicy.REPLACE, kick)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_NAME)
        wm.cancelUniqueWork(UNIQUE_KICK)
    }
}
