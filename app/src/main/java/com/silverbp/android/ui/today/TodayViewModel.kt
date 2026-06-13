package com.silverbp.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelLoadStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class TodayUiState(
    val latest: BpReading? = null,
    val totalCount: Int = 0,
    val modelPhase: ModelLoadPhase = ModelLoadPhase.Idle,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val modelStatus: ModelLoadStatus = ServiceLocator.modelLoadStatus,
) : ViewModel() {

    val state: StateFlow<TodayUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        modelStatus.phase,
    ) { all, phase ->
        TodayUiState(
            latest = all.firstOrNull(),
            totalCount = all.size,
            modelPhase = phase,
            isLoading = false,
        )
    }
        .catch { emit(TodayUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
