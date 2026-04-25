package com.silverbp.android.settings

import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.recognition.GeminiCloudRecognizer
import com.silverbp.android.recognition.ModelCatalog
import com.silverbp.android.recognition.RecognitionBackend

data class UserSettings(
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    val enableHealthConnect: Boolean = false,
    val enableCloudSync: Boolean = false,        // stub — feature deferred (matches iOS)
    val didOnboard: Boolean = false,
    val modelDownloaded: Boolean = false,

    /** "local" (default) or "cloud" — picks which [com.silverbp.android.recognition.BpRecognizer] to use. */
    val recognitionBackend: RecognitionBackend = RecognitionBackend.Local,
    /** Local model id (matches one of [ModelCatalog.variants]). */
    val selectedModelId: String = ModelCatalog.default.id,
    /** Google AI Studio API key for the cloud route. Stored in plain DataStore — fine for personal use,
     *  swap to EncryptedSharedPreferences if you want device-level secrecy. */
    val geminiApiKey: String = "",
    /** Gemini model id (e.g. "gemini-2.5-flash"). */
    val geminiModel: String = GeminiCloudRecognizer.DEFAULT_MODEL,
)
