package com.silverbp.android.achievements

/**
 * Pure decision rule: given a [stats] snapshot and the set of medals already
 * unlocked, return any newly-unlocked medals. Idempotent — re-running with
 * the same inputs after persisting the result returns an empty list.
 *
 * Has no DB / context / coroutine dependency, so all the threshold edge
 * cases are covered by JVM unit tests in `AchievementEvaluatorTest`.
 */
object AchievementEvaluator {

    fun evaluate(
        stats: AchievementStats,
        alreadyUnlocked: Set<MedalKind>,
    ): List<MedalKind> = MedalKind.entries.filter { medal ->
        medal !in alreadyUnlocked && qualifies(medal, stats)
    }

    private fun qualifies(medal: MedalKind, s: AchievementStats): Boolean = when (medal.category) {
        MedalCategory.DailySteps -> s.todaySteps >= medal.threshold
        MedalCategory.Cumulative -> s.lifetimeSteps >= medal.threshold
        MedalCategory.Streak -> s.currentStreakDays >= medal.threshold
        MedalCategory.Session -> s.sessionCount >= medal.threshold
    }

    /**
     * Progress fraction in [0f, 1f] toward unlocking [medal] given current
     * [stats]. 1f means already unlocked (or qualifying right now).
     */
    fun progress(medal: MedalKind, stats: AchievementStats): Float {
        val current: Long = when (medal.category) {
            MedalCategory.DailySteps -> stats.todaySteps.toLong()
            MedalCategory.Cumulative -> stats.lifetimeSteps
            MedalCategory.Streak -> stats.currentStreakDays.toLong()
            MedalCategory.Session -> stats.sessionCount.toLong()
        }
        if (medal.threshold <= 0L) return 1f
        return (current.toFloat() / medal.threshold.toFloat()).coerceIn(0f, 1f)
    }
}
