package com.silverbp.android.ui.strength

import androidx.lifecycle.ViewModel
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.strength.SetLog
import com.silverbp.android.strength.StrengthWorkoutLive
import com.silverbp.android.strength.StrengthWorkoutLiveStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Read-only view of the live strength workout held by
 * [StrengthWorkoutLiveStore]; mutations are delegated straight to the store.
 * Finishing leaves the snapshot in the store (Finished) for the summary screen.
 */
class WorkoutSessionViewModel(
    private val liveStore: StrengthWorkoutLiveStore = ServiceLocator.strengthWorkoutLiveStore,
) : ViewModel() {

    val state: StateFlow<StrengthWorkoutLive?> = liveStore.flow

    fun setCurrentIndex(index: Int) = liveStore.setCurrentIndex(index)

    /** Add a new logged set to [exerciseId] with the next set number. */
    fun addSet(exerciseId: String, reps: Int, weightKg: Double?) {
        val sets = state.value?.exercises
            ?.firstOrNull { it.exercise.id == exerciseId }?.sets ?: return
        val setNumber = sets.size + 1
        liveStore.logSet(
            exerciseId,
            SetLog(
                id = UUID.randomUUID().toString(),
                exerciseId = exerciseId,
                setNumber = setNumber,
                reps = reps,
                weightKg = weightKg,
            ),
        )
    }

    fun markSetComplete(exerciseId: String, setNumber: Int, completed: Boolean) =
        liveStore.markSetComplete(exerciseId, setNumber, completed)

    fun skipExercise(exerciseId: String) = liveStore.skipExercise(exerciseId)

    fun addNote(note: String) = liveStore.addNote(note)

    /** Mark the workout finished; the snapshot stays in the store for summary. */
    fun finish() {
        liveStore.snapshotAndFinish()
    }
}
