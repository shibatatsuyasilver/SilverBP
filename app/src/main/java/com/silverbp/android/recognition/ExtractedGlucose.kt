package com.silverbp.android.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed output of the blood-glucose-meter OCR model — the glucose analogue of
 * [ExtractedReading]. The model reads the number on a glucometer LCD AND detects
 * the on-screen unit; it does NOT judge clinical category (that is
 * [com.silverbp.android.core.GlucoseClassifier]'s job at confirm/save time).
 *
 * Field naming deliberately matches the canonical raw strings of the domain
 * enums so [com.silverbp.android.ui.confirm.ConfirmGlucoseViewModel] can map with
 * `GlucoseUnit.fromRaw(unit)` / `MeasureContext.fromRaw(measureContext)` without
 * a translation table:
 *  - [unit] is "mgdl" | "mmol" (matches `GlucoseUnit.raw`).
 *  - [measureContext] is "fasting" | "before_meal" | "after_meal" | "bedtime" |
 *    "random" (matches `MeasureContext.raw`), or null when the meter shows no
 *    meal marker — the confirm screen then asks the user.
 *
 * [confidence] is the model's read confidence after [GlucoseResponseParser]'s
 * unit/range cross-check (see [GlucosePrompt]); the parser LOWERS it when the
 * claimed unit contradicts the numeric range so the confirm screen can warn.
 */
@Serializable
data class ExtractedGlucose(
    /** The numeric value as shown on the meter, in [unit]. Null if unreadable. */
    val value: Double? = null,
    /** Detected display unit: "mgdl" | "mmol". Null when the meter hides the unit label. */
    val unit: String? = null,
    /** Meal/sleep context if the meter shows a marker; else null (user picks at confirm). */
    @SerialName("measure_context") val measureContext: String? = null,
    /** Timestamp printed on the meter, "YYYY-MM-DDTHH:mm" or null. Parsed in Kotlin. */
    @SerialName("timestamp_on_device") val timestampOnDevice: String? = null,
    /** Overall read confidence 0–1, after the unit/range cross-check. */
    val confidence: Double? = null,
)
