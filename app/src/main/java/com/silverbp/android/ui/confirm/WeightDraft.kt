package com.silverbp.android.ui.confirm

import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import java.time.Instant

/**
 * Mutable in-memory working copy of a weight reading — the weight analogue of
 * [GlucoseDraft]. The value is held in the [displayUnit] the user is editing
 * in (kg or lb) as raw text so partial input ("70.", "") never snaps; the
 * canonical kg is computed only at save time via [WeightReading.kgFrom].
 *
 * Photo bytes are NOT held here: the capture path (a later phase) persists the
 * JPEG under filesDir/photos during analysis and hands the filename through
 * [photoFilename], so a draft is fully process-death-serialisable through a
 * SavedStateHandle — a Bitmap would not survive that.
 */
data class WeightDraft(
    /** Raw text the user is typing, in [displayUnit]. Empty/partial is allowed mid-edit. */
    val valueText: String = "",
    val displayUnit: WeightUnit = WeightUnit.Kg,
    val timestamp: Instant = Instant.now(),
    val source: WeightSource = WeightSource.Manual,
    val confidence: Double = 1.0,
    val note: String = "",
    val photoFilename: String? = null,
    /**
     * Member this reading is attributed to. Defaults to the current selection
     * when a new draft is created; empty resolves to the owner in
     * [com.silverbp.android.core.WeightRepository.upsert].
     */
    val memberId: String = "",
) {
    /** The parsed numeric value in [displayUnit], or null while the field is empty/partial. */
    val parsedValue: Double?
        get() = valueText.replace(",", ".").toDoubleOrNull()

    /**
     * Valid when the parsed value lands in a plausible human body-weight range
     * for the unit: kg scales ~2–500, lb ~4.4–1102 (1 lb = 0.453592 kg).
     */
    val isValid: Boolean
        get() = parsedValue?.let { v ->
            when (displayUnit) {
                WeightUnit.Kg -> v in 2.0..500.0
                WeightUnit.Lb -> v in 4.4..1102.0
            }
        } ?: false

    /** Canonical kg for the current value (0.0 when the field is empty/partial). */
    val valueKg: Double
        get() = parsedValue?.let { WeightReading.kgFrom(it, displayUnit) } ?: 0.0

    /**
     * Re-express the current value in [target] without losing what the user typed
     * — used by the kg ↔ lb toggle so the displayed number converts.
     */
    fun convertedTo(target: WeightUnit): WeightDraft {
        if (target == displayUnit) return this
        val v = parsedValue ?: return copy(displayUnit = target)
        val newValue = when (target) {
            WeightUnit.Kg -> WeightUnit.lbToKg(v)
            WeightUnit.Lb -> WeightUnit.kgToLb(v)
        }
        return copy(displayUnit = target, valueText = formatValue(newValue, target))
    }

    fun toReading(photoFilename: String? = null) = WeightReading(
        memberId = memberId,
        valueKg = valueKg,
        displayUnit = displayUnit,
        timestamp = timestamp,
        source = source,
        confidence = confidence,
        note = note,
        photoFilename = photoFilename ?: this.photoFilename,
    )

    companion object {
        fun fromReading(r: WeightReading) = WeightDraft(
            valueText = formatValue(r.valueIn(r.displayUnit), r.displayUnit),
            displayUnit = r.displayUnit,
            timestamp = r.timestamp,
            source = r.source,
            confidence = r.confidence,
            note = r.note,
            photoFilename = r.photoFilename,
            memberId = r.memberId,
        )

        /**
         * Format a value for the editable field. Body weight is meaningful to
         * one decimal in both units (kg scales read 0.1 kg; lb to 0.1 lb), and
         * keeping a single decimal makes the kg ↔ lb toggle a near-lossless
         * display round-trip rather than truncating across an integer.
         */
        fun formatValue(value: Double, unit: WeightUnit): String =
            String.format(java.util.Locale.US, "%.1f", value)
    }
}
