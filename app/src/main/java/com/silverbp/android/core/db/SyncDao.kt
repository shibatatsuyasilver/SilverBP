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

    /**
     * Highest HLC still recoverable from durable DB state. Folded into the
     * cold-start clock seed (via HlcClock.observe) so a prefs-losing restore
     * can't regress the clock below tombstones that survive in the DB (QA P0-4).
     * Cheap: `hlc` is indexed, so MAX is an index seek. Implemented as a default
     * over [rawHlc] (not a new abstract @Query) so the existing test fakes need
     * no extra override.
     */
    suspend fun maxTombstoneHlc(): String? =
        rawHlc(SimpleSQLiteQuery("SELECT MAX(hlc) FROM tombstone"))

    // ----- Paired devices -----

    @Upsert
    suspend fun upsertDevice(device: SyncDeviceEntity)

    @Query("SELECT * FROM sync_device ORDER BY lastSeenAt DESC")
    fun devicesFlow(): Flow<List<SyncDeviceEntity>>

    @Query("SELECT * FROM sync_device WHERE deviceId = :deviceId LIMIT 1")
    suspend fun device(deviceId: String): SyncDeviceEntity?

    /** All paired peers (one-shot) — used by the background sync worker. */
    @Query("SELECT * FROM sync_device")
    suspend fun allDevices(): List<SyncDeviceEntity>

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
