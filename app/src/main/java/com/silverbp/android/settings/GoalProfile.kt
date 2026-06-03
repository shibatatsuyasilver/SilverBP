package com.silverbp.android.settings

/**
 * Goal-profile enums captured during onboarding (Phase 4) and fed into
 * [com.silverbp.android.coach.CoachEngine] plan generation. Stored in
 * [UserSettings] as their [raw] strings; an empty raw means "unset" so
 * pre-existing users / fresh installs fall back to the engine defaults.
 *
 * Labels are zh-TW because the onboarding selection chips render the enum
 * label directly (the surrounding step copy lives in strings_onboarding.xml).
 */

enum class PrimaryGoal(val raw: String, val label: String) {
    Strength("strength", "力量"),
    FatLoss("fat_loss", "減脂"),
    GeneralFitness("general_fitness", "體能"),
    Hypertrophy("hypertrophy", "增肌");

    companion object {
        fun fromRaw(raw: String): PrimaryGoal? = entries.firstOrNull { it.raw == raw }
    }
}

enum class ExperienceLevel(val raw: String, val label: String) {
    Beginner("beginner", "初學"),
    Intermediate("intermediate", "中級"),
    Advanced("advanced", "進階");

    companion object {
        fun fromRaw(raw: String): ExperienceLevel? = entries.firstOrNull { it.raw == raw }
    }
}

enum class TrainingStyle(val raw: String, val label: String) {
    FullBody("full_body", "全身"),
    CardioFocus("cardio_focus", "有氧為主"),
    StrengthFocus("strength_focus", "重訓為主"),
    BodyWeight("body_weight", "自體重量");

    companion object {
        fun fromRaw(raw: String): TrainingStyle? = entries.firstOrNull { it.raw == raw }
    }
}

/** Weekly availability is captured as a plain Int (exercise days/week). */
object WeeklyAvailability {
    val OPTIONS: List<Int> = (2..6).toList()
}
