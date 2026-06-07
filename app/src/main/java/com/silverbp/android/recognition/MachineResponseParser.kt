package com.silverbp.android.recognition

import kotlinx.serialization.json.Json

/** Parses the model's JSON into [ExtractedMachineWorkout]; mirrors [NutritionResponseParser]. */
object MachineResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): ExtractedMachineWorkout {
        val cleaned = stripFences(raw)
        val lo = cleaned.indexOf('{')
        val hi = cleaned.lastIndexOf('}')
        if (lo < 0 || hi <= lo) throw BpExtractionError.InvalidJson
        val result = try {
            json.decodeFromString<ExtractedMachineWorkout>(cleaned.substring(lo, hi + 1))
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
        // Nothing usable read → treat as a failed recognition so the caller can
        // fall back to manual entry (mirrors NutritionResponseParser's empty check).
        val hasAnything = result.timeText != null ||
            result.distanceValue != null ||
            result.calories != null ||
            result.heartRate != null ||
            result.metrics.isNotEmpty()
        if (!hasAnything) throw BpExtractionError.InvalidJson
        return result
    }

    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s
    }
}
