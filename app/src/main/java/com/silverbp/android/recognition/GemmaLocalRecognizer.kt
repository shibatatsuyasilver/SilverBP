package com.silverbp.android.recognition

import android.graphics.Bitmap

/** Thin wrapper around the LiteRT-LM-backed [GemmaBpService] singleton. */
class GemmaLocalRecognizer : BpRecognizer {
    override fun isReady(): Boolean = GemmaBpService.isLoaded()
    override suspend fun extract(bitmap: Bitmap): ExtractedReading = GemmaBpService.extract(bitmap)
}
