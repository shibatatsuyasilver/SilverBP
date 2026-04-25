package com.silverbp.android.recognition

import android.graphics.Bitmap

/**
 * Common contract for any blood-pressure-monitor OCR pipeline.
 * Implementations:
 *  - [GemmaLocalRecognizer] — wraps [GemmaBpService] (LiteRT-LM on-device)
 *  - [GeminiCloudRecognizer] — calls Google Gemini multimodal API
 *
 * The choice of which to use is driven by [com.silverbp.android.settings.UserSettings.recognitionBackend]
 * via [RecognizerFactory].
 */
interface BpRecognizer {
    /** True iff [extract] is ready to be called (e.g. model loaded, or API key set). */
    fun isReady(): Boolean
    /** Run multimodal OCR on the given bitmap and return parsed reading. Throws [BpExtractionError] on failure. */
    suspend fun extract(bitmap: Bitmap): ExtractedReading
}

/**
 * User-selectable inference backend. Mirror the plain enum in [com.silverbp.android.settings.UserSettings].
 */
enum class RecognitionBackend(val raw: String) {
    Local("local"),
    Cloud("cloud");

    companion object {
        fun fromRaw(s: String): RecognitionBackend =
            entries.firstOrNull { it.raw == s } ?: Local
    }
}
