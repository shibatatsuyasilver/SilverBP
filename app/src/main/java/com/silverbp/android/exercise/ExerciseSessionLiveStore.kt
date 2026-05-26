package com.silverbp.android.exercise

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/**
 * Pure data view of a GPS fix; exists so [ExerciseSessionLiveStore.appendSample]
 * can be tested in JVM without an Android [Location] instance.
 */
internal data class GpsSample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speedMps: Float?,
    val altitude: Double?,
)

/**
 * Live snapshot of an in-progress session. Held by the singleton
 * [ExerciseSessionLiveStore]; consumed by [com.silverbp.android.ui.exercise]
 * ViewModels via [ExerciseSessionLiveStore.flow]. Never persisted.
 */
data class SessionLive(
    val id: UUID,
    val kind: ActivityKind,
    val startedAt: Instant,
    val runState: RunState,
    val routePoints: List<RoutePoint>,
    val accumulatedDistanceMeters: Double,
    val activeDurationMillis: Long,
    val paceSecPerKm: Double?,
    val stepCount: Int?,
    /** Raw step counter value at session start; used to compute per-session delta. */
    val stepBaseline: Long?,
    /** Wall-clock millis of the last GPS sample where speed >= 0.3 m/s. */
    val lastMovementAtMillis: Long,
    /** Wall-clock millis of the most recent appendPoint call (for duration accrual). */
    val lastSampleAtMillis: Long,
    val lastResumeAtMillis: Long,
    /**
     * Wall-clock millis when we last entered Paused / AutoPaused. Null while
     * Running or Finished. Used by [LocationTrackingService] to surface an
     * idle-reminder heads-up notification once continuous pause time crosses
     * [IDLE_REMINDER_THRESHOLD_MS]. Reset via [acknowledgeIdleReminder] when
     * the user dismisses that reminder with "Keep going".
     */
    val pausedSinceMillis: Long?,
)

/**
 * Process-wide singleton holding the live state of the active session.
 *
 * - Service writes via [start] / [appendPoint] / [pause] / [resume] / [snapshotAndFinish].
 * - UI reads via [flow]; do NOT scope to ViewModelStore — losing state when
 *   the user backs out of the session screen would visibly break tracking.
 *
 * GPS filters here mirror iOS BPExercise's LocationTracker:
 *   maxAccuracyMeters = 50f,  maxAgeMillis = 5_000L,  maxInstantSpeedMps = 30f.
 *
 * Auto-pause: 8 s of speed < 0.3 m/s while Running ⇒ AutoPaused. The next
 * moving sample auto-resumes back to Running. Manual Paused (user tap) is
 * sticky and only exits via [resume].
 */
class ExerciseSessionLiveStore {

    private val _flow = MutableStateFlow<SessionLive?>(null)
    val flow: StateFlow<SessionLive?> = _flow.asStateFlow()

    fun start(kind: ActivityKind, startedAt: Instant, stepBaseline: Long?) {
        val nowMs = startedAt.toEpochMilli()
        _flow.value = SessionLive(
            id = UUID.randomUUID(),
            kind = kind,
            startedAt = startedAt,
            runState = RunState.Running,
            routePoints = emptyList(),
            accumulatedDistanceMeters = 0.0,
            activeDurationMillis = 0L,
            paceSecPerKm = null,
            stepCount = null,
            stepBaseline = stepBaseline,
            lastMovementAtMillis = nowMs,
            lastSampleAtMillis = nowMs,
            lastResumeAtMillis = nowMs,
            pausedSinceMillis = null,
        )
    }

    /** Update the session's running step count from a raw cumulative sensor value. */
    fun updateRawStepCounter(rawCount: Long) {
        val cur = _flow.value ?: return
        val baseline = cur.stepBaseline ?: rawCount
        // Reboot-resilience: if the raw counter went backwards (device rebooted
        // mid-session), clamp delta to the new raw value.
        val delta = if (rawCount < baseline) rawCount.toInt() else (rawCount - baseline).toInt()
        _flow.value = cur.copy(
            stepCount = delta,
            stepBaseline = baseline,
        )
    }

    /**
     * Apply a fresh GPS sample. Drops samples that fail the accuracy / age /
     * speed filters; on first sample, just records the point with zero delta.
     */
    fun appendPoint(loc: Location) {
        appendSample(
            GpsSample(
                timeMs = loc.time,
                lat = loc.latitude,
                lon = loc.longitude,
                accuracy = loc.accuracy,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
            ),
            wallClockNowMs = System.currentTimeMillis(),
        )
    }

    /**
     * Pure-JVM-testable core of [appendPoint]. The Location-bound public entry
     * point only exists so this can be exercised without Robolectric.
     */
    internal fun appendSample(sample: GpsSample, wallClockNowMs: Long) {
        val cur = _flow.value ?: return
        // Manual pause / Idle / Finished: drop. AutoPaused still flows through so a
        // moving sample can flip us back to Running without losing this point.
        when (cur.runState) {
            RunState.Running, RunState.AutoPaused -> Unit
            else -> return
        }

        if (sample.accuracy > MAX_ACCURACY_METERS) return
        if ((wallClockNowMs - sample.timeMs) > MAX_AGE_MILLIS) return
        val speed = sample.speedMps
        if (speed != null && speed > MAX_INSTANT_SPEED_MPS) return

        val nowMs = sample.timeMs
        val newPoint = RoutePoint(
            sessionId = cur.id,
            timestamp = Instant.ofEpochMilli(nowMs),
            lat = sample.lat,
            lon = sample.lon,
            horizontalAccuracy = sample.accuracy,
            altitude = sample.altitude,
            speedMps = speed,
        )

        val prev = cur.routePoints.lastOrNull()
        val deltaMeters = if (prev == null) 0.0
        else ExerciseMath.haversineMeters(prev.lat, prev.lon, newPoint.lat, newPoint.lon)

        val moving = speed != null && speed >= MOVING_SPEED_MPS
        val nextLastMovement = if (moving) nowMs else cur.lastMovementAtMillis

        val nextRunState = when {
            cur.runState == RunState.AutoPaused && moving -> RunState.Running
            cur.runState == RunState.Running &&
                !moving &&
                (nowMs - cur.lastMovementAtMillis) >= AUTO_PAUSE_WINDOW_MS -> RunState.AutoPaused
            else -> cur.runState
        }

        // pausedSinceMillis tracks how long we've been continuously in
        // Paused/AutoPaused — feeds the 10 min idle reminder. Use the
        // wall-clock arg (not sample.timeMs) since the service's idle check
        // also reads System.currentTimeMillis(); the two clocks must match.
        val nextPausedSince = when {
            nextRunState == RunState.AutoPaused && cur.runState == RunState.Running -> wallClockNowMs
            nextRunState == RunState.Running && cur.runState == RunState.AutoPaused -> null
            else -> cur.pausedSinceMillis
        }

        // Active duration only accrues while we believe the user is moving — match
        // the prior behaviour (Running) and exclude time spent AutoPaused.
        val durationDeltaMs = if (cur.runState == RunState.Running) {
            (nowMs - cur.lastSampleAtMillis).coerceAtLeast(0L)
        } else 0L
        val newDuration = cur.activeDurationMillis + durationDeltaMs

        val newDistance = cur.accumulatedDistanceMeters + deltaMeters

        _flow.value = cur.copy(
            runState = nextRunState,
            routePoints = cur.routePoints + newPoint,
            accumulatedDistanceMeters = newDistance,
            activeDurationMillis = newDuration,
            paceSecPerKm = ExerciseMath.paceSecPerKm(newDuration, newDistance),
            lastMovementAtMillis = nextLastMovement,
            lastSampleAtMillis = nowMs,
            pausedSinceMillis = nextPausedSince,
        )
    }

    fun pause() {
        val cur = _flow.value ?: return
        // Manual pause overrides AutoPaused too — once the user taps Pause,
        // auto-resume must not silently undo that intent.
        if (cur.runState != RunState.Running && cur.runState != RunState.AutoPaused) return
        // Reset the idle-reminder countdown from this manual-pause moment so
        // a user transitioning AutoPaused → Paused doesn't immediately trip
        // the reminder using the older AutoPaused timestamp.
        _flow.value = cur.copy(
            runState = RunState.Paused,
            pausedSinceMillis = System.currentTimeMillis(),
        )
    }

    fun resume() {
        val cur = _flow.value ?: return
        if (cur.runState != RunState.Paused && cur.runState != RunState.AutoPaused) return
        val nowMs = System.currentTimeMillis()
        _flow.value = cur.copy(
            runState = RunState.Running,
            lastMovementAtMillis = nowMs,
            lastSampleAtMillis = nowMs,
            lastResumeAtMillis = nowMs,
            pausedSinceMillis = null,
        )
    }

    /**
     * Build the persisted [ExerciseSession] + its [RoutePoint] list and mark
     * the in-memory state as Finished. Caller is responsible for the actual DB
     * write; in-memory state is preserved so the Summary screen can still read
     * it. Call [clear] once persistence is done (or the user discards).
     */
    fun snapshotAndFinish(endedAt: Instant): Pair<ExerciseSession, List<RoutePoint>>? {
        val cur = _flow.value ?: return null
        val session = ExerciseSession(
            id = cur.id,
            kind = cur.kind,
            startedAt = cur.startedAt,
            endedAt = endedAt,
            activeDurationMillis = cur.activeDurationMillis,
            distanceMeters = cur.accumulatedDistanceMeters,
            stepCount = cur.stepCount,
            averagePaceSecPerKm = ExerciseMath.paceSecPerKm(
                cur.activeDurationMillis,
                cur.accumulatedDistanceMeters,
            ),
            source = ExerciseSource.Gps,
            note = "",
            hcRecordId = null,
        )
        val points = cur.routePoints
        _flow.value = cur.copy(runState = RunState.Finished, pausedSinceMillis = null)
        return session to points
    }

    /**
     * Called when the user taps "Keep going" on the idle-reminder notification.
     * Restarts the 10-minute idle countdown without changing runState — the
     * session stays Paused/AutoPaused but the next reminder won't fire for
     * another [IDLE_REMINDER_THRESHOLD_MS].
     */
    fun acknowledgeIdleReminder(nowMs: Long = System.currentTimeMillis()) {
        val cur = _flow.value ?: return
        if (cur.runState != RunState.Paused && cur.runState != RunState.AutoPaused) return
        _flow.value = cur.copy(pausedSinceMillis = nowMs)
    }

    fun clear() {
        _flow.value = null
    }

    companion object {
        const val MAX_ACCURACY_METERS = 50f
        const val MAX_AGE_MILLIS = 5_000L
        const val MAX_INSTANT_SPEED_MPS = 30f
        const val MOVING_SPEED_MPS = 0.3f
        const val AUTO_PAUSE_WINDOW_MS = 8_000L
        /**
         * Continuous Paused/AutoPaused duration after which the service
         * surfaces a heads-up reminder asking the user whether to keep going
         * or finish-and-save the session.
         */
        const val IDLE_REMINDER_THRESHOLD_MS = 10 * 60_000L
    }
}
