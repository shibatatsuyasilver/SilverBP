package com.silverbp.android.recognition.chat

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.silverbp.android.recognition.AICoreBpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Task 2 investigation note (2026-05-15):
// genai-prompt 1.0.0-beta2 jar not available in sandbox (build fails offline).
// Based on the single-shot API in AICoreBpService (lines 151-161), GenerativeModel
// supports generateContent(request) for single-turn. Multi-turn chat is emulated by
// flattening conversation history into a single TextPart transcript.
// ImagePart in multi-turn context (imageBitmap on a non-first turn) is a known risk:
// the beta2 API documentation for ML Kit GenAI Prompt API does not document multi-turn
// support with images, so we guard with the catch+retry-text-only approach below.
// generateContentStream is assumed to exist by analogy with the Gemini SDK and is the
// streaming path; if it doesn't compile, replace with generateContent and emit once.

private const val TAG = "AICoreChatService"

object AICoreChatService {

    /**
     * Send a multi-turn chat request. History is flattened into a single prompt because
     * the genai-prompt beta2 API does not expose a native multi-turn session object.
     *
     * Task 2 hardening: if [imageBitmap] is non-null and the SDK rejects the combination
     * (IllegalArgumentException / IllegalStateException / any Throwable), we retry with
     * text-only and prepend a user-visible note in Traditional Chinese.
     *
     * @param history         Alternating [role:text] pairs before the current user turn.
     * @param currentUserText The latest user message.
     * @param imageBitmap     Optional image for the current turn.
     * @param onToken         Streaming callback. Called once per chunk if the SDK streams,
     *                        or once with the full reply for single-shot fallback.
     * @param onImageRejected Called if the image was stripped after a failure, so the
     *                        recognizer can flip its [supportsImages] flag.
     */
    suspend fun chat(
        history: List<Pair<String, String>>,   // role ("user"/"assistant") + text
        currentUserText: String,
        imageBitmap: Bitmap?,
        onToken: suspend (String) -> Unit,
        onImageRejected: () -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val model = AICoreBpService.modelForChat()
            ?: error("AICore GenerativeModel not loaded — preload via AICoreBpService.preload()")

        val transcript = buildTranscript(history, currentUserText)

        if (imageBitmap != null) {
            try {
                return@withContext generateWithImage(model, transcript, imageBitmap, onToken)
            } catch (e: Throwable) {
                val isRejection = e is IllegalArgumentException || e is IllegalStateException
                        || e.javaClass.name.contains("genai", ignoreCase = true)
                if (!isRejection) throw e   // propagate unexpected errors (OOM, etc.)

                Log.w(
                    TAG,
                    "Image rejected by AICore (${e.javaClass.simpleName}: ${e.message}); " +
                        "retrying text-only",
                )
                onImageRejected()

                val fallbackPrefix =
                    "(Pixel 9/10 的 Gemini Nano 目前不支援多輪對話帶圖片，已改用文字回答。)\n\n"
                val textOnlyReply = generateTextOnly(model, transcript, onToken)
                return@withContext fallbackPrefix + textOnlyReply
            }
        }

        generateTextOnly(model, transcript, onToken)
    }

    private suspend fun generateWithImage(
        model: GenerativeModel,
        transcript: String,
        imageBitmap: Bitmap,
        onToken: suspend (String) -> Unit,
    ): String {
        val req = generateContentRequest(ImagePart(imageBitmap), TextPart(transcript)) {
            temperature = 0.7f
            topK = 40
            candidateCount = 1
            maxOutputTokens = 512
        }
        return collectStreamingResponse(model, req, onToken)
    }

    private suspend fun generateTextOnly(
        model: GenerativeModel,
        transcript: String,
        onToken: suspend (String) -> Unit,
    ): String {
        val req = generateContentRequest(TextPart(transcript)) {
            temperature = 0.7f
            topK = 40
            candidateCount = 1
            maxOutputTokens = 512
        }
        return collectStreamingResponse(model, req, onToken)
    }

    private suspend fun collectStreamingResponse(
        model: GenerativeModel,
        req: GenerateContentRequest,
        onToken: suspend (String) -> Unit,
    ): String {
        var lastEmitted = ""
        val fullReply = StringBuilder()

        // generateContentStream returns Flow<GenerateContentResponse>.
        // If this method does not exist in beta2, fall back to generateContent (non-streaming).
        model.generateContentStream(req).collect { response ->
            val chunk = response.candidates.firstOrNull()?.text.orEmpty()
            val delta = computeDelta(lastEmitted, chunk)
            if (delta.isNotEmpty()) {
                lastEmitted = chunk
                fullReply.append(delta)
                onToken(delta)
            }
        }

        val result = fullReply.toString().ifBlank { lastEmitted }
        Log.d(TAG, "chat ok replyChars=${result.length}")
        return result
    }

    /**
     * Handles both cumulative (SDK emits growing text) and per-chunk (SDK emits snippets)
     * streaming modes. If [newText] starts with [prev], it's cumulative → emit the suffix.
     * Otherwise it's a new chunk → emit as-is.
     */
    internal fun computeDelta(prev: String, newText: String): String = when {
        newText.isEmpty() -> ""
        newText.startsWith(prev) -> newText.removePrefix(prev)
        else -> newText
    }

    private fun buildTranscript(
        history: List<Pair<String, String>>,
        currentUserText: String,
    ): String = buildString {
        for ((role, text) in history) {
            val label = if (role == "user") "User" else "Assistant"
            appendLine("$label: $text")
        }
        append("User: $currentUserText")
    }
}
