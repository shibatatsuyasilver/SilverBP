package com.silverbp.android.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GuidelineClassifier
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
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
    /** Reading counts bucketed by (part-of-day × category) for the daypart heatmap. */
    val daypartCategory: Map<Pair<PartOfDay, BpCategory>, Int> = emptyMap(),
    /** Active guideline — the scatter colours each point by its classified category. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
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
        // Bucket morning/evening by the reading's actual local time, not the
        // user-set partOfDay field — that field defaults to Morning and is often
        // left unchanged, so evening readings would otherwise all show as morning.
        val morningSys = filtered.filter { it.daypartByTime() == PartOfDay.Morning }.map { it.systolic.toDouble() }
        val eveningSys = filtered.filter { it.daypartByTime() == PartOfDay.Evening }.map { it.systolic.toDouble() }
        val classifier = GuidelineClassifier(user.guideline)
        val dist = filtered.groupBy { classifier.classify(it.systolic, it.diastolic) }
            .mapValues { it.value.size }
        val daypart = filtered
            .groupingBy { it.daypartByTime() to classifier.classify(it.systolic, it.diastolic) }
            .eachCount()

        InsightsUiState(
            range = range,
            readings = filtered.sortedBy { it.timestamp },
            meanSystolic = StatsEngine.mean(sys),
            meanDiastolic = StatsEngine.mean(dia),
            sdSystolic = StatsEngine.standardDeviation(sys),
            arvSystolic = StatsEngine.averageRealVariability(sys),
            morningSurge = StatsEngine.morningSurge(morningSys, eveningSys),
            distribution = dist,
            daypartCategory = daypart,
            guideline = user.guideline,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun setRange(r: InsightsRange) { rangeFlow.value = r }
}

/**
 * Morning vs evening derived from the reading's local time (Morning = 05:00–11:59;
 * everything else — afternoon, evening, late night — is Evening). Insights buckets
 * on this instead of the user-set [PartOfDay] field, which defaults to Morning and
 * is frequently left unchanged.
 */
private fun BpReading.daypartByTime(): PartOfDay =
    if (timestamp.atZone(ZoneId.systemDefault()).hour in 5 until 12) PartOfDay.Morning else PartOfDay.Evening
