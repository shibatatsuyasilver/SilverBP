package com.silverbp.android.recognition

import kotlinx.serialization.json.Json

/** Parses the model's JSON into [ExtractedWeight]; mirrors [MachineResponseParser]. */
object WeightResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): ExtractedWeight {
        val cleaned = stripFences(raw)
        val lo = cleaned.indexOf('{')
        val hi = cleaned.lastIndexOf('}')
        if (lo < 0 || hi <= lo) throw BpExtractionError.InvalidJson
        val result = try {
            json.decodeFromString<ExtractedWeight>(cleaned.substring(lo, hi + 1))
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
        // A weight read is useless without a value → treat as a failed recognition
        // so the caller can fall back to manual entry (mirrors MachineResponseParser's
        // empty check).
        if (result.value == null) throw BpExtractionError.InvalidJson
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
