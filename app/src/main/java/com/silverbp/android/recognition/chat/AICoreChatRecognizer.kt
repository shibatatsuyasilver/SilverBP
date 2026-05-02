package com.silverbp.android.recognition.chat

import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.AICoreBpService
import kotlinx.coroutines.flow.Flow

class AICoreChatRecognizer : ChatRecognizer {
    override fun isReady(): Boolean = AICoreBpService.isLoaded()

    /**
     * AICore Gemini Nano accepts a single ImagePart on `generateContentRequest`,
     * already verified at AICoreBpService.kt:157-165 for OCR. Multi-turn chat
     * with images is unverified; if the SDK rejects an image at runtime, the
     * Flow throws and the UI surfaces the error.
     */
    override fun supportsImages(): Boolean = AICoreBpService.isLoaded()

    override fun chat(messages: List<ChatMessage>): Flow<String> =
        AICoreChatService.chat(messages)
}
