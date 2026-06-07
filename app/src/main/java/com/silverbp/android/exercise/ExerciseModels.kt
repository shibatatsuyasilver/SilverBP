package com.silverbp.android.exercise

import java.time.Instant
import java.util.UUID

/**
 * Walking/Running/BriskWalking/Cycling match iOS BPExercise.ActivityKind and are
 * the GPS/pedometer-trackable outdoor kinds. Treadmill…StairClimber are indoor
 * gym-machine kinds logged from a console-display photo (OCR) — they have no GPS
 * route and are Android-only for now.
 */
enum class ActivityKind(val raw: String) {
    Walking("walking"),
    Running("running"),
    BriskWalking("brisk_walking"),
    Cycling("cycling"),
    Treadmill("treadmill"),
    IndoorBike("indoor_bike"),
    Elliptical("elliptical"),
    Rower("rower"),
    StairClimber("stair_climber");

    /**
     * True for the outdoor kinds that the live GPS/pedometer tracker can record.
     * Machine kinds are OCR-only, so the live "start cardio" picker filters on this.
     */
    val isGpsTrackable: Boolean
        get() = this == Walking || this == Running || this == BriskWalking || this == Cycling

    companion object {
        fun fromRaw(s: String): ActivityKind = entries.first { it.raw == s }

        /** Indoor machine kinds captured from a console-display photo (OCR). */
        val machineKinds: List<ActivityKind> = listOf(Treadmill, IndoorBike, Elliptical, Rower, StairClimber)
    }
}

/** Match iOS BPExercise.Source — provenance of the recorded session. */
enum class ExerciseSource(val raw: String) {
    Gps("gps"),
    Pedometer("pedometer"),
    Manual("manual"),

    /** Captured by OCR'ing a gym-machine console display. */
    Ocr("ocr");

    companion object {
        fun fromRaw(s: String): ExerciseSource = entries.first { it.raw == s }
    }
}

/**
 * Live state machine. Idle → Running ↔ Paused / AutoPaused → Finished → Idle.
 *
 * - Paused: user tapped Pause. Samples are dropped; only manual Resume exits.
 * - AutoPaused: 8 s of speed < 0.3 m/s while Running. Samples are still
 *   processed; the next moving sample auto-resumes back to Running.
 */
enum class RunState { Idle, Running, Paused, AutoPaused, Finished }

/**
 * Persisted exercise session. Mirrors iOS BPExercise.ExerciseSession 1:1
 * except [hcRecordId] (Android Health Connect) replaces hkWorkoutUUID.
 *
 * [activeDurationMillis] is the running-state-only accumulation captured by
 * [ExerciseSessionLiveStore.SessionLive.activeDurationMillis]. It excludes
 * time spent in Paused / AutoPaused, so summary UI and aggregations reflect
 * actual exercise time rather than the inflated wall-clock window
 * `endedAt − startedAt`.
 */
data class ExerciseSession(
    val id: UUID = UUID.randomUUID(),
    val kind: ActivityKind,
    val startedAt: Instant,
    val endedAt: Instant,
    val activeDurationMillis: Long,
    val distanceMeters: Double,
    val stepCount: Int? = null,
    val averagePaceSecPerKm: Double? = null,
    val source: ExerciseSource = ExerciseSource.Gps,
    val note: String = "",
    val hcRecordId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    // --- Gym-machine OCR fields (null/estimate for GPS & pedometer sessions) ---
    /** Calories shown on the console. An estimate (see [caloriesIsEstimate]). */
    val caloriesKcal: Double? = null,
    /** Heart rate shown on the console; often absent (grip/strap only). */
    val heartRateBpm: Int? = null,
    /** Machine calories are systematically high — flag them as estimates, never truth. */
    val caloriesIsEstimate: Boolean = true,
    /** Contact/grip HR is unreliable; flag any captured value as an estimate. */
    val heartRateIsEstimate: Boolean = true,
    /** Unit shown for [distanceMeters]/[floors]: "m"|"km"|"mi"|"floors"|"steps". */
    val distanceUnitRaw: String? = null,
    /** Floors/flights climbed on stair machines that show floors instead of distance. */
    val floors: Int? = null,
    /** Raw OCR'd console metrics (label/value/unit/confidence) as JSON, for transparency. */
    val rawMetricsJson: String? = null,
)

/** Single GPS sample tied to a session by FK. */
data class RoutePoint(
    val id: UUID = UUID.randomUUID(),
    val sessionId: UUID,
    val timestamp: Instant,
    val lat: Double,
    val lon: Double,
    val horizontalAccuracy: Float,
    val altitude: Double? = null,
    val speedMps: Float? = null,
)
