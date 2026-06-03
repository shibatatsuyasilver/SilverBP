package com.silverbp.android.coach

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fires once per day (~06:30 local). Surfaces today's primary task as a
 * notification. If no plan exists for the current week (e.g. user opened the
 * app once last month), [CoachEngine.generateWeeklyPlan] runs synchronously
 * before posting so the notification body is real.
 *
 * Failure mode: when the user disabled coach in Settings or denied
 * POST_NOTIFICATIONS, we silently no-op.
 */
class DailyReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (s?.enableCoach != true) return Result.success()
        // Honor the user's reminder prefs: master toggle + weekday mask. The
        // worker runs daily but skips notifying on excluded days. (Re-scheduling
        // moves the next-fire instant; this guard covers same-period edits and
        // the periodic 24h drift onto a now-excluded day.)
        if (!s.reminderEnabled) return Result.success()
        val todayDow = LocalDate.now(ZoneId.systemDefault()).dayOfWeek
        if (!DayOfWeekMask.contains(s.reminderDaysMask, todayDow)) return Result.success()

        return runCatching {
            val now = Clock.systemDefaultZone().millis()
            val plan = ServiceLocator.coachRepository.currentPlan(now)
                ?: ServiceLocator.coachEngine.generateWeeklyPlan().also {
                    ServiceLocator.coachRepository.savePlan(it)
                }
            val today = todayTaskOf(plan) ?: run {
                CoachNotifier.postDailyReminder(applicationContext, body = null)
                return@runCatching Result.success()
            }
            val body = if (today.safetyHold) {
                applicationContext.getString(
                    com.silverbp.android.R.string.coach_safety_hold_body,
                )
            } else {
                today.title
            }
            CoachNotifier.postDailyReminder(applicationContext, body = body)
            Result.success()
        }.getOrElse { t ->
            Log.w(TAG, "[CoachDaily] worker failed; will retry", t)
            Result.retry()
        }
    }

    private fun todayTaskOf(plan: CoachPlan): CoachTask? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Decode weekStart via the same zone it was written in — see
        // CoachViewModel.todayDayOffset for the timezone-rationale.
        val planStart = Instant.ofEpochMilli(plan.weekStartMillis).atZone(zone).toLocalDate()
        val offset = (today.toEpochDay() - planStart.toEpochDay()).toInt().coerceIn(0, 6)
        val sameDay = plan.tasks.filter { it.dayOffset == offset }
        // Prefer Exercise (the actionable card) when present, otherwise the first scheduled task.
        return sameDay.firstOrNull { it.module == LifestyleModule.Exercise } ?: sameDay.firstOrNull()
    }

    private companion object { const val TAG = "CoachDailyWorker" }
}
