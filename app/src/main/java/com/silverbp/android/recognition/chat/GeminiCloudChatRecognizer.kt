package com.silverbp.android.recognition.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.GeminiContent
import com.silverbp.android.recognition.GeminiInlineData
import com.silverbp.android.recognition.GeminiPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiCloudChat"

/**
 * Free-form chat recognizer that calls Gemini's REST `:streamGenerateContent`.
 *
 * Reuses the wire types declared in [com.silverbp.android.recognition.GeminiCloudRecognizer]
 * (now `internal`) so we have a single source of truth for the request shape.
 *
 * Streaming uses Server-Sent-Events (`?alt=sse`). Each SSE chunk's
 * `candidates[0].content.parts[*].text` is emitted as a delta. If the API key
 * isn't authorised for `:streamGenerateContent`, the request 4xx-fails and we
 * fall back once to `:generateContent` non-streaming and emit a single chunk.
 */
class GeminiCloudChatRecognizer(
    private val apiKey: String,
    private val modelId: String,
) : ChatRecognizer {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isReady(): Boolean = apiKey.isNotBlank()
    override fun supportsImages(): Boolean = true

    override fun chat(messages: List<ChatMessage>): Flow<String> = flow {
        require(apiKey.isNotBlank()) { "Gemini API key not set" }

        val systemText = messages
            .filter { it.role == ChatMessage.Role.System }
            .joinToString("\n\n") { it.text.trim() }
        val turnsForApi = messages.filter { it.role != ChatMessage.Role.System }

        val contents = turnsForApi.map { m ->
            val parts = mutableListOf<GeminiPart>()
            if (m.imagePath != null && m.role == ChatMessage.Role.User) {
                inlineImagePart(m.imagePath)?.let { parts.add(it) }
            }
            if (m.text.isNotBlank()) parts.add(GeminiPart(text = m.text))
            GeminiContent(
                parts = parts,
                role = if (m.role == ChatMessage.Role.User) "user" else "model",
            )
        }

        val body = ChatRequest(
            contents = contents,
            systemInstruction = if (systemText.isNotBlank()) {
                GeminiContent(parts = listOf(GeminiPart(text = systemText)))
            } else null,
            generationConfig = ChatGenConfig(
                temperature = 0.6,
                topP = 0.95,
                maxOutputTokens = 1024,
                // Disable thinking ONLY on models that support the field.
                // Flash 2.5 / Pro 2.5 default thinking=ON and leak the trace
                // ("User asks: ...", "Constraints: ...") into the response.
                // Gemma cloud + flash-lite don't have thinking and reject
                // this field with HTTP 400 INVALID_ARGUMENT.
                thinkingConfig = if (supportsThinkingConfig(modelId)) {
                    ThinkingConfig(thinkingBudget = 0)
                } else null,
            ),
        )
        val payload = json.encodeToString(ChatRequest.serializer(), body)

        val needsCleanup = isGemmaCloudModel(modelId)
        if (needsCleanup) {
            // Gemma cloud's verbose default style benefits from buffer-then-clean.
            // The user sees a single "thinking" indicator until the full clean
            // answer is ready, instead of watching CoT preamble stream in.
            val raw = runCatching { collectFullResponse(payload) }
                .getOrElse { t ->
                    android.util.Log.w(
                        TAG,
                        "[Cloud] stream failed: ${t.javaClass.simpleName}: ${t.message}; trying non-stream",
                    )
                    // No inner catch: if the non-stream retry also fails (e.g.
                    // network down) let it propagate so ChatViewModel surfaces a
                    // localized network error instead of an empty "(no content)" bubble.
                    generateContentOnce(payload)
                }
            val cleaned = stripCotPreambleIfPresent(raw)
            if (cleaned.isNotEmpty()) emit(cleaned)
            return@flow
        }

        val streamed = runCatching { streamSse(this, payload) }
            .getOrElse { t ->
                android.util.Log.w(
                    TAG,
                    "[Cloud] stream failed: ${t.javaClass.simpleName}: ${t.message}; falling back",
                )
                false
            }

        if (!streamed) {
            emit(generateContentOnce(payload))
        }
    }.flowOn(Dispatchers.IO)

    private fun isGemmaCloudModel(modelId: String): Boolean =
        modelId.lowercase().startsWith("gemma-")

    /** Stream SSE into a single concatenated string. Used when we plan to post-process. */
    private suspend fun collectFullResponse(payload: String): String {
        val buf = StringBuilder()
        val sink = object : FlowCollector<String> {
            override suspend fun emit(value: String) { buf.append(value) }
        }
        val ok = streamSse(sink, payload)
        if (ok) return buf.toString()
        // Stream wasn't available; fall back to non-stream.
        return generateContentOnce(payload)
    }

    /**
     * Last-resort defence against Gemma cloud's "show your work" output style.
     *
     * Patterns observed on `gemma-4-31b-it`:
     *  1. CoT preamble — `* User question: ...`, `* Role: ...`,
     *     `* Constraints: ...`, English bullet labels before the answer.
     *  2. Self-quoted answer — `"<answer>."<answer>.` (quoted then unquoted).
     *  3. Doubled answer — `<answer><answer>` (literal repetition).
     *  4. Self-checklist — `* First word is the answer? Yes. * No headers?
     *     Yes. ...` where the model evaluates its own compliance with the
     *     prompt and the actual answer follows the last `Yes.`.
     *
     * Strategy (idempotent on clean responses):
     *  - Step A: if response contains checklist tails (`? Yes.`, `? No.`,
     *    `？是。`, `？否。`), take everything after the LAST one.
     *  - Step B: drop preamble lines (bullets, headings, CoT labels).
     *  - Step C: if a quoted segment remains, prefer trailing unquoted text;
     *    else use the quoted content itself.
     *  - Step D: dedup if exactly `X + X`.
     */
    internal fun stripCotPreambleIfPresent(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.trim()

        // Step A: strip self-checklist preamble.
        val checklistEndRegex = Regex("[?？]\\s*(Yes|No|是|否|True|False|true|false)[.。]?")
        val checklistEnds = checklistEndRegex.findAll(t).toList()
        if (checklistEnds.size >= 2) {
            val last = checklistEnds.last()
            val tail = t.substring(last.range.last + 1).trim()
            if (tail.length >= 10) t = tail
        }

        // Step B: drop preamble lines.
        val cotMarkers = listOf(
            "User question:", "User asks:", "Role:", "Constraints:",
            "Latest blood pressure:", "Final answer:",
        )
        val lines = t.lines()
        val firstAnswerIdx = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@indexOfFirst false
            val isMeta = trimmed.startsWith("*") ||
                trimmed.startsWith("-") ||
                trimmed.startsWith("#") ||
                cotMarkers.any { trimmed.contains(it) }
            !isMeta
        }
        if (firstAnswerIdx > 0) {
            t = lines.drop(firstAnswerIdx).joinToString("\n").trim()
        }

        // Step C: strip self-quoted version.
        val selfQuoteRegex = Regex("[\"“「]([^\"”」]{10,})[\"”」]")
        val matches = selfQuoteRegex.findAll(t).toList()
        if (matches.isNotEmpty()) {
            val lastMatch = matches.last()
            val tail = t.substring(lastMatch.range.last + 1).trim()
            t = if (tail.isNotEmpty()) tail else lastMatch.groupValues[1].trim()
        }

        // Step D: dedup exact repetition.
        return dedupExactRepeat(t)
    }

    /**
     * Drop a duplicated trailing copy. Two cases:
     *  1. Whole string is `X + X` → return `X`.
     *  2. Trailing suffix is repeated: `<preamble><X><X>` → return `<preamble><X>`.
     */
    private fun dedupExactRepeat(s: String): String {
        val t = s.trim()
        val n = t.length
        if (n < 20) return t

        // Case 1: whole string is X+X (with small whitespace slack).
        val mid = n / 2
        for (offset in -3..3) {
            val cut = mid + offset
            if (cut < 10 || cut > n - 10) continue
            val a = t.substring(0, cut).trim()
            val b = t.substring(cut).trim()
            if (a.isNotEmpty() && a == b) return a
        }

        // Case 2: longest repeated trailing suffix.
        // Find largest L in [10..n/2] such that t[n-2L..n-L].trim() == t[n-L..n].trim().
        for (L in (n / 2) downTo 10) {
            if (n - 2 * L < 0) continue
            val a = t.substring(n - 2 * L, n - L).trim()
            val b = t.substring(n - L).trim()
            if (a == b && a.length >= 10) {
                return t.substring(0, n - L).trimEnd()
            }
        }
        return t
    }

    /** Returns true iff at least one delta was emitted. Caller passes the FlowCollector. */
    private suspend fun streamSse(collector: FlowCollector<String>, payload: String): Boolean {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string()?.take(500).orEmpty()
            android.util.Log.w(TAG, "[Cloud] stream HTTP ${response.code}: $errBody")
            response.close()
            return false
        }
        var emitted = false
        var totalChars = 0
        try {
            response.body?.charStream()?.buffered()?.useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payloadJson = line.removePrefix("data:").trim()
                    if (payloadJson.isEmpty() || payloadJson == "[DONE]") continue
                    val parsed = runCatching {
                        json.decodeFromString<ChatStreamChunk>(payloadJson)
                    }.getOrNull() ?: continue
                    val text = parsed.candidates
                        ?.firstOrNull()?.content?.parts
                        ?.mapNotNull { it.text }
                        ?.joinToString("")
                        .orEmpty()
                    if (text.isNotEmpty()) {
                        collector.emit(text)
                        emitted = true
                        totalChars += text.length
                    }
                }
            }
        } finally {
            response.close()
        }
        android.util.Log.i(TAG, "[Cloud] stream done emitted=$emitted chars=$totalChars")
        return emitted
    }

    private fun generateContentOnce(payload: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val resp = client.newCall(request).execute()
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            android.util.Log.e(TAG, "[Cloud] non-stream HTTP ${resp.code}: ${raw.take(400)}")
            error("Gemini HTTP ${resp.code}")
        }
        val parsed = runCatching { json.decodeFromString<ChatStreamChunk>(raw) }.getOrNull()
        val text = parsed?.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("")
            .orEmpty()
        if (text.isEmpty()) {
            android.util.Log.w(TAG, "[Cloud] non-stream empty text. body=${raw.take(400)}")
        }
        return text
    }

    private fun inlineImagePart(path: String): GeminiPart? {
        val file = File(path)
        if (!file.exists()) return null
        val bmp: Bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        val bytes = ByteArrayOutputStream().use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, os)
            os.toByteArray()
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = b64))
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Only `gemini-2.5-pro` and `gemini-2.5-flash` accept `thinkingConfig`
         * on the REST API. `gemini-2.5-flash-lite` and Gemma cloud models
         * reject it with 400 INVALID_ARGUMENT.
         */
        internal fun supportsThinkingConfig(modelId: String): Boolean {
            val m = modelId.lowercase()
            if (!m.startsWith("gemini-2.5-")) return false
            if (m.contains("flash-lite")) return false
            return true
        }
    }
}

@Serializable
private data class ChatRequest(
    val contents: List<GeminiContent>,
    @SerialName("system_instruction") val systemInstruction: GeminiContent? = null,
    val generationConfig: ChatGenConfig,
)

@Serializable
private data class ChatGenConfig(
    val temperature: Double,
    val topP: Double,
    val maxOutputTokens: Int,
    val thinkingConfig: ThinkingConfig? = null,
)

@Serializable
private data class ThinkingConfig(
    val thinkingBudget: Int,
)

@Serializable
private data class ChatStreamChunk(
    val candidates: List<ChatCandidate>? = null,
)

@Serializable
private data class ChatCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)
