package com.silverbp.android.ui.confirm

import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseSource
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import java.time.Instant
import java.util.UUID

/**
 * Mutable in-memory working copy of a glucose reading — the glucose analogue of
 * [BpReadingDraft]. The value is held in the [displayUnit] the user is editing
 * in (mg/dL or mmol/L) as raw text so partial input ("13.", "") never snaps; the
 * canonical mg/dL is computed only at save time via [GlucoseReading.mgdlFrom].
 *
 * Photo bytes are NOT held here (unlike the BP draft): the glucose capture path
 * persists the JPEG under filesDir/photos during analysis and hands the filename
 * through [photoFilename], so a draft is fully process-death-serialisable through
 * [com.silverbp.android.ui.confirm.ConfirmGlucoseViewModel]'s SavedStateHandle
 * (M5) — a Bitmap would not survive that.
 */
data class GlucoseDraft(
    /** Raw text the user is typing, in [displayUnit]. Empty/partial is allowed mid-edit. */
    val valueText: String = "",
    val displayUnit: GlucoseUnit = GlucoseUnit.Mgdl,
    val measureContext: MeasureContext = MeasureContext.Fasting,
    val timestamp: Instant = Instant.now(),
    val source: GlucoseSource = GlucoseSource.Manual,
    val confidence: Double = 1.0,
    val note: String = "",
    val photoFilename: String? = null,
    /**
     * Member this reading is attributed to (v19). Defaults to the current
     * selection when a new draft is created; empty resolves to the owner in
     * [com.silverbp.android.core.GlucoseRepository.upsert].
     */
    val memberId: String = "",
) {
    /** The parsed numeric value in [displayUnit], or null while the field is empty/partial. */
    val parsedValue: Double?
        get() = valueText.replace(",", ".").toDoubleOrNull()

    /**
     * Valid when the parsed value lands in a plausible meter range for the unit:
     * mg/dL meters read ~20–700, mmol/L meters ~1.1–38.9 (1 mmol/L = 18.016 mg/dL).
     */
    val isValid: Boolean
        get() = parsedValue?.let { v ->
            when (displayUnit) {
                GlucoseUnit.Mgdl -> v in 20.0..700.0
                GlucoseUnit.Mmol -> v in 1.1..38.9
            }
        } ?: false

    /** Canonical mg/dL for the current value (0.0 when the field is empty/partial). */
    val valueMgdl: Double
        get() = parsedValue?.let { GlucoseReading.mgdlFrom(it, displayUnit) } ?: 0.0

    /**
     * Re-express the current value in [target] without losing what the user typed
     * — used by the mg/dL ↔ mmol/L toggle so the displayed number converts.
     */
    fun convertedTo(target: GlucoseUnit): GlucoseDraft {
        if (target == displayUnit) return this
        val v = parsedValue ?: return copy(displayUnit = target)
        val newValue = when (target) {
            GlucoseUnit.Mgdl -> GlucoseUnit.mmolToMgdl(v)
            GlucoseUnit.Mmol -> GlucoseUnit.mgdlToMmol(v)
        }
        return copy(displayUnit = target, valueText = formatValue(newValue, target))
    }

    fun toReading(photoFilename: String? = null) = GlucoseReading(
        memberId = memberId,
        valueMgdl = valueMgdl,
        displayUnit = displayUnit,
        measureContext = measureContext,
        timestamp = timestamp,
        source = source,
        confidence = confidence,
        note = note,
        photoFilename = photoFilename ?: this.photoFilename,
    )

    companion object {
        fun fromReading(r: GlucoseReading) = GlucoseDraft(
            valueText = formatValue(r.valueIn(r.displayUnit), r.displayUnit),
            displayUnit = r.displayUnit,
            measureContext = r.measureContext,
            timestamp = r.timestamp,
            source = r.source,
            confidence = r.confidence,
            note = r.note,
            photoFilename = r.photoFilename,
            memberId = r.memberId,
        )

        /**
         * Format a value for the editable field: mg/dL shows a whole integer
         * (meters never read fractional mg/dL); mmol/L keeps one decimal.
         *
         * mg/dL **rounds** (not truncates) so the mg/dL ↔ mmol/L toggle is a
         * lossless display round-trip: e.g. 200 → 11.1 mmol → 199.978 mg/dL must
         * read back as 200, not 199. Truncation (toLong) always biased downward
         * and could cross a medical threshold (a post-meal 200 → 199 = High →
         * Elevated). See the round-trip unit test.
         */
        fun formatValue(value: Double, unit: GlucoseUnit): String = when (unit) {
            GlucoseUnit.Mgdl -> Math.round(value).toString()
            GlucoseUnit.Mmol -> String.format(java.util.Locale.US, "%.1f", value)
        }
    }
}
