package com.silverbp.android.coach

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Coach domain types. Persistence representations live in `core/db/CoachEntities.kt`;
 * mappers in `core/db/CoachMappers.kt`.
 *
 * Why @Serializable: [CoachPlan.goals] is stored as a JSON blob inside
 * [com.silverbp.android.core.db.CoachPlanEntity.goalsJson] so the goal schema
 * can evolve without forcing a Room migration each time we tune a target.
 */

enum class LifestyleModule(val raw: String) {
    Exercise("ex"),
    Diet("diet"),
    Sleep("sleep"),
    Medication("med");

    companion object {
        fun fromRaw(raw: String): LifestyleModule = entries.firstOrNull { it.raw == raw } ?: Exercise
    }
}

enum class TaskIntensity(val raw: String) {
    Rest("rest"),
    Light("light"),
    Moderate("moderate"),
    Vigorous("vigorous");

    companion object {
        fun fromRaw(raw: String): TaskIntensity = entries.firstOrNull { it.raw == raw } ?: Light
    }
}

/**
 * Phase the coaching engine is in for a given week. Drives volume modulation
 * across consecutive plans:
 *  - Baseline: first plan ever (cap weekly volume, keep intensity Light)
 *  - Ramp: bump volume +10–15%
 *  - Hold: same volume as last week
 *  - DeRamp: drop volume −30% after a low-adherence week
 */
enum class Phase(val raw: String) {
    Baseline("baseline"),
    Ramp("ramp"),
    Hold("hold"),
    DeRamp("deramp");

    companion object {
        fun fromRaw(raw: String): Phase = entries.firstOrNull { it.raw == raw } ?: Baseline
    }
}

enum class Severity(val raw: String) {
    Caution("caution"),
    Critical("critical");

    companion object {
        fun fromRaw(raw: String): Severity = entries.firstOrNull { it.raw == raw } ?: Caution
    }
}

/**
 * One per-day item in a [CoachPlan]. Adherence is *derived* in SQL by counting
 * tasks where [completedAtMillis] is non-null — never store an Adherence row.
 */
@Serializable
data class CoachTask(
    val id: String = UUID.randomUUID().toString(),
    val planId: String,
    val dayOffset: Int,                    // 0..6 from week start
    val module: LifestyleModule,
    val title: String,
    val targetValue: Double? = null,
    val targetUnit: String? = null,        // "min" | "mg" | "h" | "doses"
    val intensity: TaskIntensity = TaskIntensity.Light,
    val safetyHold: Boolean = false,
    val completedAtMillis: Long? = null,
)

@Serializable
data class CoachGoal(
    val module: LifestyleModule,
    val targetValue: Double,
    val targetUnit: String,
    /** key into a strings.xml table; engine never produces user-visible Chinese to keep persistence locale-agnostic */
    val rationaleKey: String,
)

/**
 * One plan covers a single week starting Monday 00:00 in the user's local zone.
 *
 * Persistence: [goals] is stored as JSON inside the plan row; tasks are
 * normalised in their own table for per-row completion toggling.
 */
@Serializable
data class CoachPlan(
    val id: String = UUID.randomUUID().toString(),
    val weekStartMillis: Long,
    val generatedAtMillis: Long,
    val ruleVersion: Int,
    val phase: Phase,
    val goals: List<CoachGoal>,
    val tasks: List<CoachTask>,
)

/**
 * Derived view used by UI module rings. Build from SQL aggregates — never persist.
 */
data class Adherence(
    val module: LifestyleModule,
    val completed: Int,
    val scheduled: Int,
) {
    val ratio: Float get() = if (scheduled == 0) 0f else (completed.toFloat() / scheduled).coerceIn(0f, 1f)
}

/**
 * Snapshot used to render the weekly report; the coach narrator turns this
 * into prose. Cached only as the rendered narrative string in DataStore —
 * the snapshot itself is recomputed on demand.
 */
@Serializable
data class WeeklyReport(
    val weekStartMillis: Long,
    val sbpMean: Double,
    /** delta vs. the prior 7-day window; positive = went up, negative = went down */
    val sbpDelta: Double,
    val aerobicMin: Int,
    val aerobicTarget: Int,
    val sleepMeanH: Double,
    val sodiumDaysOver: Int,
    val medAdherence: Float,
    val highlights: List<String>,
    val nextWeekFocus: List<String>,
)

/**
 * Events emitted by [CoachEngine.detectAnomaly]. Currently only the BP
 * anomaly path is wired; [CoachEvent] stays sealed so future cardio /
 * adherence anomalies plug in without breaking notifier callers.
 */
sealed interface CoachEvent {
    data class Anomaly(
        val severity: Severity,
        val latestSystolic: Int,
        val latestDiastolic: Int,
        val triggeredAtMillis: Long,
    ) : CoachEvent
}
