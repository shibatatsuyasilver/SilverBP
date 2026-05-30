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
    /**
     * True when the variant ships embedded MTP (Multi-Token Prediction) heads
     * usable by LiteRT-LM v0.11.0+ speculative decoding (~2x decode speedup).
     * Gated on a user setting in [com.silverbp.android.settings.UserSettings].
     */
    val supportsSpeculativeDecoding: Boolean,
    val notes: String,
    /**
     * Lowercase hex SHA-256 of the downloaded file. When non-null,
     * [ModelDownloader.download] verifies the bytes after download and aborts
     * on mismatch (supply-chain / corrupt-download guard). Null = no check.
     *
     * To populate for an LFS-hosted HuggingFace file, the OID *is* the SHA-256:
     *   curl -sI -H "Authorization: Bearer $HF_TOKEN" <resolve-url> | grep -i x-linked-etag
     * or compute locally once downloaded: `shasum -a 256 <file>`.
     */
    val sha256: String? = null,
) {
    val approxSizeGB: Double get() = approxSizeBytes / 1_073_741_824.0
}

object ModelCatalog {
    val variants: List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma-4-E2B-it",
            displayName = "Gemma 4 E2B (推薦)",
            // -mtp suffix forces a re-download for users on the pre-2026-05-05 file
            // (litert-community re-uploaded the URL in place to add MTP heads).
            filename = "gemma-4-E2B-it-mtp.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            approxSizeBytes = 2_147_483_648L,  // ~2 GB
            supportsVision = true,
            supportsSpeculativeDecoding = true,
            notes = "推薦給多數裝置 (約 0.7 GB GPU 記憶體)。MTP 加速 ~2x decode。",
        ),
        ModelVariant(
            id = "gemma-4-E4B-it",
            displayName = "Gemma 4 E4B (高精度)",
            filename = "gemma-4-E4B-it-mtp.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            approxSizeBytes = 3_654_467_584L,  // 3.65 GB measured (pre-MTP); re-measure after download
            supportsVision = true,
            supportsSpeculativeDecoding = true,
            notes = "需 GPU 記憶體較多 (旗艦機如 S24 Ultra)。MTP 加速 ~2x decode。",
        ),
        ModelVariant(
            id = "gemma-3n-E4B-it",
            displayName = "Gemma 3n E4B (iOS 同款)",
            filename = "gemma-3n-E4B-it-int4.task",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
            approxSizeBytes = 4_400_000_000L,  // ~4.41 GB
            supportsVision = true,
            supportsSpeculativeDecoding = false,
            notes = "與 iOS 版一致的模型 (.task 格式)。",
        ),
    )

    val default: ModelVariant get() = variants.first()
    fun byId(id: String): ModelVariant = variants.firstOrNull { it.id == id } ?: default
}
