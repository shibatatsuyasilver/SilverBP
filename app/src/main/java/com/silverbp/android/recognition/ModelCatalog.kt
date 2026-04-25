package com.silverbp.android.recognition

/**
 * Catalog of locally-runnable VLM variants. The user picks one in Settings.
 * Each variant is downloaded into `filesDir/models/<filename>` and loaded by
 * [GemmaBpService.preload] when selected.
 *
 * Sizes are approximate (rounded to nearest 0.1 GB). Real on-disk size is
 * verified by [ModelDownloader] after download.
 */
data class ModelVariant(
    val id: String,
    val displayName: String,
    val filename: String,
    val downloadUrl: String,
    val approxSizeBytes: Long,
    val supportsVision: Boolean,
    val notes: String,
) {
    val approxSizeGB: Double get() = approxSizeBytes / 1_073_741_824.0
}

object ModelCatalog {
    val variants: List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma-4-E4B-it",
            displayName = "Gemma 4 E4B (旗艦,最佳精度)",
            filename = "gemma-4-E4B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            approxSizeBytes = 3_654_467_584L,  // 3.65 GB measured
            supportsVision = true,
            notes = "推薦給 8 GB+ RAM 旗艦機 (Pixel 8 Pro / S23+)。",
        ),
        ModelVariant(
            id = "gemma-4-E2B-it",
            displayName = "Gemma 4 E2B (中階機)",
            filename = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            approxSizeBytes = 2_147_483_648L,  // ~2 GB
            supportsVision = true,
            notes = "適合 6 GB RAM。精度略低於 E4B,但 30-40% 快。",
        ),
        ModelVariant(
            id = "gemma-3n-E4B-it",
            displayName = "Gemma 3n E4B (iOS 同款)",
            filename = "gemma-3n-E4B-it-int4.task",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
            approxSizeBytes = 4_400_000_000L,  // ~4.41 GB
            supportsVision = true,
            notes = "與 iOS 版一致的模型 (.task 格式)。",
        ),
    )

    val default: ModelVariant get() = variants.first()
    fun byId(id: String): ModelVariant = variants.firstOrNull { it.id == id } ?: default
}
