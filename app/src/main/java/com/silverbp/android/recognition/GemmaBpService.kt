package com.silverbp.android.recognition

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
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

object GemmaBpService {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var ctx: Context? = null

    suspend fun preload(context: Context, modelFile: File) = mutex.withLock {
        if (engine != null) return@withLock
        require(modelFile.exists()) { "Model file missing at ${modelFile.absolutePath}" }
        ctx = context.applicationContext
        withContext(Dispatchers.Default) {
            // Vision encoder allocates an internal {12, 1, 2520, 1, 2520} float32
            // attention buffer ≈ 290 MiB regardless of maxNumTokens, which exceeds
            // the Adreno 7xx Vulkan maxStorageBufferRange of 128 MiB. The 2520
            // dimension is hardcoded by the vision-transformer architecture, not
            // configurable from EngineConfig. Workaround: run the vision encoder
            // on CPU (no per-buffer limit, slower but works) while keeping the
            // language model on GPU.
            // GpuArtisan (OpenCL) was tried but the litert-community .litertlm
            // doesn't ship gpu_artisan subgraphs — "Unsupported backend: 2".
            val cfg = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = 2048,  // generous KV cache so vision tokens + ~500 prompt + output all fit with margin
                maxNumImages = 1,
            )
            try {
                engine = Engine(cfg).also { it.initialize() }
                android.util.Log.i(
                    TAG,
                    "LiteRT-LM engine initialized on GPU (model=${modelFile.name}, maxTokens=${cfg.maxNumTokens})",
                )
            } catch (t: Throwable) {
                android.util.Log.e(
                    TAG,
                    "GPU initialize failed for ${modelFile.name}. " +
                        "Likely GPU OOM — try a smaller variant (E2B) or lower maxNumTokens. " +
                        "Native error: ${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
                throw t
            }
        }
    }

    suspend fun tearDown() = mutex.withLock {
        try {
            engine?.close()
        } catch (_: Throwable) { /* engine close is best-effort */ }
        engine = null
    }

    suspend fun extract(bitmap: Bitmap): ExtractedReading = withContext(Dispatchers.Default) {
        val eng = engine ?: throw BpExtractionError.ModelNotLoaded
        val appCtx = ctx ?: throw BpExtractionError.ModelNotLoaded
        val processed = bitmap.preprocessForOcr()

        // Content.ImageFile takes a filesystem path; persist preprocessed bitmap to cache.
        val tmp = File(appCtx.cacheDir, "gemma/${UUID.randomUUID()}.jpg").apply {
            parentFile?.mkdirs()
        }
        FileOutputStream(tmp).use { processed.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        try {
            eng.createConversation().use { conversation ->
                val response = conversation.sendMessage(
                    Contents.of(
                        Content.ImageFile(tmp.absolutePath),
                        Content.Text(BpPrompt.systemAndExtract()),
                    )
                )
                val raw = response.toString()
                android.util.Log.d(TAG, "Gemma raw response (${raw.length} chars): $raw")
                try {
                    BpResponseParser.parse(raw)
                } catch (e: BpExtractionError) {
                    android.util.Log.w(TAG, "Parse failed (${e.message}); raw was: ${raw.take(500)}")
                    throw e
                }
            }
        } finally {
            tmp.delete()
        }
    }

    fun isLoaded(): Boolean = engine != null
}
