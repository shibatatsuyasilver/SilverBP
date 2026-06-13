package com.silverbp.android.coach

import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.BpReadingEntity
import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.core.member.MemberRepository
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
import java.time.Clock
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

    // --- BP-aware "measure first" hint (Phase 6) ---

    private val fixedNow = Instant.parse("2026-05-07T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"))

    @Test
    fun `high recent BP forces measure-first hint and overrides LLM subtitle`() = runTest {
        // A crisis-level reading in the last 24h → gate BLOCK → hint shown.
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("""{"title":"今天先 18 分鐘","subtitle":"從家門口開始"}""") },
            bp = fakeBpRepo(listOf(reading(systolic = 190, diastolic = 120, at = fixedNow))),
            clock = fixedClock,
            measureHint = { "先量血壓再開始" },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals("今天先 18 分鐘", result.title)
        assertEquals("先量血壓再開始", result.subtitle)
        assertTrue(result.isLlmGenerated)
    }

    @Test
    fun `no recent BP forces measure-first hint on fallback path`() = runTest {
        // Empty BP → gate CAUTION (measure first) → hint even when LLM not ready.
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("ignored", ready = false) },
            bp = fakeBpRepo(emptyList()),
            clock = fixedClock,
            measureHint = { "先量血壓再開始" },
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals(baseTask.title, result.title)
        assertEquals("先量血壓再開始", result.subtitle)
        assertFalse(result.isLlmGenerated)
    }

    @Test
    fun `normal recent BP keeps the LLM subtitle and adds no hint`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { emptySummary },
            chatFactory = { fakeRecognizer("""{"title":"今天先 18 分鐘","subtitle":"從家門口開始"}""") },
            bp = fakeBpRepo(listOf(reading(systolic = 120, diastolic = 78, at = fixedNow))),
            clock = fixedClock,
        )
        val result = gen.generate(plan, baseTask, settings)
        assertEquals("從家門口開始", result.subtitle)
    }

    @Test
    fun `high recent BP shows hint even on safety-hold task`() = runTest {
        val gen = TodayExerciseTaskGenerator(
            summaryProvider = { error("summary should not be requested for safety-hold task") },
            chatFactory = { error("chat should not be invoked for safety-hold task") },
            bp = fakeBpRepo(listOf(reading(systolic = 190, diastolic = 120, at = fixedNow))),
            clock = fixedClock,
            measureHint = { "先量血壓再開始" },
        )
        val result = gen.generate(plan, baseTask.copy(safetyHold = true), settings)
        assertEquals(baseTask.title, result.title)
        assertEquals("先量血壓再開始", result.subtitle)
        assertFalse(result.isLlmGenerated)
    }

    // --- Test helpers ---

    private fun reading(systolic: Int, diastolic: Int, at: Instant) = BpReading(
        systolic = systolic,
        diastolic = diastolic,
        timestamp = at,
    )

    private fun fakeBpRepo(readings: List<BpReading>): BpRepository =
        BpRepository(
            object : BpDao {
                // bpGateHint() now reads the owner-scoped overload (it resolves
                // the owner via BpRepository.ownerId()), so the filter lives here.
                override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<BpReadingEntity>> =
                    flowOf(
                        readings
                            .filter { it.timestamp.toEpochMilli() in from..to }
                            .map { it.toEntity() },
                    )

                override fun observeRange(from: Long, to: Long) = error("unused")
                override fun observeLatest() = error("unused")
                override fun observeAll() = error("unused")
                override fun observeLatest(memberId: String) = error("unused")
                override fun observeAll(memberId: String) = error("unused")
                override suspend fun count(memberId: String): Int = error("unused")
                override suspend fun findById(id: String): BpReadingEntity? = error("unused")
                override suspend fun insert(r: BpReadingEntity) = error("unused")
                override suspend fun update(r: BpReadingEntity) = error("unused")
                override suspend fun delete(id: String) = error("unused")
                override suspend fun count(): Int = error("unused")
                override suspend fun findUnmirrored(ownerId: String) = error("unused")
            },
            members = MemberRepository(FakeOwnerMemberDao()),
        )

    /** Minimal MemberDao that always resolves a single stable owner. */
    private class FakeOwnerMemberDao : MemberDao {
        private val owner = MemberEntity(
            id = "owner-test", displayName = "", isOwner = true, birthYear = null,
            hasDiabetes = false, hasCKD = false, hasASCVD = false, guideline = "taiwan2022",
            colorIndex = 0, sortOrder = 0, archived = false, createdAt = 0, updatedAt = 0,
        )
        override fun observeActive() = flowOf(listOf(owner))
        override suspend fun getAll() = listOf(owner)
        override suspend fun getOwner() = owner
        override suspend fun findById(id: String) = owner.takeIf { it.id == id }
        override suspend fun upsert(m: MemberEntity) = Unit
        override suspend fun archive(id: String, now: Long) = Unit
        override suspend fun unarchive(id: String, now: Long) = Unit
        override suspend fun updateSortOrder(id: String, sortOrder: Int, now: Long) = Unit
        override suspend fun count() = 1
        override suspend fun deleteById(id: String) = Unit
    }

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
