package com.silverbp.android.coach

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * Fires Mon ~07:00 local. Runs the engine + narrator, then drops a
 * notification deep-linking to the weekly-report screen.
 *
 * The narrator output is collected as a single concatenated string so the
 * notification can show its first sentence (Android collapses long bodies).
 * The screen itself re-streams on open — we don't cache the prose because
 * different recognizer backends may render different prose.
 */
class WeeklyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (s?.enableCoach != true) return Result.success()

        return runCatching {
            // Generate and persist next week's plan so Coach screen has data
            // when the user taps the notification.
            val plan = ServiceLocator.coachEngine.generateWeeklyPlan()
            ServiceLocator.coachRepository.savePlan(plan)
            CoachNotifier.postWeeklyReport(applicationContext)
            Result.success()
        }.getOrElse { t ->
            Log.w(TAG, "[CoachWeekly] worker failed; will retry", t)
            Result.retry()
        }
    }

    private companion object { const val TAG = "CoachWeeklyWorker" }
}
