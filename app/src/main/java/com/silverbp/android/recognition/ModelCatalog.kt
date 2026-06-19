package com.silverbp.android.recognition

import androidx.annotation.StringRes
import com.silverbp.android.BuildConfig
import com.silverbp.android.R

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
    @StringRes val displayNameRes: Int,
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
    @StringRes val notesRes: Int,
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
    // SHA-256 hashes are the HuggingFace LFS OIDs of the resolved download
    // targets (the OID *is* the file's sha256), captured 2026-05-30. They are
    // verified after download by ModelDownloader. NOTE: litert-community has
    // re-uploaded these files in place before; if upstream changes the file, the
    // download will fail "sha256 mismatch" until the hash here is refreshed
    // (`curl -sIL <resolve-url> | grep -i x-linked-etag`, or `shasum -a 256`).
    private val releaseVariants: List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma-4-E2B-it",
            displayNameRes = R.string.model_gemma4_e2b_name,
            // -mtp suffix forces a re-download for users on the pre-2026-05-05 file
            // (litert-community re-uploaded the URL in place to add MTP heads).
            filename = "gemma-4-E2B-it-mtp.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            approxSizeBytes = 2_147_483_648L,  // ~2 GB
            supportsVision = true,
            supportsSpeculativeDecoding = true,
            notesRes = R.string.model_gemma4_e2b_notes,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        ),
        ModelVariant(
            id = "gemma-4-E4B-it",
            displayNameRes = R.string.model_gemma4_e4b_name,
            filename = "gemma-4-E4B-it-mtp.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            approxSizeBytes = 3_654_467_584L,  // 3.65 GB measured (pre-MTP); re-measure after download
            supportsVision = true,
            supportsSpeculativeDecoding = true,
            notesRes = R.string.model_gemma4_e4b_notes,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        ),
    )

    private val debugOnlyVariants: List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma-3n-E4B-it",
            displayNameRes = R.string.model_gemma3n_e4b_name,
            filename = "gemma-3n-E4B-it-int4.task",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
            approxSizeBytes = 4_400_000_000L,  // ~4.41 GB
            supportsVision = true,
            supportsSpeculativeDecoding = false,
            notesRes = R.string.model_gemma3n_e4b_notes,
            // Debug-only until an authenticated SHA-256 pin is added for release.
        ),
    )

    val variants: List<ModelVariant> =
        if (BuildConfig.DEBUG) releaseVariants + debugOnlyVariants else releaseVariants

    val default: ModelVariant get() = variants.first()
    fun byId(id: String): ModelVariant = variants.firstOrNull { it.id == id } ?: default
}
