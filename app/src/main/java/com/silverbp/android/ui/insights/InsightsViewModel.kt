package com.silverbp.android.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GuidelineClassifier
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class InsightsRange(val days: Long?) {
    Last7(7), Last30(30), Last90(90), All(null);
}

data class InsightsUiState(
    val range: InsightsRange = InsightsRange.Last30,
    val readings: List<BpReading> = emptyList(),
    val meanSystolic: Double = 0.0,
    val meanDiastolic: Double = 0.0,
    val sdSystolic: Double = 0.0,
    val arvSystolic: Double = 0.0,
    val morningSurge: Double? = null,
    val distribution: Map<BpCategory, Int> = emptyMap(),
)

class InsightsViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(InsightsRange.Last30)

    val state: StateFlow<InsightsUiState> = combine(
        repo.observeAll(),
        rangeFlow,
        settings.flow,
    ) { all, range, user ->
        val cutoff = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
        val filtered = if (cutoff == null) all else all.filter { it.timestamp.isAfter(cutoff) }
        val sys = filtered.map { it.systolic.toDouble() }
        val dia = filtered.map { it.diastolic.toDouble() }
        val morningSys = filtered.filter { it.partOfDay.raw == "morning" }.map { it.systolic.toDouble() }
        val eveningSys = filtered.filter { it.partOfDay.raw == "evening" }.map { it.systolic.toDouble() }
        val classifier = GuidelineClassifier(user.guideline)
        val dist = filtered.groupBy { classifier.classify(it.systolic, it.diastolic) }
            .mapValues { it.value.size }

        InsightsUiState(
            range = range,
            readings = filtered.sortedBy { it.timestamp },
            meanSystolic = StatsEngine.mean(sys),
            meanDiastolic = StatsEngine.mean(dia),
            sdSystolic = StatsEngine.standardDeviation(sys),
            arvSystolic = StatsEngine.averageRealVariability(sys),
            morningSurge = StatsEngine.morningSurge(morningSys, eveningSys),
            distribution = dist,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun setRange(r: InsightsRange) { rangeFlow.value = r }
}
