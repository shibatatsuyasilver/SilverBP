package com.silverbp.android.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.coach.Adherence
import com.silverbp.android.coach.CoachEngine
import com.silverbp.android.coach.CoachNarrator
import com.silverbp.android.coach.CoachPlan
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.coach.CoachTask
import com.silverbp.android.coach.LifestyleModule
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Coach ViewModel.
 *
 * Source of truth: [CoachRepository.observeCurrentPlan] (Room-backed Flow).
 * On first observation, if there is no plan for the current week, we trigger
 * [CoachEngine.generateWeeklyPlan] once and persist it. The Flow then emits
 * the freshly-saved plan and the screen renders it.
 *
 * State machine: Loading until the first emission resolves; then Ready.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModel(
    private val coachRepo: CoachRepository = ServiceLocator.coachRepository,
    private val engine: CoachEngine = ServiceLocator.coachEngine,
    private val narrator: CoachNarrator = ServiceLocator.coachNarrator,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val nowMillis: Long get() = clock.millis()

    private val _narration = MutableStateFlow(NarrationUi(text = "", isStreaming = false))
    private var narrationJob: Job? = null
    private var lastNarratedTaskId: String? = null

    init {
        // Generate the current week's plan if missing. Idempotent: subsequent
        // calls in the same session see a non-null plan and skip generation.
        viewModelScope.launch {
            if (coachRepo.currentPlan(nowMillis) == null) {
                val plan = engine.generateWeeklyPlan(weekStart = currentWeekStart())
                coachRepo.savePlan(plan)
            }
        }
    }

    val state: StateFlow<CoachUiState> = combine(
        coachRepo.observeCurrentPlan(nowMillis),
        _narration,
    ) { plan, narration ->
        if (plan == null) {
            CoachUiState.Loading
        } else {
            val ready = buildReadyState(plan)
            // Lazily kick off narration the first time we see a usable today task.
            // Re-narrating on every plan-task update would spam the LLM whenever
            // the user toggles a checkbox.
            ready.todayTaskId?.let { id ->
                if (id != lastNarratedTaskId) {
                    lastNarratedTaskId = id
                    val today = plan.tasks.firstOrNull { it.id == id }
                    if (today != null) startTodayNarration(plan, today)
                }
            }
            ready.copy(narration = narration)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachUiState.Loading)

    private fun startTodayNarration(plan: CoachPlan, today: CoachTask) {
        narrationJob?.cancel()
        _narration.value = NarrationUi(text = "", isStreaming = true)
        narrationJob = viewModelScope.launch {
            val sb = StringBuilder()
            runCatching {
                narrator.narrateDailyTask(plan, today).collect { delta ->
                    sb.append(delta)
                    _narration.value = NarrationUi(text = sb.toString(), isStreaming = true)
                }
            }
            _narration.value = _narration.value.copy(isStreaming = false)
        }
    }

    fun onTodayTaskCheck() {
        viewModelScope.launch {
            val ready = state.value as? CoachUiState.Ready ?: return@launch
            val taskId = ready.todayTaskId ?: return@launch
            val toggled = if (ready.todayTask.completed) null else clock.millis()
            coachRepo.setTaskCompleted(taskId, toggled)
        }
    }

    private suspend fun buildReadyState(plan: CoachPlan): CoachUiState.Ready {
        val today = todayDayOffset(plan)
        val tasksByDay = plan.tasks.filter { it.dayOffset == today }
        val today_exercise = tasksByDay.firstOrNull { it.module == LifestyleModule.Exercise }
        val priorityToday = today_exercise ?: tasksByDay.firstOrNull()
        val adherence = coachRepo.adherenceForPlan(plan.id).associateBy { it.module }

        val modules = listOf(
            moduleRow(LifestyleModule.Exercise, "Exercise", adherence[LifestyleModule.Exercise]),
            moduleRow(LifestyleModule.Diet, "Diet", adherence[LifestyleModule.Diet]),
            moduleRow(LifestyleModule.Sleep, "Sleep", adherence[LifestyleModule.Sleep]),
            moduleRow(LifestyleModule.Medication, "Medication", adherence[LifestyleModule.Medication]),
        )

        return CoachUiState.Ready(
            todayTask = TodayTaskUi(
                title = priorityToday?.title ?: "今日無任務",
                subtitle = priorityToday?.targetUnit?.let { unit ->
                    priorityToday.targetValue?.let { v -> "${formatTarget(v)} $unit" }
                },
                completed = priorityToday?.completedAtMillis != null,
                safetyHold = priorityToday?.safetyHold == true,
            ),
            modules = modules,
            weeklyProgress = WeeklyProgressUi(
                placeholderText = "Charts will appear once 7 days of data are available.",
                hasData = false,
            ),
            narration = NarrationUi(
                text = "Once data is collected, your coach will explain your plan here.",
                isStreaming = false,
            ),
            todayTaskId = priorityToday?.id,
        )
    }

    private fun moduleRow(
        module: LifestyleModule,
        displayName: String,
        adherence: Adherence?,
    ): ModuleRowUi {
        val key = when (module) {
            LifestyleModule.Exercise -> ModuleKey.Exercise
            LifestyleModule.Diet -> ModuleKey.Diet
            LifestyleModule.Sleep -> ModuleKey.Sleep
            LifestyleModule.Medication -> ModuleKey.Medication
        }
        return ModuleRowUi(
            moduleKey = key,
            displayName = displayName,
            completed = adherence?.completed ?: 0,
            target = adherence?.scheduled ?: 0,
        )
    }

    private fun formatTarget(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    private fun currentWeekStart(): LocalDate {
        val today = LocalDate.now(clock)
        return today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    }

    private fun todayDayOffset(plan: CoachPlan): Int {
        val today = LocalDate.now(clock)
        // weekStartMillis is the local-zone Monday 00:00 instant. Decoding via
        // `millis / 86_400_000` would give a UTC epoch day, which is off by one
        // for any zone east of UTC (Asia/Taipei = +08:00 → off by -1).
        // Convert through ZonedDateTime in the same zone we wrote with.
        val weekStart = Instant.ofEpochMilli(plan.weekStartMillis).atZone(zone).toLocalDate()
        // Handle negative offsets gracefully (plan from future, ext. clock skew):
        return ((today.toEpochDay() - weekStart.toEpochDay()).toInt()).coerceIn(0, 6)
    }
}
