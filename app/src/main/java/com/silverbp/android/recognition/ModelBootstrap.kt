package com.silverbp.android.recognition

import android.content.Context
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background lifecycle for the user-selected local model variant.
 *
 * - [start] (called from `SilverBpApplication.onCreate`) checks whether the
 *   currently-selected variant is already on disk and preloads it. No-op for
 *   the cloud backend or when the file isn't present yet.
 * - [downloadAndPreload] (wired to Settings → "下載並載入模型") fetches the
 *   selected variant from its catalog URL and preloads on completion.
 * - [switchTo] (called when the user picks a different variant) tears down
 *   the running engine and preloads the new variant if its file is present.
 */
object ModelBootstrap {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        val downloader = ModelDownloader(context.applicationContext)
        val status = ServiceLocator.modelLoadStatus
        scope.launch {
            val settings = ServiceLocator.userSettings.flow.first()
            when (settings.recognitionBackend) {
                RecognitionBackend.Cloud -> {
                    status.set(ModelLoadPhase.Ready)  // Cloud route doesn't need local preload
                }
                RecognitionBackend.AICore -> {
                    // AICore manages its own model bytes; warm it (and trigger
                    // download if needed) so the first capture isn't the slow path.
                    runCatching { AICoreBpService.preload(context.applicationContext) }
                        .onFailure { e ->
                            android.util.Log.w(
                                "ModelBootstrap",
                                "[ModelLoad] AICore preload at start failed: ${e.message}",
                            )
                        }
                }
                RecognitionBackend.Local -> {
                    val variant = ModelCatalog.byId(settings.selectedModelId)
                    if (downloader.isDownloaded(variant)) {
                        // Only sweep legacy orphans once the new file is in place,
                        // so a power-cut mid-rename can't strand the user.
                        cleanupLegacyModelFiles(context.applicationContext)
                        preload(context.applicationContext, variant)
                    } else if (downloader.hasPartialDownload(variant)) {
                        // An interrupted download left a `.part` file — resume it
                        // in the foreground worker (continues via the Range header).
                        ModelDownloadWorker.enqueue(context.applicationContext, variant.id)
                    }
                }
            }
        }
    }

    /**
     * Delete `.litertlm`/`.task` files in `filesDir/models/` that aren't claimed
     * by any current [ModelCatalog] variant — e.g. the pre-MTP
     * `gemma-4-E2B-it.litertlm` left over when the catalog moved to the
     * `-mtp` filename. Best-effort; logs but never throws.
     */
    private fun cleanupLegacyModelFiles(context: Context) {
        val dir = java.io.File(context.filesDir, "models")
        if (!dir.isDirectory) return
        val keep = ModelCatalog.variants.map { it.filename }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name in keep) return@forEach
            // Don't touch in-flight `.part` downloads.
            if (name.endsWith(".part")) return@forEach
            // Only sweep things that look like our model files.
            if (!name.endsWith(".litertlm") && !name.endsWith(".task")) return@forEach
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            android.util.Log.i(
                "ModelBootstrap",
                "[ModelLoad] legacy cleanup name=$name sizeBytes=${file.length()} deleted=$deleted",
            )
        }
    }

    /**
     * Settings-triggered "preload AICore (Gemini Nano)" — checks status,
     * downloads if needed, warms the engine. Mirrors [downloadAndPreload]
     * for the LiteRT-LM path. Errors surface through [ModelLoadStatus].
     */
    fun preloadAICore(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            // Tear down any running LiteRT-LM engine so we don't double-occupy GPU/RAM.
            GemmaBpService.tearDown()
            runCatching { AICoreBpService.preload(ctx) }
                .onFailure { e ->
                    android.util.Log.w(
                        "ModelBootstrap",
                        "[ModelLoad] AICore preload failed: ${e.message}",
                    )
                }
        }
    }

    /**
     * Kick off (or keep) the model download as a **foreground** WorkManager job
     * so it survives app-backgrounding / the screen leaving / the process being
     * reclaimed — and auto-resumes via the `.part` Range header after a kill.
     * Progress + final state still flow through [ServiceLocator.modelLoadStatus],
     * so callers and the UI are unchanged. See [ModelDownloadWorker].
     */
    fun downloadAndPreload(
        context: Context,
        variant: ModelVariant,
        hfToken: String? = null,
        sha256: String? = null,
    ) {
        ServiceLocator.modelLoadStatus.set(ModelLoadPhase.Downloading(0f, variant.id))
        ModelDownloadWorker.enqueue(context.applicationContext, variant.id, hfToken, sha256)
    }

    /**
     * Load the just-downloaded [variant] into the engine (Loading → Ready).
     * Public entry for [ModelDownloadWorker] to call on download completion;
     * thin wrapper over the shared [preload].
     */
    suspend fun preloadVariant(context: Context, variant: ModelVariant) =
        preload(context.applicationContext, variant)

    /**
     * Delete a downloaded variant to free space. If it's the currently-selected
     * (possibly loaded) variant, tear the engine down and reset to Idle so every
     * capture gate falls back to "not ready". selectedModelId is left pointing at
     * it — matches the existing "selected but not downloaded" state, so re-download
     * just works.
     */
    suspend fun deleteVariant(context: Context, variant: ModelVariant) {
        val ctx = context.applicationContext
        val downloader = ModelDownloader(ctx)
        val isCurrent = ServiceLocator.userSettings.flow.first().selectedModelId == variant.id
        if (isCurrent) {
            GemmaBpService.tearDown()
            ServiceLocator.modelLoadStatus.set(ModelLoadPhase.Idle)
        }
        val deleted = downloader.deleteVariant(variant)
        // Keep the persisted flag honest (used only for backup, not gating).
        ServiceLocator.userSettings.setModelDownloaded(
            ModelCatalog.variants.any { downloader.isDownloaded(it) },
        )
        android.util.Log.i(
            "ModelBootstrap",
            "[ModelLoad] delete id=${variant.id} deleted=$deleted current=$isCurrent",
        )
    }

    fun switchTo(context: Context, variant: ModelVariant) {
        val downloader = ModelDownloader(context.applicationContext)
        scope.launch {
            ServiceLocator.userSettings.setSelectedModelId(variant.id)
            GemmaBpService.tearDown()
            if (downloader.isDownloaded(variant)) {
                preload(context.applicationContext, variant)
            } else {
                ServiceLocator.modelLoadStatus.set(ModelLoadPhase.Idle)
            }
        }
    }

    /**
     * Tear down the running engine and re-preload the currently-selected variant.
     * Used by Settings when the user changes a tunable (e.g. maxNumTokens) that
     * is only read at engine init time.
     */
    fun reloadCurrentVariant(context: Context) {
        val downloader = ModelDownloader(context.applicationContext)
        scope.launch {
            val variantId = ServiceLocator.userSettings.flow.first().selectedModelId
            val variant = ModelCatalog.byId(variantId)
            GemmaBpService.tearDown()
            if (downloader.isDownloaded(variant)) {
                preload(context.applicationContext, variant)
            } else {
                ServiceLocator.modelLoadStatus.set(ModelLoadPhase.Idle)
            }
        }
    }

    /**
     * Release the native LiteRT engine and its OpenCL GPU context. Called from
     * [MainActivity.onDestroy] when the user exits the app — without this, the
     * leaked GPU context can wedge the device's GPU driver until reboot.
     *
     * Runs the suspending [GemmaBpService.tearDown] under a timeout so a stuck
     * native cleanup can't block the UI thread indefinitely. Best-effort: if
     * the timeout fires, we let the OS reclaim the process.
     */
    fun shutdown() {
        runBlocking {
            withTimeoutOrNull(2_000) {
                GemmaBpService.tearDown()
                AICoreBpService.tearDown()
            }
        }
    }

    private suspend fun preload(context: Context, variant: ModelVariant) {
        val status = ServiceLocator.modelLoadStatus
        val downloader = ModelDownloader(context)
        val settings = ServiceLocator.userSettings.flow.first()
        val maxTokens = settings.maxNumTokens
        // Speculative decoding requires both a user-side opt-in and an MTP-capable
        // .litertlm. 3n (.task / MediaPipe) variants stay false regardless.
        val speculative = settings.enableSpeculativeDecoding && variant.supportsSpeculativeDecoding
        status.set(ModelLoadPhase.Loading)
        try {
            GemmaBpService.preload(context, downloader.targetFile(variant), maxTokens, speculative)
            status.set(ModelLoadPhase.Ready)
        } catch (e: Throwable) {
            status.set(ModelLoadPhase.Failed(e.message ?: "preload failed"))
        }
    }
}
