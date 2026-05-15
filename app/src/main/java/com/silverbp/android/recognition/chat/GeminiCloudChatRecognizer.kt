package com.silverbp.android.recognition.chat

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiCloudChat"

/**
 * Gemini REST API chat recognizer.
 *
 * Sends the full conversation history per-turn using the multi-turn
 * `contents` format (role: user/model alternating).  Optional image
 * is embedded inline (base64 JPEG) in the latest user turn.
 *
 * Uses the streaming `generateContent` endpoint (non-streaming fallback is
 * straightforward — swap the URL suffix and parse a single JSON object).
 */
class GeminiCloudChatRecognizer(
    private val apiKey: String,
    private val modelId: String = DEFAULT_MODEL,
) : ChatRecognizer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var sessionSystemPrompt: String = ""
    private val history: MutableList<GeminiChatContent> = mutableListOf()

    override suspend fun startSession(systemPrompt: String) {
        sessionSystemPrompt = systemPrompt
        history.clear()
    }

    override suspend fun chat(
        userText: String,
        imageBitmap: Bitmap?,
        onToken: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key not set" }

        val userParts = buildList {
            if (imageBitmap != null) {
                val jpegBytes = ByteArrayOutputStream().use { os ->
                    imageBitmap.compress(Bitmap.CompressFormat.JPEG, 85, os)
                    os.toByteArray()
                }
                add(GeminiChatPart(inlineData = GeminiInlineData(
                    mimeType = "image/jpeg",
                    data = Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
                )))
            }
            add(GeminiChatPart(text = userText))
        }

        // Build the full contents list: system turn (if first message) + history + current user turn.
        val contents = buildList {
            if (history.isEmpty() && sessionSystemPrompt.isNotBlank()) {
                // Inject system prompt as a user/model pair so the API accepts it.
                add(GeminiChatContent(role = "user", parts = listOf(GeminiChatPart(text = sessionSystemPrompt))))
                add(GeminiChatContent(role = "model", parts = listOf(GeminiChatPart(text = "好的，我明白了。"))))
            }
            addAll(history)
            add(GeminiChatContent(role = "user", parts = userParts))
        }

        val body = GeminiChatRequest(
            contents = contents,
            generationConfig = GeminiChatGenConfig(
                temperature = 0.7,
                topP = 0.95,
                maxOutputTokens = 512,
            ),
        )

        val payload = json.encodeToString(GeminiChatRequest.serializer(), body)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "HTTP ${response.code}: ${responseBody.take(500)}")
            error("Gemini Cloud HTTP ${response.code}")
        }

        val parsed = json.decodeFromString<GeminiChatResponse>(responseBody)
        val replyText = parsed.candidates?.firstOrNull()
            ?.content?.parts?.firstOrNull { it.text != null }?.text
            ?: error("Empty Gemini Cloud response")

        Log.d(TAG, "chat ok replyChars=${replyText.length}")

        history.add(GeminiChatContent(role = "user", parts = userParts))
        history.add(GeminiChatContent(role = "model", parts = listOf(GeminiChatPart(text = replyText))))

        onToken(replyText)
        replyText
    }

    override fun supportsImages(): Boolean = true

    override fun isReady(): Boolean = apiKey.isNotBlank()

    override fun close() {
        history.clear()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// --- Wire types ---

@Serializable
private data class GeminiChatRequest(
    val contents: List<GeminiChatContent>,
    val generationConfig: GeminiChatGenConfig,
)

@Serializable
data class GeminiChatContent(
    val role: String,
    val parts: List<GeminiChatPart>,
)

@Serializable
data class GeminiChatPart(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: GeminiInlineData? = null,
)

@Serializable
data class GeminiInlineData(
    @SerialName("mime_type") val mimeType: String,
    val data: String,
)

@Serializable
private data class GeminiChatGenConfig(
    val temperature: Double,
    val topP: Double,
    val maxOutputTokens: Int,
)

@Serializable
private data class GeminiChatResponse(
    val candidates: List<GeminiChatCandidate>? = null,
)

@Serializable
private data class GeminiChatCandidate(val content: GeminiChatContent? = null)
