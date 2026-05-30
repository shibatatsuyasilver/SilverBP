package com.silverbp.android.exercise

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.time.Instant

/**
 * Thin facade over [LocationTrackingService] + [ExerciseSessionLiveStore] that
 * the UI uses to drive a session. Doesn't hold state itself — the LiveStore is
 * the single source of truth.
 */
class ExerciseController(
    private val context: Context,
    private val liveStore: ExerciseSessionLiveStore,
) {
    fun start(kind: ActivityKind) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_KIND, kind.raw)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun pause() = sendAction(LocationTrackingService.ACTION_PAUSE)
    fun resume() = sendAction(LocationTrackingService.ACTION_RESUME)

    /**
     * Stops the underlying service and returns a snapshot of the session at
     * the moment of stop. The snapshot lives in the LiveStore until the
     * Summary screen calls [discard] or persists via the repository (which
     * also clears the LiveStore).
     */
    fun stop(): Pair<ExerciseSession, List<RoutePoint>>? {
        val snapshot = liveStore.snapshotAndFinish(Instant.now())
        sendAction(LocationTrackingService.ACTION_STOP)
        return snapshot
    }

    fun discard() {
        sendAction(LocationTrackingService.ACTION_STOP)
        liveStore.clear()
    }

    /** A persisted session orphaned by a process kill, or null. */
    fun recoverableCheckpoint(): SessionLive? = liveStore.recoverableCheckpoint()

    /**
     * Resume an orphaned [live] session (already loaded off the main thread):
     * re-seat it into the LiveStore (Paused) and re-attach the foreground
     * service. The session screen then shows it; the user taps Resume to keep
     * going.
     */
    fun restore(live: SessionLive) {
        liveStore.restore(live)
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_RESTORE
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Drop an orphaned checkpoint the user chose not to resume. */
    fun discardCheckpoint() = liveStore.clear()

    private fun sendAction(actionName: String) {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = actionName
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
