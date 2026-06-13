package com.silverbp.android.ui.today

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
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Today's combined daily record (owner decision §1/§2): the unified card shows
 * the selected member's **readings taken today** for both blood pressure and
 * blood glucose, on equal footing. This replaced the old "latest-ever" hero +
 * separate glucose card — hence [todayBp]/[todayGlucose] are today-scoped lists
 * (system-tz calendar day), not the single most-recent reading.
 *
 * Both lists arrive in timestamp-ASC order (the DAO's `observeRange` ordering),
 * so the UI takes [List.lastOrNull] for "the latest reading today" and uses the
 * list size for the "N readings today" affordance.
 */
data class TodayUiState(
    /** The selected member's BP readings taken today, timestamp-ASC. */
    val todayBp: List<BpReading> = emptyList(),
    /** The selected member's glucose readings taken today, timestamp-ASC. */
    val todayGlucose: List<GlucoseReading> = emptyList(),
    /** Local calendar date the card is scoped to (drives the "今天 M/D (週X)" title). */
    val today: LocalDate = LocalDate.now(),
    val modelPhase: ModelLoadPhase = ModelLoadPhase.Idle,
    /** Selected member's guideline — classifies the BP section's colour/label. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    /** User's preferred glucose display unit (mg/dL default). */
    val glucoseUnit: GlucoseUnit = GlucoseUnit.Mgdl,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repo: BpRepository = ServiceLocator.bpRepository,
    private val currentMember: CurrentMemberStore = ServiceLocator.currentMemberStore,
    private val modelStatus: ModelLoadStatus = ServiceLocator.modelLoadStatus,
    private val members: MemberRepository = ServiceLocator.memberRepository,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val glucose: GlucoseRepository = ServiceLocator.glucoseRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    // The selected member's own guideline (roadmap §3-1); falls back to the
    // owner's settings guideline if the member row can't be resolved.
    private val guidelineFlow = currentMember.flow.flatMapLatest { id ->
        settings.flow.map { user ->
            runCatching { members.findById(UUID.fromString(id)) }.getOrNull()?.guideline
                ?: user.guideline
        }
    }

    // Today's window, recomputed per emission so it tracks the wall clock: a
    // reading saved just after midnight (a fresh DB emission) re-derives the
    // boundary, so "today" resets to the new calendar day. todayStart = system-tz
    // 00:00; the upper bound is "now" (we never show future-dated rows).
    private fun todayStart(): Instant = LocalDate.now(zone).atStartOfDay(zone).toInstant()

    // Selected member's BP readings for today (member-scoped via flatMapLatest,
    // exactly like the old card). observeRange returns timestamp-ASC.
    private val todayBpFlow = currentMember.flow.flatMapLatest { id ->
        repo.observeRange(id, todayStart(), Instant.now())
    }

    // Selected member's glucose readings for today, paired with the user's unit
    // preference so the card renders mg/dL or mmol/L without a separate combine
    // source. Follows the same member selection as the BP section.
    private val todayGlucoseFlow = combine(
        currentMember.flow.flatMapLatest { id ->
            glucose.observeRange(id, todayStart(), Instant.now())
        },
        settings.flow.map { GlucoseUnit.fromRaw(it.glucoseUnit) },
    ) { readings, unit -> readings to unit }

    val state: StateFlow<TodayUiState> = combine(
        todayBpFlow,
        modelStatus.phase,
        guidelineFlow,
        todayGlucoseFlow,
    ) { bp, phase, guideline, (glucoseReadings, glucoseUnit) ->
        TodayUiState(
            todayBp = bp,
            todayGlucose = glucoseReadings,
            today = LocalDate.now(zone),
            modelPhase = phase,
            guideline = guideline,
            glucoseUnit = glucoseUnit,
            isLoading = false,
        )
    }
        .catch { emit(TodayUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
