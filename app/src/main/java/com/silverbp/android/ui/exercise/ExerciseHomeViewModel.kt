package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.coach.CoachTask
import com.silverbp.android.coach.LifestyleModule
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseController
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.HealthConnectExerciseBridge
import com.silverbp.android.exercise.SessionLive
import com.silverbp.android.strength.StrengthWorkoutRepository
import com.silverbp.android.strength.StrengthWorkoutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ExerciseRange(val days: Long?) {
    Last7(7), Last30(30), Last90(90), All(null);
}

data class ExerciseHomeUiState(
    val selectedKind: ActivityKind = ActivityKind.Walking,
    val recent: List<ExerciseSession> = emptyList(),
    /** Total cardio session count — drives the "view all" entry; [recent] is take(3). */
    val allCount: Int = 0,
    val range: ExerciseRange = ExerciseRange.Last30,
    // (date, perKindMeters), zero-filled across the active range. Per-kind
    // metres come from the day's [ActivityKind] → distance map; kinds with no
    // session that day are simply absent from the map.
    val dailyDistanceByKind: List<Pair<LocalDate, Map<ActivityKind, Double>>> = emptyList(),
    val paceSeriesByKind: Map<ActivityKind, List<Pair<Instant, Double>>> = emptyMap(),
    val kindCounts: Map<ActivityKind, Int> = emptyMap(),
    val totalDistanceMeters: Double = 0.0,
    val totalDurationMillis: Long = 0L,
    val sessionCount: Int = 0,
    val weekStepCount: Int = 0,
    // Today's coach exercise task (null = rest day or no plan), surfaced on the
    // hub's 課表 section. Strength sessions feed the 歷史 section alongside cardio.
    val todayExerciseTask: CoachTask? = null,
    val hasPlan: Boolean = false,
    val strengthSessions: List<StrengthWorkoutSession> = emptyList(),
)

class ExerciseHomeViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val healthConnect: HealthConnectExerciseBridge = ServiceLocator.healthConnectExerciseBridge,
    private val controller: ExerciseController = ServiceLocator.exerciseController,
    private val coachRepo: CoachRepository = ServiceLocator.coachRepository,
    private val strengthRepo: StrengthWorkoutRepository = ServiceLocator.strengthWorkoutRepository,
) : ViewModel() {

    private val kindFlow = MutableStateFlow(ActivityKind.Walking)
    private val rangeFlow = MutableStateFlow(ExerciseRange.Last30)
    private val weekStepFlow = MutableStateFlow<Int?>(null)

    /** A session orphaned by a process kill, surfaced as a resume/discard prompt. */
    private val _recoverable = MutableStateFlow<SessionLive?>(null)
    val recoverable: StateFlow<SessionLive?> = _recoverable.asStateFlow()

    // Plan + strength sessions are pre-combined into one flow so the outer
    // combine stays within Kotlin's typed (≤5 flows) overloads.
    private val hubExtrasFlow = combine(
        coachRepo.observeCurrentPlan(System.currentTimeMillis()),
        strengthRepo.observeAllSessions(),
    ) { plan, strengthSessions -> plan to strengthSessions }

    val state: StateFlow<ExerciseHomeUiState> = combine(
        repo.observeAll(),
        kindFlow,
        rangeFlow,
        weekStepFlow,
        hubExtrasFlow,
    ) { all, kind, range, hcWeekSteps, hubExtras ->
        val (plan, strengthSessions) = hubExtras
        val now = Instant.now()
        val cutoff = range.days?.let { now.minus(it, ChronoUnit.DAYS) }
        val filtered = if (cutoff == null) all else all.filter { it.startedAt.isAfter(cutoff) }

        val zone = ZoneId.systemDefault()
        val rangeStartDate = if (range.days != null) {
            LocalDate.now(zone).minusDays(range.days - 1)
        } else {
            filtered.minByOrNull { it.startedAt }?.startedAt?.atZone(zone)?.toLocalDate()
                ?: LocalDate.now(zone)
        }
        val rangeEndDate = LocalDate.now(zone)
        val daySpan = ChronoUnit.DAYS.between(rangeStartDate, rangeEndDate).toInt() + 1

        // Per-kind distance per day. Each kind is tracked separately so the bar
        // chart and the heatmap can render every colour instead of tinting
        // combined data with whichever kind chip is currently selected.
        val byDateKind: Map<LocalDate, Map<ActivityKind, Double>> = filtered
            .groupBy { it.startedAt.atZone(zone).toLocalDate() }
            .mapValues { (_, list) ->
                list.groupBy { it.kind }.mapValues { (_, ses) -> ses.sumOf { it.distanceMeters } }
            }
        val dailyByKind: List<Pair<LocalDate, Map<ActivityKind, Double>>> = (0 until daySpan).map { offset ->
            val d = rangeStartDate.plusDays(offset.toLong())
            d to byDateKind[d].orEmpty()
        }

        val paceByKind: Map<ActivityKind, List<Pair<Instant, Double>>> = filtered
            .filter { it.averagePaceSecPerKm != null && it.averagePaceSecPerKm > 0 }
            .groupBy { it.kind }
            .mapValues { (_, list) ->
                list.sortedBy { it.startedAt }
                    .map { it.startedAt to it.averagePaceSecPerKm!! }
            }

        val kindCounts = filtered.groupBy { it.kind }.mapValues { it.value.size }

        val totalDistance = filtered.sumOf { it.distanceMeters }
        val totalDuration = filtered.sumOf { it.activeDurationMillis }

        val weekSteps = hcWeekSteps ?: run {
            val weekCutoff = now.minus(7, ChronoUnit.DAYS)
            all.filter { it.startedAt.isAfter(weekCutoff) }.sumOf { it.stepCount ?: 0 }
        }

        // Today's exercise task: the Exercise-module task whose dayOffset matches
        // today relative to the plan's local-zone week start. Null on rest days.
        val todayTask = plan?.let { p ->
            val weekStart = Instant.ofEpochMilli(p.weekStartMillis).atZone(zone).toLocalDate()
            val todayOffset = (LocalDate.now(zone).toEpochDay() - weekStart.toEpochDay())
                .toInt().coerceIn(0, 6)
            p.tasks.firstOrNull {
                it.module == LifestyleModule.Exercise && it.dayOffset == todayOffset
            }
        }

        ExerciseHomeUiState(
            selectedKind = kind,
            recent = all.take(3),
            allCount = all.size,
            range = range,
            dailyDistanceByKind = dailyByKind,
            paceSeriesByKind = paceByKind,
            kindCounts = kindCounts,
            totalDistanceMeters = totalDistance,
            totalDurationMillis = totalDuration,
            sessionCount = filtered.size,
            weekStepCount = weekSteps,
            todayExerciseTask = todayTask,
            hasPlan = plan != null,
            strengthSessions = strengthSessions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseHomeUiState())

    init {
        refreshWeekSteps()
    }

    /** Check for an orphaned session (off the main thread — it reads a file). */
    fun checkRecoverable() {
        viewModelScope.launch {
            _recoverable.value = withContext(Dispatchers.IO) { controller.recoverableCheckpoint() }
        }
    }

    /** Re-attach the orphaned session and let the caller open the session screen. */
    fun resumeRecoverable() {
        _recoverable.value?.let { controller.restore(it) }
        _recoverable.value = null
    }

    fun discardRecoverable() {
        controller.discardCheckpoint()
        _recoverable.value = null
    }

    fun selectKind(kind: ActivityKind) { kindFlow.value = kind }

    fun setRange(r: ExerciseRange) {
        rangeFlow.value = r
    }

    fun refreshWeekSteps() {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.minusDays(6)
            val daily = healthConnect.queryDailySteps(from, today, zone)
            weekStepFlow.value = daily?.sumOf { it.steps }
        }
    }
}
