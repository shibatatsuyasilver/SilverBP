package com.silverbp.android.billing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the 24 h entitlement revalidation. Mirrors
 * [com.silverbp.android.achievements.StepSyncScheduler]: unique periodic work,
 * KEEP policy so a cold-start reconcile is a no-op once scheduled. Requires
 * network (the check is a Play round-trip) — when offline the run is deferred,
 * and [EntitlementManager]'s cache keeps the last-known tier in the meantime.
 */
object EntitlementRevalidationScheduler {
    const val UNIQUE_NAME = "silverbp.entitlement-revalidation.periodic"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<EntitlementRevalidationWorker>(
            24, TimeUnit.HOURS,
            3, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
