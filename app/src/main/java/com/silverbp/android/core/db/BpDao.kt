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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MedicationEntity)

    @Query("DELETE FROM medication WHERE id = :id")
    suspend fun delete(id: String)
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
