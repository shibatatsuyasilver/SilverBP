package com.silverbp.android.ui.confirm

import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import java.time.Instant

/**
 * Mutable in-memory working copy of a weight reading — the weight analogue of
 * [GlucoseDraft]. The value is held in the [displayUnit] the user is editing in
 * (kg or lb) as raw text so partial input ("65.", "") never snaps; the canonical
 * kg is computed only at save time via [WeightReading.kgFrom].
 *
 * Manual entry only this round (scale OCR is backlog), so unlike the glucose
 * draft there is no photo bitmap and no [photoFilename] capture path — every
 * field is a primitive, so the whole draft survives process death through
 * [com.silverbp.android.ui.confirm.ConfirmWeightViewModel]'s SavedStateHandle (M5).
 */
data class WeightDraft(
    /** Raw text the user is typing, in [displayUnit]. Empty/partial is allowed mid-edit. */
    val valueText: String = "",
    val displayUnit: WeightUnit = WeightUnit.Kg,
    val timestamp: Instant = Instant.now(),
    val source: WeightSource = WeightSource.Manual,
    val note: String = "",
    /**
     * Member this reading is attributed to (v20). Defaults to the current
     * selection when a new draft is created; empty resolves to the owner in
     * [com.silverbp.android.core.WeightRepository.upsert].
     */
    val memberId: String = "",
) {
    /** The parsed numeric value in [displayUnit], or null while the field is empty/partial. */
    val parsedValue: Double?
        get() = valueText.replace(",", ".").toDoubleOrNull()

    /**
     * Valid when the parsed value lands in a plausible human-body range for the
     * unit: kg scales read ~2–500, lb ~4–1100 (1 kg = 2.20462 lb).
     */
    val isValid: Boolean
        get() = parsedValue?.let { v ->
            when (displayUnit) {
                WeightUnit.Kg -> v in 2.0..500.0
                WeightUnit.Lb -> v in 4.0..1100.0
            }
        } ?: false

    /** Canonical kg for the current value (0.0 when the field is empty/partial). */
    val weightKg: Double
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
        return copy(displayUnit = target, valueText = formatValue(newValue))
    }

    fun toReading() = WeightReading(
        memberId = memberId,
        weightKg = weightKg,
        displayUnit = displayUnit,
        timestamp = timestamp,
        source = source,
        note = note,
    )

    companion object {
        fun fromReading(r: WeightReading) = WeightDraft(
            valueText = formatValue(r.valueIn(r.displayUnit)),
            displayUnit = r.displayUnit,
            timestamp = r.timestamp,
            source = r.source,
            note = r.note,
            memberId = r.memberId,
        )

        /**
         * Format a value for the editable field: scales read to one decimal in
         * both kg and lb, so we always show one decimal place. The kg ↔ lb toggle
         * is therefore a display round-trip with at most ±0.05 unit jitter, which
         * is below the resolution of a consumer scale.
         */
        fun formatValue(value: Double): String =
            String.format(java.util.Locale.US, "%.1f", value)
    }
}
