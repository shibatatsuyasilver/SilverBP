package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Strength-training persistence (v13). Mirrors the cardio entity style:
 * snake_case table names, enum values stored as their `raw` string, JSON for
 * list columns, and `hlcUpdatedAt` (default "0" pre-sync) on every synced row.
 */
@Entity(tableName = "exercise_catalog_item")
data class ExerciseCatalogItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** [com.silverbp.android.strength.BodyPart] raw discriminator. */
    val bodyPart: String,
    /** JSON-encoded List<String> of 中文 muscle-group labels. */
    val muscleGroupsJson: String,
    val description: String,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
)

@Entity(tableName = "strength_workout_session")
data class StrengthWorkoutSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val note: String,
    /** [com.silverbp.android.strength.DifficultyFeedback] raw, null = unreported. */
    val difficultyRaw: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
)

@Entity(
    tableName = "set_log",
    foreignKeys = [
        ForeignKey(
            entity = StrengthWorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutSessionId")],
)
data class SetLogEntity(
    @PrimaryKey val id: String,
    val workoutSessionId: String,
    val exerciseId: String,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?,
    val isCompleted: Boolean,
    val skipped: Boolean = false,
    val notes: String,
    val createdAt: Long,
    val hlcUpdatedAt: String = "0",
)
