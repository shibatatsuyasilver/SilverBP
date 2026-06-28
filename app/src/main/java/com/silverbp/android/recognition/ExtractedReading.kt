package com.silverbp.android.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExtractedReading(
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val pulse: Int? = null,
    @SerialName("irregular_heartbeat") val irregularHeartbeat: Boolean? = null,
    @SerialName("timestamp_on_device") val timestampOnDevice: String? = null,
    val confidence: Double? = null,
)

sealed class BpExtractionError(message: String) : RuntimeException(message) {
    data object InvalidJson : BpExtractionError("invalidJSON")
    data object MissingFields : BpExtractionError("missingFields")
    data class LowConfidence(val confidence: Double) : BpExtractionError("lowConfidence:$confidence")

    /**
     * Parsed values are physiologically implausible (e.g. systolic ≤ diastolic,
     * or a number well outside the device's measurable range). Shared across the
     * BP / glucose / weight parsers as a pre-save sanity gate. [reason] is an
     * internal short tag for logging — UI maps it to a localized message.
     */
    data class InvalidReading(val reason: String) : BpExtractionError("invalidReading:$reason")
    data object ModelNotLoaded : BpExtractionError("modelNotLoaded")

    /** No connectivity / DNS failure / timeout reaching the cloud API. */
    data object NetworkError : BpExtractionError("networkError")

    /** Cloud API returned a non-2xx status (401/403 bad key, 429 quota, 5xx). */
    data class ApiError(val code: Int) : BpExtractionError("apiError:$code")
}
