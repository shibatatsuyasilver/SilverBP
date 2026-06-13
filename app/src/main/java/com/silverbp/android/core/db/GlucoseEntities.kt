package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence row for a single blood-glucose reading (血糖). Born member-native
 * in v19 — every row carries a non-empty [memberId] (no backfill: the table is
 * new, so [com.silverbp.android.core.member.MemberRepository] resolves the owner
 * at insert time). Domain type [com.silverbp.android.core.GlucoseReading] — keep
 * enum raw values + nullable fields in sync via [GlucoseMappers].
 *
 * Canonical unit is mg/dL ([valueMgdl]); [displayUnit] records what the user
 * entered/saw at capture time so the same row reads back in their preferred unit
 * (1 mmol/L = 18.016 mg/dL — see [com.silverbp.android.core.GlucoseUnit]).
 *
 * `hlcUpdatedAt` carries cross-device LWW; `hcRecordId` is the device-local
 * Health Connect mirror id (never synced — a fresh device re-mirrors and gets
 * its own id, mirroring [BpReadingEntity] / [FoodLogEntity]).
 *
 * Owner-only mirror (roadmap §3-5, §4-4): only the owner member's glucose is
 * mirrored to Health Connect. Non-owner rows stay `hcRecordId == null` **by
 * design** — see the mirror guard in [com.silverbp.android.core.GlucoseRepository].
 */
@Entity(
    tableName = "glucose_reading",
    indices = [Index("timestamp"), Index("memberId", "timestamp")],
)
data class GlucoseReadingEntity(
    @PrimaryKey val id: String,
    /** Owning member id (v19 — NOT NULL, no backfill: the table is born member-native). */
    val memberId: String,
    /** Canonical value in mg/dL; 1 mmol/L = 18.016 mg/dL. */
    val valueMgdl: Double,
    /** "mgdl" | "mmol" — the input/display unit captured at record time. */
    val displayUnit: String,
    /** fasting | before_meal | after_meal | bedtime | random. Drives classification. */
    val measureContext: String,
    val timestamp: Long,
    /** manual | camera (aligns with bp_reading.source semantics). */
    val source: String,
    val confidence: Double,
    val note: String,
    val photoFilename: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Packed HLC string for cross-device LWW. Lex-sortable; defaults to "0" pre-sync. */
    val hlcUpdatedAt: String = "0",
    val hcRecordId: String? = null,
)
