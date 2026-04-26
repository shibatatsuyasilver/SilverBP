package com.silverbp.android.recognition

import android.graphics.Bitmap
import android.util.Base64
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

/**
 * Google Gemini multimodal API recognizer.
 *
 * Reuses [BpPrompt.systemAndExtract] as the text part and sends the bitmap
 * inline (base64 JPEG) — same prompt as the local model so output format /
 * parser logic ([BpResponseParser]) doesn't have to fork.
 *
 * No API-key persistence beyond DataStore (Settings screen).
 *
 * @param apiKey  Google AI Studio key (`AIza...`). Get one at https://aistudio.google.com/app/apikey
 * @param modelId Default `gemini-2.5-flash` — fast & cheap. Use `gemini-2.5-pro` for tougher photos.
 */
class GeminiCloudRecognizer(
    private val apiKey: String,
    private val modelId: String = DEFAULT_MODEL,
) : BpRecognizer {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isReady(): Boolean = apiKey.isNotBlank()

    override suspend fun extract(bitmap: Bitmap): ExtractedReading = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key not set" }
        val processed = bitmap.preprocessForOcr()
        val jpegBytes = ByteArrayOutputStream().use { os ->
            processed.compress(Bitmap.CompressFormat.JPEG, 90, os)
            os.toByteArray()
        }
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val body = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(
                    GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = base64)),
                    GeminiPart(text = BpPrompt.systemAndExtract()),
                )),
            ),
            generationConfig = GeminiGenConfig(
                temperature = 0.0,
                topP = 0.95,
                maxOutputTokens = 256,
                responseMimeType = "application/json",
            ),
        )

        val payload = json.encodeToString(GeminiRequest.serializer(), body)
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
            throw BpExtractionError.InvalidJson.also {
                android.util.Log.e("GeminiCloud", "HTTP ${response.code}: $responseBody")
            }
        }

        val parsed = try {
            json.decodeFromString<GeminiResponse>(responseBody)
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
        val text = parsed.candidates?.firstOrNull()
            ?.content?.parts?.firstOrNull { it.text != null }?.text
            ?: throw BpExtractionError.InvalidJson

        BpResponseParser.parse(text)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        val SUPPORTED_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemma-3-27b-it",
            "gemma-3-12b-it",
            "gemma-3-4b-it",
            "gemma-4-31b-it",
            "gemma-4-26b-a4b-it",
            "gemma-4-e4b-it",
            "gemma-4-e2b-it",
        )
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// --- Gemini wire types (minimal) ---

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenConfig,
)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: GeminiInlineData? = null,
)

@Serializable
private data class GeminiInlineData(
    @SerialName("mime_type") val mimeType: String,
    val data: String,
)

@Serializable
private data class GeminiGenConfig(
    val temperature: Double,
    val topP: Double,
    val maxOutputTokens: Int,
    @SerialName("response_mime_type") val responseMimeType: String? = null,
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
)

@Serializable
private data class GeminiCandidate(val content: GeminiContent? = null)
