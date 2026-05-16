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
        val nickname = settings.flow.first().userNickname
        val recognizer = ChatRecognizerFactory.current()
        val records = recordsBuilder.build(forCoach = true)
        val sys = buildSystemPrompt(records, role = Role.Daily, nickname = nickname)
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
        val sys = buildSystemPrompt(records, role = Role.Weekly, nickname = s.userNickname)
        val user = buildWeeklyPrompt(report)
        emitAll(recognizer.chat(messagesOf(sys, user)))
    }

    fun narrateAnomalyTip(event: CoachEvent.Anomaly): Flow<String> = flow {
        val nickname = settings.flow.first().userNickname
        val recognizer = ChatRecognizerFactory.current()
        val records = recordsBuilder.build(forCoach = true)
        val sys = buildSystemPrompt(records, role = Role.Anomaly, nickname = nickname)
        val user = buildAnomalyPrompt(event)
        emitAll(recognizer.chat(messagesOf(sys, user)))
    }

    private fun messagesOf(systemText: String, userText: String): List<ChatMessage> = listOf(
        ChatMessage(role = ChatMessage.Role.System, text = systemText),
        ChatMessage(role = ChatMessage.Role.User, text = userText),
    )

    private fun buildSystemPrompt(records: String, role: Role, nickname: String): String {
        val persona = when (role) {
            Role.Daily -> CoachPrompts.dailyPersona
            Role.Weekly -> CoachPrompts.weeklyPersona
            Role.Anomaly -> CoachPrompts.anomalyPersona
        }
        return buildString {
            appendLine(persona)
            val instruction = CoachPrompts.nicknameInstruction(nickname)
            if (instruction != null) {
                appendLine()
                appendLine(instruction)
            }
            appendLine()
            append(records)
            appendLine()
            appendLine()
            append(CoachPrompts.safetySuffix)
        }
    }

    private fun buildDailyPrompt(plan: CoachPlan, today: CoachTask): String =
        CoachPrompts.buildDailyPrompt(plan, today)

    private fun buildWeeklyPrompt(r: WeeklyReport): String =
        CoachPrompts.buildWeeklyPrompt(r)

    private fun buildAnomalyPrompt(event: CoachEvent.Anomaly): String =
        CoachPrompts.buildAnomalyPrompt(event)

    private enum class Role { Daily, Weekly, Anomaly }

    companion object {
        /**
         * Returns a one-line instruction telling the model how to use the user's
         * preferred nickname. Returns null when the nickname is blank so the
         * persona stays untouched. Tone: friendly but not overused — matches the
         * "親切但不過度" product direction. Locale-aware: delegates to
         * [CoachPrompts.nicknameInstruction] which switches between zh-TW and en.
         */
        internal fun nicknameInstruction(nickname: String): String? =
            CoachPrompts.nicknameInstruction(nickname)
    }
}
