package com.silverbp.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.GlucoseUnit
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
 * One day's glucose readings for [GlucoseHistoryScreen], mirroring the BP
 * [DayGroup]. [meanMgdl] is the day's mean canonical value; the screen renders it
 * in the user's preferred [GlucoseUnit].
 */
data class GlucoseDayGroup(
    val date: LocalDate,
    val readings: List<GlucoseReading>,
    val meanMgdl: Double,
)

data class GlucoseHistoryUiState(
    val range: DateRange = DateRange.All,
    val sort: SortOrder = SortOrder.Newest,
    val grouped: List<GlucoseDayGroup> = emptyList(),
    /** User's preferred display unit (mg/dL default); rows render values in it. */
    val unit: GlucoseUnit = GlucoseUnit.Mgdl,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

/**
 * Member-scoped glucose history, the BP [HistoryViewModel] sibling. Follows the
 * selected member ([CurrentMemberStore]); supports the same date-range + sort
 * filter and the same delete-with-undo contract. Glucose has no per-member
 * guideline (the classifier keys on [com.silverbp.android.core.MeasureContext],
 * not a member threshold set), so the only member-derived input is the readings
 * stream itself; the user's [GlucoseUnit] preference drives the display unit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseHistoryViewModel(
    private val repo: GlucoseRepository = ServiceLocator.glucoseRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(DateRange.All)
    private val sortFlow = MutableStateFlow(SortOrder.Newest)

    private val unitFlow = settings.flow.map { GlucoseUnit.fromRaw(it.glucoseUnit) }

    val state: StateFlow<GlucoseHistoryUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        sortFlow,
        unitFlow,
    ) { all, range, sort, unit ->
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val filtered = all.filter { range.matchesGlucose(it, today, zone) }
        val sorted = if (sort == SortOrder.Newest) filtered else filtered.reversed()
        val groups = sorted
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
            .map { (date, readings) ->
                GlucoseDayGroup(date, readings, readings.map { it.valueMgdl }.average())
            }
            .let { if (sort == SortOrder.Oldest) it.sortedBy { g -> g.date } else it.sortedByDescending { g -> g.date } }
        GlucoseHistoryUiState(range = range, sort = sort, grouped = groups, unit = unit, isLoading = false)
    }
        .catch { emit(GlucoseHistoryUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseHistoryUiState())

    fun setRange(r: DateRange) { rangeFlow.value = r }
    fun setSort(s: SortOrder) { sortFlow.value = s }

    fun delete(id: UUID) {
        viewModelScope.launch { repo.delete(id) }
    }

    /** Re-insert a just-deleted reading (Snackbar undo). Same id → restores in place. */
    fun restore(reading: GlucoseReading) {
        viewModelScope.launch { repo.upsert(reading) }
    }
}

/**
 * [DateRange] applied to a glucose reading's timestamp (the BP variant takes a BpReading).
 * Package-internal so [UnifiedHistoryViewModel] can reuse the same range predicate.
 */
internal fun DateRange.matchesGlucose(reading: GlucoseReading, today: LocalDate, zone: ZoneId): Boolean {
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
