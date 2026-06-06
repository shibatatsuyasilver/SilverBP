package com.silverbp.android.recognition

import android.graphics.Bitmap
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

/**
 * Common contract for any food-photo nutrition pipeline — the nutrition
 * analogue of [BpRecognizer]. Implementations reuse the same on-device
 * engines / cloud API as BP, switched by the SAME
 * [com.silverbp.android.settings.UserSettings.recognitionBackend] via
 * [NutritionRecognizerFactory].
 */
interface NutritionRecognizer {
    /** Backend id stored on the resulting log ("ai_local"|"ai_cloud"|"ai_aicore"). */
    val backendTag: String

    /** True iff [analyze] is ready (model loaded, or API key set). */
    fun isReady(): Boolean

    /** Run multimodal analysis and return the parsed estimate. Throws [BpExtractionError]. */
    suspend fun analyze(bitmap: Bitmap): ExtractedNutrition
}

/** LiteRT-LM Gemma on-device — reuses the warmed [GemmaBpService] engine. */
class GemmaLocalNutritionRecognizer : NutritionRecognizer {
    override val backendTag = "ai_local"
    override fun isReady(): Boolean = GemmaBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedNutrition =
        NutritionResponseParser.parse(GemmaBpService.generate(bitmap, NutritionPrompt.systemAndAnalyze()))
}

/** Gemini Nano via AICore (Pixel 9/10) — reuses the warmed [AICoreBpService]. */
class AICoreNutritionRecognizer : NutritionRecognizer {
    override val backendTag = "ai_aicore"
    override fun isReady(): Boolean = AICoreBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedNutrition =
        NutritionResponseParser.parse(AICoreBpService.generate(bitmap, NutritionPrompt.systemAndAnalyze()))
}

/**
 * Resolves a [NutritionRecognizer] from current [UserSettings]. Cheap to call —
 * re-reads settings so toggling the backend applies on the next analysis. Uses
 * the same backend choice as BP capture so users configure it once.
 */
object NutritionRecognizerFactory {
    suspend fun current(): NutritionRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudNutritionRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL },
            )
            RecognitionBackend.Local -> GemmaLocalNutritionRecognizer()
            RecognitionBackend.AICore -> AICoreNutritionRecognizer()
        }
    }
}
