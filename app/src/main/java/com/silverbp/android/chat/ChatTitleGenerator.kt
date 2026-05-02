package com.silverbp.android.chat

import com.silverbp.android.recognition.chat.ChatRecognizer
import kotlinx.coroutines.flow.collect

/**
 * Generates a short Chinese title for a session from its first turn pair.
 * Single-shot — caller decides when to fire (typically once after the first
 * assistant response completes for a session whose title is still default).
 *
 * The generator reuses whichever [ChatRecognizer] the user has selected so the
 * extra inference cost stays on the same backend (no surprise cloud call from a
 * Local-only user). Failures return null so the caller can keep the default
 * title silently.
 */
object ChatTitleGenerator {

    /** Trim the assistant message to keep the title-gen prompt cheap. */
    private const val ASSISTANT_SNIPPET_MAX = 800

    /** Final title cap, enforced after sanitization. */
    private const val TITLE_MAX_CHARS = 20

    /** Chars that we strip from the model's output (quotes/punctuation). */
    private val STRIP_CHARS: Set<Char> = setOf(
        '"', '\'', '“', '”', '「', '」', '『', '』',
        '`', '*', '#', '。', '!', '?', '!', '?', '.',
    )

    suspend fun generate(
        userMsg: String,
        assistantMsg: String,
        recognizer: ChatRecognizer,
    ): String? {
        if (!recognizer.isReady()) return null
        if (userMsg.isBlank() && assistantMsg.isBlank()) return null

        // Pack the conversation into ONE user turn so the transcript ends in a
        // user turn — Gemini's structured `contents` API expects to generate the
        // NEXT model turn; if we left the assistant message as a separate
        // Assistant role at the tail, the API often returns an empty body
        // (observed: `finishReason=STOP` with no `parts`). The Gemma/AICore
        // backends flatten anyway so this packing is a no-op for them.
        val packed = buildString {
            append("以下是我和助理的對話:\n\n")
            append("使用者: ")
            append(userMsg.trim())
            append("\n\n")
            append("助理: ")
            append(assistantMsg.trim().take(ASSISTANT_SNIPPET_MAX))
            append("\n\n")
            append("請把這段對話濃縮成 8 字以內的繁體中文標題,只回標題本身,不加引號或標點。")
        }
        val transcript = listOf(
            ChatMessage(
                role = ChatMessage.Role.System,
                text = "你是對話標題助手,只回一個簡短的繁體中文標題,絕對不加引號或標點。",
            ),
            ChatMessage(role = ChatMessage.Role.User, text = packed),
        )

        val acc = StringBuilder()
        runCatching {
            recognizer.chat(transcript).collect { delta -> acc.append(delta) }
        }.onFailure {
            return null
        }

        return sanitize(acc.toString())
    }

    internal fun sanitize(raw: String): String? {
        // Take the first non-blank line, then strip noise chars & enforce length.
        val firstLine = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val cleaned = buildString {
            for (c in firstLine) {
                if (c !in STRIP_CHARS) append(c)
            }
        }.trim().take(TITLE_MAX_CHARS)
        return cleaned.takeIf { it.isNotBlank() }
    }
}
