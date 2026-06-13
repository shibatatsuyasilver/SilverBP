package com.silverbp.android.recognition

import kotlinx.serialization.Serializable

/**
 * Parsed output of the body-weight scale-display OCR model — the number shown on a
 * digital body-weight scale, plus its kg/lb unit.
 *
 * The model reads what is on screen; it does NOT judge reliability. [value] is the
 * weight as displayed and is nullable so a blank / unreadable display round-trips as
 * null rather than a fake zero. [unit] is the on-screen unit label as read — "kg" or
 * "lb"; when no label is visible the model infers it from the plausible range
 * (kg 30..250, lb 60..550) and may still leave it null when ambiguous. [confidence]
 * is the overall read confidence 0–1.
 */
@Serializable
data class ExtractedWeight(
    /** Weight as displayed, or null when the scale shows blank / dashes / unreadable. */
    val value: Double? = null,
    /** On-screen unit: "kg"|"lb"|null. Read the label; infer from range only if absent. */
    val unit: String? = null,
    /** Overall read confidence 0–1. */
    val confidence: Double? = null,
)
