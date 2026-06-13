package com.silverbp.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One day's weight readings for the weight history screen, mirroring the BP
 * [DayGroup] and the glucose [GlucoseDayGroup]. [meanKg] is the day's mean
 * canonical value; the screen renders it in the user's preferred [WeightUnit].
 */
data class WeightDayGroup(
    val date: LocalDate,
    val readings: List<WeightReading>,
    val meanKg: Double,
)

data class WeightHistoryUiState(
    val range: DateRange = DateRange.All,
    val sort: SortOrder = SortOrder.Newest,
    val grouped: List<WeightDayGroup> = emptyList(),
    /** User's preferred display unit (kg default); rows render values in it. */
    val unit: WeightUnit = WeightUnit.Kg,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

/**
 * Member-scoped weight history, the BP [HistoryViewModel] / glucose
 * [GlucoseHistoryViewModel] sibling. Follows the selected member
 * ([CurrentMemberStore]); supports the same date-range + sort filter and the
 * same delete-with-undo contract. Weight's BMI/category is a per-reading concern
 * keyed on the member's height (handled on the confirm screen), not a list-level
 * member threshold, so the only member-derived input here is the readings stream
 * itself; the user's [WeightUnit] preference drives the display unit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeightHistoryViewModel(
    private val repo: WeightRepository = ServiceLocator.weightRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(DateRange.All)
    private val sortFlow = MutableStateFlow(SortOrder.Newest)

    private val unitFlow = settings.flow.map { WeightUnit.fromRaw(it.weightUnit) }

    val state: StateFlow<WeightHistoryUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        sortFlow,
        unitFlow,
    ) { all, range, sort, unit ->
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val filtered = all.filter { range.matchesWeight(it, today, zone) }
        val sorted = if (sort == SortOrder.Newest) filtered else filtered.reversed()
        val groups = sorted
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
            .map { (date, readings) ->
                WeightDayGroup(date, readings, readings.map { it.valueKg }.average())
            }
            .let { if (sort == SortOrder.Oldest) it.sortedBy { g -> g.date } else it.sortedByDescending { g -> g.date } }
        WeightHistoryUiState(range = range, sort = sort, grouped = groups, unit = unit, isLoading = false)
    }
        .catch { emit(WeightHistoryUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightHistoryUiState())

    fun setRange(r: DateRange) { rangeFlow.value = r }
    fun setSort(s: SortOrder) { sortFlow.value = s }

    fun delete(id: UUID) {
        viewModelScope.launch { repo.delete(id) }
    }

    /** Re-insert a just-deleted reading (Snackbar undo). Same id → restores in place. */
    fun restore(reading: WeightReading) {
        viewModelScope.launch { repo.upsert(reading) }
    }
}

/**
 * [DateRange] applied to a weight reading's timestamp (the BP variant takes a
 * BpReading, the glucose variant a GlucoseReading). Package-internal to match
 * its [matchesGlucose] sibling.
 */
internal fun DateRange.matchesWeight(reading: WeightReading, today: LocalDate, zone: ZoneId): Boolean {
    if (this == DateRange.All) return true
    val d = reading.timestamp.atZone(zone).toLocalDate()
    return when (this) {
        DateRange.All -> true
        DateRange.Today -> d == today
        DateRange.ThisWeek -> {
            val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
            !d.isBefore(start) && !d.isAfter(today)
        }
        DateRange.ThisMonth -> d.year == today.year && d.month == today.month
        DateRange.Last30 -> !d.isBefore(today.minusDays(30)) && !d.isAfter(today)
        DateRange.Last90 -> !d.isBefore(today.minusDays(90)) && !d.isAfter(today)
    }
}
