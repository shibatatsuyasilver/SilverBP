package com.silverbp.android.recognition.chat

import android.graphics.Bitmap
import com.silverbp.android.recognition.AICoreBpService

/**
 * Chat recognizer backed by AICore (Gemini Nano, Pixel 9/10 only).
 *
 * Task 2: [supportsImages] starts true but flips to false permanently (process lifetime)
 * the first time the SDK rejects an ImagePart in a chat request.  Subsequent turns then
 * skip image attachment automatically without repeating the expensive retry round-trip.
 */
class AICoreChatRecognizer : ChatRecognizer {

    companion object {
        // @Volatile so the flag is visible across all AICoreChatRecognizer instances
        // (ChatRecognizerFactory may create a new instance per session).
        @Volatile private var imageRejectedBySDK = false
    }

    private var sessionSystemPrompt: String = ""
    private val history: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun startSession(systemPrompt: String) {
        sessionSystemPrompt = systemPrompt
        history.clear()
    }

    override suspend fun chat(
        userText: String,
        imageBitmap: Bitmap?,
        onToken: suspend (String) -> Unit,
    ): String {
        val imageToSend = if (supportsImages()) imageBitmap else null

        val reply = AICoreChatService.chat(
            history = history.toList(),
            currentUserText = if (sessionSystemPrompt.isNotBlank() && history.isEmpty()) {
                // Prepend system prompt on the first turn as a hidden context prefix.
                "$sessionSystemPrompt\n\n$userText"
            } else {
                userText
            },
            imageBitmap = imageToSend,
            onToken = onToken,
            onImageRejected = { imageRejectedBySDK = true },
        )

        // Append this turn to the local history for subsequent turns.
        history.add("user" to userText)
        history.add("assistant" to reply)
        return reply
    }

    /** Returns false permanently after the first SDK image rejection (Task 2). */
    override fun supportsImages(): Boolean = !imageRejectedBySDK

    override fun isReady(): Boolean = AICoreBpService.isLoaded()

    override fun close() {
        history.clear()
    }
}
