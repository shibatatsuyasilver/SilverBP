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

    /**
     * Fold a stray member's data into [ownerId] and delete the stray, atomically
     * (extra-member-after-merge-restore repair). Reassigns BP/glucose/weight/
     * medication rows to the owner with fresh HLCs and writes a MEMBER tombstone.
     * Defaulted to a no-op so the in-memory test writers keep compiling; the
     * production [RoomLocalSyncWriter] implements it.
     */
    suspend fun mergeMemberIntoOwner(strayId: String, ownerId: String) {}

    /** Total member-scoped record count for [memberId] (drives the merge confirm dialog). */
    suspend fun countMemberData(memberId: String): Int = 0
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

    override suspend fun mergeMemberIntoOwner(strayId: String, ownerId: String) {
        dao.mergeMemberIntoOwner(
            strayId = strayId,
            ownerId = ownerId,
            memberEntityType = SyncEntityType.MEMBER.tableName,
            reassignHlc = nextHlc(),
            deleteHlc = nextHlc(),
            deletedAt = nowMs(),
        )
    }

    override suspend fun countMemberData(memberId: String): Int = dao.countMemberData(memberId)
}
