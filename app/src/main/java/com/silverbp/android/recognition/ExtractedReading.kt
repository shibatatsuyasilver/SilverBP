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
    data object ModelNotLoaded : BpExtractionError("modelNotLoaded")
}
