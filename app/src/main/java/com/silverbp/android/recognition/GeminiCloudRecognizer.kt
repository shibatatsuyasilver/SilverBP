package com.silverbp.android.recognition

import android.graphics.Bitmap
import android.util.Base64
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
private const val TAG = "GeminiCloud"

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
        val systemOverride = ServiceLocator.userSettings.flow.first().systemPrompt
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
                    GeminiPart(text = BpPrompt.systemAndExtract(systemOverride)),
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

        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            // No connectivity, DNS failure, or timeout — distinct from a bad
            // payload so the UI can tell the user it's a connection problem.
            android.util.Log.w(TAG, "[Cloud] network failure: ${e.javaClass.simpleName}: ${e.message}")
            throw BpExtractionError.NetworkError
        }
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            android.util.Log.e(
                TAG,
                "[Cloud] HTTP ${response.code} model=$modelId body: ${responseBody.take(500)}",
            )
            // Surface the status so the UI can distinguish quota (429) / bad key
            // (401/403) from a transient server error.
            throw BpExtractionError.ApiError(response.code)
        }

        val parsed = try {
            json.decodeFromString<GeminiResponse>(responseBody)
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "[Cloud] envelope decode failed: ${e.message}; body=${responseBody.take(500)}",
            )
            throw BpExtractionError.InvalidJson
        }
        val firstCandidate = parsed.candidates?.firstOrNull()
        val rawText = firstCandidate
            ?.content?.parts?.firstOrNull { it.text != null }?.text
            ?: run {
                android.util.Log.w(
                    TAG,
                    "[Cloud] no text in response. finishReason=${firstCandidate?.finishReason ?: "?"}",
                )
                android.util.Log.w(TAG, "[Cloud] full body: ${responseBody.take(800)}")
                throw BpExtractionError.InvalidJson
            }

        android.util.Log.i(
            TAG,
            "[Cloud] finishReason=${firstCandidate?.finishReason ?: "?"} text len=${rawText.length}",
        )
        android.util.Log.i(TAG, "[Cloud] Final JSON to parse:\n$rawText\n[Cloud] (end)")

        BpResponseParser.parse(rawText)
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

/**
 * Cloud Gemini food-nutrition recognizer — the nutrition analogue of
 * [GeminiCloudRecognizer]. Reuses the same wire types and HTTP shape with
 * [NutritionPrompt] / [NutritionResponseParser] and a larger token budget
 * (nutrition JSON with a per-item breakdown is longer than a BP reading).
 */
class GeminiCloudNutritionRecognizer(
    private val apiKey: String,
    private val modelId: String = GeminiCloudRecognizer.DEFAULT_MODEL,
) : NutritionRecognizer {

    override val backendTag = "ai_cloud"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun isReady(): Boolean = apiKey.isNotBlank()

    override suspend fun analyze(bitmap: Bitmap): ExtractedNutrition = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini API key not set" }
        val jpegBytes = ByteArrayOutputStream().use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
            os.toByteArray()
        }
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val body = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(
                    GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = base64)),
                    GeminiPart(text = NutritionPrompt.systemAndAnalyze()),
                )),
            ),
            generationConfig = GeminiGenConfig(
                temperature = 0.0,
                topP = 0.95,
                maxOutputTokens = 800,
                responseMimeType = "application/json",
            ),
        )

        val payload = json.encodeToString(GeminiRequest.serializer(), body)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(NUTRITION_JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            android.util.Log.w(TAG, "[Cloud] nutrition network failure: ${e.javaClass.simpleName}: ${e.message}")
            throw BpExtractionError.NetworkError
        }
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            android.util.Log.e(
                TAG,
                "[Cloud] nutrition HTTP ${response.code} model=$modelId body: ${responseBody.take(500)}",
            )
            throw BpExtractionError.ApiError(response.code)
        }
        val parsed = try {
            json.decodeFromString<GeminiResponse>(responseBody)
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
        val rawText = parsed.candidates?.firstOrNull()
            ?.content?.parts?.firstOrNull { it.text != null }?.text
            ?: throw BpExtractionError.InvalidJson
        NutritionResponseParser.parse(rawText)
    }

    private companion object {
        private val NUTRITION_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// --- Gemini wire types (minimal) ---

@Serializable
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenConfig,
)

@Serializable
internal data class GeminiContent(
    val parts: List<GeminiPart>,
    /** "user" | "model" | "system". Optional so OCR call sites stay byte-identical. */
    val role: String? = null,
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: GeminiInlineData? = null,
)

@Serializable
internal data class GeminiInlineData(
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
private data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)
