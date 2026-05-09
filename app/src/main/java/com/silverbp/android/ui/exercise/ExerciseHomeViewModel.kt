package com.silverbp.android.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.HealthConnectExerciseBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
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
    val range: ExerciseRange = ExerciseRange.Last30,
    // (date, walkingMeters, runningMeters), zero-filled across the active range.
    val dailyDistanceByKind: List<Triple<LocalDate, Double, Double>> = emptyList(),
    val paceSeriesByKind: Map<ActivityKind, List<Pair<Instant, Double>>> = emptyMap(),
    val kindCounts: Map<ActivityKind, Int> = emptyMap(),
    val totalDistanceMeters: Double = 0.0,
    val totalDurationMillis: Long = 0L,
    val sessionCount: Int = 0,
    val weekStepCount: Int = 0,
)

class ExerciseHomeViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val healthConnect: HealthConnectExerciseBridge = ServiceLocator.healthConnectExerciseBridge,
) : ViewModel() {

    private val kindFlow = MutableStateFlow(ActivityKind.Walking)
    private val rangeFlow = MutableStateFlow(ExerciseRange.Last30)
    private val weekStepFlow = MutableStateFlow<Int?>(null)

    val state: StateFlow<ExerciseHomeUiState> = combine(
        repo.observeAll(),
        kindFlow,
        rangeFlow,
        weekStepFlow,
    ) { all, kind, range, hcWeekSteps ->
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

        // Per-kind distance per day. Walking and Running are tracked separately so
        // the bar chart and the heatmap can render both colours instead of tinting
        // combined data with whichever kind chip is currently selected.
        val byDateKind: Map<LocalDate, Map<ActivityKind, Double>> = filtered
            .groupBy { it.startedAt.atZone(zone).toLocalDate() }
            .mapValues { (_, list) ->
                list.groupBy { it.kind }.mapValues { (_, ses) -> ses.sumOf { it.distanceMeters } }
            }
        val dailyByKind: List<Triple<LocalDate, Double, Double>> = (0 until daySpan).map { offset ->
            val d = rangeStartDate.plusDays(offset.toLong())
            val perKind = byDateKind[d].orEmpty()
            Triple(
                d,
                perKind[ActivityKind.Walking] ?: 0.0,
                perKind[ActivityKind.Running] ?: 0.0,
            )
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
        val totalDuration = filtered.sumOf { Duration.between(it.startedAt, it.endedAt).toMillis() }

        val weekSteps = hcWeekSteps ?: run {
            val weekCutoff = now.minus(7, ChronoUnit.DAYS)
            all.filter { it.startedAt.isAfter(weekCutoff) }.sumOf { it.stepCount ?: 0 }
        }

        ExerciseHomeUiState(
            selectedKind = kind,
            recent = all.take(3),
            range = range,
            dailyDistanceByKind = dailyByKind,
            paceSeriesByKind = paceByKind,
            kindCounts = kindCounts,
            totalDistanceMeters = totalDistance,
            totalDurationMillis = totalDuration,
            sessionCount = filtered.size,
            weekStepCount = weekSteps,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseHomeUiState())

    init {
        refreshWeekSteps()
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
