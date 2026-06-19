package com.silverbp.android.recognition

import android.graphics.Bitmap
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

/**
 * Common contract for the gym-machine console-display OCR pipeline — the cardio
 * analogue of [NutritionRecognizer]. Implementations reuse the SAME on-device
 * engines / cloud API as BP + nutrition, switched by the SAME
 * [com.silverbp.android.settings.UserSettings.recognitionBackend] via
 * [MachineDisplayRecognizerFactory].
 */
interface MachineDisplayRecognizer {
    /** Backend id stored on the resulting session ("ai_local"|"ai_cloud"|"ai_aicore"). */
    val backendTag: String

    /** True iff [analyze] is ready (model loaded, or API key set). */
    fun isReady(): Boolean

    /** Run multimodal OCR and return the parsed console readout. Throws [BpExtractionError]. */
    suspend fun analyze(bitmap: Bitmap): ExtractedMachineWorkout
}

/** LiteRT-LM Gemma on-device — reuses the warmed [GemmaBpService] engine. */
class GemmaLocalMachineRecognizer : MachineDisplayRecognizer {
    override val backendTag = "ai_local"
    override fun isReady(): Boolean = GemmaBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedMachineWorkout =
        MachineResponseParser.parse(
            GemmaBpService.generate(bitmap.preprocessForOcr(), MachineDisplayPrompt.systemAndAnalyze()),
        )
}

/** Gemini Nano via AICore (Pixel 9/10) — reuses the warmed [AICoreBpService]. */
class AICoreMachineRecognizer : MachineDisplayRecognizer {
    override val backendTag = "ai_aicore"
    override fun isReady(): Boolean = AICoreBpService.isLoaded()
    override suspend fun analyze(bitmap: Bitmap): ExtractedMachineWorkout =
        MachineResponseParser.parse(
            AICoreBpService.generate(bitmap.preprocessForOcr(), MachineDisplayPrompt.systemAndAnalyze()),
        )
}

/**
 * Resolves a [MachineDisplayRecognizer] from current [UserSettings]. Cheap to
 * call — re-reads settings so toggling the backend applies on the next analysis.
 * Uses the same backend choice as BP / nutrition so users configure it once.
 */
object MachineDisplayRecognizerFactory {
    suspend fun current(): MachineDisplayRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudMachineRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL },
            )
            RecognitionBackend.Local -> GemmaLocalMachineRecognizer()
            RecognitionBackend.AICore -> AICoreMachineRecognizer()
        }
    }
}
