package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence representations for the Coach feature. Domain types live in
 * [com.silverbp.android.coach] — keep enum raw values + nullable fields in
 * sync via [CoachMappers].
 */

@Entity(
    tableName = "coach_plan",
    indices = [Index("weekStart")],
)
data class CoachPlanEntity(
    @PrimaryKey val id: String,
    val weekStart: Long,
    val generatedAt: Long,
    val ruleVersion: Int,
    val phaseRaw: String,
    /** Goals serialised as JSON so the schema can evolve without migrations. */
    val goalsJson: String,
    val hlcUpdatedAt: String = "0",
)

@Entity(
    tableName = "coach_task",
    foreignKeys = [
        ForeignKey(
            entity = CoachPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("dayOffset")],
)
data class CoachTaskEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val dayOffset: Int,
    val moduleRaw: String,
    val title: String,
    val targetValue: Double?,
    val targetUnit: String?,
    val intensityRaw: String,
    val safetyHold: Boolean,
    val completedAt: Long?,
    val skipped: Boolean = false,
    /** null = keep the original [dayOffset]; non-null = user-moved target day. */
    val movedDayOffset: Int? = null,
    val hlcUpdatedAt: String = "0",
)

@Entity(tableName = "sleep_log")
data class SleepLogEntity(
    /** Day start at local midnight, epoch-millis. Doubles as the natural primary key. */
    @PrimaryKey val dayStart: Long,
    val durationMin: Int,
    /** "hc" | "manual" */
    val sourceRaw: String,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
)

@Entity(tableName = "diet_check")
data class DietCheckEntity(
    @PrimaryKey val dayStart: Long,
    /** "low" | "mid" | "high" */
    val sodiumLevelRaw: String,
    val vegServings: Int,
    /** "hc" | "manual" */
    val sourceRaw: String,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
)

@Entity(
    tableName = "medication_dose",
    indices = [Index("dayStart"), Index("medicationId")],
)
data class MedicationDoseEntity(
    @PrimaryKey val id: String,
    val dayStart: Long,
    val medicationId: String,
    val scheduledHour: Int,
    val taken: Boolean,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
)
