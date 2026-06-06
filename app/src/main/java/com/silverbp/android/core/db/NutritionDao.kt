package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodLogDao {
    @Query("SELECT * FROM food_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<FoodLogEntity>>

    @Query("SELECT * FROM food_log WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun observeRange(from: Long, to: Long): Flow<List<FoodLogEntity>>

    @Query("SELECT * FROM food_log WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): FoodLogEntity?

    /** One-shot range read (e.g. daily sodium roll-up for the Coach module). */
    @Query("SELECT * FROM food_log WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun rangeOnce(from: Long, to: Long): List<FoodLogEntity>

    /** Full table snapshot for the cross-device sync source. */
    @Query("SELECT * FROM food_log ORDER BY timestamp ASC")
    suspend fun all(): List<FoodLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: FoodLogEntity)

    @Update
    suspend fun update(r: FoodLogEntity)

    @Query("DELETE FROM food_log WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM food_log")
    suspend fun count(): Int

    /** Rows not yet mirrored to Health Connect — the retry/backfill set. */
    @Query("SELECT * FROM food_log WHERE hcRecordId IS NULL ORDER BY timestamp ASC")
    suspend fun findUnmirrored(): List<FoodLogEntity>
}
