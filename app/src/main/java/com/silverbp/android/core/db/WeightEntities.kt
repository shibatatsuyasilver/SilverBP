package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence row for a single body-weight reading (體重). Born member-native in
 * v20 — every row carries a non-empty [memberId] (no backfill: the table is new,
 * so [com.silverbp.android.core.member.MemberRepository] resolves the owner at
 * insert time). Domain type [com.silverbp.android.core.WeightReading] — keep enum
 * raw values + nullable fields in sync via [WeightMappers].
 *
 * Canonical unit is kg ([weightKg]); [displayUnit] records what the user
 * entered/saw at capture time so the same row reads back in their preferred unit
 * (1 kg = 2.20462 lb — see [com.silverbp.android.core.WeightUnit]). BMI is NOT
 * stored — it's derived on read from [weightKg] + the member's `heightCm` via
 * [com.silverbp.android.core.BmiCalculator] (height can change, so a stored BMI
 * would drift stale).
 *
 * `hlcUpdatedAt` carries cross-device LWW; `hcRecordId` is the device-local
 * Health Connect mirror id (never synced — a fresh device re-mirrors and gets
 * its own id, mirroring [BpReadingEntity] / [GlucoseReadingEntity]).
 *
 * Owner-only mirror (roadmap §3-5, §4-4): only the owner member's weight is
 * mirrored to Health Connect. Non-owner rows stay `hcRecordId == null` **by
 * design** — see the mirror guard in [com.silverbp.android.core.WeightRepository].
 */
@Entity(
    tableName = "weight_reading",
    indices = [Index("timestamp"), Index("memberId", "timestamp")],
)
data class WeightReadingEntity(
    @PrimaryKey val id: String,
    /** Owning member id (v20 — NOT NULL, no backfill: the table is born member-native). */
    val memberId: String,
    /** Canonical body weight in kilograms; 1 kg = 2.20462 lb. */
    val weightKg: Double,
    /** "kg" | "lb" — the input/display unit captured at record time. */
    val displayUnit: String,
    val timestamp: Long,
    /** manual (only source this round; scale OCR is backlog). Aligns with bp_reading.source. */
    val source: String,
    val note: String,
    val photoFilename: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Packed HLC string for cross-device LWW. Lex-sortable; defaults to "0" pre-sync. */
    val hlcUpdatedAt: String = "0",
    val hcRecordId: String? = null,
)
