package com.silverbp.android.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.billing.EntitlementManager
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
    // Premium gate (Phase 3): false → AI narration was skipped (free tier); the
    // screen shows the computed [report] numbers plus an upsell instead of prose.
    // With PREMIUM_ENFORCED=false isPremium() is always true, so narration runs as
    // before and this stays true (zero behaviour change).
    val narrationPremium: Boolean = true,
)

/**
 * Computes the weekly snapshot once on first observation, then streams the
 * narrator's deltas into [WeeklyReportUiState.narration]. Re-runnable via
 * [regenerate] for users who want a different phrasing.
 */
class CoachWeeklyReportViewModel(
    private val engine: CoachEngine = ServiceLocator.coachEngine,
    private val narrator: CoachNarrator = ServiceLocator.coachNarrator,
    private val entitlements: EntitlementManager = ServiceLocator.entitlementManager,
) : ViewModel() {

    private val _state = MutableStateFlow(WeeklyReportUiState(streaming = true))
    val state: StateFlow<WeeklyReportUiState> = _state.asStateFlow()

    init {
        regenerate()
    }

    fun regenerate() {
        viewModelScope.launch {
            // Resolve the gate once per run. With PREMIUM_ENFORCED=false this is
            // always true → full narration as before.
            val premium = entitlements.isPremium()
            _state.value = WeeklyReportUiState(streaming = premium, narrationPremium = premium)
            runCatching {
                // The computed numbers are FREE — only the AI prose is gated. Always
                // surface the report so a free user still sees their weekly snapshot.
                val report = engine.computeWeeklyReport()
                _state.value = _state.value.copy(report = report)
                if (!premium) {
                    // Free tier: skip advanced narration entirely; the screen shows
                    // the upsell (narrationPremium=false) beside the numbers.
                    _state.value = _state.value.copy(streaming = false)
                    return@runCatching
                }
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
