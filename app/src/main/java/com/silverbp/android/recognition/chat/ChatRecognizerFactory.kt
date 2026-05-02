package com.silverbp.android.recognition.chat

import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

/**
 * Mirror of [com.silverbp.android.recognition.RecognizerFactory] for chat.
 * Re-reads settings on every call so backend toggles in Settings take effect
 * on the next user turn — same UX as OCR.
 *
 * Cloud chat does NOT reuse [UserSettings.geminiModel]. That field is the
 * model the user picked for OCR — Gemma cloud (e.g. `gemma-4-31b-it`)
 * works for structured JSON extraction but produces verbose CoT-style chat
 * replies that no amount of prompting reliably tames. We always pick
 * [DEFAULT_CHAT_MODEL] for chat, with `thinkingConfig.thinkingBudget=0` to
 * keep replies clean. If users want a different chat model later we can add
 * a separate `chatModel` setting.
 */
object ChatRecognizerFactory {
    /** Chat-tuned default — good multilingual answers, supports `thinkingConfig`. */
    const val DEFAULT_CHAT_MODEL: String = "gemini-2.5-flash"

    suspend fun current(): ChatRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudChatRecognizer(
                apiKey = s.geminiApiKey,
                modelId = DEFAULT_CHAT_MODEL,
            )
            RecognitionBackend.Local -> GemmaLocalChatRecognizer()
            RecognitionBackend.AICore -> AICoreChatRecognizer()
        }
    }
}
