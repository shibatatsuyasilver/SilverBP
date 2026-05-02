package com.silverbp.android.coach

import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Rule-driven plan generator. The narrator (PR3) only paraphrases this output;
 * intensities, durations, and frequencies must never be decided by an LLM.
 *
 * Numeric thresholds annotated with their source so future maintainers can
 * audit. References:
 *  - WHO 2020 physical activity guidelines: 150 min/week moderate aerobic.
 *  - AHA: dietary sodium target <2000 mg/day.
 *  - National Sleep Foundation: 7–8 h/night for adults.
 *  - ACC/AHA 2017 + Taiwan 2022: hypertensive crisis ≥180/120; we use the more
 *    conservative ≥180/110 from JNC8 as our exercise-stop threshold.
 *  - 7-day SBP mean ≥160 → cap intensity at Light (clinical-judgement
 *    heuristic from systematic-review meta-analyses on home BP coaching).
 */
class CoachEngine(
    private val bp: BpRepository,
    private val exercise: ExerciseRepository,
    private val coachRepo: CoachRepository,
    private val settings: UserSettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Recent-window BP anomaly. Triggered when the most recent 24 h contain
     * ≥3 consecutive readings of ≥160 systolic OR ≥100 diastolic. Escalates
     * to [Severity.Critical] when any of those readings is ≥180 systolic or
     * ≥110 diastolic.
     *
     * Caller is expected to debounce (cooldown is owned by the watcher).
     */
    suspend fun detectAnomaly(now: Instant = clock.instant()): CoachEvent.Anomaly? {
        val from = now.minus(24, ChronoUnit.HOURS)
        val readings: List<BpReading> = bp.observeRange(from, now).first()
            .sortedBy { it.timestamp }
        if (readings.size < ANOMALY_MIN_CONSECUTIVE) return null

        // Slide a 3-reading window through the chronologically-ordered list.
        // First time we see three in a row over the threshold → fire.
        for (i in 0..readings.size - ANOMALY_MIN_CONSECUTIVE) {
            val window = readings.subList(i, i + ANOMALY_MIN_CONSECUTIVE)
            if (window.all { it.isElevated() }) {
                val critical = window.any { it.isCritical() }
                val latest = window.last()
                return CoachEvent.Anomaly(
                    severity = if (critical) Severity.Critical else Severity.Caution,
                    latestSystolic = latest.systolic,
                    latestDiastolic = latest.diastolic,
                    triggeredAtMillis = latest.timestamp.toEpochMilli(),
                )
            }
        }
        return null
    }

    private fun BpReading.isElevated(): Boolean = systolic >= 160 || diastolic >= 100
    private fun BpReading.isCritical(): Boolean = systolic >= 180 || diastolic >= 110

    /**
     * Build the plan for the week containing [weekStart] (Monday). Pure
     * function over the data already in the database — no LLM, no narrative.
     */
    /**
     * Snapshot used by [CoachNarrator.narrateWeeklyReport]. Derived from the
     * past 7 days of records — pure read, no writes.
     *
     * "Highlights" / "nextWeekFocus" are deliberately empty: the narrator
     * picks those out of the prose. Engine should not editorialise.
     */
    suspend fun computeWeeklyReport(now: Instant = clock.instant()): WeeklyReport {
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)
        val fourteenDaysAgo = now.minus(14, ChronoUnit.DAYS)
        val s = settings.flow.first()

        val recentBp = bp.observeRange(sevenDaysAgo, now).first()
        val priorBp = bp.observeRange(fourteenDaysAgo, sevenDaysAgo).first()

        val sbpMean = if (recentBp.isEmpty()) 0.0
            else recentBp.map { it.systolic.toDouble() }.average()
        val sbpDelta = if (priorBp.isEmpty() || recentBp.isEmpty()) 0.0
            else sbpMean - priorBp.map { it.systolic.toDouble() }.average()

        val recentExercise = exercise.observeRange(sevenDaysAgo, now).first()
        val aerobicMin = recentExercise.sumOf {
            ((it.endedAt.toEpochMilli() - it.startedAt.toEpochMilli()) / 60_000L).toInt()
        }

        val sleepRows = coachRepo.sleepRange(sevenDaysAgo.toEpochMilli(), now.toEpochMilli())
        val sleepMeanH = if (sleepRows.isEmpty()) 0.0
            else sleepRows.map { it.durationMin }.average() / 60.0

        val dietRows = coachRepo.dietRange(sevenDaysAgo.toEpochMilli(), now.toEpochMilli())
        val sodiumDaysOver = dietRows.count { it.sodiumLevelRaw == "high" }

        val medAdherence = coachRepo.medicationAdherence(
            sevenDaysAgo.toEpochMilli(),
            now.toEpochMilli(),
        )

        return WeeklyReport(
            weekStartMillis = currentWeekStart().atStartOfDay(zone).toInstant().toEpochMilli(),
            sbpMean = sbpMean,
            sbpDelta = sbpDelta,
            aerobicMin = aerobicMin,
            aerobicTarget = s.weeklyAerobicMinTarget,
            sleepMeanH = sleepMeanH,
            sodiumDaysOver = sodiumDaysOver,
            medAdherence = medAdherence,
            highlights = emptyList(),
            nextWeekFocus = emptyList(),
        )
    }

    suspend fun generateWeeklyPlan(weekStart: LocalDate = currentWeekStart()): CoachPlan {
        val weekStartInstant = weekStart.atStartOfDay(zone).toInstant()
        val weekStartMillis = weekStartInstant.toEpochMilli()
        val now = clock.instant()
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)

        val recentBp = bp.observeRange(sevenDaysAgo, now).first().sortedBy { it.timestamp }
        val recentExercise = exercise.observeRange(sevenDaysAgo, now).first()
        val priorPlans = coachRepo.recentPlans(2)
        val s = settings.flow.first()

        val recovery = needsRecoveryDay(recentBp)
        val intensityCap = intensityCapFor(recentBp)
        val phase = derivePhase(priorPlans)

        val planId = UUID.randomUUID().toString()
        val tasks = buildTasks(
            planId = planId,
            phase = phase,
            intensityCap = intensityCap,
            recoveryDay = recovery,
            recentExercise = recentExercise,
            settings = s,
        )

        val goals = buildGoals(s, phase)

        return CoachPlan(
            id = planId,
            weekStartMillis = weekStartMillis,
            generatedAtMillis = now.toEpochMilli(),
            ruleVersion = RULE_VERSION,
            phase = phase,
            goals = goals,
            tasks = tasks,
        )
    }

    /**
     * Phase progression based on last week's adherence:
     *  - first plan ever → Baseline
     *  - lastAdherence < 0.5 → DeRamp (reduce volume 30%)
     *  - lastAdherence < 0.8 → Hold (same volume)
     *  - lastAdherence ≥ 0.8 for 2 weeks AND not Hold → Ramp (+10–15%)
     *
     * Adherence is computed across [priorPlans]; we look at the most recent
     * fully-elapsed plan only.
     */
    private suspend fun derivePhase(priorPlans: List<CoachPlan>): Phase {
        if (priorPlans.isEmpty()) return Phase.Baseline
        val mostRecent = priorPlans.first()
        val priorAdherence = adherenceRatio(mostRecent.id)
        return when {
            priorAdherence < 0.5f -> Phase.DeRamp
            priorAdherence < 0.8f -> Phase.Hold
            priorPlans.size >= 2 && mostRecent.phase != Phase.Hold -> Phase.Ramp
            else -> Phase.Hold
        }
    }

    private suspend fun adherenceRatio(planId: String): Float {
        val rows = coachRepo.adherenceForPlan(planId)
        if (rows.isEmpty()) return 0f
        var done = 0
        var total = 0
        for (a in rows) {
            done += a.completed
            total += a.scheduled
        }
        return if (total == 0) 0f else done.toFloat() / total
    }

    private fun needsRecoveryDay(recent: List<BpReading>): Boolean {
        // Any reading in the last 48h ≥180/110 → today is a recovery day.
        val cutoff = clock.instant().minus(48, ChronoUnit.HOURS).toEpochMilli()
        return recent.any { it.timestamp.toEpochMilli() >= cutoff && it.isCritical() }
    }

    private fun intensityCapFor(recent: List<BpReading>): TaskIntensity {
        if (recent.isEmpty()) return TaskIntensity.Moderate
        val sysMean = recent.map { it.systolic }.average()
        return if (sysMean >= 160.0) TaskIntensity.Light else TaskIntensity.Moderate
    }

    private fun buildTasks(
        planId: String,
        phase: Phase,
        intensityCap: TaskIntensity,
        recoveryDay: Boolean,
        recentExercise: List<ExerciseSession>,
        settings: UserSettings,
    ): List<CoachTask> {
        val out = mutableListOf<CoachTask>()
        val perSessionMin = perSessionMinutes(phase, settings)

        // 5-day aerobic schedule: Mon/Tue/Thu/Fri/Sun.
        val aerobicDays = setOf(0, 1, 3, 4, 6)
        val intensity = if (recoveryDay) TaskIntensity.Rest else intensityCap

        for (offset in 0..6) {
            val safety = recoveryDay && offset == 0
            // Exercise — only on aerobic days (or rest banner on offset 0 if recovery)
            if (offset in aerobicDays || safety) {
                out += CoachTask(
                    planId = planId,
                    dayOffset = offset,
                    module = LifestyleModule.Exercise,
                    title = if (safety) "今日休息,聯絡醫師" else "散步 $perSessionMin 分鐘",
                    targetValue = if (safety) null else perSessionMin.toDouble(),
                    targetUnit = if (safety) null else "min",
                    intensity = intensity,
                    safetyHold = safety,
                )
            }
            // Diet — daily reminder; user logs sodium level via the diet sub-route.
            out += CoachTask(
                planId = planId,
                dayOffset = offset,
                module = LifestyleModule.Diet,
                title = "鈉攝取 < ${settings.dailySodiumTargetMg} 毫克",
                targetValue = settings.dailySodiumTargetMg.toDouble(),
                targetUnit = "mg",
                intensity = TaskIntensity.Light,
            )
            // Sleep — daily target.
            out += CoachTask(
                planId = planId,
                dayOffset = offset,
                module = LifestyleModule.Sleep,
                title = "睡眠 ${settings.targetSleepHours} 小時",
                targetValue = settings.targetSleepHours.toDouble(),
                targetUnit = "h",
                intensity = TaskIntensity.Light,
            )
            // Medication — daily, value = scheduled doses (PR2 placeholder of 1).
            out += CoachTask(
                planId = planId,
                dayOffset = offset,
                module = LifestyleModule.Medication,
                title = "依時服藥",
                targetValue = 1.0,
                targetUnit = "doses",
                intensity = TaskIntensity.Light,
            )
        }
        return out
    }

    private fun perSessionMinutes(phase: Phase, settings: UserSettings): Int {
        // 5 sessions/week → split target across days, then phase-modulate.
        val perWeek = settings.weeklyAerobicMinTarget
        val perDay = (perWeek / 5).coerceAtLeast(15)
        return when (phase) {
            Phase.Baseline -> minOf(perDay, 18)   // first week — cap to ease people in
            Phase.DeRamp   -> (perDay * 0.7).toInt().coerceAtLeast(15)
            Phase.Hold     -> perDay
            Phase.Ramp     -> (perDay * 1.1).toInt()
        }
    }

    private fun buildGoals(settings: UserSettings, phase: Phase): List<CoachGoal> {
        val volumeFactor = when (phase) {
            Phase.Baseline -> 0.6
            Phase.DeRamp -> 0.7
            Phase.Hold -> 1.0
            Phase.Ramp -> 1.1
        }
        return listOf(
            CoachGoal(
                module = LifestyleModule.Exercise,
                targetValue = (settings.weeklyAerobicMinTarget * volumeFactor),
                targetUnit = "min",
                rationaleKey = "coach_rationale_aerobic_who",
            ),
            CoachGoal(
                module = LifestyleModule.Diet,
                targetValue = settings.dailySodiumTargetMg.toDouble(),
                targetUnit = "mg",
                rationaleKey = "coach_rationale_sodium_aha",
            ),
            CoachGoal(
                module = LifestyleModule.Sleep,
                targetValue = settings.targetSleepHours.toDouble(),
                targetUnit = "h",
                rationaleKey = "coach_rationale_sleep_nsf",
            ),
            CoachGoal(
                module = LifestyleModule.Medication,
                targetValue = 7.0,
                targetUnit = "doses",
                rationaleKey = "coach_rationale_med_adherence",
            ),
        )
    }

    private fun currentWeekStart(): LocalDate {
        val today = LocalDate.now(clock)
        return today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    }

    companion object {
        const val RULE_VERSION = 1
        private const val ANOMALY_MIN_CONSECUTIVE = 3
    }
}
