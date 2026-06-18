package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    /** Active (not archived) members for the switcher, owner-first by sortOrder.
     *  `id` is a deterministic tiebreak so equal sortOrder values don't leave the
     *  order (and the comparison colour-collision walk that depends on it) to SQLite. */
    @Query("SELECT * FROM member WHERE archived = 0 ORDER BY sortOrder ASC, id ASC")
    fun observeActive(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM member ORDER BY sortOrder ASC")
    suspend fun getAll(): List<MemberEntity>

    @Query("SELECT * FROM member WHERE isOwner = 1 ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun getOwner(): MemberEntity?

    @Query("SELECT * FROM member WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MemberEntity)

    @Query("UPDATE member SET archived = 1, updatedAt = :now WHERE id = :id")
    suspend fun archive(id: String, now: Long)

    @Query("UPDATE member SET archived = 0, updatedAt = :now WHERE id = :id")
    suspend fun unarchive(id: String, now: Long)

    @Query("UPDATE member SET sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int, now: Long)

    @Query("SELECT COUNT(*) FROM member")
    suspend fun count(): Int

    /** Hard delete by id — used by the sync tombstone apply path (never the UI,
     *  which soft-deletes via [archive] to keep a member's history). */
    @Query("DELETE FROM member WHERE id = :id")
    suspend fun deleteById(id: String)
}
