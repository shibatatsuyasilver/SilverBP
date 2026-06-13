package com.silverbp.android.core

import java.time.Instant
import java.util.UUID

/**
 * Blood-glucose display unit. Canonical storage is always mg/dL; mmol/L is a
 * display/entry convenience. Conversion factor: **1 mmol/L = 18.016 mg/dL**
 * (the molar mass of glucose, 180.16 g/mol, over 10) — the value the Taiwan
 * Diabetes Association and ADA use.
 */
enum class GlucoseUnit(val raw: String) {
    Mgdl("mgdl"),
    Mmol("mmol");

    companion object {
        /** 1 mmol/L = 18.016 mg/dL. */
        const val MMOL_TO_MGDL: Double = 18.016

        fun fromRaw(s: String): GlucoseUnit = entries.firstOrNull { it.raw == s } ?: Mgdl

        /** mmol/L → mg/dL. */
        fun mmolToMgdl(mmol: Double): Double = mmol * MMOL_TO_MGDL

        /** mg/dL → mmol/L. */
        fun mgdlToMmol(mgdl: Double): Double = mgdl / MMOL_TO_MGDL
    }
}

/**
 * When the reading was taken relative to meals/sleep — drives the
 * context-aware classification thresholds in [GlucoseClassifier].
 */
enum class MeasureContext(val raw: String) {
    Fasting("fasting"),
    BeforeMeal("before_meal"),
    AfterMeal("after_meal"),
    Bedtime("bedtime"),
    Random("random");

    companion object {
        fun fromRaw(s: String): MeasureContext = entries.firstOrNull { it.raw == s } ?: Random
    }
}

/** How the reading was captured. Aligns with [Source] (BP) semantics. */
enum class GlucoseSource(val raw: String) {
    Manual("manual"),
    Camera("camera");

    companion object {
        fun fromRaw(s: String): GlucoseSource = entries.firstOrNull { it.raw == s } ?: Manual
    }
}

/**
 * Core glucose domain reading. Born member-native (v19): [memberId] is empty
 * only on a fresh draft, where [com.silverbp.android.core.GlucoseRepository]
 * resolves it to the current/owner member before save. Mirrors
 * [com.silverbp.android.core.db.GlucoseReadingEntity]; see [GlucoseUnit] for the
 * mg/dL ↔ mmol/L conversion.
 */
data class GlucoseReading(
    val id: UUID = UUID.randomUUID(),
    /** The member this reading belongs to. Empty string means "resolve to current/owner". */
    val memberId: String = "",
    /** Canonical value in mg/dL. Use [valueIn] to read in the user's preferred unit. */
    val valueMgdl: Double,
    /** Unit captured at record time; the value reads back in this unit by default. */
    val displayUnit: GlucoseUnit = GlucoseUnit.Mgdl,
    val measureContext: MeasureContext = MeasureContext.Fasting,
    val timestamp: Instant,
    val source: GlucoseSource = GlucoseSource.Manual,
    val confidence: Double = 1.0,
    val note: String = "",
    val photoFilename: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /**
     * Android-only: Health Connect record id once mirrored (null = pending).
     * Non-owner readings stay null by design — see [GlucoseRepository].
     */
    val hcRecordId: String? = null,
) {
    /** This reading's value expressed in [unit]. */
    fun valueIn(unit: GlucoseUnit): Double = when (unit) {
        GlucoseUnit.Mgdl -> valueMgdl
        GlucoseUnit.Mmol -> GlucoseUnit.mgdlToMmol(valueMgdl)
    }

    companion object {
        /** Build the canonical mg/dL value from a number entered in [unit]. */
        fun mgdlFrom(value: Double, unit: GlucoseUnit): Double = when (unit) {
            GlucoseUnit.Mgdl -> value
            GlucoseUnit.Mmol -> GlucoseUnit.mmolToMgdl(value)
        }
    }
}
