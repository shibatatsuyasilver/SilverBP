package com.silverbp.android.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.coach.CoachEngine
import com.silverbp.android.coach.CoachNarrator
import com.silverbp.android.coach.WeeklyReport
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WeeklyReportUiState(
    val report: WeeklyReport? = null,
    val narration: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
)

/**
 * Computes the weekly snapshot once on first observation, then streams the
 * narrator's deltas into [WeeklyReportUiState.narration]. Re-runnable via
 * [regenerate] for users who want a different phrasing.
 */
class CoachWeeklyReportViewModel(
    private val engine: CoachEngine = ServiceLocator.coachEngine,
    private val narrator: CoachNarrator = ServiceLocator.coachNarrator,
) : ViewModel() {

    private val _state = MutableStateFlow(WeeklyReportUiState(streaming = true))
    val state: StateFlow<WeeklyReportUiState> = _state.asStateFlow()

    init {
        regenerate()
    }

    fun regenerate() {
        viewModelScope.launch {
            _state.value = WeeklyReportUiState(streaming = true)
            runCatching {
                val report = engine.computeWeeklyReport()
                _state.value = _state.value.copy(report = report)
                val sb = StringBuilder()
                narrator.narrateWeeklyReport(report).collect { delta ->
                    sb.append(delta)
                    _state.value = _state.value.copy(narration = sb.toString())
                }
                _state.value = _state.value.copy(streaming = false)
            }.onFailure { t ->
                _state.value = _state.value.copy(streaming = false, error = t.message ?: t::class.simpleName)
            }
        }
    }
}
