package com.silverbp.android.recognition.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.AICoreBpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "AICoreChatService"

/**
 * Chat counterpart to [AICoreBpService]. Reuses the warm Gemini Nano client via
 * [AICoreBpService.clientOrNull].
 *
 * Gemini Nano on AICore exposes `generateContentRequest { ... }` taking a
 * single `TextPart` and at most one `ImagePart`, with `maxOutputTokens` capped
 * at 256. So multi-turn is collapsed to a single text prompt and the image (if
 * any) attaches the most recent user turn.
 *
 * Streaming uses [com.google.mlkit.genai.prompt.GenerativeModel.generateContentStream]
 * which returns a `Flow<GenerateContentResponse>`. Each emission's
 * `candidates[0].text` is concatenated; we compute deltas defensively so this
 * works whether the SDK emits cumulative text or per-chunk deltas.
 */
object AICoreChatService {

    private val mutex = Mutex()

    fun chat(messages: List<ChatMessage>): Flow<String> = flow {
        mutex.withLock {
            val client = AICoreBpService.clientOrNull()
                ?: error("AICore client not warm — preload via ModelBootstrap.preloadAICore() first.")
            val tStart = SystemClock.elapsedRealtime()

            val flattened = flattenForAiCore(messages)
            val latestImagePath = messages
                .lastOrNull { it.role == ChatMessage.Role.User && it.imagePath != null }
                ?.imagePath
            val imageBitmap: Bitmap? = latestImagePath?.let { path ->
                runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
            }

            val req: GenerateContentRequest = if (imageBitmap != null) {
                generateContentRequest(ImagePart(imageBitmap), TextPart(flattened)) {
                    temperature = 0.4f
                    candidateCount = 1
                    maxOutputTokens = 256
                }
            } else {
                generateContentRequest(TextPart(flattened)) {
                    temperature = 0.4f
                    candidateCount = 1
                    maxOutputTokens = 256
                }
            }

            val acc = StringBuilder()
            client.generateContentStream(req).collect { resp: GenerateContentResponse ->
                val raw = resp.candidates.firstOrNull()?.text.orEmpty()
                if (raw.isEmpty()) return@collect
                val delta = computeDelta(acc.toString(), raw)
                if (delta.isNotEmpty()) {
                    acc.append(delta)
                    emit(delta)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - tStart
            android.util.Log.i(
                TAG,
                "[Chat] turns=${messages.size} hasImage=${imageBitmap != null} " +
                    "respChars=${acc.length} elapsedMs=$elapsed",
            )
        }
    }.flowOn(Dispatchers.IO)

    /** Heuristic: if [next] starts with what we've accumulated, treat as cumulative; otherwise it's a delta. */
    private fun computeDelta(accumulated: String, next: String): String =
        if (accumulated.isNotEmpty() && next.startsWith(accumulated)) {
            next.substring(accumulated.length)
        } else {
            next
        }

    private fun flattenForAiCore(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val systems = messages.filter { it.role == ChatMessage.Role.System }
        if (systems.isNotEmpty()) {
            sb.append("[System]\n")
            for (m in systems) sb.append(m.text.trim()).append('\n')
            sb.append('\n')
        }
        for (m in messages) {
            when (m.role) {
                ChatMessage.Role.System -> {}
                ChatMessage.Role.User -> sb.append("[User]\n").append(m.text.trim()).append('\n')
                ChatMessage.Role.Assistant -> sb.append("[Assistant]\n").append(m.text.trim()).append('\n')
            }
        }
        sb.append("[Assistant]\n")
        return sb.toString()
    }
}
