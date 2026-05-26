package com.silverbp.android.coach

import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.recognition.chat.ChatRecognizer
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class TodayExerciseTaskGeneratorTest {

    private val zone = ZoneId.of("Asia/Taipei")
    private val previousLocale: Locale = Locale.getDefault()

    @Before fun setUp() {
        // System prompts in CoachPrompts switch on Locale.getDefault();
        // pin to zh-TW for the existing assertions and override per-test for English.
        Locale.setDefault(Locale.TAIWAN)
    }

    @After fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    private val baseTask = CoachTask(
        id = "task-1",
        planId = "plan-1",
        dayOffset = 0,
        module = LifestyleModule.Exercise,
        title = "散步 18 分鐘",
        targetValue = 18.0,
        targetUnit = "min",
        intensity = TaskIntensity.Moderate,
        safetyHold = false,
    )

    private val plan = CoachPlan(
        id = "plan-1",
        weekStartMillis = 0L,
        generatedAtMillis = 0L,
        ruleVersion = 1,
        phase = Phase.Baseline,
        goals = emptyList(),
        tasks = listOf(baseTask),
    )

    private val settings = UserSettings()

    private val emptySummary = RecentExerciseSummary(
        daysWithExerciseLast7 = 0,
        totalMinutesLast7 = 0,
        lastSessionMinutesAgo = null,
        lastKind = null,
        weeklyTargetMin = settings.weeklyAerobicMinTarget,
        weeklyAchievedMin = 0,
    )

    @Test
    fun `safetyHold short-circuits the LLM and returns deterministic title`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { error("summary should not be requested for safety-hold task") },
            chatFactory = { error("chat should not be invoked for safety-hold task") },
        )
        val result = gen.generate(plan, baseTask.copy(safetyHold = true), settings)
        assertEquals(baseTask.title, result.title)
        assertNull(result.subtitle)
        assertFalse(result.isLlmGenerated)
    }

    @Test
    fun `valid JSON yields parsed title and subtitle`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("""{"title":"今天先 18 分鐘暖身","subtitle":"從家門口開始"}""") },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals("今天先 18 分鐘暖身", result.title)
        assertEquals("從家門口開始", result.subtitle)
        assertTrue(result.isLlmGenerated)
    }

    @Test
    fun `markdown fence around JSON is stripped`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = {
                fakeRecognizer("```json\n{\"title\":\"動起來\",\"subtitle\":\"\"}\n```")
            },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals("動起來", result.title)
        // Empty subtitle string should be normalised to null.
        assertNull(result.subtitle)
        assertTrue(result.isLlmGenerated)
    }

    @Test
    fun `malformed JSON falls back to deterministic title`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("definitely not json") },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals(baseTask.title, result.title)
        assertFalse(result.isLlmGenerated)
    }

    @Test
    fun `empty title falls back to deterministic title`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("""{"title":"   ","subtitle":"x"}""") },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals(baseTask.title, result.title)
        assertFalse(result.isLlmGenerated)
    }

    @Test
    fun `recognizer not ready falls back to deterministic title`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("ignored", ready = false) },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals(baseTask.title, result.title)
        assertFalse(result.isLlmGenerated)
    }

    @Test
    fun `chat throwing falls back to deterministic title`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { ThrowingRecognizer() },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals(baseTask.title, result.title)
        assertFalse(result.isLlmGenerated)
    }

    @Test
    fun `non-blank nickname is injected into system prompt`() = runTest {
        val capturer = CapturingRecognizer("""{"title":"先走 18 分鐘","subtitle":""}""")
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { capturer },
        )
        gen.generate(plan, baseTask, settings.copy(userNickname = "阿公"))
        val systemText = capturer.lastMessages
            .first { it.role == ChatMessage.Role.System }
            .text
        assertTrue(
            "system prompt should reference the nickname, was:\n$systemText",
            systemText.contains("阿公"),
        )
    }

    @Test
    fun `blank nickname does not add nickname instruction`() = runTest {
        val capturer = CapturingRecognizer("""{"title":"散步 18 分鐘","subtitle":""}""")
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { capturer },
        )
        gen.generate(plan, baseTask, settings.copy(userNickname = ""))
        val systemText = capturer.lastMessages
            .first { it.role == ChatMessage.Role.System }
            .text
        assertFalse(
            "blank nickname should not produce the 「」 quoting in the system prompt:\n$systemText",
            systemText.contains("使用者希望被稱為"),
        )
    }

    @Test
    fun `english locale produces english system prompt`() = runTest {
        Locale.setDefault(Locale.ENGLISH)
        val capturer = CapturingRecognizer("""{"title":"Walk 18 min","subtitle":""}""")
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { capturer },
        )
        gen.generate(plan, baseTask, settings)
        val systemText = capturer.lastMessages
            .first { it.role == ChatMessage.Role.System }
            .text
        assertTrue(
            "english system prompt should mention 'health coach', was:\n$systemText",
            systemText.contains("health coach"),
        )
        assertFalse(
            "english system prompt must not include the zh persona, was:\n$systemText",
            systemText.contains("健康教練"),
        )
    }

    @Test
    fun `summary aggregation - empty sessions`() {
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val s = RecentExerciseSummary.from(emptyList(), now, zone, weeklyTargetMin = 150)
        assertEquals(0, s.daysWithExerciseLast7)
        assertEquals(0, s.totalMinutesLast7)
        assertNull(s.lastSessionMinutesAgo)
        assertNull(s.lastKind)
        assertEquals(150, s.weeklyTargetMin)
        assertEquals(0, s.weeklyAchievedMin)
    }

    @Test
    fun `summary aggregation - one session yesterday`() {
        val now = Instant.parse("2026-05-07T10:00:00Z")
        val yesterdayStart = Instant.parse("2026-05-06T08:00:00Z")
        val yesterdayEnd = Instant.parse("2026-05-06T08:25:00Z")
        val sessions = listOf(
            session(yesterdayStart, yesterdayEnd, ActivityKind.Walking),
        )
        val s = RecentExerciseSummary.from(sessions, now, zone, weeklyTargetMin = 150)
        assertEquals(1, s.daysWithExerciseLast7)
        assertEquals(25, s.totalMinutesLast7)
        assertNotNull(s.lastSessionMinutesAgo)
        // ~26 hours ago in minutes.
        assertEquals(((26 * 60).toLong()) - 25, s.lastSessionMinutesAgo)
        assertEquals(ActivityKind.Walking, s.lastKind)
    }

    @Test
    fun `summary aggregation - multiple sessions across two days`() {
        val now = Instant.parse("2026-05-07T20:00:00Z")
        val sessions = listOf(
            session(
                Instant.parse("2026-05-05T07:00:00Z"),
                Instant.parse("2026-05-05T07:30:00Z"),
                ActivityKind.Walking,
            ),
            session(
                // Stay on 2026-05-05 in Asia/Taipei (UTC+8) — this is 16:00–16:15 local.
                Instant.parse("2026-05-05T08:00:00Z"),
                Instant.parse("2026-05-05T08:15:00Z"),
                ActivityKind.Walking,
            ),
            session(
                Instant.parse("2026-05-07T06:00:00Z"),
                Instant.parse("2026-05-07T06:40:00Z"),
                ActivityKind.Running,
            ),
        )
        val s = RecentExerciseSummary.from(sessions, now, zone, weeklyTargetMin = 150)
        assertEquals(2, s.daysWithExerciseLast7)
        assertEquals(30 + 15 + 40, s.totalMinutesLast7)
        assertEquals(ActivityKind.Running, s.lastKind)
    }

    // --- Test helpers ---

    private fun fakeRecognizer(response: String, ready: Boolean = true) = object : ChatRecognizer {
        override fun isReady(): Boolean = ready
        override fun supportsImages(): Boolean = false
        override fun chat(messages: List<ChatMessage>): Flow<String> = flowOf(response)
    }

    private class ThrowingRecognizer : ChatRecognizer {
        override fun isReady(): Boolean = true
        override fun supportsImages(): Boolean = false
        override fun chat(messages: List<ChatMessage>): Flow<String> = flow {
            throw RuntimeException("boom")
        }
    }

    private class CapturingRecognizer(private val response: String) : ChatRecognizer {
        var lastMessages: List<ChatMessage> = emptyList()
            private set

        override fun isReady(): Boolean = true
        override fun supportsImages(): Boolean = false
        override fun chat(messages: List<ChatMessage>): Flow<String> {
            lastMessages = messages
            return flowOf(response)
        }
    }

    private fun session(
        startedAt: Instant,
        endedAt: Instant,
        kind: ActivityKind,
    ): ExerciseSession = ExerciseSession(
        id = UUID.randomUUID(),
        kind = kind,
        startedAt = startedAt,
        endedAt = endedAt,
        activeDurationMillis = (endedAt.toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0L),
        distanceMeters = 0.0,
    )
}
