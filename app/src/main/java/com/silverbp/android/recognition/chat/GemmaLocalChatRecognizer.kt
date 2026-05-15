package com.silverbp.android.recognition.chat

import android.content.Context
import android.graphics.Bitmap
import com.silverbp.android.recognition.GemmaBpService

/** Thin wrapper that routes chat requests through [GemmaChatService]. */
class GemmaLocalChatRecognizer(private val context: Context) : ChatRecognizer {

    override suspend fun startSession(systemPrompt: String) =
        GemmaChatService.startSession(systemPrompt)

    override suspend fun chat(
        userText: String,
        imageBitmap: Bitmap?,
        onToken: suspend (String) -> Unit,
    ): String = GemmaChatService.sendUserTurn(context, userText, imageBitmap, onToken)

    override fun supportsImages(): Boolean = true

    override fun isReady(): Boolean = GemmaBpService.isLoaded()

    override fun close() {
        // Session lifecycle managed by ViewModel; GemmaChatService.clearSession() is
        // called explicitly in ChatViewModel.onCleared().
    }
}
