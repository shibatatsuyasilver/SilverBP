package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Member-scoped access to `glucose_reading` (v19). Mirrors the member-scoped
 * [BpDao] surface — every read takes a memberId and uses the
 * `(memberId, timestamp)` index — and the owner-only [findUnmirrored] retry set
 * for Health Connect.
 */
@Dao
interface GlucoseDao {
    @Query("SELECT * FROM glucose_reading WHERE memberId = :memberId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(memberId: String): Flow<GlucoseReadingEntity?>

    @Query("SELECT * FROM glucose_reading WHERE memberId = :memberId ORDER BY timestamp DESC")
    fun observeAll(memberId: String): Flow<List<GlucoseReadingEntity>>

    @Query(
        "SELECT * FROM glucose_reading WHERE memberId = :memberId AND timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp ASC",
    )
    fun observeRange(memberId: String, from: Long, to: Long): Flow<List<GlucoseReadingEntity>>

    /** Per-member count — backs the free-tier 10-record gate (roadmap §4-6). */
    @Query("SELECT COUNT(*) FROM glucose_reading WHERE memberId = :memberId")
    suspend fun count(memberId: String): Int

    @Query("SELECT * FROM glucose_reading WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): GlucoseReadingEntity?

    /**
     * All rows across every member — the full-table dump for backup/sync export
     * ([com.silverbp.android.sync.GlucoseReadingSyncMapper]). Unscoped on purpose:
     * a backup/sync snapshot carries every member's glucose (the MEMBER records
     * carry the owning member; per-member scoping is a UI/read concern).
     */
    @Query("SELECT * FROM glucose_reading ORDER BY timestamp ASC")
    suspend fun getAll(): List<GlucoseReadingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: GlucoseReadingEntity)

    @Query("DELETE FROM glucose_reading WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Readings not yet mirrored to Health Connect — the retry/backfill set.
     * Scoped to the owner: only the owner's glucose is ever mirrored, so
     * non-owner rows (which stay `hcRecordId == null` by design) must not be
     * retried (mirrors [BpDao.findUnmirrored]).
     */
    @Query(
        "SELECT * FROM glucose_reading WHERE hcRecordId IS NULL AND memberId = :ownerId " +
            "ORDER BY timestamp ASC",
    )
    suspend fun findUnmirrored(ownerId: String): List<GlucoseReadingEntity>
}
