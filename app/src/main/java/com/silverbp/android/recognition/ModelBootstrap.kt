package com.silverbp.android.recognition

import android.content.Context
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            if (settings.recognitionBackend == RecognitionBackend.Cloud) {
                status.set(ModelLoadPhase.Ready)  // Cloud route doesn't need local preload
                return@launch
            }
            val variant = ModelCatalog.byId(settings.selectedModelId)
            if (downloader.isDownloaded(variant)) {
                preload(context.applicationContext, variant)
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
                status.set(ModelLoadPhase.Downloading(0f))
                downloader.download(variant, sha256, hfToken).collect { p ->
                    status.set(ModelLoadPhase.Downloading(p.fraction))
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

    private suspend fun preload(context: Context, variant: ModelVariant) {
        val status = ServiceLocator.modelLoadStatus
        val downloader = ModelDownloader(context)
        status.set(ModelLoadPhase.Loading)
        try {
            GemmaBpService.preload(context, downloader.targetFile(variant))
            status.set(ModelLoadPhase.Ready)
        } catch (e: Throwable) {
            status.set(ModelLoadPhase.Failed(e.message ?: "preload failed"))
        }
    }
}
