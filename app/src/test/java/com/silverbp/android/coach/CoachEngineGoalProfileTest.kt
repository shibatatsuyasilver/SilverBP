package com.silverbp.android.coach

import com.silverbp.android.settings.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 goal-profile plumbing into [CoachEngine] plan generation.
 *
 * These exercise the pure rule helpers that [CoachEngine.generateWeeklyPlan]
 * delegates to ([CoachEngine.aerobicDaysFor] decides the exercise-day set,
 * [CoachEngine.startingPhaseFor] the first-week phase, [CoachEngine.aerobicEmphasis]
 * the aerobic-vs-strength balance). Driving the full engine would require a
 * Context-backed UserSettingsRepository + Room DAOs; the helpers are the
 * deterministic seam, matching the existing coach unit-test style.
 */
class CoachEngineGoalProfileTest {

    // --- weeklyAvailabilityDays → number of exercise days/week ---

    @Test
    fun `aerobic day count matches weeklyAvailabilityDays`() {
        for (days in 2..6) {
            assertEquals(
                "expected $days exercise days for availability=$days",
                days,
                CoachEngine.aerobicDaysFor(days).size,
            )
        }
    }

    @Test
    fun `unset availability falls back to the 5-day default`() {
        assertEquals(setOf(0, 1, 3, 4, 6), CoachEngine.aerobicDaysFor(0))
    }

    @Test
    fun `availability is clamped to a safe range`() {
        assertEquals(2, CoachEngine.aerobicDaysFor(1).size)   // below floor → 2
        assertEquals(6, CoachEngine.aerobicDaysFor(9).size)   // above ceiling → 6
    }

    @Test
    fun `scheduled days stay within Mon-Sun and do not collide`() {
        for (days in 2..6) {
            val set = CoachEngine.aerobicDaysFor(days)
            assertEquals("no collisions for $days", days, set.size)
            assertTrue("offsets in 0..6 for $days", set.all { it in 0..6 })
        }
    }

    // --- experienceLevel → starting phase (beginner starts gentler) ---

    @Test
    fun `beginner starts at Baseline (gentler)`() {
        val s = UserSettings(experienceLevel = "beginner")
        assertEquals(Phase.Baseline, CoachEngine.startingPhaseFor(s))
    }

    @Test
    fun `unset experience defaults to Baseline`() {
        assertEquals(Phase.Baseline, CoachEngine.startingPhaseFor(UserSettings()))
    }

    @Test
    fun `advanced skips the easing cap and starts at Hold`() {
        val s = UserSettings(experienceLevel = "advanced")
        assertEquals(Phase.Hold, CoachEngine.startingPhaseFor(s))
    }

    // --- trainingStyle → aerobic-vs-strength emphasis ---

    @Test
    fun `cardio focus boosts aerobic emphasis above strength focus`() {
        val cardio = CoachEngine.aerobicEmphasis("cardio_focus")
        val strength = CoachEngine.aerobicEmphasis("strength_focus")
        assertTrue("cardio ($cardio) should exceed strength ($strength)", cardio > strength)
    }

    @Test
    fun `unset training style keeps neutral emphasis`() {
        assertEquals(1.0, CoachEngine.aerobicEmphasis(""), 0.0)
    }
}
