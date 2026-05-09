package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read+write API for [AchievementEntity] and [DailyStepLogEntity]. Mirrors
 * [BpDao]/[ExerciseDao]: Flow for reads, suspend for writes; entities only.
 *
 * Achievement insert uses `IGNORE` so the evaluator can be re-run with the
 * full candidate set on every refresh without ever resurrecting a prior
 * unlock or duplicating the row.
 */
@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievement ORDER BY unlockedAt DESC")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievement ORDER BY unlockedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AchievementEntity>

    @Query("SELECT * FROM achievement")
    suspend fun listAll(): List<AchievementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<AchievementEntity>): List<Long>

    @Query("UPDATE achievement SET notifiedAt = :at WHERE kindRaw = :kindRaw")
    suspend fun markNotified(kindRaw: String, at: Long)

    @Query("SELECT COUNT(*) FROM exercise_session")
    suspend fun sessionCount(): Int

    @Query("SELECT COALESCE(SUM(stepCount), 0) FROM exercise_session")
    suspend fun totalSessionSteps(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStepLog(log: DailyStepLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStepLogs(logs: List<DailyStepLogEntity>)

    @Query(
        "SELECT * FROM daily_step_log " +
            "WHERE dayStart BETWEEN :from AND :to " +
            "ORDER BY dayStart DESC"
    )
    suspend fun stepLogsBetween(from: Long, to: Long): List<DailyStepLogEntity>

    @Query("SELECT COALESCE(SUM(steps), 0) FROM daily_step_log")
    suspend fun totalLoggedSteps(): Long

    @Query("SELECT * FROM daily_step_log ORDER BY dayStart DESC LIMIT :limit")
    suspend fun recentStepLogs(limit: Int): List<DailyStepLogEntity>

    @Query("SELECT * FROM daily_step_log")
    suspend fun listAllStepLogs(): List<DailyStepLogEntity>

    @Query("SELECT * FROM daily_step_log WHERE dayStart = :dayStart")
    suspend fun findStepLog(dayStart: Long): DailyStepLogEntity?
}
