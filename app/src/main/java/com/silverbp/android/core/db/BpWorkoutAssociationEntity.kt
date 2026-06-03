package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links a [BpReadingEntity] to a workout session (cardio [ExerciseSessionEntity]
 * or [StrengthWorkoutSessionEntity]) as a pre- or post-workout context reading.
 * Used by the BP↔workout deep-linking feature (Phase 6).
 *
 * No FK back into bp_reading / *_session: the association is informational and
 * must survive even if a peer hasn't synced the referenced rows yet. Stale rows
 * are harmless (the UI joins by id and skips misses).
 */
@Entity(
    tableName = "bp_workout_association",
    indices = [Index("sessionId"), Index("bpReadingId")],
)
data class BpWorkoutAssociationEntity(
    @PrimaryKey val id: String,
    val bpReadingId: String,
    val sessionId: String,
    /** "cardio" | "strength" */
    val sessionType: String,
    /** "pre" | "post" */
    val contextType: String,
    val createdAt: Long,
    val hlcUpdatedAt: String = "0",
)
