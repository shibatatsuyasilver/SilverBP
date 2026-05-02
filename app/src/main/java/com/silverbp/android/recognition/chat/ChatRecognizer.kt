package com.silverbp.android.recognition.chat

import com.silverbp.android.chat.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Free-form chat counterpart to [com.silverbp.android.recognition.BpRecognizer].
 *
 * The OCR recognizer is hard-coded to a single-shot prompt + strict JSON parser.
 * Chat needs free-form generation, multi-turn history and (optionally) image
 * conditioning. We implement it as a separate interface so the OCR pipeline
 * stays untouched and so the chat backends can lean on each backend SDK's chat
 * idioms instead of cramming everything through `extract(bitmap)`.
 *
 * Implementations:
 *  - [GemmaLocalChatRecognizer]   — reuses the LiteRT-LM engine loaded by GemmaBpService
 *  - [AICoreChatRecognizer]       — reuses the AICore client loaded by AICoreBpService
 *  - [GeminiCloudChatRecognizer]  — calls Gemini :streamGenerateContent over HTTP
 *
 * The factory ([ChatRecognizerFactory]) picks one based on
 * [com.silverbp.android.settings.UserSettings.recognitionBackend], same as the
 * OCR factory does.
 */
interface ChatRecognizer {
    /** True iff [chat] can be called now (model warm / API key present). */
    fun isReady(): Boolean

    /**
     * Whether this backend can attach an image to the latest user turn. Cloud
     * is always true; AICore depends on a runtime probe; Local follows the
     * vision-encoder readiness of GemmaBpService.
     */
    fun supportsImages(): Boolean

    /**
     * Free-form generation. [messages] is the full transcript including a
     * leading [ChatMessage.Role.System] turn that carries persona + records
     * summary. The flow emits text deltas in arrival order; collectors should
     * concatenate. Implementations that don't expose token-streaming SDK calls
     * may emit a single chunk with the full response.
     */
    fun chat(messages: List<ChatMessage>): Flow<String>
}
