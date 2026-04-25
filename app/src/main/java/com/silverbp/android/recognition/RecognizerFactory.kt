package com.silverbp.android.recognition

import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

/**
 * Resolves a [BpRecognizer] from current [UserSettings]. Cheap to call —
 * always re-reads settings so toggling backend in the UI applies on next capture.
 */
object RecognizerFactory {
    suspend fun current(): BpRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudRecognizer.DEFAULT_MODEL },
            )
            RecognitionBackend.Local -> GemmaLocalRecognizer()
        }
    }
}
