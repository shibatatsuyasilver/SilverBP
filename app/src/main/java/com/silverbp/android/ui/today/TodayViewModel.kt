package com.silverbp.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

data class TodayUiState(
    val latest: BpReading? = null,
    val totalCount: Int = 0,
    val modelPhase: ModelLoadPhase = ModelLoadPhase.Idle,
    /** Selected member's guideline — classifies the latest-reading card colour/label. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val modelStatus: ModelLoadStatus = ServiceLocator.modelLoadStatus,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    // The selected member's own guideline (roadmap §3-1); falls back to the
    // owner's settings guideline if the member row can't be resolved.
    private val guidelineFlow = currentMember.flow.flatMapLatest { id ->
        settings.flow.map { user ->
            runCatching { members.findById(UUID.fromString(id)) }.getOrNull()?.guideline
                ?: user.guideline
        }
    }

    val state: StateFlow<TodayUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        modelStatus.phase,
        guidelineFlow,
    ) { all, phase, guideline ->
        TodayUiState(
            latest = all.firstOrNull(),
            totalCount = all.size,
            modelPhase = phase,
            guideline = guideline,
            isLoading = false,
        )
    }
        .catch { emit(TodayUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
