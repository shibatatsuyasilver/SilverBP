package com.silverbp.android.coach

import com.silverbp.android.R
import com.silverbp.android.core.BpReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Phase 6 BP-safety gate. Exercises the pure companion seam
 * [CoachEngine.shouldAllowWorkout] / [CoachEngine.computeWorkoutIntensity] —
 * the deterministic source of truth the watcher + UI call. Driving the full
 * engine would need a Context-backed UserSettingsRepository + Room DAOs; the
 * companion functions are the testable seam, matching the existing coach
 * unit-test style ([CoachEngineGoalProfileTest]).
 *
 * Safety-critical: when BP is dangerously high the gate must BLOCK / cap DOWN —
 * the boundary table below pins systolic 159/160/179/180 and diastolic
 * 99/100/109/110 so a future tweak can't silently loosen them.
 */
class CoachEngineWorkoutGateTest {

    private val now: Instant = Instant.parse("2026-06-03T08:00:00Z")

    private fun reading(
        systolic: Int,
        diastolic: Int,
        minutesAgo: Long = 30,
    ): BpReading = BpReading(
        systolic = systolic,
        diastolic = diastolic,
        timestamp = now.minus(minutesAgo, ChronoUnit.MINUTES),
    )

    private fun gate(vararg readings: BpReading): WorkoutBpGate =
        CoachEngine.shouldAllowWorkout(readings.toList(), now)

    // --- missing data: CAUTION, never silent-allow, never block ---

    @Test
    fun `empty readings yield Caution to measure first, not Allow or Block`() {
        val g = CoachEngine.shouldAllowWorkout(emptyList(), now)
        assertTrue("expected Caution for no data, got $g", g is WorkoutBpGate.Caution)
        assertEquals(R.string.coach_gate_measure_first, g.reasonRes)
    }

    // --- BLOCK boundary: crisis ≥180/110 in last 24h ---

    @Test
    fun `systolic boundary 179 vs 180 flips Caution to Block`() {
        // 179 is below crisis but ≥140 latest → Caution; 180 → Block.
        assertTrue(gate(reading(179, 105)) is WorkoutBpGate.Caution)
        val blocked = gate(reading(180, 105))
        assertTrue("180 systolic must Block, got $blocked", blocked is WorkoutBpGate.Block)
    }

    @Test
    fun `diastolic boundary 109 vs 110 flips Caution to Block`() {
        assertTrue(gate(reading(150, 109)) is WorkoutBpGate.Caution)
        val blocked = gate(reading(150, 110))
        assertTrue("110 diastolic must Block, got $blocked", blocked is WorkoutBpGate.Block)
    }

    @Test
    fun `crisis reading older than 24h does not Block`() {
        // 26h-old crisis falls outside the 24h Block window; a calm latest reading allows.
        val g = CoachEngine.shouldAllowWorkout(
            listOf(reading(190, 120, minutesAgo = 26 * 60), reading(118, 78)),
            now,
        )
        assertTrue("stale crisis must not Block, got $g", g !is WorkoutBpGate.Block)
    }

    // --- CAUTION boundary: latest reading ≥140/90 ---

    @Test
    fun `latest systolic boundary 139 Allows but 140 Cautions`() {
        assertEquals(WorkoutBpGate.Allow, gate(reading(139, 89)))
        assertTrue(gate(reading(140, 89)) is WorkoutBpGate.Caution)
    }

    @Test
    fun `latest diastolic boundary 89 Allows but 90 Cautions`() {
        assertEquals(WorkoutBpGate.Allow, gate(reading(135, 89)))
        assertTrue(gate(reading(135, 90)) is WorkoutBpGate.Caution)
    }

    // --- CAUTION: 7-day SBP mean ≥160 ---

    @Test
    fun `seven day SBP mean at 160 Cautions even when latest is calm`() {
        // Two old high readings raise the mean; latest is calm but mean ≥160 → Caution.
        val g = CoachEngine.shouldAllowWorkout(
            listOf(
                reading(170, 95, minutesAgo = 3 * 24 * 60),
                reading(175, 95, minutesAgo = 2 * 24 * 60),
                reading(135, 85),
            ),
            now,
        )
        assertTrue("mean ≥160 must Caution, got $g", g is WorkoutBpGate.Caution)
    }

    // --- CAUTION: 3 consecutive elevated (≥160/100) in 24h ---

    @Test
    fun `three consecutive elevated readings in 24h Caution`() {
        val g = gate(
            reading(162, 101, minutesAgo = 180),
            reading(165, 102, minutesAgo = 120),
            reading(168, 103, minutesAgo = 60),
        )
        assertTrue("3 consecutive elevated must Caution, got $g", g is WorkoutBpGate.Caution)
    }

    @Test
    fun `only two elevated readings does not trip the consecutive rule`() {
        // Two elevated + a calm latest, mean kept under 160 → Allow (no 3-in-a-row).
        val g = gate(
            reading(160, 100, minutesAgo = 180),
            reading(160, 100, minutesAgo = 120),
            reading(118, 76, minutesAgo = 60),
        )
        assertEquals("two elevated should not Caution", WorkoutBpGate.Allow, g)
    }

    // --- table-driven systolic/diastolic boundary matrix ---

    @Test
    fun `boundary matrix pins crisis and caution edges`() {
        data class Case(val sys: Int, val dia: Int, val expect: Class<out WorkoutBpGate>)
        val cases = listOf(
            Case(159, 99, WorkoutBpGate.Caution::class.java),  // ≥140 latest → Caution
            Case(160, 100, WorkoutBpGate.Caution::class.java), // stage-2, not crisis
            Case(179, 109, WorkoutBpGate.Caution::class.java), // just under crisis
            Case(180, 100, WorkoutBpGate.Block::class.java),   // crisis by systolic
            Case(160, 110, WorkoutBpGate.Block::class.java),   // crisis by diastolic
            Case(180, 110, WorkoutBpGate.Block::class.java),   // crisis both
        )
        for (c in cases) {
            val g = gate(reading(c.sys, c.dia))
            assertTrue(
                "sys=${c.sys} dia=${c.dia} expected ${c.expect.simpleName} got $g",
                c.expect.isInstance(g),
            )
        }
    }

    // --- computeWorkoutIntensity: caps DOWN, never above the phase baseline ---

    @Test
    fun `intensity never exceeds the phase baseline for any BP`() {
        val phases = Phase.entries
        val bpSets = listOf(
            emptyList(),                                  // CAUTION (no data)
            listOf(reading(115, 75)),                    // ALLOW (calm)
            listOf(reading(145, 92)),                    // CAUTION (latest stage-2)
            listOf(reading(185, 112)),                   // BLOCK (crisis)
        )
        for (phase in phases) {
            val baseline = CoachEngine.computeWorkoutIntensity(
                listOf(reading(115, 75)), null, phase, now,
            )
            for (bp in bpSets) {
                val got = CoachEngine.computeWorkoutIntensity(bp, null, phase, now)
                assertTrue(
                    "phase=$phase bp=$bp intensity $got exceeded baseline $baseline",
                    got.ordinal <= baseline.ordinal,
                )
            }
        }
    }

    @Test
    fun `crisis caps intensity to Rest regardless of phase`() {
        for (phase in Phase.entries) {
            assertEquals(
                "crisis must force Rest in $phase",
                TaskIntensity.Rest,
                CoachEngine.computeWorkoutIntensity(listOf(reading(185, 112)), null, phase, now),
            )
        }
    }

    @Test
    fun `caution caps intensity to at most Light`() {
        val got = CoachEngine.computeWorkoutIntensity(listOf(reading(145, 92)), null, Phase.Hold, now)
        assertEquals(TaskIntensity.Light, got)
    }

    @Test
    fun `calm BP keeps the phase baseline (Moderate for Hold)`() {
        val got = CoachEngine.computeWorkoutIntensity(listOf(reading(118, 76)), null, Phase.Hold, now)
        assertEquals(TaskIntensity.Moderate, got)
    }

    @Test
    fun `post-exercise spike over 200 caps a calm-BP plan to Light`() {
        val spike = reading(205, 95)
        val got = CoachEngine.computeWorkoutIntensity(
            listOf(reading(118, 76)), spike, Phase.Hold, now,
        )
        assertEquals(TaskIntensity.Light, got)
    }

    @Test
    fun `post-exercise systolic at exactly 200 does not cap (strict greater-than)`() {
        val got = CoachEngine.computeWorkoutIntensity(
            listOf(reading(118, 76)), reading(200, 95), Phase.Hold, now,
        )
        assertEquals(TaskIntensity.Moderate, got)
    }
}
