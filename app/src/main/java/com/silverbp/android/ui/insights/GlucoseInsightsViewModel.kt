package com.silverbp.android.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.GlucoseCategory
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Glucose analytics state for [GlucoseInsightsScreen].
 *
 * Single-member only this phase (glucose compare mode is deferred — see the KDoc
 * on [GlucoseInsightsViewModel]). Reuses the BP [InsightsRange] enum for the
 * 7/30/90/All chips. [readings] is range-filtered + time-sorted for the trend
 * chart; [contextDistribution] backs the timing-distribution chart; the three
 * headline stats mirror the roadmap §4-5 summary (fasting mean, after-meal mean,
 * low-event count). All values are canonical mg/dL; the screen renders them in
 * [unit].
 */
data class GlucoseInsightsUiState(
    val range: InsightsRange = InsightsRange.Last30,
    val readings: List<GlucoseReading> = emptyList(),
    /** Mean of fasting + before-meal readings (mg/dL), or null when none in range. */
    val fastingMeanMgdl: Double? = null,
    /** Mean of after-meal readings (mg/dL), or null when none in range. */
    val afterMealMeanMgdl: Double? = null,
    /** Count of VeryLow/Low readings in range (the hypoglycaemia headline). */
    val lowEventCount: Int = 0,
    val contextDistribution: Map<MeasureContext, Int> = emptyMap(),
    val categoryDistribution: Map<GlucoseCategory, Int> = emptyMap(),
    val unit: GlucoseUnit = GlucoseUnit.Mgdl,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

/**
 * Member-scoped glucose insights, the BP [InsightsViewModel] sibling. Follows the
 * selected member ([CurrentMemberStore]) and the chosen [InsightsRange].
 *
 * **Compare mode is deferred for glucose this phase** (roadmap §4-3 marks compare
 * as optional-if-trivial): glucose classification keys on [MeasureContext], not a
 * per-member guideline, and the meaningful per-context split (fasting vs
 * post-meal) doesn't map cleanly onto the BP SBP/DBP overlay, so a faithful
 * multi-member overlay is non-trivial. Single-member is the shipped behaviour;
 * the member switcher still scopes the view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseInsightsViewModel(
    private val repo: GlucoseRepository = ServiceLocator.glucoseRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(InsightsRange.Last30)
    private val unitFlow = settings.flow.map { GlucoseUnit.fromRaw(it.glucoseUnit) }

    val state: StateFlow<GlucoseInsightsUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        unitFlow,
    ) { all, range, unit ->
        val summary = computeGlucoseInsights(all, range)
        GlucoseInsightsUiState(
            range = range,
            readings = summary.readings,
            fastingMeanMgdl = summary.fastingMeanMgdl,
            afterMealMeanMgdl = summary.afterMealMeanMgdl,
            lowEventCount = summary.lowEventCount,
            contextDistribution = summary.contextDistribution,
            categoryDistribution = summary.categoryDistribution,
            unit = unit,
            isLoading = false,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseInsightsUiState())

    fun setRange(r: InsightsRange) { rangeFlow.value = r }
}

/** Pure glucose analytics, mirroring [computeMemberInsights] for BP. No Android deps. */
data class GlucoseSummary(
    val readings: List<GlucoseReading> = emptyList(),
    val fastingMeanMgdl: Double? = null,
    val afterMealMeanMgdl: Double? = null,
    val lowEventCount: Int = 0,
    val contextDistribution: Map<MeasureContext, Int> = emptyMap(),
    val categoryDistribution: Map<GlucoseCategory, Int> = emptyMap(),
)

fun computeGlucoseInsights(all: List<GlucoseReading>, range: InsightsRange): GlucoseSummary {
    val cutoff = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
    val filtered = if (cutoff == null) all else all.filter { it.timestamp.isAfter(cutoff) }
    val classifier = GlucoseClassifier()

    val fasting = filtered.filter {
        it.measureContext == MeasureContext.Fasting || it.measureContext == MeasureContext.BeforeMeal
    }.map { it.valueMgdl }
    val afterMeal = filtered.filter { it.measureContext == MeasureContext.AfterMeal }.map { it.valueMgdl }
    val lowEvents = filtered.count {
        classifier.classify(it.valueMgdl, it.measureContext).isHypoglycemic
    }

    return GlucoseSummary(
        readings = filtered.sortedBy { it.timestamp },
        fastingMeanMgdl = fasting.average().takeIf { fasting.isNotEmpty() },
        afterMealMeanMgdl = afterMeal.average().takeIf { afterMeal.isNotEmpty() },
        lowEventCount = lowEvents,
        contextDistribution = filtered.groupingBy { it.measureContext }.eachCount(),
        categoryDistribution = filtered
            .groupingBy { classifier.classify(it.valueMgdl, it.measureContext) }
            .eachCount(),
    )
}
