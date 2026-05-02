package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ExerciseHomeUiState(
    val selectedKind: ActivityKind = ActivityKind.Walking,
    val recent: List<ExerciseSession> = emptyList(),
)

class ExerciseHomeViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
) : ViewModel() {

    private val kindFlow = MutableStateFlow(ActivityKind.Walking)

    val state: StateFlow<ExerciseHomeUiState> = combine(
        repo.observeAll(),
        kindFlow,
    ) { all, kind ->
        ExerciseHomeUiState(selectedKind = kind, recent = all.take(3))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseHomeUiState())

    fun selectKind(kind: ActivityKind) { kindFlow.value = kind }
}
