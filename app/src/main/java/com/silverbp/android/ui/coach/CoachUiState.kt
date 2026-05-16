package com.silverbp.android.ui.coach

/**
 * Lightweight UI state for the Coach screen.
 *
 * PR1 ships placeholder content — `CoachEngine` does not yet exist and there
 * is no plan persistence. Once PR2 lands the real CoachPlan / CoachTask
 * domain types will replace these placeholder fields, but the screen contract
 * (TodayTask + 4 ModuleRows + Weekly + Narration) is intentionally stable
 * so later PRs can swap data sources without UI churn.
 */
sealed interface CoachUiState {
    data object Loading : CoachUiState

    data class Ready(
        val todayTask: TodayTaskUi,
        val modules: List<ModuleRowUi>,
        val weeklyProgress: WeeklyProgressUi,
        val narration: NarrationUi,
        /** Persistence id of the task surfaced as today's primary action; null when there is no actionable task today. */
        val todayTaskId: String? = null,
    ) : CoachUiState
}

/** Single primary action shown at the top of the Coach tab. */
data class TodayTaskUi(
    val title: String,
    val subtitle: String? = null,
    val completed: Boolean = false,
    /** When true the run-engine flagged today as a recovery day; UI shows a red banner instead of the action button. */
    val safetyHold: Boolean = false,
    /** Minutes of qualifying exercise logged today (Walking + Running). */
    val achievedMinutes: Int = 0,
    /** Target minutes for today's exercise task; 0 means no quantitative target. */
    val targetMinutes: Int = 0,
    /** True when today has no scheduled Exercise task (Wed/Sat). UI renders a rest-day headline instead. */
    val isRestDay: Boolean = false,
)

/**
 * Row showing one of the four lifestyle modules (Exercise/Diet/Sleep/Medication)
 * with a simple ratio-based progress ring.
 */
data class ModuleRowUi(
    val moduleKey: ModuleKey,
    val displayName: String,
    val completed: Int,
    val target: Int,
    val tapRoute: String? = null,
) {
    val ratio: Float get() = if (target == 0) 0f else (completed.toFloat() / target).coerceIn(0f, 1f)
}

enum class ModuleKey { Exercise, Diet, Sleep, Medication }

/**
 * Compact card summarising last 7 days. PR1 only shows a placeholder caption —
 * the Vico chart wires up in PR3 once CoachRepository exposes weekly history.
 */
data class WeeklyProgressUi(
    val placeholderText: String,
    val hasData: Boolean = false,
)

/**
 * Streaming-friendly narration block. PR1 leaves it static; PR3 introduces
 * real LLM streaming via [com.silverbp.android.coach.CoachNarrator].
 */
data class NarrationUi(
    val text: String,
    val isStreaming: Boolean = false,
)
