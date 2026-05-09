package com.silverbp.android.coach

import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.recognition.chat.ChatRecognizer
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Live, per-page generator for the Coach tab's "今日任務" headline.
 *
 * **Safety contract** (matches [CoachNarrator]): the LLM must NOT decide
 * minutes, intensity, kind, or whether today is a rest day. Those come from
 * [CoachEngine] inside [baseTask]. The LLM only re-words the [CoachTask.title]
 * so the headline references the user's recent exercise records (yesterday's
 * walk, weekly progress, last-walked-N-days-ago) and feels personal.
 *
 * If the chat backend isn't ready, the response is malformed JSON, the call
 * times out, or [baseTask.safetyHold] is set, we fall back to the deterministic
 * [baseTask.title] ("散步 X 分鐘") so the screen never blocks on the LLM.
 */
class TodayExerciseTaskGenerator(
    private val summaryProvider: RecentExerciseSummaryProvider,
    private val chatFactory: suspend () -> ChatRecognizer,
) {

    suspend fun generate(
        plan: CoachPlan,
        baseTask: CoachTask,
        settings: UserSettings,
    ): TodayTaskOverlay {
        if (baseTask.safetyHold) {
            return TodayTaskOverlay(
                title = baseTask.title,
                subtitle = null,
                isLlmGenerated = false,
            )
        }

        val summary = runCatching { summaryProvider.get(settings) }.getOrNull()
            ?: return fallback(baseTask)

        val recognizer = runCatching { chatFactory() }.getOrNull()
        if (recognizer == null || !recognizer.isReady()) {
            return fallback(baseTask)
        }

        val raw = runCatching {
            val sys = ChatMessage(role = ChatMessage.Role.System, text = systemPrompt(baseTask))
            val user = ChatMessage(role = ChatMessage.Role.User, text = userPrompt(baseTask, summary))
            val sb = StringBuilder()
            recognizer.chat(listOf(sys, user)).collect { delta -> sb.append(delta) }
            sb.toString()
        }.getOrNull() ?: return fallback(baseTask)

        val parsed = parseJson(raw) ?: return fallback(baseTask)
        val title = parsed.title.trim().takeIf { it.isNotEmpty() } ?: return fallback(baseTask)
        val subtitle = parsed.subtitle?.trim()?.takeIf { it.isNotEmpty() }
        return TodayTaskOverlay(
            title = title,
            subtitle = subtitle,
            isLlmGenerated = true,
        )
    }

    private fun fallback(baseTask: CoachTask): TodayTaskOverlay = TodayTaskOverlay(
        title = baseTask.title,
        subtitle = null,
        isLlmGenerated = false,
    )

    private fun systemPrompt(baseTask: CoachTask): String = buildString {
        appendLine("你是 SilverBp 健康教練。根據使用者最近 7 天的運動紀錄,")
        appendLine("為今日的運動任務寫一個短標題,讓使用者覺得親切、想立刻去做。")
        appendLine()
        appendLine("固定條件 (絕對不可更動):")
        appendLine("- 運動類型: ${baseTask.module.raw}")
        baseTask.targetValue?.let { v ->
            appendLine("- 目標時間: ${v.toInt()} 分鐘")
        }
        appendLine("- 強度: ${baseTask.intensity.raw}")
        appendLine()
        appendLine("只能改變【標題的措辭】,不可改變分鐘數、強度、類型,")
        appendLine("不可建議休息或省略運動,不可加入未提供的數字。")
        appendLine()
        appendLine("只輸出 JSON,不要 Markdown 也不要程式碼框:")
        appendLine("{\"title\": \"...\", \"subtitle\": \"...\"}")
        appendLine("- title ≤ 16 個中文字,口語、自然引用最近紀錄")
        append("- subtitle ≤ 24 個中文字,可省略 (空字串)")
    }

    private fun userPrompt(baseTask: CoachTask, s: RecentExerciseSummary): String = buildString {
        appendLine("# 最近 7 天運動紀錄")
        appendLine("- 有運動的天數: ${s.daysWithExerciseLast7}")
        appendLine("- 累計分鐘: ${s.totalMinutesLast7}")
        appendLine("- 距離上次運動: ${s.lastSessionMinutesAgo?.let { "${it / 60} 小時前" } ?: "從未紀錄"}")
        appendLine("- 上次運動類型: ${s.lastKind?.raw ?: "無"}")
        appendLine("- 本週目標: ${s.weeklyTargetMin} 分鐘 (已達 ${s.weeklyAchievedMin} 分鐘)")
        appendLine()
        appendLine("# 今日任務基礎資料")
        appendLine("- 預設標題: ${baseTask.title}")
        baseTask.targetValue?.let { v ->
            appendLine("- 目標分鐘: ${v.toInt()}")
        }
        append("- 強度: ${baseTask.intensity.raw}")
    }

    private fun parseJson(raw: String): TitleJson? {
        val cleaned = stripFence(raw).trim()
        if (cleaned.isEmpty()) return null
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            JSON.decodeFromString(TitleJson.serializer(), cleaned.substring(start, end + 1))
        }.getOrNull()
    }

    private fun stripFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```json").removePrefix("```").trimStart()
        val closeAt = withoutOpen.lastIndexOf("```")
        return if (closeAt >= 0) withoutOpen.substring(0, closeAt) else withoutOpen
    }

    @Serializable
    private data class TitleJson(
        val title: String = "",
        val subtitle: String? = null,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

data class TodayTaskOverlay(
    val title: String,
    val subtitle: String?,
    val isLlmGenerated: Boolean,
)

data class RecentExerciseSummary(
    val daysWithExerciseLast7: Int,
    val totalMinutesLast7: Int,
    val lastSessionMinutesAgo: Long?,
    val lastKind: ActivityKind?,
    val weeklyTargetMin: Int,
    val weeklyAchievedMin: Int,
) {
    companion object {
        /** Pure aggregation — extracted so it can be unit tested without a database. */
        fun from(
            sessions: List<ExerciseSession>,
            now: Instant,
            zone: ZoneId,
            weeklyTargetMin: Int,
        ): RecentExerciseSummary {
            val daysWithExercise = sessions
                .map { it.startedAt.atZone(zone).toLocalDate() }
                .distinct()
                .size
            val totalMinutes = sessions.sumOf { sessionMinutes(it) }
            val last = sessions.maxByOrNull { it.endedAt }
            val lastSessionMinutesAgo = last?.let {
                Duration.between(it.endedAt, now).toMinutes().coerceAtLeast(0)
            }
            return RecentExerciseSummary(
                daysWithExerciseLast7 = daysWithExercise,
                totalMinutesLast7 = totalMinutes,
                lastSessionMinutesAgo = lastSessionMinutesAgo,
                lastKind = last?.kind,
                weeklyTargetMin = weeklyTargetMin,
                weeklyAchievedMin = totalMinutes,
            )
        }

        private fun sessionMinutes(s: ExerciseSession): Int =
            Duration.between(s.startedAt, s.endedAt).toMinutes().toInt().coerceAtLeast(0)
    }
}

fun interface RecentExerciseSummaryProvider {
    suspend fun get(settings: UserSettings): RecentExerciseSummary
}

/** Production implementation: reads the last 7 days from the exercise repository. */
class ExerciseRepoSummaryProvider(
    private val exerciseRepo: ExerciseRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : RecentExerciseSummaryProvider {
    override suspend fun get(settings: UserSettings): RecentExerciseSummary {
        val now = clock.instant()
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)
        val sessions = exerciseRepo.observeRange(sevenDaysAgo, now).first()
        return RecentExerciseSummary.from(sessions, now, zone, settings.weeklyAerobicMinTarget)
    }
}
