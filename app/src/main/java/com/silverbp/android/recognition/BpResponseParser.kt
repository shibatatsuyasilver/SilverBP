package com.silverbp.android.recognition

import kotlinx.serialization.json.Json

object BpResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, minimumConfidence: Double = 0.5): ExtractedReading {
        val cleaned = stripFences(raw)
        val lo = cleaned.indexOf('{')
        val hi = cleaned.lastIndexOf('}')
        if (lo < 0 || hi <= lo) throw BpExtractionError.InvalidJson
        val r = try {
            json.decodeFromString<ExtractedReading>(cleaned.substring(lo, hi + 1))
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
        if (r.systolic == null || r.diastolic == null) throw BpExtractionError.MissingFields
        // Physiological sanity gate: reject parses that fall outside what a cuff
        // can measure before they ever reach the confirm screen.
        val sys = r.systolic
        val dia = r.diastolic
        if (sys !in 40..300) throw BpExtractionError.InvalidReading("systolicRange")
        if (dia !in 30..200) throw BpExtractionError.InvalidReading("diastolicRange")
        if (sys <= dia) throw BpExtractionError.InvalidReading("systolicNotAboveDiastolic")
        r.pulse?.let { if (it !in 20..300) throw BpExtractionError.InvalidReading("pulseRange") }
        val c = r.confidence ?: 1.0
        if (c < minimumConfidence) throw BpExtractionError.LowConfidence(c)
        return r
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
