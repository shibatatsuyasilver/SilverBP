package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.ExerciseSessionLiveStore
import com.silverbp.android.exercise.RoutePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reads the finalized snapshot left in [ExerciseSessionLiveStore] by
 * [ExerciseController.stop], lets the user edit a note and Save (write to
 * Room + Health Connect best-effort) or Discard.
 */
class ExerciseSummaryViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val liveStore: ExerciseSessionLiveStore = ServiceLocator.exerciseLiveStore,
) : ViewModel() {

    val session: ExerciseSession?
    val points: List<RoutePoint>

    init {
        val live = liveStore.flow.value
        if (live != null) {
            session = ExerciseSession(
                id = live.id,
                kind = live.kind,
                startedAt = live.startedAt,
                endedAt = java.time.Instant.now(),
                distanceMeters = live.accumulatedDistanceMeters,
                stepCount = live.stepCount,
                averagePaceSecPerKm = live.paceSecPerKm,
                source = com.silverbp.android.exercise.ExerciseSource.Gps,
                note = "",
                hcRecordId = null,
            )
            points = live.routePoints
        } else {
            session = null
            points = emptyList()
        }
    }

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun save(note: String, onDone: () -> Unit) {
        val s = session ?: return onDone()
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            repo.upsert(s.copy(note = note), points)
            liveStore.clear()
            _saving.value = false
            onDone()
        }
    }

    fun discard(onDone: () -> Unit) {
        liveStore.clear()
        onDone()
    }
}
