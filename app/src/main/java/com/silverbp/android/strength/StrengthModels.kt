package com.silverbp.android.strength

/**
 * Coarse body region for the exercise catalog filter chips. [raw] is the
 * persisted/synced discriminator; [labelZh] is the user-facing 中文 label.
 */
enum class BodyPart(val raw: String, val labelZh: String) {
    UpperBody("upper", "上半身"),
    LowerBody("lower", "下半身"),
    Core("core", "核心"),
    FullBody("full", "全身");

    companion object {
        fun fromRaw(s: String): BodyPart = entries.first { it.raw == s }
    }
}

/**
 * Post-workout difficulty self-report. Drives later auto-plan progression
 * (Phase 4). [raw] is the persisted/synced discriminator.
 */
enum class DifficultyFeedback(val raw: String) {
    TooEasy("too_easy"),
    JustRight("just_right"),
    TooHard("too_hard");

    companion object {
        fun fromRaw(s: String): DifficultyFeedback = entries.first { it.raw == s }
    }
}

/**
 * A single exercise definition from the catalog (seeded library + user
 * favorites). [muscleGroups] are 中文 labels (e.g. 胸部 / 三頭肌).
 */
data class ExerciseCatalogItem(
    val id: String,
    val name: String,
    val bodyPart: BodyPart,
    val muscleGroups: List<String>,
    val description: String,
    val isFavorite: Boolean = false,
)

/**
 * One logged set within a workout session. [weightKg] is null for bodyweight
 * moves. [skipped] marks a set the user explicitly passed on.
 */
data class SetLog(
    val id: String,
    val exerciseId: String,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double? = null,
    val isCompleted: Boolean = false,
    val skipped: Boolean = false,
    val notes: String = "",
)

/**
 * A completed strength-training session: the chosen exercises in order, each
 * paired with its logged sets. [difficulty] is the optional post-workout
 * self-report.
 */
data class StrengthWorkoutSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val note: String = "",
    val difficulty: DifficultyFeedback? = null,
    val items: List<Pair<ExerciseCatalogItem, List<SetLog>>> = emptyList(),
)
