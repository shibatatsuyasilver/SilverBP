package com.silverbp.android.ui.insights

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GuidelineClassifier
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.Member
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.member.MemberPalette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class InsightsRange(val days: Long?) {
    Last7(7), Last30(30), Last90(90), All(null);
}

/** Which series the comparison trend line plots (roadmap: SBP/DBP toggle). */
enum class TrendMetric { Systolic, Diastolic }

/**
 * All per-member analytics derived from a range-filtered reading list. Shared by
 * the single-member path and every compare member so both compute identically.
 * Pure data — no Android dependencies — see [computeMemberInsights].
 */
data class MemberInsights(
    val readings: List<BpReading> = emptyList(),
    val meanSystolic: Double = 0.0,
    val meanDiastolic: Double = 0.0,
    val sdSystolic: Double = 0.0,
    val arvSystolic: Double = 0.0,
    val morningSurge: Double? = null,
    val distribution: Map<BpCategory, Int> = emptyMap(),
    /** Reading counts bucketed by (part-of-day × category) for the daypart heatmap. */
    val daypartCategory: Map<Pair<PartOfDay, BpCategory>, Int> = emptyMap(),
)

/**
 * One member's data for the comparison charts: identity (id/name/owner-flag),
 * the resolved identity [color] (collision-reassigned — see [buildSeries]) and
 * the per-member [insights]. [name] is the raw [Member.displayName]; the screen
 * applies the localized "Me" fallback when [isOwner] && name is blank (the VM
 * stays free of Android resource lookups).
 */
data class MemberSeries(
    val memberId: UUID,
    val name: String,
    val isOwner: Boolean,
    val color: Color,
    val insights: MemberInsights,
)

/**
 * Lightweight metadata for one active member, used to render the compare
 * multi-select chips before any member is selected. [color] is the member's
 * own palette colour (NOT collision-reassigned — chips are labelled by name).
 */
data class ActiveMember(
    val id: UUID,
    val name: String,
    val isOwner: Boolean,
    val color: Color,
)

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
    // --- multi-member comparison (transient analysis view; not persisted) ---
    /** When true the charts overlay/small-multiple [series] instead of the single fields above. */
    val compareMode: Boolean = false,
    /** Which metric the comparison trend line plots. */
    val compareMetric: TrendMetric = TrendMetric.Systolic,
    /** One entry per selected member, in stable active-member order. Empty when [compareMode] is off. */
    val series: List<MemberSeries> = emptyList(),
    /** All active members (owner first, then sortOrder) for the compare multi-select chips. */
    val activeMembers: List<ActiveMember> = emptyList(),
    /** The currently-selected member ids (drives the chip checked state). */
    val selectedMemberIds: Set<UUID> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val members: MemberRepository = ServiceLocator.memberRepository,
) : ViewModel() {

    private val rangeFlow = MutableStateFlow(InsightsRange.Last30)

    // --- compare state: local, transient (NOT persisted — analysis-page view) ---
    private val compareModeFlow = MutableStateFlow(false)
    private val selectedMemberIdsFlow = MutableStateFlow<Set<UUID>>(emptySet())
    private val compareMetricFlow = MutableStateFlow(TrendMetric.Systolic)

    // Classify the SELECTED member's readings with the SELECTED member's own
    // guideline (roadmap §3-1: each member may carry their own threshold set —
    // e.g. CKD/diabetes). Falls back to the owner's settings guideline only if
    // the member row can't be resolved.
    private val guidelineFlow = currentMember.flow.flatMapLatest { id ->
        settings.flow.map { user ->
            runCatching { members.findById(UUID.fromString(id)) }.getOrNull()?.guideline
                ?: user.guideline
        }
    }

    // --- single-member state (compare OFF). Behaviour identical to pre-compare. ---
    private val singleState: StateFlow<InsightsUiState> = combine(
        currentMember.flow.flatMapLatest { repo.observeAll(it) },
        rangeFlow,
        guidelineFlow,
    ) { all, range, guideline ->
        val insights = computeMemberInsights(all, range, guideline)
        InsightsUiState(
            range = range,
            readings = insights.readings,
            meanSystolic = insights.meanSystolic,
            meanDiastolic = insights.meanDiastolic,
            sdSystolic = insights.sdSystolic,
            arvSystolic = insights.arvSystolic,
            morningSurge = insights.morningSurge,
            distribution = insights.distribution,
            daypartCategory = insights.daypartCategory,
            guideline = guideline,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    // --- compare series (compare ON). One MemberSeries per selected member. ---
    // observeActive() gives the stable order (owner first, then sortOrder) used
    // both for the chip list and the deterministic colour-collision reassignment.
    private val seriesState: StateFlow<List<MemberSeries>> = combine(
        members.observeActive(),
        selectedMemberIdsFlow,
        rangeFlow,
    ) { active, selected, range ->
        // Selected members in stable active order; ignore ids that have left the
        // active set (archived/deleted) so a stale selection never shows a ghost.
        active.filter { it.id in selected } to range
    }.flatMapLatest { (ordered, range) ->
        if (ordered.isEmpty()) {
            flowOf(emptyList())
        } else {
            // One readings stream per selected member, combined positionally so the
            // result preserves the stable active order (= colour-collision order).
            combine(ordered.map { repo.observeAll(it.id.toString()) }) { perMember ->
                buildSeries(ordered, perMember.toList(), range)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- active members for the chips (independent of selection). ---
    private val activeMembersState: StateFlow<List<ActiveMember>> = members.observeActive()
        .map { active ->
            active.map {
                ActiveMember(
                    id = it.id,
                    name = it.displayName,
                    isOwner = it.isOwner,
                    color = MemberPalette.colorFor(it.colorIndex),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The screen's single source of truth: merges single-member + compare state. */
    val state: StateFlow<InsightsUiState> = combine(
        singleState,
        compareModeFlow,
        compareMetricFlow,
        seriesState,
        combine(activeMembersState, selectedMemberIdsFlow) { a, s -> a to s },
    ) { single, compareMode, metric, series, (activeMembers, selectedIds) ->
        single.copy(
            compareMode = compareMode,
            compareMetric = metric,
            series = if (compareMode) series else emptyList(),
            activeMembers = activeMembers,
            selectedMemberIds = selectedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun setRange(r: InsightsRange) { rangeFlow.value = r }

    /**
     * Turn comparison mode on/off. Turning it ON with an empty selection seeds
     * every active member so the user sees a populated comparison immediately
     * (roadmap §3: default-select all on first enable).
     */
    fun setCompareMode(enabled: Boolean) {
        compareModeFlow.value = enabled
        if (enabled && selectedMemberIdsFlow.value.isEmpty()) {
            selectedMemberIdsFlow.value = activeMembersState.value.map { it.id }.toSet()
        }
    }

    /** Add/remove a member from the comparison selection. */
    fun toggleMember(id: UUID) {
        selectedMemberIdsFlow.value = selectedMemberIdsFlow.value.let {
            if (id in it) it - id else it + id
        }
    }

    fun setCompareMetric(metric: TrendMetric) { compareMetricFlow.value = metric }
}

/**
 * Pure per-member analytics. Range-filters [all] by [range]'s cutoff then runs the
 * same mean/SD/ARV/morning-surge/distribution/daypart pipeline the single-member
 * Insights state has always used, so the compare path matches it bit-for-bit.
 * No Android dependencies — unit-testable in isolation.
 */
fun computeMemberInsights(
    all: List<BpReading>,
    range: InsightsRange,
    guideline: HypertensionGuideline,
): MemberInsights {
    val cutoff = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
    val filtered = if (cutoff == null) all else all.filter { it.timestamp.isAfter(cutoff) }
    val sys = filtered.map { it.systolic.toDouble() }
    val dia = filtered.map { it.diastolic.toDouble() }
    // Bucket morning/evening by the reading's actual local time, not the
    // user-set partOfDay field — that field defaults to Morning and is often
    // left unchanged, so evening readings would otherwise all show as morning.
    val morningSys = filtered.filter { it.daypartByTime() == PartOfDay.Morning }.map { it.systolic.toDouble() }
    val eveningSys = filtered.filter { it.daypartByTime() == PartOfDay.Evening }.map { it.systolic.toDouble() }
    val classifier = GuidelineClassifier(guideline)
    val dist = filtered.groupBy { classifier.classify(it.systolic, it.diastolic) }
        .mapValues { it.value.size }
    val daypart = filtered
        .groupingBy { it.daypartByTime() to classifier.classify(it.systolic, it.diastolic) }
        .eachCount()

    return MemberInsights(
        readings = filtered.sortedBy { it.timestamp },
        meanSystolic = StatsEngine.mean(sys),
        meanDiastolic = StatsEngine.mean(dia),
        sdSystolic = StatsEngine.standardDeviation(sys),
        arvSystolic = StatsEngine.averageRealVariability(sys),
        morningSurge = StatsEngine.morningSurge(morningSys, eveningSys),
        distribution = dist,
        daypartCategory = daypart,
    )
}

/**
 * Build the comparison series for [ordered] members (stable active order) paired
 * positionally with their [perMember] readings.
 *
 * Colour collision (roadmap): two members with the same [Member.colorIndex] would
 * draw indistinguishable lines/points. Walking the members in stable order we
 * keep each member's own palette colour when free; on a collision we
 * deterministically reassign the first still-unused palette colour (by palette
 * index). If the whole palette is exhausted (>8 members) we fall back to the
 * member's own colour — the name legend still disambiguates (never colour-only).
 * Stable ordering means the reassignment is deterministic across recompositions.
 */
internal fun buildSeries(
    ordered: List<Member>,
    perMember: List<List<BpReading>>,
    range: InsightsRange,
): List<MemberSeries> {
    val used = mutableSetOf<Color>()
    return ordered.mapIndexed { index, member ->
        val own = MemberPalette.colorFor(member.colorIndex)
        val color = if (used.add(own)) {
            own
        } else {
            // Own colour taken — pick the first palette colour not yet used.
            val free = MemberPalette.colors.firstOrNull { it !in used }
            if (free != null) { used.add(free); free } else own
        }
        MemberSeries(
            memberId = member.id,
            name = member.displayName,
            isOwner = member.isOwner,
            color = color,
            insights = computeMemberInsights(
                all = perMember.getOrElse(index) { emptyList() },
                range = range,
                // Each member classifies with their OWN guideline (per-member
                // thresholds, e.g. CKD/diabetes) — carried directly on the row.
                guideline = member.guideline,
            ),
        )
    }
}

/**
 * Morning vs evening derived from the reading's local time (Morning = 05:00–11:59;
 * everything else — afternoon, evening, late night — is Evening). Insights buckets
 * on this instead of the user-set [PartOfDay] field, which defaults to Morning and
 * is frequently left unchanged.
 */
private fun BpReading.daypartByTime(): PartOfDay =
    if (timestamp.atZone(ZoneId.systemDefault()).hour in 5 until 12) PartOfDay.Morning else PartOfDay.Evening
