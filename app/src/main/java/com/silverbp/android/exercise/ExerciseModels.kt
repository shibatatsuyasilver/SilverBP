package com.silverbp.android.exercise

import java.time.Instant
import java.util.UUID

/** Match iOS BPExercise.ActivityKind. */
enum class ActivityKind(val raw: String) {
    Walking("walking"),
    Running("running");

    companion object {
        fun fromRaw(s: String): ActivityKind = entries.first { it.raw == s }
    }
}

/** Match iOS BPExercise.Source — provenance of the recorded session. */
enum class ExerciseSource(val raw: String) {
    Gps("gps"),
    Pedometer("pedometer"),
    Manual("manual");

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
