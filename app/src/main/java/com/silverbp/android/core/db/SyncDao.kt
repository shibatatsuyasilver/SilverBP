package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    // ----- Tombstone -----

    @Upsert
    suspend fun upsertTombstone(tombstone: TombstoneEntity)

    @Query("SELECT * FROM tombstone WHERE entityType = :entityType AND pk = :pk LIMIT 1")
    suspend fun tombstoneFor(entityType: String, pk: String): TombstoneEntity?

    /**
     * Reads the live row's `hlcUpdatedAt` for the B6 LWW gate. [table] /
     * [pkColumn] come from a fixed in-code allowlist
     * ([com.silverbp.android.sync.LwwTables]), never from wire input, so this
     * raw query can't be turned into SQL injection by a peer; [pk] is a bound
     * parameter. Returns null when no such row exists (record always applies).
     */
    @RawQuery
    suspend fun rawHlc(query: SupportSQLiteQuery): String?

    /** Convenience wrapper that builds the parameterised [rawHlc] query. */
    suspend fun localRowHlc(table: String, pkColumn: String, pk: String): String? =
        rawHlc(
            SimpleSQLiteQuery(
                "SELECT hlcUpdatedAt FROM $table WHERE $pkColumn = ? LIMIT 1",
                arrayOf<Any>(pk),
            ),
        )

    @Query("SELECT * FROM tombstone WHERE hlc > :sinceHlc ORDER BY hlc")
    suspend fun tombstonesSince(sinceHlc: String): List<TombstoneEntity>

    @Query("DELETE FROM tombstone WHERE hlc < :pruneBeforeHlc")
    suspend fun gcTombstones(pruneBeforeHlc: String): Int

    // ----- Paired devices -----

    @Upsert
    suspend fun upsertDevice(device: SyncDeviceEntity)

    @Query("SELECT * FROM sync_device ORDER BY lastSeenAt DESC")
    fun devicesFlow(): Flow<List<SyncDeviceEntity>>

    @Query("SELECT * FROM sync_device WHERE deviceId = :deviceId LIMIT 1")
    suspend fun device(deviceId: String): SyncDeviceEntity?

    @Query("UPDATE sync_device SET lastSeenAt = :nowMs, lastHlcSeen = :hlc WHERE deviceId = :deviceId")
    suspend fun touchDevice(deviceId: String, nowMs: Long, hlc: String)

    @Query("DELETE FROM sync_device WHERE deviceId = :deviceId")
    suspend fun forgetDevice(deviceId: String)

    @Query("SELECT MIN(lastHlcSeen) FROM sync_device")
    suspend fun minLastHlcSeen(): String?

    // ----- Outbox -----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueOutbox(entry: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox ORDER BY seq ASC LIMIT :limit")
    suspend fun peekOutbox(limit: Int): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE seq <= :seq")
    suspend fun ackOutboxThrough(seq: Long): Int
}
