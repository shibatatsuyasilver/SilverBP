package com.silverbp.android.recognition.chat

import android.graphics.Bitmap

/**
 * Abstraction over chat backends: LiteRT-LM (Gemma), AICore (Gemini Nano), or Gemini Cloud.
 *
 * Each instance owns its session state. Call [startSession] at the start of every
 * new chat conversation; call [close] when the ViewModel is cleared.
 */
interface ChatRecognizer {

    /**
     * Initialises (or resets) the conversation session.
     * [systemPrompt] is prepended to the first user turn so the model understands its role.
     * Safe to call multiple times — each call starts a fresh session.
     */
    suspend fun startSession(systemPrompt: String)

    /**
     * Sends one user turn and collects the assistant reply.
     *
     * @param userText      The user's message text.
     * @param imageBitmap   Optional image attached to this turn. Only passed when
     *                      [supportsImages] returns true.
     * @param onToken       Callback invoked for each incremental text delta.
     *                      Called at least once with the complete reply for non-streaming backends.
     * @return              The full assistant reply text.
     */
    suspend fun chat(
        userText: String,
        imageBitmap: Bitmap? = null,
        onToken: suspend (String) -> Unit = {},
    ): String

    /**
     * Whether this backend can accept an image attachment in a chat turn.
     * May flip to false at runtime if the backend rejects the combination
     * (see AICoreChatRecognizer Task 2 hardening).
     */
    fun supportsImages(): Boolean

    fun isReady(): Boolean

    fun close()
}
