package com.silverbp.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.WeightGuideline
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
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
    /**
     * The selected member's most-recent weight reading (latest-ever, not
     * today-scoped). Unlike BP/glucose, weight is tracked as a single hero value
     * — a "today's weigh-in" list adds no clinical signal — so this mirrors the
     * old latest-ever card via [WeightRepository.observeLatest]. null = no data.
     */
    val latestWeight: WeightReading? = null,
    /**
     * BMI for [latestWeight] using the selected member's profile height, or null
     * when there's no weight reading or the member's heightCm is missing/invalid.
     * Computed here (not in the screen) so the card can render the 過輕/正常/過重/
     * 肥胖 band without re-reading the member row. See [WeightGuideline].
     */
    val weightBmi: Double? = null,
    /** Local calendar date the card is scoped to (drives the "今天 M/D (週X)" title). */
    val today: LocalDate = LocalDate.now(),
    val modelPhase: ModelLoadPhase = ModelLoadPhase.Idle,
    /** Selected member's guideline — classifies the BP section's colour/label. */
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    /** User's preferred glucose display unit (mg/dL default). */
    val glucoseUnit: GlucoseUnit = GlucoseUnit.Mgdl,
    /** How to address the user in the greeting (UserSettings.userNickname); blank = no name. */
    val userName: String = "",
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
    private val weight: WeightRepository = ServiceLocator.weightRepository,
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

    // Wall-clock day ticker (round-1 fix #1). Room's observeRange re-emits on
    // table changes but with the *fixed* from/to args it was first called with —
    // it does NOT re-evaluate the calendar boundary. So when the Today screen
    // stays continuously subscribed across midnight, a window pinned to member
    // emissions would freeze on yesterday (stale title; a 00:30 reading falls
    // outside the frozen `to=now` and vanishes). Driving the window off this tick
    // — which re-emits whenever the system-tz day rolls over (and, defensively,
    // at least once a minute) — makes `today`, `todayStart`, and `to=now`
    // re-derive on the day change while subscribed, without changing the
    // member-scoped / today-scoped semantics. distinctUntilChanged collapses the
    // sub-minute polls so we only restart the downstream queries on an actual
    // date change.
    private val dayTicker: Flow<LocalDate> = flow {
        while (true) {
            val today = LocalDate.now(zone)
            emit(today)
            // Sleep until the next system-tz midnight, capped at one minute so a
            // device clock/time-zone jump is picked up promptly too.
            val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant()
            val untilMidnightMs = Duration.between(Instant.now(), nextMidnight).toMillis()
            delay(untilMidnightMs.coerceIn(1L, 60_000L))
        }
    }.distinctUntilChanged()

    // System-tz start-of-day for the given calendar date (the BETWEEN lower bound).
    private fun dayStart(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

    // Selected member's BP readings for today (member-scoped via flatMapLatest,
    // exactly like the old card). The day ticker re-keys the inner observeRange so
    // the window follows the calendar day even while subscribed across midnight.
    // observeRange returns timestamp-ASC.
    private val todayBpFlow = combine(currentMember.flow, dayTicker) { id, date -> id to date }
        .flatMapLatest { (id, date) ->
            repo.observeRange(id, dayStart(date), Instant.now())
        }

    // Selected member's glucose readings for today, paired with the user's unit
    // preference so the card renders mg/dL or mmol/L without a separate combine
    // source. Follows the same member selection (and day ticker) as the BP section.
    private val todayGlucoseFlow = combine(
        combine(currentMember.flow, dayTicker) { id, date -> id to date }
            .flatMapLatest { (id, date) ->
                glucose.observeRange(id, dayStart(date), Instant.now())
            },
        settings.flow.map { GlucoseUnit.fromRaw(it.glucoseUnit) },
    ) { readings, unit -> readings to unit }

    // Selected member's latest weight reading paired with the BMI it implies for
    // that member's profile height. Weight is a latest-ever hero (no today-scoped
    // list — see [TodayUiState.latestWeight]), so this follows member selection
    // via flatMapLatest like guidelineFlow but skips the day ticker. heightCm is
    // resolved off the member row (null when missing/unresolvable), so the BMI is
    // null until both a reading and a height exist. Pairs reading+BMI in a single
    // source to avoid an extra combine arity downstream.
    private val latestWeightFlow = currentMember.flow.flatMapLatest { id ->
        weight.observeLatest(id).map { reading ->
            val heightCm = runCatching { members.findById(UUID.fromString(id)) }
                .getOrNull()?.heightCm
            val bmi = reading?.let { WeightGuideline.bmi(it.valueKg, heightCm) }
            reading to bmi
        }
    }

    // The user's preferred form of address for the greeting ("早安, <暱稱>"); blank
    // = no name (greeting stays "早安"). distinctUntilChanged so unrelated settings
    // edits don't restart the downstream combine.
    private val nicknameFlow = settings.flow.map { it.userNickname }.distinctUntilChanged()

    // The guideline, latest weight, and greeting nickname are all profile-derived;
    // folding them into one source keeps the outer state combine at five typed args
    // (Kotlin's combine only has typed overloads up to arity five — a sixth flow
    // would force the type-erased vararg form).
    private val profileFlow = combine(
        guidelineFlow,
        latestWeightFlow,
        nicknameFlow,
    ) { guideline, weight, nickname -> Triple(guideline, weight, nickname) }

    val state: StateFlow<TodayUiState> = combine(
        todayBpFlow,
        modelStatus.phase,
        profileFlow,
        todayGlucoseFlow,
        dayTicker,
    ) { bp, phase, (guideline, weight, userName), (glucoseReadings, glucoseUnit), today ->
        val (latestWeight, weightBmi) = weight
        TodayUiState(
            todayBp = bp,
            todayGlucose = glucoseReadings,
            latestWeight = latestWeight,
            weightBmi = weightBmi,
            today = today,
            modelPhase = phase,
            guideline = guideline,
            glucoseUnit = glucoseUnit,
            userName = userName,
            isLoading = false,
        )
    }
        .catch { emit(TodayUiState(isLoading = false, error = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())
}
