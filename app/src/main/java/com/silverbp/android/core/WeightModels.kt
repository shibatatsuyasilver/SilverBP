package com.silverbp.android.core

import java.time.Instant
import java.util.UUID

/**
 * Body-weight display unit. Canonical storage is always kilograms; pounds are a
 * display/entry convenience. Conversion factor: **1 kg = 2.20462 lb** (the
 * international avoirdupois pound, 0.45359237 kg, inverted) — the value clinical
 * and consumer scales use.
 */
enum class WeightUnit(val raw: String) {
    Kg("kg"),
    Lb("lb");

    companion object {
        /** 1 kg = 2.20462 lb. */
        const val KG_TO_LB: Double = 2.20462

        fun fromRaw(s: String): WeightUnit = entries.firstOrNull { it.raw == s } ?: Kg

        /** kg → lb. */
        fun kgToLb(kg: Double): Double = kg * KG_TO_LB

        /** lb → kg. */
        fun lbToKg(lb: Double): Double = lb / KG_TO_LB
    }
}

/** How the reading was captured. Aligns with [GlucoseSource] semantics; scale OCR is backlog. */
enum class WeightSource(val raw: String) {
    Manual("manual");

    companion object {
        fun fromRaw(s: String): WeightSource = entries.firstOrNull { it.raw == s } ?: Manual
    }
}

/**
 * Core body-weight domain reading. Born member-native (v20): [memberId] is empty
 * only on a fresh draft, where [com.silverbp.android.core.WeightRepository]
 * resolves it to the current/owner member before save. Mirrors
 * [com.silverbp.android.core.db.WeightReadingEntity]; see [WeightUnit] for the
 * kg ↔ lb conversion and [BmiCalculator] for the BMI derived from this weight +
 * the member's height.
 */
data class WeightReading(
    val id: UUID = UUID.randomUUID(),
    /** The member this reading belongs to. Empty string means "resolve to current/owner". */
    val memberId: String = "",
    /** Canonical body weight in kg. Use [valueIn] to read in the user's preferred unit. */
    val weightKg: Double,
    /** Unit captured at record time; the value reads back in this unit by default. */
    val displayUnit: WeightUnit = WeightUnit.Kg,
    val timestamp: Instant,
    val source: WeightSource = WeightSource.Manual,
    val note: String = "",
    val photoFilename: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /**
     * Android-only: Health Connect record id once mirrored (null = pending).
     * Non-owner readings stay null by design — see [WeightRepository].
     */
    val hcRecordId: String? = null,
) {
    /** This reading's value expressed in [unit]. */
    fun valueIn(unit: WeightUnit): Double = when (unit) {
        WeightUnit.Kg -> weightKg
        WeightUnit.Lb -> WeightUnit.kgToLb(weightKg)
    }

    companion object {
        /** Build the canonical kg value from a number entered in [unit]. */
        fun kgFrom(value: Double, unit: WeightUnit): Double = when (unit) {
            WeightUnit.Kg -> value
            WeightUnit.Lb -> WeightUnit.lbToKg(value)
        }
    }
}
