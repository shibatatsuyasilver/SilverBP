package com.silverbp.android.recognition

import kotlinx.serialization.json.Json

/**
 * Parses the glucose model's JSON into [ExtractedGlucose]; mirrors
 * [BpResponseParser]'s extraction idiom (strip Markdown fences, slice the first
 * `{`…last `}`, lenient decode tolerating unknown keys).
 *
 * Adds a UNIT/RANGE cross-check on top of the parse: because 1 mmol/L = 18.016
 * mg/dL, a wrong unit is an ~18× error, so when the model's claimed [unit]
 * contradicts the numeric range of [value] we LOWER the confidence (capped at
 * [CONFLICT_MAX_CONFIDENCE]) and, when the unit was missing, INFER it from the
 * range. This runs independently of the prompt's own instruction so the confirm
 * screen always gets a calibrated confidence to warn on.
 */
object GlucoseResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A value with a fractional part below this is almost certainly mmol/L. */
    private const val MMOL_MAX = 35.0

    /** A 2–3 digit integer at/above this is almost certainly mg/dL. */
    private const val MGDL_MIN = 40.0

    /** Confidence ceiling when the claimed unit conflicts with the numeric range. */
    private const val CONFLICT_MAX_CONFIDENCE = 0.4

    fun parse(raw: String): ExtractedGlucose {
        val cleaned = stripFences(raw)
        val lo = cleaned.indexOf('{')
        val hi = cleaned.lastIndexOf('}')
        if (lo < 0 || hi <= lo) throw BpExtractionError.InvalidJson
        val parsed = try {
            json.decodeFromString<ExtractedGlucose>(cleaned.substring(lo, hi + 1))
        } catch (e: Exception) {
            throw BpExtractionError.InvalidJson
        }
        // Nothing usable read → failed recognition so the caller can fall back to
        // manual entry (mirrors NutritionResponseParser's empty check). A glucose
        // read is useless without a value.
        val value = parsed.value ?: throw BpExtractionError.MissingFields
        return crossCheckUnit(parsed, value)
    }

    /**
     * Reconcile the claimed [ExtractedGlucose.unit] with [value]'s range. Returns
     * a copy with the unit inferred (if it was null) and confidence lowered when
     * the claim and range disagree.
     */
    private fun crossCheckUnit(parsed: ExtractedGlucose, value: Double): ExtractedGlucose {
        val claimed = parsed.unit?.lowercase()
        val looksMmol = value < MMOL_MAX
        val looksMgdl = value >= MGDL_MIN
        val baseConfidence = parsed.confidence ?: 1.0

        return when (claimed) {
            "mmol" ->
                if (!looksMmol) {
                    // Claimed mmol/L but value is too high for it (e.g. 137) → conflict.
                    parsed.copy(confidence = minOf(baseConfidence, CONFLICT_MAX_CONFIDENCE))
                } else parsed
            "mgdl", "mg/dl" ->
                if (!looksMgdl) {
                    // Claimed mg/dL but value is too low for it (e.g. 6.5) → conflict.
                    parsed.copy(unit = "mgdl", confidence = minOf(baseConfidence, CONFLICT_MAX_CONFIDENCE))
                } else parsed.copy(unit = "mgdl")
            else -> {
                // Unit missing/unknown → infer from range when unambiguous; if the
                // value sits in the overlap (35–40) leave unit null for the user.
                val inferred = when {
                    looksMmol && !looksMgdl -> "mmol"
                    looksMgdl && !looksMmol -> "mgdl"
                    else -> null
                }
                // Inferring (rather than reading) a unit is slightly less certain.
                val conf = if (inferred != null) minOf(baseConfidence, 0.9) else baseConfidence
                parsed.copy(unit = inferred, confidence = conf)
            }
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
