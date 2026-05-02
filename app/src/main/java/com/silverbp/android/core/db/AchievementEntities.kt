package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted unlock record. [kindRaw] is the medal id (e.g. `"daily.10000"`)
 * and serves as primary key — re-running the evaluator can never duplicate
 * a row for an already-unlocked medal.
 */
@Entity(
    tableName = "achievement",
    indices = [Index("unlockedAt")],
)
data class AchievementEntity(
    @PrimaryKey val kindRaw: String,
    val unlockedAt: Long,
    val notifiedAt: Long?,
    val unlockedBackfilled: Boolean,
    val valueAtUnlock: Long,
)

/**
 * Per-day step count, sampled from Health Connect when available and from the
 * `TYPE_STEP_COUNTER` baseline-delta otherwise. Primary key is the start-of-day
 * epoch millis so re-sampling the same day overwrites instead of duplicating.
 */
@Entity(tableName = "daily_step_log")
data class DailyStepLogEntity(
    @PrimaryKey val dayStart: Long,
    val steps: Int,
    val sourceRaw: String,
    val updatedAt: Long,
)
