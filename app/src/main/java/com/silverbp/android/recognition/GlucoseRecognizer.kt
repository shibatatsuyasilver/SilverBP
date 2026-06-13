package com.silverbp.android.recognition

import android.graphics.Bitmap
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

/**
 * Common contract for the blood-glucose-meter OCR pipeline — the glucose analogue
 * of [NutritionRecognizer] / [MachineDisplayRecognizer]. Implementations reuse the
 * SAME on-device engines / cloud API as BP, switched by the SAME
 * [com.silverbp.android.settings.UserSettings.recognitionBackend] via
 * [GlucoseRecognizerFactory], so users configure the backend once.
 *
 * Glucose meters are 7-segment LCDs like BP monitors, so the local/AICore
 * recognizers run [preprocessForOcr] first (the contrast/desaturation pass tuned
 * for crisp digit edges) — unlike the nutrition pipeline which must skip it to
 * preserve food colour.
 */
interface GlucoseRecognizer {
    /** Backend id ("ai_local"|"ai_cloud"|"ai_aicore") for telemetry/source tagging. */
    val backendTag: String

    /** True iff [extract] is ready (model loaded, or API key set). */
    fun isReady(): Boolean

    /** Run multimodal OCR and return the parsed meter reading. Throws [BpExtractionError]. */
    suspend fun extract(bitmap: Bitmap): ExtractedGlucose
}

/**
 * LiteRT-LM Gemma on-device — reuses the warmed [GemmaBpService] engine. Uses the
 * built-in glucose prompt (no user override): the Settings [systemPrompt] field is
 * calibrated for BP monitors and would corrupt glucose unit/value reads, so glucose
 * deliberately stays on its own brief — same as the nutrition / machine pipelines.
 */
class GemmaLocalGlucoseRecognizer : GlucoseRecognizer {
    override val backendTag = "ai_local"
    override fun isReady(): Boolean = GemmaBpService.isLoaded()
    override suspend fun extract(bitmap: Bitmap): ExtractedGlucose =
        GlucoseResponseParser.parse(
            GemmaBpService.generate(bitmap.preprocessForOcr(), GlucosePrompt.systemAndExtract()),
        )
}

/** Gemini Nano via AICore (Pixel 9/10) — reuses the warmed [AICoreBpService]. */
class AICoreGlucoseRecognizer : GlucoseRecognizer {
    override val backendTag = "ai_aicore"
    override fun isReady(): Boolean = AICoreBpService.isLoaded()
    override suspend fun extract(bitmap: Bitmap): ExtractedGlucose =
        GlucoseResponseParser.parse(
            AICoreBpService.generate(bitmap.preprocessForOcr(), GlucosePrompt.systemAndExtract()),
        )
}

/**
 * Resolves a [GlucoseRecognizer] from current [UserSettings]. Cheap to call —
 * re-reads settings so toggling the backend applies on the next capture. Uses the
 * same backend choice as BP capture so users configure it once. Mirrors
 * [NutritionRecognizerFactory] / [MachineDisplayRecognizerFactory].
 */
object GlucoseRecognizerFactory {
    suspend fun current(): GlucoseRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudGlucoseRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL },
            )
            RecognitionBackend.Local -> GemmaLocalGlucoseRecognizer()
            RecognitionBackend.AICore -> AICoreGlucoseRecognizer()
        }
    }
}
