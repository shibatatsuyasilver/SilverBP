package com.silverbp.android.settings

import androidx.annotation.StringRes
import com.silverbp.android.R

/**
 * Goal-profile enums captured during onboarding (Phase 4) and fed into
 * [com.silverbp.android.coach.CoachEngine] plan generation. Stored in
 * [UserSettings] as their [raw] strings; an empty raw means "unset" so
 * pre-existing users / fresh installs fall back to the engine defaults.
 *
 * [labelRes] is the user-facing chip label; the surrounding onboarding step
 * copy lives in strings_onboarding.xml.
 */

enum class PrimaryGoal(val raw: String, @StringRes val labelRes: Int) {
    Strength("strength", R.string.goal_primary_strength),
    FatLoss("fat_loss", R.string.goal_primary_fat_loss),
    GeneralFitness("general_fitness", R.string.goal_primary_general_fitness),
    Hypertrophy("hypertrophy", R.string.goal_primary_hypertrophy);

    companion object {
        fun fromRaw(raw: String): PrimaryGoal? = entries.firstOrNull { it.raw == raw }
    }
}

enum class ExperienceLevel(val raw: String, @StringRes val labelRes: Int) {
    Beginner("beginner", R.string.goal_experience_beginner),
    Intermediate("intermediate", R.string.goal_experience_intermediate),
    Advanced("advanced", R.string.goal_experience_advanced);

    companion object {
        fun fromRaw(raw: String): ExperienceLevel? = entries.firstOrNull { it.raw == raw }
    }
}

enum class TrainingStyle(val raw: String, @StringRes val labelRes: Int) {
    FullBody("full_body", R.string.goal_style_full_body),
    CardioFocus("cardio_focus", R.string.goal_style_cardio_focus),
    StrengthFocus("strength_focus", R.string.goal_style_strength_focus),
    BodyWeight("body_weight", R.string.goal_style_body_weight);

    companion object {
        fun fromRaw(raw: String): TrainingStyle? = entries.firstOrNull { it.raw == raw }
    }
}

/** Weekly availability is captured as a plain Int (exercise days/week). */
object WeeklyAvailability {
    val OPTIONS: List<Int> = (2..6).toList()
}
