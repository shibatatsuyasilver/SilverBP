package com.silverbp.android.achievements

/**
 * Snapshot of everything the [AchievementEvaluator] needs to judge whether
 * any new medals fired. Built by [AchievementStore.refresh] from the DAO,
 * Health Connect / sensor reads, and the user's settings.
 *
 * Pure data — no DB / context handles — so [AchievementEvaluator.evaluate]
 * stays a pure function and is trivially unit-testable.
 */
data class AchievementStats(
    /** Today's full-day step total (best available source). */
    val todaySteps: Int,
    /** All-time cumulative steps across logged days + finished sessions. */
    val lifetimeSteps: Long,
    /** Length of the most recent unbroken run of days where steps >= [dailyStepGoal]. */
    val currentStreakDays: Int,
    /** Number of saved [com.silverbp.android.exercise.ExerciseSession] rows. */
    val sessionCount: Int,
    /** User's tunable streak threshold. */
    val dailyStepGoal: Int,
)
