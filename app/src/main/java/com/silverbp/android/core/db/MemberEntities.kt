package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A care recipient whose clinical measurements (BP, medication, and — from v19 —
 * glucose) are recorded on this device. Exactly one row carries [isOwner] = true:
 * the device owner, backfilled from `user_profile` by [MIGRATION_17_18] and the
 * anchor for Health-Connect-mirrored / owner-only data.
 *
 * The single-owner invariant is enforced in [com.silverbp.android.core.member.
 * MemberRepository] and the migration backfill, NOT by a unique index: Room
 * cannot declare a partial unique index (`WHERE isOwner = 1`), so the [isOwner]
 * index below is plain (non-unique) — present only to speed the owner lookup.
 */
@Entity(tableName = "member", indices = [Index("isOwner"), Index("sortOrder")])
data class MemberEntity(
    @PrimaryKey val id: String,          // UUID
    /** Empty → UI fallback "Me"/「我」 (never store the localized literal in the DB). */
    val displayName: String,
    /** Exactly one row true; anchor for HC mirror + owner-only data (see class KDoc). */
    val isOwner: Boolean,
    val birthYear: Int?,
    val heightCm: Int? = null,
    val biologicalSex: String? = null,
    val targetWeightKg: Double? = null,
    val hasDiabetes: Boolean,
    val hasCKD: Boolean,
    val hasASCVD: Boolean,
    /** Per-member BP guideline; reuses [com.silverbp.android.core.HypertensionGuideline].raw. */
    val guideline: String,
    /** 0..7 fixed palette index for the avatar / chart identity colour. */
    val colorIndex: Int,
    val sortOrder: Int,
    /** Soft delete: keeps history, hides from the switcher. */
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Reserved for future cross-device sync; "0" pre-sync (mirrors other tables). */
    val hlcUpdatedAt: String = "0",
)
