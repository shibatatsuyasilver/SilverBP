package com.silverbp.android.coach

import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.chat.ChatRecognizerFactory
import com.silverbp.android.recognition.chat.GemmaLocalChatRecognizer
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.chat.RecordsContextBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * LLM-driven explanation layer over the rule-engine output.
 *
 * **Safety contract:** the narrator MUST NOT decide intensities, frequencies
 * or doses. Every system prompt below ends with an explicit "do not change
 * the plan" instruction. Callers feed it a typed [CoachPlan] / [WeeklyReport]
 * / [CoachEvent.Anomaly] produced by [CoachEngine] and the narrator only
 * paraphrases. If you find yourself adding rule logic here — stop, push it
 * down into [CoachEngine].
 *
 * Backend selection:
 *  - daily / anomaly: ≤256 tokens — honour user's [UserSettingsRepository.recognitionBackend].
 *  - weekly report: 800-token target. If the user chose AICore, silently
 *    fall back to Local Gemma (AICore caps maxOutputTokens at 256, which
 *    truncates the report mid-sentence).
 */
class CoachNarrator(
    private val recordsBuilder: RecordsContextBuilder = RecordsContextBuilder(),
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
) {

    fun narrateDailyTask(plan: CoachPlan, today: CoachTask): Flow<String> = flow {
        val recognizer = ChatRecognizerFactory.current()
        val records = recordsBuilder.build(forCoach = true)
        val sys = buildSystemPrompt(records, role = Role.Daily)
        val user = buildDailyPrompt(plan, today)
        emitAll(
            recognizer.chat(messagesOf(sys, user)),
        )
    }

    fun narrateWeeklyReport(report: WeeklyReport): Flow<String> = flow {
        val s = settings.flow.first()
        val recognizer = if (s.recognitionBackend == RecognitionBackend.AICore) {
            // AICore caps maxOutputTokens at 256 (see AICoreBpService line 164).
            // Silently swap to Local Gemma for this one call so the report
            // doesn't truncate mid-paragraph. Rest of the app keeps using AICore.
            GemmaLocalChatRecognizer()
        } else {
            ChatRecognizerFactory.current()
        }
        val records = recordsBuilder.build(forCoach = true)
        val sys = buildSystemPrompt(records, role = Role.Weekly)
        val user = buildWeeklyPrompt(report)
        emitAll(recognizer.chat(messagesOf(sys, user)))
    }

    fun narrateAnomalyTip(event: CoachEvent.Anomaly): Flow<String> = flow {
        val recognizer = ChatRecognizerFactory.current()
        val records = recordsBuilder.build(forCoach = true)
        val sys = buildSystemPrompt(records, role = Role.Anomaly)
        val user = buildAnomalyPrompt(event)
        emitAll(recognizer.chat(messagesOf(sys, user)))
    }

    private fun messagesOf(systemText: String, userText: String): List<ChatMessage> = listOf(
        ChatMessage(role = ChatMessage.Role.System, text = systemText),
        ChatMessage(role = ChatMessage.Role.User, text = userText),
    )

    private fun buildSystemPrompt(records: String, role: Role): String {
        val persona = when (role) {
            Role.Daily -> DAILY_PERSONA
            Role.Weekly -> WEEKLY_PERSONA
            Role.Anomaly -> ANOMALY_PERSONA
        }
        return buildString {
            appendLine(persona)
            appendLine()
            append(records)
            appendLine()
            appendLine()
            append(SAFETY_SUFFIX)
        }
    }

    private fun buildDailyPrompt(plan: CoachPlan, today: CoachTask): String = buildString {
        appendLine("# 今日任務 (請以親切口吻向使用者解釋,不超過三句)")
        appendLine("- 模組: ${today.module.raw}")
        appendLine("- 標題: ${today.title}")
        if (today.targetValue != null && today.targetUnit != null) {
            appendLine("- 目標: ${today.targetValue} ${today.targetUnit}")
        }
        appendLine("- 強度: ${today.intensity.raw}")
        if (today.safetyHold) appendLine("- ⚠ 今日為休息日,請提醒使用者聯絡醫師")
        appendLine("- 本週階段: ${plan.phase.raw}")
    }

    private fun buildWeeklyPrompt(r: WeeklyReport): String = buildString {
        appendLine("# 本週報告 (請以 3 段:回顧、亮點、下週重點)")
        appendLine("- 7 日 SBP 平均: ${"%.1f".format(r.sbpMean)} mmHg, 變化: ${"%+.1f".format(r.sbpDelta)}")
        appendLine("- 有氧運動: ${r.aerobicMin} / ${r.aerobicTarget} 分鐘")
        appendLine("- 睡眠平均: ${"%.1f".format(r.sleepMeanH)} 小時")
        appendLine("- 鈉攝取超標天數: ${r.sodiumDaysOver}")
        appendLine("- 服藥完成率: ${(r.medAdherence * 100).toInt()}%")
        if (r.highlights.isNotEmpty()) {
            appendLine("- 亮點: ${r.highlights.joinToString("; ")}")
        }
        if (r.nextWeekFocus.isNotEmpty()) {
            appendLine("- 下週重點: ${r.nextWeekFocus.joinToString("; ")}")
        }
    }

    private fun buildAnomalyPrompt(event: CoachEvent.Anomaly): String = buildString {
        appendLine("# 血壓警示 (請給 2 句安撫 + 1 個立刻可做的放鬆建議)")
        appendLine("- 嚴重度: ${event.severity.raw}")
        appendLine("- 最新讀數: ${event.latestSystolic}/${event.latestDiastolic} mmHg")
        appendLine("- 觸發於: ${Instant.ofEpochMilli(event.triggeredAtMillis)}")
        if (event.severity == Severity.Critical) {
            appendLine("- 嚴重等級為 Critical,請強烈建議立即聯絡醫師")
        }
    }

    private enum class Role { Daily, Weekly, Anomaly }

    companion object {
        private const val SAFETY_SUFFIX: String =
            "請勿建議任何劑量、強度或頻率變更;僅以你看到的數字解釋已決定的計畫。"

        private const val DAILY_PERSONA: String =
            "你是 SilverBp 的居家健康教練,語氣親切、用語簡單,像家人提醒長輩。" +
                "目標是讓今天的任務聽起來容易執行,並提供一個小動機。"

        private const val WEEKLY_PERSONA: String =
            "你是 SilverBp 的居家健康教練。請用三段格式撰寫本週報告:" +
                "(1) 回顧上週數據; (2) 一個值得鼓勵的亮點; (3) 下週重點與一個明確可做的小動作。" +
                "全程繁體中文,字數約 200–300 字,避免醫療術語。"

        private const val ANOMALY_PERSONA: String =
            "你是 SilverBp 的居家健康教練。語氣冷靜不驚慌,先肯定使用者願意量測," +
                "再給一個立刻可做的放鬆動作 (深呼吸、坐下、補水)。"
    }
}
