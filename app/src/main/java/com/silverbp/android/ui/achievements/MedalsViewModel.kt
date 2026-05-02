package com.silverbp.android.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.achievements.AchievementStats
import com.silverbp.android.achievements.MedalKind
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MedalsUiState(
    val unlocked: Map<MedalKind, Long> = emptyMap(),
    val stats: AchievementStats = AchievementStats(0, 0L, 0, 0, 8000),
    val hasHealthConnect: Boolean = false,
)

class MedalsViewModel : ViewModel() {

    private val achievementDao = ServiceLocator.database.achievementDao()
    private val store = ServiceLocator.achievementStore
    private val bridge = ServiceLocator.healthConnectExerciseBridge

    private val hcState = MutableStateFlow(false)

    val state: StateFlow<MedalsUiState> = combine(
        achievementDao.observeAll(),
        store.state,
        hcState.asStateFlow(),
    ) { rows, storeState, hasHc ->
        val unlocked = rows.mapNotNull { row ->
            MedalKind.fromRaw(row.kindRaw)?.let { it to row.unlockedAt }
        }.toMap()
        MedalsUiState(unlocked = unlocked, stats = storeState.stats, hasHealthConnect = hasHc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedalsUiState())

    init {
        viewModelScope.launch {
            hcState.value = bridge.hasReadStepsPermission()
            store.refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            hcState.value = bridge.hasReadStepsPermission()
            store.refresh()
        }
    }
}
