package com.silverbp.android.recognition

import android.graphics.Bitmap

/** Thin wrapper around [AICoreBpService] (Gemini Nano via AICore on Pixel). */
class AICoreBpRecognizer : BpRecognizer {
    override fun isReady(): Boolean = AICoreBpService.isLoaded()
    override suspend fun extract(bitmap: Bitmap): ExtractedReading = AICoreBpService.extract(bitmap)
}
