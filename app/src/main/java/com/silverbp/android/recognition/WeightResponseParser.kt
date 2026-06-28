package com.silverbp.android.recognition

import com.silverbp.android.core.WeightUnit
import kotlinx.serialization.json.Json

/** Parses the model's JSON into [ExtractedWeight]; mirrors [MachineResponseParser]. */
object WeightResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Plausible human-body scale range, normalised to kg (~2–400 kg). */
    private const val MIN_KG = 2.0
    private const val MAX_KG = 400.0

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
        val value = result.value ?: throw BpExtractionError.InvalidJson
        // Range pre-check: a garbled OCR number must not be saved. Normalise to kg
        // via the on-screen unit (default kg) and reject anything outside a
        // plausible human-body range so the caller drops to manual entry.
        val kg = when (WeightUnit.fromRaw(result.unit ?: WeightUnit.Kg.raw)) {
            WeightUnit.Kg -> value
            WeightUnit.Lb -> WeightUnit.lbToKg(value)
        }
        if (kg !in MIN_KG..MAX_KG) throw BpExtractionError.InvalidReading("weightOutOfRange")
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
