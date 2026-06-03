package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read+write API for [BpWorkoutAssociationEntity]. Mirrors the other Coach DAOs:
 * Flow reads, suspend writes, entity types only. Bulk reads ([listAll]) feed the
 * cross-device sync source.
 */
@Dao
interface BpWorkoutAssociationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(association: BpWorkoutAssociationEntity)

    @Query("SELECT * FROM bp_workout_association WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<BpWorkoutAssociationEntity>>

    @Query("SELECT * FROM bp_workout_association WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun forSession(sessionId: String): List<BpWorkoutAssociationEntity>

    @Query("SELECT * FROM bp_workout_association WHERE id = :id")
    suspend fun findById(id: String): BpWorkoutAssociationEntity?

    /** Bulk read used by the cross-device sync source. */
    @Query("SELECT * FROM bp_workout_association ORDER BY createdAt ASC")
    suspend fun listAll(): List<BpWorkoutAssociationEntity>
}
