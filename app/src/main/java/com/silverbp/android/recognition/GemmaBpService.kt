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
object GemmaBpService {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var ctx: Context? = null

    suspend fun preload(context: Context, modelFile: File) = mutex.withLock {
        if (engine != null) return@withLock
        require(modelFile.exists()) { "Model file missing at ${modelFile.absolutePath}" }
        ctx = context.applicationContext
        withContext(Dispatchers.Default) {
            // GPU backend (OpenCL) is required for Gemma 4 .litertlm — the model
            // ships GPU subgraphs only. CPU backend crashes inside nativeCreateEngine
            // because there are no CPU prefill/decode subgraphs. Real phones
            // (Adreno/Mali) ship OpenCL drivers; emulator doesn't, so on emulator
            // sendMessage will fail with "Can not find OpenCL library on this device".
            // Use :cli on macOS or a real device for actual recognition testing.
            val cfg = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
            )
            engine = Engine(cfg).also { it.initialize() }
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
                BpResponseParser.parse(response.toString())
            }
        } finally {
            tmp.delete()
        }
    }

    fun isLoaded(): Boolean = engine != null
}
