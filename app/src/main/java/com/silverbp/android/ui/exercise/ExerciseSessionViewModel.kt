package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseController
import com.silverbp.android.exercise.ExerciseSessionLiveStore
import com.silverbp.android.exercise.RunState
import com.silverbp.android.exercise.SessionLive
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the live session held by [ExerciseSessionLiveStore]; the
 * service writes, this VM reads. Stop hands the snapshot to the Summary
 * screen which decides save vs discard.
 */
class ExerciseSessionViewModel(
    private val controller: ExerciseController = ServiceLocator.exerciseController,
    liveStore: ExerciseSessionLiveStore = ServiceLocator.exerciseLiveStore,
) : ViewModel() {

    val state: StateFlow<SessionLive?> = liveStore.flow

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
