package com.silverbp.android.recognition

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Singleton VLM service backed by LiteRT-LM (Google AI Edge).
 *
 * Mirrors iOS GemmaBPService 1:1:
 *  - [preload] is idempotent and safe to call multiple times.
 *  - [extract] runs multimodal (image + text) inference and returns the parsed [ExtractedReading].
 *  - [tearDown] disposes the current engine so a different model variant can be loaded.
 *
 * The model file is downloaded by [ModelDownloader] on first launch.
 */
private const val TAG = "GemmaBpService"

/**
 * Mirrors iOS [Mem ...] snapshots in BPRecognition/GemmaBPService.swift.
 * iOS reports MLX active/cache/peak; on Android we don't have an equivalent
 * cache instrumentation in LiteRT-LM, so we report JVM heap + native heap +
 * device-available RAM, which is the closest like-for-like signal.
 */
private fun memSnapshot(context: Context?, tag: String) {
    val mb: (Long) -> String = { "%.0f MB".format(it.toDouble() / 1024 / 1024) }
    val rt = Runtime.getRuntime()
    val heapUsed = rt.totalMemory() - rt.freeMemory()
    val native = Debug.getNativeHeapAllocatedSize()
    val avail = context?.let {
        val info = ActivityManager.MemoryInfo()
        (it.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
        info.availMem
    } ?: 0L
    android.util.Log.i(
        TAG,
        "[Mem $tag] footprint=${mb(heapUsed + native)} avail=${mb(avail)} " +
            "heap=${mb(heapUsed)} native=${mb(native)}",
    )
}

object GemmaBpService {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var ctx: Context? = null

    @OptIn(ExperimentalApi::class)
    suspend fun preload(
        context: Context,
        modelFile: File,
        maxNumTokens: Int,
        enableSpeculativeDecoding: Boolean,
    ) = mutex.withLock {
        if (engine != null) return@withLock
        require(modelFile.exists()) { "Model file missing at ${modelFile.absolutePath}" }
        ctx = context.applicationContext
        withContext(Dispatchers.Default) {
            // The vision encoder allocates an internal {12, 1, 2520, 1, 2520}
            // float32 attention buffer (~290 MiB) regardless of maxNumTokens; the
            // 2520 dim is hardcoded by the ViT architecture, not configurable.
            // Adreno 7xx (Snapdragon 8 Gen 3) has Vulkan maxStorageBufferRange =
            // 128 MiB and crashes vision-on-GPU init. [DeviceCapabilities]
            // denylists those SoCs (CPU only); everything else attempts GPU first
            // and falls back to CPU automatically if init throws. Users can also
            // pin a backend in Settings via [VisionBackendOverride].
            // GpuArtisan (OpenCL) was tried for the LLM side but the
            // litert-community .litertlm doesn't ship gpu_artisan subgraphs —
            // "Unsupported backend: 2".
            val override = ServiceLocator.userSettings.flow.first().visionBackendOverride
            val visionAttempts = visionAttemptOrder(override)
            // Build a (vision, speculative) attempt grid so we can fall back from
            // speculative=true → speculative=false on the same vision backend if
            // the MTP heads reject the device. Engine init reads
            // ExperimentalFlags.enableSpeculativeDecoding at construction time.
            val attempts: List<Pair<DeviceCapabilities.VisionBackend, Boolean>> =
                if (enableSpeculativeDecoding) {
                    visionAttempts.map { it to true } + visionAttempts.map { it to false }
                } else {
                    visionAttempts.map { it to false }
                }
            var lastErr: Throwable? = null
            for ((index, attempt) in attempts.withIndex()) {
                val (choice, speculative) = attempt
                ExperimentalFlags.enableSpeculativeDecoding = speculative
                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = choice.toBackend(),
                    maxNumTokens = maxNumTokens,
                    maxNumImages = 1,
                )
                val attemptLabel = "${index + 1}/${attempts.size}"
                val t0 = SystemClock.elapsedRealtime()
                try {
                    engine = Engine(cfg).also { it.initialize() }
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    android.util.Log.i(
                        TAG,
                        "[ModelLoad] preload ok vision=$choice speculative=$speculative " +
                            "attempt=$attemptLabel elapsedMs=$elapsed model=${modelFile.name} " +
                            "maxTokens=$maxNumTokens override=$override",
                    )
                    return@withContext
                } catch (t: Throwable) {
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    lastErr = t
                    android.util.Log.w(
                        TAG,
                        "[ModelLoad] preload failed vision=$choice speculative=$speculative " +
                            "attempt=$attemptLabel elapsedMs=$elapsed model=${modelFile.name} " +
                            "err=${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }
            val cause = lastErr
                ?: IllegalStateException("preload exhausted attempts with no recorded error")
            android.util.Log.e(
                TAG,
                "[ModelLoad] preload exhausted all vision backends for ${modelFile.name}. " +
                    "Likely GPU OOM — try a smaller variant (E2B), lower maxNumTokens, " +
                    "or set Settings → Vision encoder backend → Force CPU.",
                cause,
            )
            throw cause
        }
    }

    private fun visionAttemptOrder(
        override: VisionBackendOverride,
    ): List<DeviceCapabilities.VisionBackend> = when (override) {
        VisionBackendOverride.ForceGPU -> listOf(DeviceCapabilities.VisionBackend.GPU)
        VisionBackendOverride.ForceCPU -> listOf(DeviceCapabilities.VisionBackend.CPU)
        VisionBackendOverride.Auto -> when (DeviceCapabilities.recommendedVisionBackend()) {
            DeviceCapabilities.VisionBackend.GPU ->
                listOf(DeviceCapabilities.VisionBackend.GPU, DeviceCapabilities.VisionBackend.CPU)
            DeviceCapabilities.VisionBackend.CPU ->
                listOf(DeviceCapabilities.VisionBackend.CPU)
        }
    }

    private fun DeviceCapabilities.VisionBackend.toBackend(): Backend = when (this) {
        DeviceCapabilities.VisionBackend.GPU -> Backend.GPU()
        DeviceCapabilities.VisionBackend.CPU -> Backend.CPU()
    }

    suspend fun tearDown() = mutex.withLock {
        try {
            engine?.close()
        } catch (_: Throwable) { /* engine close is best-effort */ }
        engine = null
    }

    suspend fun extract(bitmap: Bitmap): ExtractedReading = withContext(Dispatchers.Default) {
        android.util.Log.i(TAG, "[Extract] entered")
        memSnapshot(ctx, "pre-preload")
        val tExtractStart = SystemClock.elapsedRealtime()
        val eng = engine ?: throw BpExtractionError.ModelNotLoaded
        val appCtx = ctx ?: throw BpExtractionError.ModelNotLoaded
        android.util.Log.i(TAG, "[Extract] container ready")
        memSnapshot(appCtx, "post-load")
        val systemOverride = ServiceLocator.userSettings.flow.first().systemPrompt
        val processed = bitmap.preprocessForOcr()
        android.util.Log.i(TAG, "[Extract] preprocess done")

        // Content.ImageFile takes a filesystem path; persist preprocessed bitmap to cache.
        val tmp = File(appCtx.cacheDir, "gemma/${UUID.randomUUID()}.jpg").apply {
            parentFile?.mkdirs()
        }
        FileOutputStream(tmp).use { processed.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        try {
            eng.createConversation().use { conversation ->
                android.util.Log.i(TAG, "[Extract] generate() starting stream")
                val tInferStart = SystemClock.elapsedRealtime()
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.ImageFile(tmp.absolutePath),
                        Content.Text(BpPrompt.systemAndExtract(systemOverride)),
                    )
                )
                val inferMs = SystemClock.elapsedRealtime() - tInferStart
                val raw = response.toString()
                android.util.Log.i(TAG, "[Extract] stream done, ${raw.length} chars")
                memSnapshot(appCtx, "post-generate")
                android.util.Log.i(TAG, "[Extract] raw output: ${raw.take(200)}")
                try {
                    val parsed = BpResponseParser.parse(raw)
                    android.util.Log.i(
                        TAG,
                        "[Extract] complete inferMs=$inferMs " +
                            "totalMs=${SystemClock.elapsedRealtime() - tExtractStart} " +
                            "bitmapPx=${bitmap.width}x${bitmap.height} respChars=${raw.length}",
                    )
                    parsed
                } catch (e: BpExtractionError) {
                    android.util.Log.w(TAG, "[Extract] parse failed (${e.message}); raw was: ${raw.take(500)}")
                    throw e
                }
            }
        } finally {
            tmp.delete()
        }
    }

    fun isLoaded(): Boolean = engine != null

    /**
     * Expose the warmed engine to [com.silverbp.android.recognition.chat.GemmaChatService]
     * so chat reuses the same LiteRT-LM instance instead of loading a second copy.
     * Returns null until [preload] has succeeded.
     */
    internal fun engineOrNull(): Engine? = engine

    internal fun appContextOrNull(): Context? = ctx
}
