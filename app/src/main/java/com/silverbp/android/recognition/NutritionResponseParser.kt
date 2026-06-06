package com.silverbp.android.recognition

import kotlinx.serialization.json.Json

/** Parses the model's JSON into [ExtractedNutrition]; mirrors [BpResponseParser]. */
object NutritionResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): ExtractedNutrition {
        val cleaned = stripFences(raw)
        val lo = cleaned.indexOf('{')
        val hi = cleaned.lastIndexOf('}')
        if (lo < 0 || hi <= lo) throw BpExtractionError.InvalidJson
        return try {
            json.decodeFromString<ExtractedNutrition>(cleaned.substring(lo, hi + 1))
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
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
