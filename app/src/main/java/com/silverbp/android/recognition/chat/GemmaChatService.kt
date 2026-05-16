package com.silverbp.android.recognition.chat

import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.GemmaBpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "GemmaChatService"

/**
 * Chat counterpart to [GemmaBpService]. Reuses the warm LiteRT-LM engine via
 * [GemmaBpService.engineOrNull] so we don't load the model twice.
 *
 * Streams via `Conversation.sendMessageAsync(...): Flow<Message>` and re-emits
 * deltas through [ChatRecognizer.chat] so the UI can render token-by-token.
 *
 * Multi-turn: each call creates a fresh Conversation and feeds it the full
 * transcript flattened into one prompt, since per-Conversation state doesn't
 * survive across calls. This matches how [GemmaBpService.extract] uses the
 * engine and keeps inference deterministic.
 */
object GemmaChatService {

    private val mutex = Mutex()

    fun chat(messages: List<ChatMessage>): Flow<String> = flow {
        mutex.withLock {
            val eng = GemmaBpService.engineOrNull()
                ?: error("Gemma engine not loaded — preload via ModelBootstrap first.")
            val tStart = SystemClock.elapsedRealtime()
            val flattened = flattenForGemma(messages)

            // Latest user image (if any) goes first as a Content.ImageFile so the
            // vision encoder picks it up, mirroring GemmaBpService.extract().
            val latestImage = messages
                .lastOrNull { it.role == ChatMessage.Role.User && it.imagePath != null }
                ?.imagePath

            eng.createConversation().use { conversation ->
                val parts = buildList {
                    latestImage?.let { add(Content.ImageFile(it)) }
                    add(Content.Text(flattened))
                }
                val acc = StringBuilder()
                var chunks = 0
                conversation.sendMessageAsync(Contents.of(*parts.toTypedArray()))
                    .collect { msg ->
                        val text = msg.toString()
                        if (text.isEmpty()) return@collect
                        val delta = computeDelta(acc.toString(), text)
                        if (delta.isNotEmpty()) {
                            acc.append(delta)
                            chunks++
                            emit(delta)
                        }
                    }
                val elapsed = SystemClock.elapsedRealtime() - tStart
                android.util.Log.i(
                    TAG,
                    "[Chat] turns=${messages.size} hasImage=${latestImage != null} " +
                        "respChars=${acc.length} chunks=$chunks elapsedMs=$elapsed",
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    /** If [next] starts with what we've accumulated, treat as cumulative; otherwise it's a delta. */
    private fun computeDelta(accumulated: String, next: String): String =
        if (accumulated.isNotEmpty() && next.startsWith(accumulated)) {
            next.substring(accumulated.length)
        } else {
            next
        }

    /**
     * Flatten the transcript into a single text prompt that respects roles.
     * Gemma 4 follows turn markers reasonably well even without an explicit
     * chat template, and this avoids needing a tokenizer-side template.
     */
    private fun flattenForGemma(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val systems = messages.filter { it.role == ChatMessage.Role.System }
        if (systems.isNotEmpty()) {
            sb.append("[System]\n")
            for (m in systems) sb.append(m.text.trim()).append('\n')
            sb.append('\n')
        }
        for (m in messages) {
            when (m.role) {
                ChatMessage.Role.System -> {} // already emitted above
                ChatMessage.Role.User -> {
                    sb.append("[User]\n").append(m.text.trim()).append('\n')
                }
                ChatMessage.Role.Assistant -> {
                    sb.append("[Assistant]\n").append(m.text.trim()).append('\n')
                }
            }
        }
        sb.append("[Assistant]\n")
        return sb.toString()
    }
}
