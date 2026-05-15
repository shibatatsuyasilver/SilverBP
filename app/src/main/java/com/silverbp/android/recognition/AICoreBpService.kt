package com.silverbp.android.recognition

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Singleton wrapping the ML Kit GenAI Prompt API (Gemini Nano via AICore).
 *
 * Available on Pixel 9 / 10 series with Tensor G4 / G5; not on most other devices.
 * Caller flow:
 *  1. [isAvailable] gates whether the AICore backend can be selected at all.
 *  2. [preload] checks status, kicks off Gemini Nano model download if needed,
 *     warms the engine. Updates [ModelLoadStatus] for the Settings UI.
 *  3. [extract] runs an image+prompt request and parses [ExtractedReading].
 *  4. [tearDown] disposes the [GenerativeModel] so AICore can free memory.
 *
 * This sits parallel to [GemmaBpService] (LiteRT-LM) and [GeminiCloudRecognizer].
 * It deliberately re-uses [BpPrompt.systemAndExtract] and [BpResponseParser] so
 * the prompt / parser fork stays single-source.
 */
private const val TAG = "AICoreBpService"

object AICoreBpService {

    private val mutex = Mutex()
    @Volatile private var client: GenerativeModel? = null
    @Volatile private var warmed: Boolean = false

    /**
     * True iff Gemini Nano via AICore can run on this device (status is
     * AVAILABLE / DOWNLOADABLE / DOWNLOADING). False on UNAVAILABLE or any
     * exception (e.g. genai-prompt artifact stripped at install time).
     */
    suspend fun isAvailable(): Boolean = runCatching {
        when (ensureClient().checkStatus()) {
            FeatureStatus.AVAILABLE,
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> true
            else -> false
        }
    }.getOrElse { t ->
        android.util.Log.w(TAG, "isAvailable check failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * Make sure the Gemini Nano weights are on-device and the engine is warm.
     * Reports progress through [ServiceLocator.modelLoadStatus] so the existing
     * Settings download UI can render the same way it does for LiteRT-LM.
     */
    suspend fun preload(@Suppress("UNUSED_PARAMETER") context: Context) = mutex.withLock {
        if (warmed && client != null) return@withLock
        val m = ensureClient()
        val status = ServiceLocator.modelLoadStatus
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                when (m.checkStatus()) {
                    FeatureStatus.AVAILABLE -> {
                        status.set(ModelLoadPhase.Loading)
                        m.warmup()
                        warmed = true
                        status.set(ModelLoadPhase.Ready)
                        android.util.Log.i(
                            TAG,
                            "preload ok status=AVAILABLE baseModel=${baseModelName(m)} " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - t0}",
                        )
                    }
                    FeatureStatus.DOWNLOADABLE,
                    FeatureStatus.DOWNLOADING -> {
                        status.set(ModelLoadPhase.Downloading(0f, "gemini-nano"))
                        var totalToDownload = 0L
                        m.download().collect { ds ->
                            when (ds) {
                                is DownloadStatus.DownloadStarted -> {
                                    totalToDownload = ds.bytesToDownload
                                    android.util.Log.i(
                                        TAG,
                                        "download start bytesToDownload=$totalToDownload",
                                    )
                                }
                                is DownloadStatus.DownloadProgress -> {
                                    val frac = if (totalToDownload > 0L) {
                                        (ds.totalBytesDownloaded.toFloat() / totalToDownload.toFloat())
                                            .coerceIn(0f, 1f)
                                    } else 0f
                                    status.set(ModelLoadPhase.Downloading(frac, "gemini-nano"))
                                }
                                is DownloadStatus.DownloadCompleted -> {
                                    android.util.Log.i(TAG, "download complete")
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    android.util.Log.e(
                                        TAG,
                                        "download failed: ${ds.e.javaClass.simpleName}: ${ds.e.message}",
                                        ds.e,
                                    )
                                    throw ds.e
                                }
                            }
                        }
                        status.set(ModelLoadPhase.Loading)
                        m.warmup()
                        warmed = true
                        status.set(ModelLoadPhase.Ready)
                        android.util.Log.i(
                            TAG,
                            "preload ok status=DOWNLOADED+WARMED baseModel=${baseModelName(m)} " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - t0}",
                        )
                    }
                    FeatureStatus.UNAVAILABLE -> {
                        status.set(ModelLoadPhase.Failed("Gemini Nano not available on this device"))
                        error("Gemini Nano unavailable on this device")
                    }
                }
            } catch (t: Throwable) {
                status.set(ModelLoadPhase.Failed(t.message ?: "AICore preload failed"))
                android.util.Log.e(
                    TAG,
                    "preload failed elapsedMs=${SystemClock.elapsedRealtime() - t0} " +
                        "err=${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
                throw t
            }
        }
    }

    suspend fun extract(bitmap: Bitmap): ExtractedReading = withContext(Dispatchers.IO) {
        val tStart = SystemClock.elapsedRealtime()
        val m = client ?: throw BpExtractionError.ModelNotLoaded
        if (!warmed) throw BpExtractionError.ModelNotLoaded
        val systemOverride = ServiceLocator.userSettings.flow.first().systemPrompt
        val processed = bitmap.preprocessForOcr()
        val req = generateContentRequest(
            ImagePart(processed),
            TextPart(BpPrompt.systemAndExtract(systemOverride)),
        ) {
            temperature = 0.0f
            topK = 1
            candidateCount = 1
            maxOutputTokens = 256
        }
        val tInferStart = SystemClock.elapsedRealtime()
        val response = m.generateContent(req)
        val inferMs = SystemClock.elapsedRealtime() - tInferStart
        val text = response.candidates.firstOrNull()?.text
            ?: throw BpExtractionError.InvalidJson
        android.util.Log.d(TAG, "Gemini Nano raw response (${text.length} chars): $text")
        try {
            val parsed = BpResponseParser.parse(text)
            android.util.Log.i(
                TAG,
                "extract phase=full inferMs=$inferMs " +
                    "totalMs=${SystemClock.elapsedRealtime() - tStart} " +
                    "bitmapPx=${bitmap.width}x${bitmap.height} respChars=${text.length}",
            )
            parsed
        } catch (e: BpExtractionError) {
            android.util.Log.w(TAG, "Parse failed (${e.message}); raw was: ${text.take(500)}")
            throw e
        }
    }

    suspend fun tearDown() = mutex.withLock {
        try {
            client?.close()
        } catch (_: Throwable) { /* close is best-effort */ }
        client = null
        warmed = false
    }

    fun isLoaded(): Boolean = client != null && warmed

    /** Exposes the warmed model for AICoreChatService. Read-only; never close from outside. */
    fun modelForChat(): GenerativeModel? = if (warmed) client else null

    private fun ensureClient(): GenerativeModel {
        val existing = client
        if (existing != null) return existing
        return synchronized(this) {
            client ?: Generation.getClient().also { client = it }
        }
    }

    private suspend fun baseModelName(m: GenerativeModel): String =
        runCatching { m.getBaseModelName() }.getOrElse { "unknown(${it.javaClass.simpleName})" }
}
