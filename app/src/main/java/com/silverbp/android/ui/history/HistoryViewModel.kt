package com.silverbp.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
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

enum class DateRange {
    All, Today, ThisWeek, ThisMonth, Last30, Last90;

    fun matches(reading: BpReading, today: LocalDate, zone: ZoneId): Boolean {
        if (this == All) return true
        val readingDate = reading.timestamp.atZone(zone).toLocalDate()
        return when (this) {
            All -> true
            Today -> readingDate == today
            ThisWeek -> {
                val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
                !readingDate.isBefore(start) && !readingDate.isAfter(today)
            }
            ThisMonth -> readingDate.year == today.year && readingDate.month == today.month
            Last30 -> !readingDate.isBefore(today.minusDays(30)) && !readingDate.isAfter(today)
            Last90 -> !readingDate.isBefore(today.minusDays(90)) && !readingDate.isAfter(today)
        }
    }
}

enum class SortOrder { Newest, Oldest }

data class HistoryUiState(
    val range: DateRange = DateRange.All,
    val sort: SortOrder = SortOrder.Newest,
    val grouped: List<DayGroup> = emptyList(),
    /** Selected member's guideline — classifies each reading row's category colour. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

data class DayGroup(
    val date: LocalDate,
    val readings: List<BpReading>,
    val meanSystolic: Int,
    val meanDiastolic: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(DateRange.All)
    private val sortFlow = MutableStateFlow(SortOrder.Newest)

    // The selected member's own guideline (roadmap §3-1); falls back to the
    // owner's settings guideline if the member row can't be resolved.
    private val guidelineFlow = currentMember.flow.flatMapLatest { id ->
        settings.flow.map { user ->
            runCatching { members.findById(UUID.fromString(id)) }.getOrNull()?.guideline
                ?: user.guideline
        }
    }

    val state: StateFlow<HistoryUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        sortFlow,
        guidelineFlow,
    ) { all, range, sort, guideline ->
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val filtered = all.filter { range.matches(it, today, zone) }
        val sorted = if (sort == SortOrder.Newest) filtered else filtered.reversed()
        val groups = sorted
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
            .map { (date, readings) ->
                val avgSys = readings.map { it.systolic }.average().toInt()
                val avgDia = readings.map { it.diastolic }.average().toInt()
                DayGroup(date, readings, avgSys, avgDia)
            }
            .sortedByDescending { if (sort == SortOrder.Newest) it.date else LocalDate.MIN }
            .let { if (sort == SortOrder.Oldest) it.sortedBy { g -> g.date } else it }
        HistoryUiState(range = range, sort = sort, grouped = groups, guideline = guideline, isLoading = false)
    }
        .catch { emit(HistoryUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setRange(r: DateRange) { rangeFlow.value = r }
    fun setSort(s: SortOrder) { sortFlow.value = s }

    fun delete(id: UUID) {
        viewModelScope.launch { repo.delete(id) }
    }

    /** Re-insert a just-deleted reading (Snackbar undo). Same id → restores in place. */
    fun restore(reading: BpReading) {
        viewModelScope.launch { repo.upsert(reading) }
    }
}
