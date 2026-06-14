package com.silverbp.android.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BmiCalculator
import com.silverbp.android.core.BmiCategory
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Body-weight analytics state for [WeightInsightsScreen], the
 * [GlucoseInsightsViewModel] sibling.
 *
 * Single-member only this phase (mirrors glucose). Reuses the BP [InsightsRange]
 * enum for the 7/30/90/All chips. [readings] is range-filtered + time-sorted for
 * the trend chart. The three headline stat cards (owner screenshot): [latestKg]
 * (most recent weight in range), [changeKg] (latest − earliest in range, signed),
 * and the BMI derived from the latest weight + the member's [heightCm] ([bmi] /
 * [bmiCategory], null when height unset). All weights are canonical kg; the screen
 * renders them in [unit].
 */
data class WeightInsightsUiState(
    val range: InsightsRange = InsightsRange.Last30,
    val readings: List<WeightReading> = emptyList(),
    /** Most-recent weight in range (kg), or null when the range has no readings. */
    val latestKg: Double? = null,
    /** Latest − earliest weight in range (kg, signed), or null with fewer than 2. */
    val changeKg: Double? = null,
    /** BMI from the latest in-range weight + the member's height, or null when unset. */
    val bmi: Double? = null,
    /** Taiwan-standard category for [bmi], or null when height/weight unavailable. */
    val bmiCategory: BmiCategory? = null,
    /** Selected member's height (cm), null when unset — drives the BMI card / hint. */
    val heightCm: Int? = null,
    val unit: WeightUnit = WeightUnit.Kg,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

/**
 * Member-scoped weight insights, the [GlucoseInsightsViewModel] sibling. Follows
 * the selected member ([CurrentMemberStore]), the chosen [InsightsRange], the
 * user's weight unit, and the member's height (re-resolved on member switch — for
 * BMI). Single-member only this phase (compare mode is deferred, like glucose).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeightInsightsViewModel(
    private val repo: WeightRepository = ServiceLocator.weightRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(InsightsRange.Last30)
    private val unitFlow = settings.flow.map { WeightUnit.fromRaw(it.weightUnit) }

    // Selected member's height (cm) for the BMI stat card; null when unset.
    // Re-resolves on member switch (mirrors TodayViewModel.heightFlow).
    private val heightFlow = currentMember.flow.flatMapLatest { id ->
        flow {
            emit(runCatching { members.findById(UUID.fromString(id)) }.getOrNull()?.heightCm)
        }
    }

    val state: StateFlow<WeightInsightsUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        unitFlow,
        heightFlow,
    ) { all, range, unit, heightCm ->
        val summary = computeWeightInsights(all, range, heightCm)
        WeightInsightsUiState(
            range = range,
            readings = summary.readings,
            latestKg = summary.latestKg,
            changeKg = summary.changeKg,
            bmi = summary.bmi,
            bmiCategory = summary.bmiCategory,
            heightCm = heightCm,
            unit = unit,
            isLoading = false,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightInsightsUiState())

    fun setRange(r: InsightsRange) { rangeFlow.value = r }
}

/** Pure weight analytics, mirroring [computeGlucoseInsights]. No Android deps. */
data class WeightSummary(
    val readings: List<WeightReading> = emptyList(),
    val latestKg: Double? = null,
    val changeKg: Double? = null,
    val bmi: Double? = null,
    val bmiCategory: BmiCategory? = null,
)

/**
 * Range-filter + time-sort the readings, then derive the three headline stats.
 * [changeKg] is latest minus earliest in range (positive = gained); BMI is the
 * latest in-range weight against [heightCm] (null when height unset or no reading).
 */
fun computeWeightInsights(
    all: List<WeightReading>,
    range: InsightsRange,
    heightCm: Int?,
): WeightSummary {
    val cutoff = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
    val filtered = (if (cutoff == null) all else all.filter { it.timestamp.isAfter(cutoff) })
        .sortedBy { it.timestamp }
    if (filtered.isEmpty()) return WeightSummary()

    val latest = filtered.last().weightKg
    val earliest = filtered.first().weightKg
    val change = if (filtered.size >= 2) latest - earliest else null
    val bmi = heightCm?.takeIf { it > 0 }?.let { BmiCalculator.bmi(latest, it) }
    val bmiCategory = bmi?.let { BmiCategory.classify(it) }

    return WeightSummary(
        readings = filtered,
        latestKg = latest,
        changeKg = change,
        bmi = bmi,
        bmiCategory = bmiCategory,
    )
}
