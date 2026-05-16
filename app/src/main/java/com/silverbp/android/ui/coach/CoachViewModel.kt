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
import com.silverbp.android.coach.MedicationPerMedProgress
import com.silverbp.android.coach.TodayExerciseTaskGenerator
import com.silverbp.android.coach.TodayTaskOverlay
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure helper for the Exercise weekly-progress row: counts the distinct
 * local-zone calendar dates on which there is at least one [ExerciseSession].
 * Extracted as a top-level `internal` function so the same-package JUnit test
 * can call it directly (mirrors `CoachRepository.countScheduledInWeek`).
 */
internal fun countDistinctExerciseDays(
    sessions: List<ExerciseSession>,
    zone: ZoneId,
): Int = sessions
    .map { it.startedAt.atZone(zone).toLocalDate() }
    .distinct()
    .size

/**
 * Coach ViewModel.
 *
 * Source of truth: [CoachRepository.observeCurrentPlan] (Room-backed Flow).
 * On first observation, if there is no plan for the current week, we trigger
 * [CoachEngine.generateWeeklyPlan] once and persist it.
 *
 * Today task contract:
 *  - Quantitative parts (minutes, intensity, safetyHold) come from
 *    [CoachEngine] inside [CoachTask] — never from the LLM.
 *  - The headline title/subtitle is regenerated each day by
 *    [TodayExerciseTaskGenerator] referencing the user's last-7-day
 *    exercise records, then cached in `_overlay`.
 *  - Completion is *derived* from today's exercise sessions
 *    (≥ targetMin × 0.8 of qualifying minutes). The derived flag is
 *    written back to [CoachRepository.setTaskCompleted] so weekly
 *    adherence aggregates stay correct; the user cannot mark complete
 *    by hand any more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModel(
    private val coachRepo: CoachRepository = ServiceLocator.coachRepository,
    private val engine: CoachEngine = ServiceLocator.coachEngine,
    private val narrator: CoachNarrator = ServiceLocator.coachNarrator,
    private val taskGenerator: TodayExerciseTaskGenerator = ServiceLocator.todayExerciseTaskGenerator,
    private val exerciseRepo: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val userSettings: UserSettingsRepository = ServiceLocator.userSettings,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val nowMillis: Long get() = clock.millis()

    private val _narration = MutableStateFlow(NarrationUi(text = "", isStreaming = false))
    private var narrationJob: Job? = null
    private var lastNarratedTaskId: String? = null

    private data class KeyedOverlay(val key: String, val overlay: TodayTaskOverlay)

    private val _overlay = MutableStateFlow<KeyedOverlay?>(null)
    private var overlayJob: Job? = null
    private var lastOverlayKey: String? = null

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
        exerciseRepo.observeRange(weekStart(), weekEnd()),
        coachRepo.observeMedicationWeeklyProgressPerMed(
            weekStartDate = currentWeekStart(),
            zone = zone,
        ),
        _narration,
        _overlay,
    ) { plan, weekSessions, medProgressPerMed, narration, overlay ->
        if (plan == null) {
            CoachUiState.Loading
        } else {
            val ready = buildReadyState(plan, weekSessions, medProgressPerMed, overlay)
            ready.todayTaskId?.let { id ->
                if (id != lastNarratedTaskId) {
                    lastNarratedTaskId = id
                    val today = plan.tasks.firstOrNull { it.id == id }
                    if (today != null) startTodayNarration(plan, today)
                }
                val key = overlayKey(plan.id, id)
                if (key != lastOverlayKey) {
                    lastOverlayKey = key
                    // Clear so the UI shows the deterministic fallback while the
                    // new overlay is being generated, rather than yesterday's title.
                    _overlay.value = null
                    val today = plan.tasks.firstOrNull { it.id == id }
                    if (today != null) refreshOverlay(plan, today)
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

    private fun refreshOverlay(plan: CoachPlan, today: CoachTask) {
        overlayJob?.cancel()
        val key = overlayKey(plan.id, today.id)
        overlayJob = viewModelScope.launch {
            val settings = userSettings.flow.first()
            val produced = runCatching { taskGenerator.generate(plan, today, settings) }
                .getOrNull()
            if (produced != null) _overlay.value = KeyedOverlay(key, produced)
        }
    }

    private suspend fun buildReadyState(
        plan: CoachPlan,
        weekSessions: List<ExerciseSession>,
        medProgressPerMed: List<MedicationPerMedProgress>,
        overlay: KeyedOverlay?,
    ): CoachUiState.Ready {
        val today = todayDayOffset(plan)
        val tasksByDay = plan.tasks.filter { it.dayOffset == today }
        val todayExercise = tasksByDay.firstOrNull { it.module == LifestyleModule.Exercise }
        // Strictly Exercise — never fall back to Diet/Sleep, otherwise their
        // targetValue (e.g. sodium 2000 mg) leaks into the exercise UI.
        val priorityToday = todayExercise
        val isRestDay = priorityToday == null
        // Diet / Sleep still source their X/Y from coach_task; Exercise is now
        // sourced from distinct exercise-days this week (see baseModules below).
        // Medication is rendered as one row per active medication, sourced
        // from medication_dose × medication_schedule (live).
        val adherence = coachRepo.adherenceForPlan(plan.id).associateBy { it.module }

        val todayDate = LocalDate.now(clock.withZone(zone))
        val todaySessions = weekSessions.filter {
            it.startedAt.atZone(zone).toLocalDate() == todayDate
        }
        val targetMin = priorityToday?.targetValue?.toInt() ?: 0
        val achievedMin = computeAchievedMinutes(todaySessions, targetMin)
        val derivedCompleted = targetMin > 0 && achievedMin >= (targetMin * 0.8).toInt()

        // Mirror the derived flag back to Room so weekly adherence
        // (`coachRepo.adherenceForPlan`) stays accurate. Idempotent.
        if (priorityToday != null && !priorityToday.safetyHold) {
            val dbCompleted = priorityToday.completedAtMillis != null
            if (derivedCompleted != dbCompleted) {
                coachRepo.setTaskCompleted(
                    priorityToday.id,
                    if (derivedCompleted) clock.millis() else null,
                )
            }
        }

        val baseModules = listOf(
            ModuleRowUi(
                moduleKey = ModuleKey.Exercise,
                displayName = "Exercise",
                completed = countDistinctExerciseDays(weekSessions, zone),
                target = 7,
            ),
            moduleRow(LifestyleModule.Diet, "Diet", adherence[LifestyleModule.Diet]),
            moduleRow(LifestyleModule.Sleep, "Sleep", adherence[LifestyleModule.Sleep]),
        )
        val medRows = medProgressPerMed.map { p ->
            ModuleRowUi(
                moduleKey = ModuleKey.Medication,
                displayName = p.medicationName,
                completed = p.taken,
                target = p.scheduled,
            )
        }
        val modules = baseModules + medRows

        val activeOverlay = if (priorityToday != null && overlay != null &&
            overlay.key == overlayKey(plan.id, priorityToday.id)
        ) overlay.overlay else null
        val title = if (isRestDay) "" else (activeOverlay?.title ?: priorityToday!!.title)
        val subtitle = when {
            isRestDay -> null
            activeOverlay?.subtitle != null -> activeOverlay.subtitle
            priorityToday?.targetUnit != null && priorityToday.targetValue != null ->
                "${formatTarget(priorityToday.targetValue)} ${priorityToday.targetUnit}"
            else -> null
        }

        return CoachUiState.Ready(
            todayTask = TodayTaskUi(
                title = title,
                subtitle = subtitle,
                completed = derivedCompleted,
                safetyHold = priorityToday?.safetyHold == true,
                achievedMinutes = achievedMin,
                targetMinutes = targetMin,
                isRestDay = isRestDay,
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

    private fun computeAchievedMinutes(sessions: List<ExerciseSession>, targetMin: Int): Int {
        if (sessions.isEmpty()) return 0
        val cap = if (targetMin > 0) targetMin.toLong() * 2 else Long.MAX_VALUE
        return sessions
            .filter { it.kind == ActivityKind.Walking || it.kind == ActivityKind.Running }
            .sumOf { s ->
                Duration.between(s.startedAt, s.endedAt).toMinutes()
                    .coerceAtLeast(0)
                    .coerceAtMost(cap)
                    .toInt()
            }
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

    private fun overlayKey(planId: String, taskId: String): String =
        "$planId:$taskId:${LocalDate.now(clock.withZone(zone))}"

    private fun currentWeekStart(): LocalDate {
        val today = LocalDate.now(clock)
        return today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    }

    private fun todayDayOffset(plan: CoachPlan): Int {
        val today = LocalDate.now(clock)
        // weekStartMillis is the local-zone Monday 00:00 instant. Decoding via
        // `millis / 86_400_000` would give a UTC epoch day, which is off by one
        // for any zone east of UTC (Asia/Taipei = +08:00 → off by -1).
        val weekStart = Instant.ofEpochMilli(plan.weekStartMillis).atZone(zone).toLocalDate()
        return ((today.toEpochDay() - weekStart.toEpochDay()).toInt()).coerceIn(0, 6)
    }

    private fun weekStart(): Instant =
        currentWeekStart().atStartOfDay(zone).toInstant()

    private fun weekEnd(): Instant =
        currentWeekStart().plusDays(7).atStartOfDay(zone).toInstant()
}
