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
                                "AICore preload at start failed: ${e.message}",
                            )
                        }
                }
                RecognitionBackend.Local -> {
                    val variant = ModelCatalog.byId(settings.selectedModelId)
                    if (downloader.isDownloaded(variant)) {
                        preload(context.applicationContext, variant)
                    }
                }
            }
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
                    android.util.Log.w("ModelBootstrap", "AICore preload failed: ${e.message}")
                }
        }
    }

    fun downloadAndPreload(
        context: Context,
        variant: ModelVariant,
        hfToken: String? = null,
        sha256: String? = null,
    ) {
        val downloader = ModelDownloader(context.applicationContext)
        val status = ServiceLocator.modelLoadStatus
        scope.launch {
            try {
                status.set(ModelLoadPhase.Downloading(0f, variant.id))
                downloader.download(variant, sha256, hfToken).collect { p ->
                    status.set(ModelLoadPhase.Downloading(p.fraction, variant.id))
                }
                preload(context.applicationContext, variant)
                ServiceLocator.userSettings.setModelDownloaded(true)
            } catch (e: Throwable) {
                status.set(ModelLoadPhase.Failed(e.message ?: "download failed"))
            }
        }
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
        val maxTokens = ServiceLocator.userSettings.flow.first().maxNumTokens
        status.set(ModelLoadPhase.Loading)
        try {
            GemmaBpService.preload(context, downloader.targetFile(variant), maxTokens)
            status.set(ModelLoadPhase.Ready)
        } catch (e: Throwable) {
            status.set(ModelLoadPhase.Failed(e.message ?: "preload failed"))
        }
    }
}
