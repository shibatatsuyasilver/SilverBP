package com.silverbp.android.exercise

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Persists the in-flight [SessionLive] to a single file so an exercise session
 * survives the foreground service / app process being killed mid-run (low
 * memory, battery saver, force-stop). On the next launch the Exercise screen
 * can offer to resume it.
 *
 * Deliberately a standalone file — NOT a row in `exercise_session` — so an
 * incomplete session never leaks into history, achievements (`totalSessionSteps`),
 * or the coach's weekly aggregates. Best-effort: any I/O or parse failure is
 * swallowed (a lost checkpoint just means no recovery offer, never a crash).
 */
class SessionCheckpointStore(private val file: File) {

    fun save(live: SessionLive) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(live.toDto()))
        }.onFailure { Log.w(TAG, "checkpoint save failed", it) }
    }

    /** The persisted session, or null if none / unreadable / corrupt. */
    fun load(): SessionLive? = runCatching {
        if (!file.exists()) return null
        json.decodeFromString<SessionLiveDto>(file.readText()).toLive()
    }.onFailure { Log.w(TAG, "checkpoint load failed; ignoring", it) }.getOrNull()

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    private companion object {
        const val TAG = "SessionCheckpoint"
        val json = Json { ignoreUnknownKeys = true }
    }
}

// --- serializable mirror of SessionLive (UUID -> String, Instant -> epochMillis) ---

@Serializable
private data class RoutePointDto(
    val id: String,
    val sessionId: String,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val horizontalAccuracy: Float,
    val altitude: Double? = null,
    val speedMps: Float? = null,
)

@Serializable
private data class SessionLiveDto(
    val id: String,
    val kindRaw: String,
    val startedAtMs: Long,
    val runState: String,
    val routePoints: List<RoutePointDto>,
    val accumulatedDistanceMeters: Double,
    val activeDurationMillis: Long,
    val paceSecPerKm: Double? = null,
    val stepCount: Int? = null,
    val stepBaseline: Long? = null,
    val lastMovementAtMillis: Long,
    val lastSampleAtMillis: Long,
    val lastResumeAtMillis: Long,
    val pausedSinceMillis: Long? = null,
)

private fun SessionLive.toDto() = SessionLiveDto(
    id = id.toString(),
    kindRaw = kind.raw,
    startedAtMs = startedAt.toEpochMilli(),
    runState = runState.name,
    routePoints = routePoints.map {
        RoutePointDto(
            id = it.id.toString(),
            sessionId = it.sessionId.toString(),
            timestampMs = it.timestamp.toEpochMilli(),
            lat = it.lat,
            lon = it.lon,
            horizontalAccuracy = it.horizontalAccuracy,
            altitude = it.altitude,
            speedMps = it.speedMps,
        )
    },
    accumulatedDistanceMeters = accumulatedDistanceMeters,
    activeDurationMillis = activeDurationMillis,
    paceSecPerKm = paceSecPerKm,
    stepCount = stepCount,
    stepBaseline = stepBaseline,
    lastMovementAtMillis = lastMovementAtMillis,
    lastSampleAtMillis = lastSampleAtMillis,
    lastResumeAtMillis = lastResumeAtMillis,
    pausedSinceMillis = pausedSinceMillis,
)

private fun SessionLiveDto.toLive() = SessionLive(
    id = UUID.fromString(id),
    kind = ActivityKind.fromRaw(kindRaw),
    startedAt = Instant.ofEpochMilli(startedAtMs),
    runState = RunState.valueOf(runState),
    routePoints = routePoints.map {
        RoutePoint(
            id = UUID.fromString(it.id),
            sessionId = UUID.fromString(it.sessionId),
            timestamp = Instant.ofEpochMilli(it.timestampMs),
            lat = it.lat,
            lon = it.lon,
            horizontalAccuracy = it.horizontalAccuracy,
            altitude = it.altitude,
            speedMps = it.speedMps,
        )
    },
    accumulatedDistanceMeters = accumulatedDistanceMeters,
    activeDurationMillis = activeDurationMillis,
    paceSecPerKm = paceSecPerKm,
    stepCount = stepCount,
    stepBaseline = stepBaseline,
    lastMovementAtMillis = lastMovementAtMillis,
    lastSampleAtMillis = lastSampleAtMillis,
    lastResumeAtMillis = lastResumeAtMillis,
    pausedSinceMillis = pausedSinceMillis,
)
