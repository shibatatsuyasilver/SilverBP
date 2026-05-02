package com.silverbp.android.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementEvaluatorTest {

    private fun stats(
        today: Int = 0,
        lifetime: Long = 0,
        streak: Int = 0,
        sessions: Int = 0,
        goal: Int = 8000,
    ) = AchievementStats(today, lifetime, streak, sessions, goal)

    @Test
    fun `12k steps no prior unlocks all daily medals up to 10k`() {
        val out = AchievementEvaluator.evaluate(stats(today = 12_000), emptySet())
        assertEquals(
            listOf(
                MedalKind.DailySteps5k,
                MedalKind.DailySteps8k,
                MedalKind.DailySteps10k,
            ),
            out.filter { it.category == MedalCategory.DailySteps },
        )
    }

    @Test
    fun `idempotent — already-unlocked medals are not re-emitted`() {
        val already = setOf(MedalKind.DailySteps5k, MedalKind.DailySteps8k)
        val out = AchievementEvaluator.evaluate(stats(today = 12_000), already)
        assertEquals(listOf(MedalKind.DailySteps10k), out)
    }

    @Test
    fun `30-day streak fires streak7 and streak30`() {
        val out = AchievementEvaluator.evaluate(stats(streak = 30), emptySet())
        val streakMedals = out.filter { it.category == MedalCategory.Streak }
        assertEquals(listOf(MedalKind.Streak7, MedalKind.Streak30), streakMedals)
    }

    @Test
    fun `boundary — exactly threshold qualifies`() {
        val out = AchievementEvaluator.evaluate(stats(today = 5_000), emptySet())
        assertTrue(MedalKind.DailySteps5k in out)
        assertFalse(MedalKind.DailySteps8k in out)
    }

    @Test
    fun `boundary — one below threshold does NOT qualify`() {
        val out = AchievementEvaluator.evaluate(stats(today = 4_999), emptySet())
        assertFalse(MedalKind.DailySteps5k in out)
    }

    @Test
    fun `boundary — one above smallest threshold does qualify`() {
        val out = AchievementEvaluator.evaluate(stats(today = 5_001), emptySet())
        assertTrue(MedalKind.DailySteps5k in out)
    }

    @Test
    fun `zero everything emits nothing`() {
        val out = AchievementEvaluator.evaluate(stats(), emptySet())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `cumulative one million unlocks all four cumulative tiers`() {
        val out = AchievementEvaluator.evaluate(stats(lifetime = 1_000_000L), emptySet())
        val cumulative = out.filter { it.category == MedalCategory.Cumulative }
        assertEquals(
            listOf(
                MedalKind.Cumulative100k,
                MedalKind.Cumulative500k,
                MedalKind.Cumulative1M,
            ),
            cumulative,
        )
        assertFalse(MedalKind.Cumulative5M in cumulative)
    }

    @Test
    fun `10 sessions unlocks sessions1 and sessions10 only`() {
        val out = AchievementEvaluator.evaluate(stats(sessions = 10), emptySet())
        val sessions = out.filter { it.category == MedalCategory.Session }
        assertEquals(listOf(MedalKind.Sessions1, MedalKind.Sessions10), sessions)
    }

    @Test
    fun `progress is exactly 1 when stats reach threshold`() {
        val s = stats(today = 10_000)
        assertEquals(1f, AchievementEvaluator.progress(MedalKind.DailySteps10k, s), 1e-6f)
    }

    @Test
    fun `progress is half when stats reach half of threshold`() {
        val s = stats(today = 5_000)
        assertEquals(0.5f, AchievementEvaluator.progress(MedalKind.DailySteps10k, s), 1e-6f)
    }

    @Test
    fun `progress capped at 1 even when stats exceed threshold`() {
        val s = stats(lifetime = 10_000_000L)
        assertEquals(1f, AchievementEvaluator.progress(MedalKind.Cumulative1M, s), 1e-6f)
    }

    @Test
    fun `MedalKind kindRaw matches expected stable id format`() {
        assertEquals("daily.10000", MedalKind.DailySteps10k.kindRaw)
        assertEquals("cumulative.500000", MedalKind.Cumulative500k.kindRaw)
        assertEquals("streak.30", MedalKind.Streak30.kindRaw)
        assertEquals("session.100", MedalKind.Sessions100.kindRaw)
    }

    @Test
    fun `MedalKind fromRaw round-trips for every entry`() {
        MedalKind.entries.forEach { medal ->
            val recovered = MedalKind.fromRaw(medal.kindRaw)
            assertEquals(medal, recovered)
        }
    }

    @Test
    fun `byCategory returns medals sorted by ascending threshold`() {
        val daily = MedalKind.byCategory(MedalCategory.DailySteps)
        val thresholds = daily.map { it.threshold }
        assertEquals(thresholds, thresholds.sorted())
        assertEquals(5, daily.size)
    }
}
