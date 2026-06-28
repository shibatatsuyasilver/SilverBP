package com.silverbp.android.sync

import com.silverbp.android.core.db.LocalSyncMutationDao
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.SyncEntityType

interface LocalSyncWriter {
    fun nextHlc(): String
    suspend fun delete(type: SyncEntityType, pk: String)

    /**
     * Bump an existing row's `hlcUpdatedAt` to a fresh HLC after a local field
     * mutation that isn't a full upsert (e.g. archive a member, complete a coach
     * task). Without it the row keeps its old HLC, the source's incremental
     * `recordsSince` skips it, and the edit never reaches a paired device (the
     * LWW gate also rejects an equal-HLC inbound copy) — QA #3 "local edits never
     * pass incremental sync".
     */
    suspend fun stamp(type: SyncEntityType, pk: String)
}

class RoomLocalSyncWriter(
    private val dao: LocalSyncMutationDao,
    private val clock: HlcClock,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LocalSyncWriter {
    override fun nextHlc(): String = clock.next().packed

    override suspend fun delete(type: SyncEntityType, pk: String) {
        val hlc = nextHlc()
        val deletedAt = nowMs()
        when (type) {
            SyncEntityType.BP_READING ->
                dao.deleteBpReadingWithTombstone(pk, type.tableName, hlc, deletedAt)
            SyncEntityType.EXERCISE_SESSION ->
                dao.deleteExerciseSessionWithTombstone(pk, type.tableName, hlc, deletedAt)
            SyncEntityType.FOOD_LOG ->
                dao.deleteFoodLogWithTombstone(pk, type.tableName, hlc, deletedAt)
            SyncEntityType.GLUCOSE_READING ->
                dao.deleteGlucoseReadingWithTombstone(pk, type.tableName, hlc, deletedAt)
            SyncEntityType.WEIGHT_LOG ->
                dao.deleteWeightLogWithTombstone(pk, type.tableName, hlc, deletedAt)
            else -> error("Local tombstone delete is not wired for ${type.tableName}")
        }
    }

    override suspend fun stamp(type: SyncEntityType, pk: String) {
        val hlc = nextHlc()
        when (type) {
            SyncEntityType.MEMBER -> dao.stampMemberHlc(pk, hlc)
            SyncEntityType.COACH_TASK -> dao.stampCoachTaskHlc(pk, hlc)
            else -> error("Local HLC stamp is not wired for ${type.tableName}")
        }
    }
}
