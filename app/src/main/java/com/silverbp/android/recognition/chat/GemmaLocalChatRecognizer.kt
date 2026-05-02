package com.silverbp.android.recognition.chat

import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.GemmaBpService
import kotlinx.coroutines.flow.Flow

class GemmaLocalChatRecognizer : ChatRecognizer {
    override fun isReady(): Boolean = GemmaBpService.isLoaded()
    override fun supportsImages(): Boolean = GemmaBpService.isLoaded()
    override fun chat(messages: List<ChatMessage>): Flow<String> =
        GemmaChatService.chat(messages)
}
