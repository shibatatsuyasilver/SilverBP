package com.silverbp.android.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.HypertensionGuideline
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
    /** The day's weight readings (sorted like the other two types). */
    val weightReadings: List<WeightReading>,
    /** Day mean systolic/diastolic (rounded), or null when no BP that day. */
    val bpMean: Pair<Int, Int>?,
    /** Day mean canonical mg/dL, or null when no glucose that day. */
    val glucoseMean: Double?,
    /** Day mean canonical kg, or null when no weight that day. */
    val weightMean: Double?,
)

data class UnifiedHistoryUiState(
    val range: DateRange = DateRange.All,
    val sort: SortOrder = SortOrder.Newest,
    val grouped: List<CombinedDayGroup> = emptyList(),
    /** Selected member's guideline — classifies each BP row's category colour. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    /** User's preferred glucose display unit (mg/dL default); glucose rows render in it. */
    val glucoseUnit: GlucoseUnit = GlucoseUnit.Mgdl,
    /** User's preferred weight display unit (kg default); weight rows render in it. */
    val weightUnit: WeightUnit = WeightUnit.Kg,
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
    private val weightRepo: WeightRepository = ServiceLocator.weightRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(DateRange.All)
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

    // The two display-unit prefs travel together so the final combine keeps its
    // typed arity (the three reading streams are bundled into one source).
    private val unitsFlow = settings.flow.map {
        GlucoseUnit.fromRaw(it.glucoseUnit) to WeightUnit.fromRaw(it.weightUnit)
    }

    // BP + glucose + weight streams, all member-scoped, bundled into one source.
    private val readingsFlow = combine(
        currentMember.flow.flatMapLatest { bpRepo.observeAll(it) },
        currentMember.flow.flatMapLatest { glucoseRepo.observeAll(it) },
        currentMember.flow.flatMapLatest { weightRepo.observeAll(it) },
    ) { bp, glucose, weight -> Triple(bp, glucose, weight) }

    val state: StateFlow<UnifiedHistoryUiState> = combine(
        readingsFlow,
        rangeFlow,
        sortFlow,
        guidelineFlow,
        unitsFlow,
    ) { (allBp, allGlucose, allWeight), range, sort, guideline, units ->
        val (glucoseUnit, weightUnit) = units
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        val bpByDate = allBp
            .filter { range.matches(it, today, zone) }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
        val glucoseByDate = allGlucose
            .filter { range.matchesGlucose(it, today, zone) }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }
        val weightByDate = allWeight
            .filter { range.matchesWeight(it, today, zone) }
            .groupBy { it.timestamp.atZone(zone).toLocalDate() }

        val groups = (bpByDate.keys + glucoseByDate.keys + weightByDate.keys)
            .map { date ->
                // Newest-first within a day for all types when sorting newest.
                val bp = (bpByDate[date] ?: emptyList())
                    .let { if (sort == SortOrder.Oldest) it.sortedBy { r -> r.timestamp } else it.sortedByDescending { r -> r.timestamp } }
                val glucose = (glucoseByDate[date] ?: emptyList())
                    .let { if (sort == SortOrder.Oldest) it.sortedBy { r -> r.timestamp } else it.sortedByDescending { r -> r.timestamp } }
                val weight = (weightByDate[date] ?: emptyList())
                    .let { if (sort == SortOrder.Oldest) it.sortedBy { r -> r.timestamp } else it.sortedByDescending { r -> r.timestamp } }
                CombinedDayGroup(
                    date = date,
                    bpReadings = bp,
                    glucoseReadings = glucose,
                    weightReadings = weight,
                    bpMean = bp.takeIf { it.isNotEmpty() }?.let {
                        it.map { r -> r.systolic }.average().toInt() to it.map { r -> r.diastolic }.average().toInt()
                    },
                    glucoseMean = glucose.takeIf { it.isNotEmpty() }?.map { it.valueMgdl }?.average(),
                    weightMean = weight.takeIf { it.isNotEmpty() }?.map { it.weightKg }?.average(),
                )
            }
            .let { if (sort == SortOrder.Oldest) it.sortedBy { g -> g.date } else it.sortedByDescending { g -> g.date } }

        UnifiedHistoryUiState(
            range = range,
            sort = sort,
            grouped = groups,
            guideline = guideline,
            glucoseUnit = glucoseUnit,
            weightUnit = weightUnit,
            isLoading = false,
        )
    }
        .catch { emit(UnifiedHistoryUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UnifiedHistoryUiState())

    fun setRange(r: DateRange) { rangeFlow.value = r }
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

    fun deleteWeight(id: UUID) {
        viewModelScope.launch { weightRepo.delete(id) }
    }

    /** Re-insert a just-deleted weight reading (Snackbar undo). */
    fun restoreWeight(reading: WeightReading) {
        viewModelScope.launch { weightRepo.upsert(reading) }
    }
}

/**
 * [DateRange] applied to a weight reading's timestamp (the BP/glucose variants take
 * their own reading types). Package-internal so [UnifiedHistoryViewModel] reuses the
 * same range predicate; mirrors [matchesGlucose].
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
