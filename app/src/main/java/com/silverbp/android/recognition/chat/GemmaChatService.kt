package com.silverbp.android.recognition.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.silverbp.android.recognition.GemmaBpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

// TODO(Task 1 — streaming, 2026-05-15): Artifact com.google.ai.edge.litertlm:litertlm-android:0.10.2
// could not be resolved in this sandbox (AGP 9.1.1 plugin resolution fails offline), so the
// Conversation.class constant pool could not be inspected for a Flow<Message> overload.
// When the build environment is unblocked, verify with:
//   find ~/.gradle/caches -path '*litertlm*' -name '*.jar'
//   javap -verbose <jar> | grep -i "Flow\|stream\|channel"
// If a non-suspend sendMessage(Contents, Map<*,*>): Flow<Message> overload exists:
//   Replace the blocking sendMessage() call in sendUserTurn() with a collect loop that
//   calls onToken(delta) for each incremental chunk. Compute delta defensively:
//   if sdk emits cumulative text, emit suffix = newText.removePrefix(lastEmitted);
//   if per-chunk, emit the chunk directly. Same heuristic as AICoreChatService.computeDelta().

private const val TAG = "GemmaChatService"

/**
 * LiteRT-LM (Gemma) backed chat service.
 *
 * Maintains a single [Conversation] per session across turns. The conversation
 * carries its own KV-cache so history replay is not needed while the process lives.
 * On process restart the session starts fresh; Room persists display history.
 *
 * Requires [GemmaBpService] to have been preloaded before the first [sendUserTurn].
 */
object GemmaChatService {

    private val mutex = Mutex()
    @Volatile private var conversation: Conversation? = null
    @Volatile private var sessionSystemPrompt: String = ""

    /** Closes any existing conversation and notes the system prompt for the next turn. */
    suspend fun startSession(systemPrompt: String) = mutex.withLock {
        closeConversationLocked()
        sessionSystemPrompt = systemPrompt
        Log.d(TAG, "session started systemPromptChars=${systemPrompt.length}")
    }

    suspend fun clearSession() = mutex.withLock {
        closeConversationLocked()
    }

    /**
     * Sends one user turn.  [onToken] is called once with the complete reply
     * (streaming is blocked on the Flow API investigation — see TODO above).
     */
    suspend fun sendUserTurn(
        context: Context,
        userText: String,
        imageBitmap: Bitmap?,
        onToken: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        var tmpFile: File? = null
        mutex.withLock {
            val conv = ensureConversation()

            tmpFile = if (imageBitmap != null) {
                File(context.applicationContext.cacheDir, "gemma_chat/${UUID.randomUUID()}.jpg")
                    .also { f ->
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use {
                            imageBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                        }
                    }
            } else null

            try {
                val parts = buildList {
                    tmpFile?.let { add(Content.ImageFile(it.absolutePath)) }
                    add(Content.Text(userText))
                }
                val contents = Contents.of(*parts.toTypedArray())

                val t0 = System.currentTimeMillis()
                val message = conv.sendMessage(contents)
                val elapsedMs = System.currentTimeMillis() - t0

                // Try typed Content.Text accessor; fall back to toString() for parity
                // with GemmaBpService.kt:149 which also uses message.toString().
                val replyText = try {
                    message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                        .ifBlank { message.toString() }
                } catch (_: Throwable) {
                    message.toString()
                }

                Log.d(TAG, "turn ok elapsedMs=$elapsedMs replyChars=${replyText.length}")
                onToken(replyText)
                replyText
            } finally {
                tmpFile?.delete()
            }
        }
    }

    // Must be called from within mutex.withLock.
    private suspend fun ensureConversation(): Conversation {
        conversation?.let { return it }

        val eng = checkNotNull(GemmaBpService.engineForChat()) {
            "GemmaBpService not loaded — preload before starting a chat session"
        }
        val conv = eng.createConversation()
        conversation = conv

        if (sessionSystemPrompt.isNotBlank()) {
            runCatching {
                conv.sendMessage(Contents.of(Content.Text(sessionSystemPrompt)))
                Log.d(TAG, "system prompt injected chars=${sessionSystemPrompt.length}")
            }.onFailure { Log.w(TAG, "system prompt injection failed: ${it.message}") }
        }
        return conv
    }

    private fun closeConversationLocked() {
        runCatching { conversation?.close() }
        conversation = null
    }
}
