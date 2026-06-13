package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence row for a single body-weight log (體重). Born member-native
 * in v19 — every row carries a non-empty [memberId] (no backfill: the table is
 * new, so [com.silverbp.android.core.member.MemberRepository] resolves the owner
 * at insert time). Domain type [com.silverbp.android.core.WeightReading] — keep
 * enum raw values + nullable fields in sync via [WeightMappers].
 *
 * Canonical unit is kg ([valueKg]); [displayUnit] records what the user
 * entered/saw at capture time so the same row reads back in their preferred unit
 * (1 lb = 0.453592 kg — see [com.silverbp.android.core.WeightUnit]).
 *
 * `hlcUpdatedAt` carries cross-device LWW; `hcRecordId` is the device-local
 * Health Connect mirror id (never synced — a fresh device re-mirrors and gets
 * its own id, mirroring [BpReadingEntity] / [FoodLogEntity]).
 *
 * Owner-only mirror (roadmap §3-5, §4-4): only the owner member's weight is
 * mirrored to Health Connect. Non-owner rows stay `hcRecordId == null` **by
 * design** — see the mirror guard in [com.silverbp.android.core.WeightRepository].
 */
@Entity(
    tableName = "weight_log",
    indices = [Index("timestamp"), Index("memberId", "timestamp")],
)
data class WeightLogEntity(
    @PrimaryKey val id: String,
    /** Owning member id (v19 — NOT NULL, no backfill: the table is born member-native). */
    val memberId: String,
    /** Canonical value in kg; 1 lb = 0.453592 kg. */
    val valueKg: Double,
    /** "kg" | "lb" — the input/display unit captured at record time. */
    val displayUnit: String,
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
