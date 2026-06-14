package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Member-scoped access to `weight_reading` (v20). Mirrors the member-scoped
 * [GlucoseDao] surface — every read takes a memberId and uses the
 * `(memberId, timestamp)` index — and the owner-only [findUnmirrored] retry set
 * for Health Connect.
 */
@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_reading WHERE memberId = :memberId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(memberId: String): Flow<WeightReadingEntity?>

    @Query("SELECT * FROM weight_reading WHERE memberId = :memberId ORDER BY timestamp DESC")
    fun observeAll(memberId: String): Flow<List<WeightReadingEntity>>

    @Query(
        "SELECT * FROM weight_reading WHERE memberId = :memberId AND timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp ASC",
    )
    fun observeRange(memberId: String, from: Long, to: Long): Flow<List<WeightReadingEntity>>

    /** Per-member count — parity with the other member-scoped readings. */
    @Query("SELECT COUNT(*) FROM weight_reading WHERE memberId = :memberId")
    suspend fun count(memberId: String): Int

    @Query("SELECT * FROM weight_reading WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): WeightReadingEntity?

    /**
     * All rows across every member — the full-table dump for backup/sync export
     * (the weight SyncMapper). Unscoped on purpose: a backup/sync snapshot carries
     * every member's weight (the MEMBER records carry the owning member;
     * per-member scoping is a UI/read concern). Mirrors [GlucoseDao.getAll].
     */
    @Query("SELECT * FROM weight_reading ORDER BY timestamp ASC")
    suspend fun getAll(): List<WeightReadingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: WeightReadingEntity)

    @Query("DELETE FROM weight_reading WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Readings not yet mirrored to Health Connect — the retry/backfill set.
     * Scoped to the owner: only the owner's weight is ever mirrored, so non-owner
     * rows (which stay `hcRecordId == null` by design) must not be retried
     * (mirrors [GlucoseDao.findUnmirrored]).
     */
    @Query(
        "SELECT * FROM weight_reading WHERE hcRecordId IS NULL AND memberId = :ownerId " +
            "ORDER BY timestamp ASC",
    )
    suspend fun findUnmirrored(ownerId: String): List<WeightReadingEntity>
}
