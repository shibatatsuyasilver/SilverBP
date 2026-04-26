package com.silverbp.android.recognition

import android.os.Build
import android.util.Log

/**
 * SoC-driven decisions for the LiteRT-LM inference path.
 *
 * Background: the vision encoder allocates an internal {12, 1, 2520, 1, 2520}
 * float32 attention buffer (~290 MiB). Adreno 7xx (Snapdragon 8 Gen 3) has a
 * Vulkan maxStorageBufferRange of 128 MiB, so vision-on-GPU init crashes there.
 * Earlier commit faa8f42 hardcoded vision to CPU as a workaround, which is
 * correct for Adreno 7xx but unnecessarily slow on every other GPU
 * (notably Pixel Tensor). This object scopes the workaround to known-bad SoCs.
 */
object DeviceCapabilities {
    private const val TAG = "DeviceCaps"

    enum class VisionBackend { GPU, CPU }

    /**
     * SoCs whose GPU is known/expected to fail on the vision encoder's
     * 290 MiB attention buffer. Conservative list — unknown devices get the
     * GPU attempt with a CPU retry fallback in [GemmaBpService.preload].
     */
    private val DENYLISTED_SOC_MODELS = setOf(
        "SM8650",       // Snapdragon 8 Gen 3 (Adreno 750) — confirmed failing
        "SM8650-AB",
        "SM8635",       // Snapdragon 8s Gen 3 (Adreno 735)
        "SM8550",       // Snapdragon 8 Gen 2 (Adreno 740) — same 128 MiB ceiling reported
    )

    /** Lower-cased Build.HARDWARE substrings used when SOC_MODEL is empty. */
    private val DENYLISTED_HARDWARE_HINTS = setOf(
        "pineapple",    // Snapdragon 8 Gen 3 codename
        "kalama",       // Snapdragon 8 Gen 2 codename
    )

    fun recommendedVisionBackend(): VisionBackend {
        val soc = currentSocModel()
        if (soc in DENYLISTED_SOC_MODELS) {
            Log.i(TAG, "vision backend = CPU (denylisted SOC_MODEL=$soc)")
            return VisionBackend.CPU
        }
        if (soc.isEmpty()) {
            val hw = Build.HARDWARE.lowercase()
            if (DENYLISTED_HARDWARE_HINTS.any { hw.contains(it) }) {
                Log.i(TAG, "vision backend = CPU (HARDWARE hint=$hw, SOC_MODEL empty)")
                return VisionBackend.CPU
            }
        }
        Log.i(TAG, "vision backend = GPU (SOC_MODEL=$soc HARDWARE=${Build.HARDWARE})")
        return VisionBackend.GPU
    }

    fun currentSocModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""

    private fun currentSocManufacturer(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""

    fun logFingerprint() {
        Log.i(
            TAG,
            "device fingerprint MODEL=${Build.MODEL} MANUFACTURER=${Build.MANUFACTURER} " +
                "SOC_MODEL=${currentSocModel()} SOC_MFG=${currentSocManufacturer()} " +
                "HARDWARE=${Build.HARDWARE} BOARD=${Build.BOARD} " +
                "ABI=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
        )
    }
}
