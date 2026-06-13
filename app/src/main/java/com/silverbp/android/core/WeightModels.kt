package com.silverbp.android.core

import java.time.Instant
import java.util.UUID

/**
 * Body-weight display unit. Canonical storage is always kilograms; pounds is a
 * display/entry convenience. Conversion factor: **1 lb = 0.453592 kg**. Mirrors
 * [GlucoseUnit] (mg/dL canonical, mmol/L convenience).
 */
enum class WeightUnit(val raw: String) {
    Kg("kg"),
    Lb("lb");

    companion object {
        /** 1 lb = 0.453592 kg. */
        const val LB_TO_KG: Double = 0.453592

        fun fromRaw(s: String): WeightUnit = entries.firstOrNull { it.raw == s } ?: Kg

        /** lb → kg. */
        fun lbToKg(lb: Double): Double = lb * LB_TO_KG

        /** kg → lb. */
        fun kgToLb(kg: Double): Double = kg / LB_TO_KG
    }
}

/** How the weight was captured. Aligns with [GlucoseSource] semantics. */
enum class WeightSource(val raw: String) {
    Manual("manual"),
    Camera("camera"),
    HealthConnect("health_connect");

    companion object {
        fun fromRaw(s: String): WeightSource = entries.firstOrNull { it.raw == s } ?: Manual
    }
}

/**
 * Core body-weight domain reading. Born member-native: [memberId] is empty only
 * on a fresh draft, where [com.silverbp.android.core.WeightRepository] resolves
 * it to the current/owner member before save. Mirrors
 * [com.silverbp.android.core.db.WeightLogEntity]; see [WeightUnit] for the
 * kg ↔ lb conversion.
 */
data class WeightReading(
    val id: UUID = UUID.randomUUID(),
    /** The member this reading belongs to. Empty string means "resolve to current/owner". */
    val memberId: String = "",
    /** Canonical value in kilograms. Use [valueIn] to read in the user's preferred unit. */
    val valueKg: Double,
    /** Unit captured at record time; the value reads back in this unit by default. */
    val displayUnit: WeightUnit = WeightUnit.Kg,
    val timestamp: Instant,
    val source: WeightSource = WeightSource.Manual,
    val confidence: Double = 1.0,
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
        WeightUnit.Kg -> valueKg
        WeightUnit.Lb -> WeightUnit.kgToLb(valueKg)
    }

    companion object {
        /** Build the canonical kg value from a number entered in [unit]. */
        fun kgFrom(value: Double, unit: WeightUnit): Double = when (unit) {
            WeightUnit.Kg -> value
            WeightUnit.Lb -> WeightUnit.lbToKg(value)
        }
    }
}
