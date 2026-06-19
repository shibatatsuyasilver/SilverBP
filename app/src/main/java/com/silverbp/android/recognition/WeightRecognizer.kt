package com.silverbp.android.recognition

import android.graphics.Bitmap
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

/**
 * Common contract for the body-weight scale-display OCR pipeline — the weight
 * analogue of [MachineDisplayRecognizer] / [GlucoseRecognizer]. Implementations
 * reuse the SAME on-device engines / cloud API as BP + nutrition + machine,
 * switched by the SAME
 * [com.silverbp.android.settings.UserSettings.recognitionBackend] via
 * [WeightRecognizerFactory], so users configure the backend once.
 *
 * A scale shows a single number (≤256 tokens), so AICore (Gemini Nano) is a fine
 * backend here — unlike the heavier nutrition pipeline, weight does NOT use any
 * local-or-cloud fallback logic; it routes exactly by [RecognitionBackend], the
 * same way [MachineDisplayRecognizerFactory] does.
 */
interface WeightDisplayRecognizer {
    /** Backend id stored on the resulting session ("ai_local"|"ai_cloud"|"ai_aicore"). */
    val backendTag: String

    /** True iff [analyze] is ready (model loaded, or API key set). */
    fun isReady(): Boolean

    /** Run multimodal OCR and return the parsed scale readout. Throws [BpExtractionError]. */
    suspend fun analyze(bitmap: Bitmap): ExtractedWeight
}

/** LiteRT-LM Gemma on-device — reuses the warmed [GemmaBpService] engine. */
class GemmaLocalWeightRecognizer : WeightDisplayRecognizer {
    override val backendTag = "ai_local"
    override fun isReady(): Boolean = GemmaBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedWeight =
        WeightResponseParser.parse(
            GemmaBpService.generate(bitmap.preprocessForOcr(), WeightPrompt.systemAndAnalyze()),
        )
}

/** Gemini Nano via AICore (Pixel 9/10) — reuses the warmed [AICoreBpService]. */
class AICoreWeightRecognizer : WeightDisplayRecognizer {
    override val backendTag = "ai_aicore"
    override fun isReady(): Boolean = AICoreBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedWeight =
        WeightResponseParser.parse(
            AICoreBpService.generate(bitmap.preprocessForOcr(), WeightPrompt.systemAndAnalyze()),
        )
}

/**
 * Resolves a [WeightDisplayRecognizer] from current [UserSettings]. Cheap to
 * call — re-reads settings so toggling the backend applies on the next analysis.
 * Uses the same backend choice as BP / nutrition / machine so users configure it
 * once. Mirrors [MachineDisplayRecognizerFactory] / [GlucoseRecognizerFactory].
 *
 * [GeminiCloudWeightRecognizer] is the cloud analogue of
 * [GeminiCloudMachineRecognizer] and — like every other modality's cloud
 * recognizer — lives in GeminiCloudRecognizer.kt so all share the Gemini wire
 * types.
 */
object WeightRecognizerFactory {
    suspend fun current(): WeightDisplayRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudWeightRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL },
            )
            RecognitionBackend.Local -> GemmaLocalWeightRecognizer()
            RecognitionBackend.AICore -> AICoreWeightRecognizer()
        }
    }
}
