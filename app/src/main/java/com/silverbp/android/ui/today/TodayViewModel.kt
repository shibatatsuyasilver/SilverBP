package com.silverbp.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelLoadStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TodayUiState(
    val latest: BpReading? = null,
    val totalCount: Int = 0,
    val modelPhase: ModelLoadPhase = ModelLoadPhase.Idle,
)

class TodayViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val modelStatus: ModelLoadStatus = ServiceLocator.modelLoadStatus,
) : ViewModel() {

    val state: StateFlow<TodayUiState> = combine(
        repo.observeAll(),
        modelStatus.phase,
    ) { all, phase ->
        TodayUiState(
            latest = all.firstOrNull(),
            totalCount = all.size,
            modelPhase = phase,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
