package com.silverbp.android.core

import java.time.Instant
import java.util.UUID

/** Match iOS BPReading.Arm */
enum class Arm(val raw: String) {
    Left("left"), Right("right");
    companion object { fun fromRaw(s: String): Arm = entries.first { it.raw == s } }
}

/** Match iOS BPReading.Posture */
enum class Posture(val raw: String) {
    Sitting("sitting"), Supine("supine"), Standing("standing");
    companion object { fun fromRaw(s: String): Posture = entries.first { it.raw == s } }
}

/** Match iOS BPReading.PartOfDay */
enum class PartOfDay(val raw: String) {
    Morning("morning"), Evening("evening");
    companion object { fun fromRaw(s: String): PartOfDay = entries.first { it.raw == s } }
}

/** Match iOS BPReading.Source — raw values intentionally identical for cross-platform export */
enum class Source(val raw: String) {
    Manual("manual"),
    CameraGemma("camera_gemma"),
    HealthkitImport("healthkit_import");
    companion object { fun fromRaw(s: String): Source = entries.first { it.raw == s } }
}

/**
 * Core domain reading. Mirrors iOS BPCore.SchemaV1.BPReading 1:1.
 */
data class BpReading(
    val id: UUID = UUID.randomUUID(),
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    val timestamp: Instant,
    val arm: Arm = Arm.Left,
    val posture: Posture = Posture.Sitting,
    val partOfDay: PartOfDay = PartOfDay.Morning,
    val beforeMedication: Boolean = true,
    val photoFilename: String? = null,
    val confidence: Double = 1.0,
    val source: Source = Source.Manual,
    val note: String = "",
    val irregularHeartbeat: Boolean = false,
    val medicationId: UUID? = null,
    /**
     * The member this reading belongs to. Empty string means "resolve to owner"
     * (v17 rows backfilled to the owner id by MIGRATION_17_18; new drafts default
     * to the current member). Non-owner readings are never mirrored to Health
     * Connect — see [com.silverbp.android.core.BpRepository].
     */
    val memberId: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /**
     * Android-only: Health Connect record id once mirrored (null = pending).
     * Not part of the cross-platform schema; iOS has no equivalent.
     */
    val hcRecordId: String? = null,
)

data class UserProfile(
    val id: UUID = UUID.randomUUID(),
    val displayName: String = "",
    val birthYear: Int = 1960,
    val hasDiabetes: Boolean = false,
    val hasCKD: Boolean = false,
    val hasASCVD: Boolean = false,
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
)

/**
 * A care recipient profile (the device owner plus, on Premium, family members).
 * Mirrors [com.silverbp.android.core.db.MemberEntity]; see its KDoc for the
 * single-owner invariant. [displayName] empty → UI shows the localized "Me"
 * fallback (never persisted as a literal).
 */
data class Member(
    val id: UUID = UUID.randomUUID(),
    val displayName: String = "",
    val isOwner: Boolean = false,
    val birthYear: Int? = null,
    /** Height in cm (v20) for the per-member BMI; null → weight shows without BMI. */
    val heightCm: Int? = null,
    val hasDiabetes: Boolean = false,
    val hasCKD: Boolean = false,
    val hasASCVD: Boolean = false,
    val guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
    /** 0..7 fixed palette index for the avatar / chart identity colour. */
    val colorIndex: Int = 0,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class Medication(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val dose: String,
)

data class Tag(
    val id: UUID = UUID.randomUUID(),
    val name: String,
)
