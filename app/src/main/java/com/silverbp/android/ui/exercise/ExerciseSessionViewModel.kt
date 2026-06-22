package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseController
import com.silverbp.android.exercise.ExerciseSessionLiveStore
import com.silverbp.android.exercise.LiveError
import com.silverbp.android.exercise.RunState
import com.silverbp.android.exercise.SessionLive
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only view of the live session held by [ExerciseSessionLiveStore]; the
 * service writes, this VM reads. Stop hands the snapshot to the Summary
 * screen which decides save vs discard.
 */
class ExerciseSessionViewModel(
    private val controller: ExerciseController = ServiceLocator.exerciseController,
    private val liveStore: ExerciseSessionLiveStore = ServiceLocator.exerciseLiveStore,
    private val userSettings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    val state: StateFlow<SessionLive?> = liveStore.flow

    /** Non-null when tracking aborted (e.g. location permission revoked). */
    val error: StateFlow<LiveError?> = liveStore.error

    fun clearError() = liveStore.setError(null)

    /**
     * Device-local toggle: when true the session map renders the GL-free
     * [RouteCanvasMap] instead of [GoogleLiveRouteMap]. The safety net for the
     * new-renderer black-screen bug — persisted so a user who flips it once stays
     * on the route view across sessions. See [UserSettings.liveMapUseCanvas].
     */
    val liveMapUseCanvas: StateFlow<Boolean> = userSettings.flow
        .map { it.liveMapUseCanvas }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setLiveMapUseCanvas(useCanvas: Boolean) {
        viewModelScope.launch { userSettings.setLiveMapUseCanvas(useCanvas) }
    }

    /**
     * Deep-linked here (e.g. notification tap) after a process kill emptied the
     * in-memory store: rehydrate the orphaned session from the on-disk checkpoint
     * and re-attach the foreground service before giving up. [onNothing] runs only
     * when there is genuinely nothing to restore (no checkpoint, or a Finished one
     * that belongs on the summary flow, not the live map). Reads the checkpoint
     * file off the main thread, mirroring [ExerciseHomeViewModel.checkRecoverable].
     */
    fun attemptRestoreFromCheckpoint(onNothing: () -> Unit) {
        viewModelScope.launch {
            val cp = withContext(Dispatchers.IO) { controller.recoverableCheckpoint() }
            if (cp != null && cp.runState != RunState.Finished) {
                // Seats the session (Paused) into the store and restarts GPS; the
                // map appears as soon as liveStore.flow emits. If location permission
                // was revoked meanwhile, the service surfaces LocationPermissionRevoked
                // and the error screen explains why (see LocationTrackingService).
                controller.restore(cp)
            } else {
                onNothing()
            }
        }
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()

    /** Trigger service stop and return the snapshot for the summary screen to consume. */
    fun stop(): Boolean {
        controller.stop() ?: return false
        return true
    }

    val isPaused: Boolean
        get() = state.value?.runState.let { it == RunState.Paused || it == RunState.AutoPaused }
}
