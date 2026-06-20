package com.silverbp.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.GlucoseUnit
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

/**
 * One day's combined record: both the BP and the glucose readings logged on
 * [date], the sibling of [DayGroup]/[GlucoseDayGroup] merged into a single
 * day group (owner decision §4 — a day's record covers BOTH types). Either list
 * may be empty (the other type still anchors the day); a [CombinedDayGroup] is
 * only emitted when at least one list is non-empty. [bpMean]/[glucoseMean] are
 * null when that type has no readings for the day so the screen can hide the
 * matching section summary.
 */
data class CombinedDayGroup(
    val date: LocalDate,
    val bpReadings: List<BpReading>,
    val glucoseReadings: List<GlucoseReading>,
    /** Day mean systolic/diastolic (rounded), or null when no BP that day. */
    val bpMean: Pair<Int, Int>?,
    /** Day mean canonical mg/dL, or null when no glucose that day. */
    val glucoseMean: Double?,
)

data class UnifiedHistoryUiState(
    val range: DateRange = DateRange.All,
    val sort: SortOrder = SortOrder.Newest,
    val grouped: List<CombinedDayGroup> = emptyList(),
    /** Selected member's guideline — classifies each BP row's category colour. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    /** User's preferred glucose display unit (mg/dL default); glucose rows render in it. */
    val glucoseUnit: GlucoseUnit = GlucoseUnit.Mgdl,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

/**
 * Member-scoped combined history (紀錄 segment of the Data hub). Merges the BP
 * stream ([BpHistoryViewModel]) and the glucose stream ([GlucoseHistoryViewModel])
 * into one [CombinedDayGroup] list grouped by [LocalDate] in the system zone,
 * reusing the per-type date-grouping idiom (HistoryViewModel + GlucoseHistoryViewModel).
 * Follows the selected member via [CurrentMemberStore] and keeps the same
 * date-range + sort filter and delete-with-undo contract as the per-type
 * histories (both types are deletable from the unified rows).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedHistoryViewModel(
    private val bpRepo: BpRepository = ServiceLocator.bpRepository,
    private val glucoseRepo: GlucoseRepository = ServiceLocator.glucoseRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val rangeStore: DataRangeFilterStore = ServiceLocator.dataRangeFilterStore,
) : ViewModel() {

    // Date range is shared with the 分析 segment via [DataRangeFilterStore] ("連動共用");
    // sort stays local to the 紀錄 list (charts have no sort concept).
    private val sortFlow = MutableStateFlow(SortOrder.Newest)

    // The selected member's own guideline (roadmap §3-1); falls back to the
    // owner's settings guideline if the member row can't be resolved. Mirrors
    // HistoryViewModel.guidelineFlow so BP row colours stay consistent.
    private val guidelineFlow = currentMember.flow.flatMapLatest { id ->
        settings.flow.map { user ->
            runCatching { members.findById(UUID.fromString(id)) }.getOrNull()?.guideline
                ?: user.guideline
        }
    }

    private val glucoseUnitFlow = settings.flow.map { GlucoseUnit.fromRaw(it.glucoseUnit) }

    val state: StateFlow<UnifiedHistoryUiState> = combine(
        combine(
            currentMember.flow.flatMapLatest { bpRepo.observeAll(it) },
            currentMember.flow.flatMapLatest { glucoseRepo.observeAll(it) },
        ) { bp, glucose -> bp to glucose },
        rangeStore.range,
        sortFlow,
        guidelineFlow,
        glucoseUnitFlow,
    ) { (allBp, allGlucose), range, sort, guideline, glucoseUnit ->
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        val bpByDate = allBp
            .filter { range.matches(it, today, zone) }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
        val glucoseByDate = allGlucose
            .filter { range.matchesGlucose(it, today, zone) }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }

        val groups = (bpByDate.keys + glucoseByDate.keys)
            .map { date ->
                // Newest-first within a day for both types when sorting newest.
                val bp = (bpByDate[date] ?: emptyList())
                    .let { if (sort == SortOrder.Oldest) it.sortedBy { r -> r.timestamp } else it.sortedByDescending { r -> r.timestamp } }
                val glucose = (glucoseByDate[date] ?: emptyList())
                    .let { if (sort == SortOrder.Oldest) it.sortedBy { r -> r.timestamp } else it.sortedByDescending { r -> r.timestamp } }
                CombinedDayGroup(
                    date = date,
                    bpReadings = bp,
                    glucoseReadings = glucose,
                    bpMean = bp.takeIf { it.isNotEmpty() }?.let {
                        it.map { r -> r.systolic }.average().toInt() to it.map { r -> r.diastolic }.average().toInt()
                    },
                    glucoseMean = glucose.takeIf { it.isNotEmpty() }?.map { it.valueMgdl }?.average(),
                )
            }
            .let { if (sort == SortOrder.Oldest) it.sortedBy { g -> g.date } else it.sortedByDescending { g -> g.date } }

        UnifiedHistoryUiState(
            range = range,
            sort = sort,
            grouped = groups,
            guideline = guideline,
            glucoseUnit = glucoseUnit,
            isLoading = false,
        )
    }
        .catch { emit(UnifiedHistoryUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnifiedHistoryUiState())

    fun setRange(r: DateRange) { rangeStore.set(r) }
    fun setSort(s: SortOrder) { sortFlow.value = s }

    fun deleteBp(id: UUID) {
        viewModelScope.launch { bpRepo.delete(id) }
    }

    /** Re-insert a just-deleted BP reading (Snackbar undo). Same id → restores in place. */
    fun restoreBp(reading: BpReading) {
        viewModelScope.launch { bpRepo.upsert(reading) }
    }

    fun deleteGlucose(id: UUID) {
        viewModelScope.launch { glucoseRepo.delete(id) }
    }

    /** Re-insert a just-deleted glucose reading (Snackbar undo). */
    fun restoreGlucose(reading: GlucoseReading) {
        viewModelScope.launch { glucoseRepo.upsert(reading) }
    }
}
