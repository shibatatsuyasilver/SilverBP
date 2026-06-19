package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BpDao {
    @Query("SELECT * FROM bp_reading ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<BpReadingEntity?>

    @Query("SELECT * FROM bp_reading ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BpReadingEntity>>

    @Query("SELECT * FROM bp_reading WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    fun observeRange(from: Long, to: Long): Flow<List<BpReadingEntity>>

    // --- Member-scoped variants (v18). Use the (memberId, timestamp) index. ---

    @Query("SELECT * FROM bp_reading WHERE memberId = :memberId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(memberId: String): Flow<BpReadingEntity?>

    @Query("SELECT * FROM bp_reading WHERE memberId = :memberId ORDER BY timestamp DESC")
    fun observeAll(memberId: String): Flow<List<BpReadingEntity>>

    @Query(
        "SELECT * FROM bp_reading WHERE memberId = :memberId AND timestamp BETWEEN :from AND :to " +
            "ORDER BY timestamp ASC",
    )
    fun observeRange(memberId: String, from: Long, to: Long): Flow<List<BpReadingEntity>>

    @Query("SELECT COUNT(*) FROM bp_reading WHERE memberId = :memberId")
    suspend fun count(memberId: String): Int

    @Query("SELECT * FROM bp_reading WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BpReadingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: BpReadingEntity)

    @Update
    suspend fun update(r: BpReadingEntity)

    @Query("DELETE FROM bp_reading WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM bp_reading")
    suspend fun count(): Int

    /**
     * Readings not yet mirrored to Health Connect — the retry/backfill set.
     * Scoped to the owner: only the owner's BP is ever mirrored, so non-owner
     * rows (which stay `hcRecordId == null` by design) must not be retried.
     */
    @Query(
        "SELECT * FROM bp_reading WHERE hcRecordId IS NULL AND memberId = :ownerId " +
            "ORDER BY timestamp ASC",
    )
    suspend fun findUnmirrored(ownerId: String): List<BpReadingEntity>
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile LIMIT 1")
    fun observe(): Flow<UserProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: UserProfileEntity)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medication ORDER BY name ASC")
    fun observeAll(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medication WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: String): Flow<List<MedicationEntity>>

    /**
     * Member-scoped list for the medication management UI (v18). Reminder
     * enumeration stays global (all members) — this phone is the household care
     * hub — so [observeAll] is kept for the scheduler/sync paths.
     */
    @Query("SELECT * FROM medication WHERE memberId = :memberId ORDER BY name ASC")
    fun observeForMember(memberId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medication WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MedicationEntity?

    @Query("SELECT COUNT(*) FROM medication WHERE memberId = ''")
    suspend fun countBlankMemberIds(): Int

    @Query(
        "UPDATE medication SET memberId = :ownerId, " +
            "hlcUpdatedAt = CASE WHEN :hlc IS NULL THEN hlcUpdatedAt ELSE :hlc END " +
            "WHERE memberId = ''"
    )
    suspend fun backfillBlankMemberIds(ownerId: String, hlc: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MedicationEntity)

    @Query("DELETE FROM medication WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MedicationScheduleDao {
    @Query("SELECT * FROM medication_schedule ORDER BY medicationId, hour, minute")
    fun observeAll(): Flow<List<MedicationScheduleEntity>>

    @Query("SELECT * FROM medication_schedule WHERE medicationId = :medicationId ORDER BY hour, minute")
    fun observeForMedication(medicationId: String): Flow<List<MedicationScheduleEntity>>

    @Query(
        "SELECT s.* FROM medication_schedule s " +
            "INNER JOIN medication m ON m.id = s.medicationId " +
            "WHERE m.memberId = :memberId " +
            "ORDER BY s.medicationId, s.hour, s.minute"
    )
    fun observeForMember(memberId: String): Flow<List<MedicationScheduleEntity>>

    @Query("SELECT * FROM medication_schedule ORDER BY medicationId, hour, minute")
    suspend fun all(): List<MedicationScheduleEntity>

    @Query("SELECT * FROM medication_schedule WHERE enabled = 1 ORDER BY medicationId, hour, minute")
    suspend fun allEnabled(): List<MedicationScheduleEntity>

    @Query("SELECT * FROM medication_schedule WHERE medicationId = :medicationId ORDER BY hour, minute")
    suspend fun forMedication(medicationId: String): List<MedicationScheduleEntity>

    @Query("SELECT * FROM medication_schedule WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MedicationScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: MedicationScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<MedicationScheduleEntity>)

    @Query("DELETE FROM medication_schedule WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM medication_schedule WHERE medicationId = :medicationId")
    suspend fun deleteForMedication(medicationId: String)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TagEntity)

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(crossRef: ReadingTagCrossRef)

    @Query("DELETE FROM reading_tag WHERE readingId = :readingId AND tagId = :tagId")
    suspend fun unlink(readingId: String, tagId: String)

    @Query("SELECT t.* FROM tag t INNER JOIN reading_tag rt ON rt.tagId = t.id WHERE rt.readingId = :readingId")
    suspend fun tagsFor(readingId: String): List<TagEntity>
}
