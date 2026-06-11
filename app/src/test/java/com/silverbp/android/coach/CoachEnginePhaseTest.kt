package com.silverbp.android.coach

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase progression for non-first weeks, via the pure companion seams
 * [CoachEngine.exerciseAdherenceRatio] + [CoachEngine.phaseFor] that
 * `derivePhase` delegates to (same approach as [CoachEngineGoalProfileTest]).
 *
 * Regression context: Diet/Sleep/Medication `coach_task` rows never get
 * `completedAt` set (their rings derive from logged rows), so computing
 * adherence across ALL modules capped the ratio around 5/26 and locked every
 * plan into [Phase.DeRamp] — adherence must come from the Exercise rows only.
 */
class CoachEnginePhaseTest {

    private val delta = 1e-4f

    /** A typical plan week: N exercise days plus the three daily never-completed modules. */
    private fun weekRows(exerciseDone: Int, exerciseScheduled: Int = 5) = listOf(
        Adherence(LifestyleModule.Exercise, completed = exerciseDone, scheduled = exerciseScheduled),
        Adherence(LifestyleModule.Diet, completed = 0, scheduled = 7),
        Adherence(LifestyleModule.Sleep, completed = 0, scheduled = 7),
        Adherence(LifestyleModule.Medication, completed = 0, scheduled = 7),
    )

    // --- exerciseAdherenceRatio: Exercise rows only ---

    @Test
    fun `ratio ignores the never-completed Diet Sleep Medication rows`() {
        // All-module ratio would be 5/26 ≈ 0.19 (the DeRamp-forever bug).
        assertEquals(1f, CoachEngine.exerciseAdherenceRatio(weekRows(exerciseDone = 5)), delta)
    }

    @Test
    fun `partial exercise adherence is the exercise fraction`() {
        assertEquals(0.6f, CoachEngine.exerciseAdherenceRatio(weekRows(exerciseDone = 3)), delta)
    }

    @Test
    fun `no rows at all yields zero`() {
        assertEquals(0f, CoachEngine.exerciseAdherenceRatio(emptyList()), delta)
    }

    @Test
    fun `non-exercise rows alone yield zero`() {
        val rows = weekRows(exerciseDone = 0).filter { it.module != LifestyleModule.Exercise }
        assertEquals(0f, CoachEngine.exerciseAdherenceRatio(rows), delta)
    }

    // --- phaseFor thresholds (<0.5 DeRamp, <0.8 Hold, ≥0.8 + 2 plans Ramp) ---

    @Test
    fun `fully adherent week ramps after two plans`() {
        val ratio = CoachEngine.exerciseAdherenceRatio(weekRows(exerciseDone = 5))
        assertEquals(Phase.Ramp, CoachEngine.phaseFor(ratio, priorPlanCount = 2, mostRecentPhase = Phase.Baseline))
    }

    @Test
    fun `fully adherent first week holds (needs two plans to ramp)`() {
        val ratio = CoachEngine.exerciseAdherenceRatio(weekRows(exerciseDone = 5))
        assertEquals(Phase.Hold, CoachEngine.phaseFor(ratio, priorPlanCount = 1, mostRecentPhase = Phase.Baseline))
    }

    @Test
    fun `fully adherent week stays Hold when the last phase was Hold`() {
        assertEquals(Phase.Hold, CoachEngine.phaseFor(1f, priorPlanCount = 2, mostRecentPhase = Phase.Hold))
    }

    @Test
    fun `partial adherence holds`() {
        val ratio = CoachEngine.exerciseAdherenceRatio(weekRows(exerciseDone = 3))
        assertEquals(Phase.Hold, CoachEngine.phaseFor(ratio, priorPlanCount = 2, mostRecentPhase = Phase.Baseline))
    }

    @Test
    fun `low adherence de-ramps`() {
        val ratio = CoachEngine.exerciseAdherenceRatio(weekRows(exerciseDone = 2))
        assertEquals(Phase.DeRamp, CoachEngine.phaseFor(ratio, priorPlanCount = 2, mostRecentPhase = Phase.Baseline))
    }

    @Test
    fun `threshold boundaries - exactly half holds, exactly point-eight ramps`() {
        assertEquals(Phase.Hold, CoachEngine.phaseFor(0.5f, priorPlanCount = 2, mostRecentPhase = Phase.Baseline))
        assertEquals(Phase.Ramp, CoachEngine.phaseFor(0.8f, priorPlanCount = 2, mostRecentPhase = Phase.Baseline))
    }
}
