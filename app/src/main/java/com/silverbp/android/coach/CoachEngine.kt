package com.silverbp.android.coach

import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.settings.ExperienceLevel
import com.silverbp.android.settings.TrainingStyle
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

    /**
     * Instance wrapper over [Companion.shouldAllowWorkout] using the engine clock.
     * Pure logic lives in the companion so it is unit-testable without the full
     * repository chain (mirrors [aerobicDaysFor] / [startingPhaseFor]).
     */
    fun shouldAllowWorkout(
        recentBp: List<BpReading>,
        now: Instant = clock.instant(),
    ): WorkoutBpGate = Companion.shouldAllowWorkout(recentBp, now)

    /** Instance wrapper over [Companion.computeWorkoutIntensity] using the engine clock. */
    fun computeWorkoutIntensity(
        recentBp: List<BpReading>,
        lastWorkoutPostBp: BpReading?,
        phase: Phase,
    ): TaskIntensity =
        Companion.computeWorkoutIntensity(recentBp, lastWorkoutPostBp, phase, clock.instant())

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
            (it.activeDurationMillis / 60_000L).toInt()
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
        val phase = derivePhase(priorPlans, s)

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
     *  - first plan ever → [startingPhaseFor] (experience-aware: beginners ease
     *    in at Baseline; advanced users skip the easing cap and start at Hold)
     *  - lastAdherence < 0.5 → DeRamp (reduce volume 30%)
     *  - lastAdherence < 0.8 → Hold (same volume)
     *  - lastAdherence ≥ 0.8 for 2 weeks AND not Hold → Ramp (+10–15%)
     *
     * Adherence is computed across [priorPlans]; we look at the most recent
     * fully-elapsed plan only, and over its Exercise tasks only (see
     * [exerciseAdherenceRatio]).
     */
    private suspend fun derivePhase(priorPlans: List<CoachPlan>, settings: UserSettings): Phase {
        if (priorPlans.isEmpty()) return startingPhaseFor(settings)
        val mostRecent = priorPlans.first()
        val priorAdherence = exerciseAdherenceRatio(coachRepo.adherenceForPlan(mostRecent.id))
        return phaseFor(priorAdherence, priorPlans.size, mostRecent.phase)
    }

    private fun needsRecoveryDay(recent: List<BpReading>): Boolean {
        // Any reading in the last 48h ≥180/110 → today is a recovery day. Wider
        // window than [shouldAllowWorkout]'s 24h Block by design (post-crisis
        // recovery banner persists an extra day), so kept separate but using the
        // same [isCritical] threshold for one source of truth.
        val cutoff = clock.instant().minus(48, ChronoUnit.HOURS).toEpochMilli()
        return recent.any { it.timestamp.toEpochMilli() >= cutoff && it.isCritical() }
    }

    private fun intensityCapFor(recent: List<BpReading>): TaskIntensity =
        // Delegate to the single source of truth. Hold's Moderate baseline keeps
        // the historical cap; BP only ever pulls it down (at least as safe).
        computeWorkoutIntensity(recent, lastWorkoutPostBp = null, phase = Phase.Hold)

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

        // Aerobic schedule — number of days derives from the user's onboarding
        // availability (falls back to 5 days when unset).
        val aerobicDays = aerobicDaysFor(settings.weeklyAvailabilityDays)
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
        // Split the (style-weighted) weekly target across the scheduled days,
        // then phase-modulate.
        val sessions = aerobicDaysFor(settings.weeklyAvailabilityDays).size
        val perWeek = (settings.weeklyAerobicMinTarget * aerobicEmphasis(settings.trainingStyle)).toInt()
        val perDay = (perWeek / sessions).coerceAtLeast(15)
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
                targetValue = (settings.weeklyAerobicMinTarget * volumeFactor * aerobicEmphasis(settings.trainingStyle)),
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

        // BP safety thresholds — single source of truth (see class KDoc for sources).
        private const val CRISIS_SBP = 180   // hypertensive crisis (exercise-stop)
        private const val CRISIS_DBP = 110
        private const val STAGE2_SBP = 160   // ≥3-consecutive "elevated" floor
        private const val STAGE2_DBP = 100
        private const val STAGE1_SBP = 140   // latest-reading caution floor
        private const val STAGE1_DBP = 90
        private const val SBP_MEAN_CAP = 160.0          // 7-day SBP mean caution cap
        private const val POST_EXERCISE_SPIKE_SBP = 200 // post-session spike → Light

        private fun BpReading.isElevated(): Boolean = systolic >= STAGE2_SBP || diastolic >= STAGE2_DBP
        private fun BpReading.isCritical(): Boolean = systolic >= CRISIS_SBP || diastolic >= CRISIS_DBP

        /**
         * BP-aware workout gate — the single safety entry point the UI calls
         * before starting a session. Conservative by construction: missing data
         * yields [WorkoutBpGate.Caution] (measure first), never a silent allow.
         * Reasons are Traditional-Chinese, user-facing strings (the only Chinese
         * the engine emits, intentionally — a safety message, not a target).
         *
         * Thresholds reuse the same [CRISIS_SBP]/[STAGE2_SBP] constants as
         * [detectAnomaly] / [intensityCapFor] so there is one source of truth.
         */
        fun shouldAllowWorkout(recentBp: List<BpReading>, now: Instant): WorkoutBpGate {
            if (recentBp.isEmpty()) {
                return WorkoutBpGate.Caution("建議先量一次血壓再開始")
            }
            val last24h = recentBp
                .filter { it.timestamp >= now.minus(24, ChronoUnit.HOURS) }
                .sortedBy { it.timestamp }

            // BLOCK — hypertensive crisis in the last 24h.
            if (last24h.any { it.isCritical() }) {
                return WorkoutBpGate.Block("血壓過高(≥180/110),今天請先休息")
            }

            // CAUTION — 7-day SBP mean high, 3 consecutive elevated, or latest stage-2+.
            val sbpMeanHigh = recentBp.map { it.systolic.toDouble() }.average() >= SBP_MEAN_CAP
            val threeConsecutiveElevated = hasConsecutiveElevated(last24h)
            val latestStage2 = recentBp.maxByOrNull { it.timestamp }?.let {
                it.systolic >= STAGE1_SBP || it.diastolic >= STAGE1_DBP
            } == true
            if (sbpMeanHigh || threeConsecutiveElevated || latestStage2) {
                return WorkoutBpGate.Caution("血壓偏高,建議降低強度")
            }

            return WorkoutBpGate.Allow
        }

        /**
         * Single source of truth for the intensity of an exercise task. Starts
         * from the phase's normal intensity, then only ever caps it *down*:
         *  - [WorkoutBpGate.Block] → [TaskIntensity.Rest]
         *  - [WorkoutBpGate.Caution] → [TaskIntensity.Light]
         *  - a large post-exercise spike on the last logged session
         *    (systolic > [POST_EXERCISE_SPIKE_SBP]) → at most [TaskIntensity.Light]
         * Never raises above the phase baseline.
         */
        fun computeWorkoutIntensity(
            recentBp: List<BpReading>,
            lastWorkoutPostBp: BpReading?,
            phase: Phase,
            now: Instant,
        ): TaskIntensity {
            var intensity = phaseBaselineIntensity(phase)
            when (shouldAllowWorkout(recentBp, now)) {
                is WorkoutBpGate.Block -> intensity = capDown(intensity, TaskIntensity.Rest)
                is WorkoutBpGate.Caution -> intensity = capDown(intensity, TaskIntensity.Light)
                WorkoutBpGate.Allow -> Unit
            }
            if (lastWorkoutPostBp != null && lastWorkoutPostBp.systolic > POST_EXERCISE_SPIKE_SBP) {
                intensity = capDown(intensity, TaskIntensity.Light)
            }
            return intensity
        }

        /** Phase's normal (unconstrained-by-BP) intensity baseline. */
        private fun phaseBaselineIntensity(phase: Phase): TaskIntensity = when (phase) {
            Phase.Baseline -> TaskIntensity.Light   // first week — ease in
            Phase.DeRamp -> TaskIntensity.Moderate
            Phase.Hold -> TaskIntensity.Moderate
            Phase.Ramp -> TaskIntensity.Moderate
        }

        /** Lower of two intensities by severity (Rest < Light < Moderate < Vigorous). */
        private fun capDown(current: TaskIntensity, cap: TaskIntensity): TaskIntensity =
            if (cap.ordinal < current.ordinal) cap else current

        /** ≥3 consecutive elevated readings in the supplied (sorted) window. */
        private fun hasConsecutiveElevated(sorted: List<BpReading>): Boolean {
            if (sorted.size < ANOMALY_MIN_CONSECUTIVE) return false
            for (i in 0..sorted.size - ANOMALY_MIN_CONSECUTIVE) {
                if (sorted.subList(i, i + ANOMALY_MIN_CONSECUTIVE).all { it.isElevated() }) return true
            }
            return false
        }

        /**
         * Day offsets (0 = Mon … 6 = Sun) on which an aerobic task is scheduled.
         * [availabilityDays] is the onboarding preference; 0 (unset) keeps the
         * historical 5-day cadence. Values are clamped to a safe 2..6 range and
         * the days are spread evenly across the week so sessions don't bunch up.
         */
        fun aerobicDaysFor(availabilityDays: Int): Set<Int> {
            if (availabilityDays <= 0) return setOf(0, 1, 3, 4, 6) // unset → 5-day default
            val n = availabilityDays.coerceIn(2, 6)
            // Even spread: pick n offsets from 0..6 at proportional positions.
            return (0 until n)
                .map { (it * 7 / n) }
                .toSet()
        }

        /**
         * Aerobic-volume multiplier driven by [TrainingStyle]. Strength tasks
         * aren't wired yet (later phase), so a strength-leaning user simply gets
         * a lighter aerobic target rather than new task types; cardio-focused
         * users get more. Unknown / unset → 1.0 (no change).
         */
        fun aerobicEmphasis(trainingStyleRaw: String): Double =
            when (TrainingStyle.fromRaw(trainingStyleRaw)) {
                TrainingStyle.CardioFocus -> 1.2
                TrainingStyle.StrengthFocus -> 0.8
                else -> 1.0
            }

        /**
         * Last week's adherence over the Exercise rows ONLY — the module the
         * phase actually ramps. Diet/Sleep tasks never get `completedAt` set
         * (their rings are derived from the logged rows themselves, see
         * [CoachRepository.observeWeeklyLifestyleLogs]), so an all-module ratio
         * tops out around 5/26 and locked every plan into [Phase.DeRamp] no
         * matter how adherent the user was.
         */
        fun exerciseAdherenceRatio(rows: List<Adherence>): Float {
            var done = 0
            var total = 0
            for (a in rows) {
                if (a.module != LifestyleModule.Exercise) continue
                done += a.completed
                total += a.scheduled
            }
            return if (total == 0) 0f else done.toFloat() / total
        }

        /**
         * Pure phase-progression rule applied to a non-first week — see
         * [derivePhase]'s KDoc for the thresholds. Companion-level so it is
         * unit-testable without the repository chain.
         */
        fun phaseFor(priorAdherence: Float, priorPlanCount: Int, mostRecentPhase: Phase): Phase =
            when {
                priorAdherence < 0.5f -> Phase.DeRamp
                priorAdherence < 0.8f -> Phase.Hold
                priorPlanCount >= 2 && mostRecentPhase != Phase.Hold -> Phase.Ramp
                else -> Phase.Hold
            }

        /**
         * First-ever plan phase, biased by [ExperienceLevel]: beginners (or
         * unset) ease in at [Phase.Baseline] (volume-capped); advanced users
         * skip the easing cap and start at [Phase.Hold].
         */
        fun startingPhaseFor(settings: UserSettings): Phase =
            when (ExperienceLevel.fromRaw(settings.experienceLevel)) {
                ExperienceLevel.Advanced -> Phase.Hold
                else -> Phase.Baseline
            }
    }
}

/**
 * Result of [CoachEngine.shouldAllowWorkout]. [reason] is an optional
 * user-facing Traditional-Chinese string the UI surfaces verbatim ([Allow]
 * carries none). Read it via `gate.reason`; branch on the subtype:
 *  - [Allow]   — clear to start at the planned intensity.
 *  - [Caution] — let the user start but downgrade to Light / suggest measuring.
 *  - [Block]   — do NOT start a session today (hypertensive crisis).
 */
sealed interface WorkoutBpGate {
    val reason: String?

    object Allow : WorkoutBpGate {
        override val reason: String? = null
    }

    data class Caution(override val reason: String) : WorkoutBpGate

    data class Block(override val reason: String) : WorkoutBpGate
}
