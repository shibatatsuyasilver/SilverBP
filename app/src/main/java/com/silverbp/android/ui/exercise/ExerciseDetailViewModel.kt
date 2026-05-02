package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.RoutePoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class ExerciseDetailUiState(
    val session: ExerciseSession? = null,
    val points: List<RoutePoint> = emptyList(),
)

class ExerciseDetailViewModel(
    sessionId: String,
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
) : ViewModel() {

    private val id: UUID = UUID.fromString(sessionId)

    val state: StateFlow<ExerciseDetailUiState> =
        repo.observeWithRoute(id)
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { pair ->
                        emit(
                            ExerciseDetailUiState(
                                session = pair?.first,
                                points = pair?.second ?: emptyList(),
                            )
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseDetailUiState())

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repo.delete(id)
            onDeleted()
        }
    }
}
