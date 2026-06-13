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

/**
 * Gemini Nano via AICore (Pixel 9/10). NOTE: no longer used for nutrition —
 * AICore caps output at 256 tokens, too small for the food-identification JSON
 * (see [AICoreBpService.generate]). Kept only so BP capture's AICore path and
 * any future short-output nutrition use compile; [NutritionRecognizerFactory]
 * never returns it.
 */
class AICoreNutritionRecognizer : NutritionRecognizer {
    override val backendTag = "ai_aicore"
    override fun isReady(): Boolean = AICoreBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedNutrition =
        NutritionResponseParser.parse(AICoreBpService.generate(bitmap, NutritionPrompt.systemAndAnalyze()))
}

/**
 * Sentinel for "no usable nutrition backend" — local model not downloaded and
 * no cloud API key. [isReady] is false so the caller can prompt the user to
 * download the model or configure cloud instead of silently failing.
 */
class NotConfiguredNutritionRecognizer : NutritionRecognizer {
    override val backendTag = "none"
    override fun isReady(): Boolean = false
    override suspend fun analyze(bitmap: Bitmap): ExtractedNutrition =
        throw BpExtractionError.ModelNotLoaded
}

/**
 * Resolves a [NutritionRecognizer] for FOOD photos. Unlike BP capture, nutrition
 * never uses AICore — Gemini Nano's 256-token output ceiling truncates the
 * food-identification JSON. Routing (independent of the BP backend setting):
 * prefer the on-device Gemma model when downloaded, else cloud (if an API key is
 * set), else [NotConfiguredNutritionRecognizer] so the UI can prompt for setup.
 */
object NutritionRecognizerFactory {
    suspend fun current(): NutritionRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        val variant = ModelCatalog.byId(s.selectedModelId)
        return when {
            ModelDownloader(ServiceLocator.context).isDownloaded(variant) ->
                GemmaLocalNutritionRecognizer()
            s.geminiApiKey.isNotBlank() -> GeminiCloudNutritionRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL },
            )
            else -> NotConfiguredNutritionRecognizer()
        }
    }
}
