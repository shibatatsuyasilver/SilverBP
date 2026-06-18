package com.silverbp.android.sync

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoomSyncAdaptersTest {
    @Test
    fun selectRecordsSince_limits_by_lowest_hlc_not_table_order() {
        val highEarlyTable = record(SyncEntityType.BP_READING, "bp-high", Hlc.of(3_000L, 0, 1L))
        val lowLateTable = record(SyncEntityType.ROUTE_POINT, "route-low", Hlc.of(1_000L, 0, 1L))
        val midLaterTable = record(SyncEntityType.FOOD_LOG, "food-mid", Hlc.of(2_000L, 0, 1L))

        val selected = selectRecordsSince(
            candidates = listOf(
                SyncCandidate(highEarlyTable, dependencyRank = 10),
                SyncCandidate(lowLateTable, dependencyRank = 190),
                SyncCandidate(midLaterTable, dependencyRank = 160),
            ),
            peerLastHlcSeen = Hlc.ZERO,
            limit = 2,
        )

        assertEquals(setOf("route-low", "food-mid"), selected.map { it.pk }.toSet())
        assertFalse("higher early-table row must wait for a later batch", selected.any { it.pk == "bp-high" })
    }

    @Test
    fun selectRecordsSince_orders_parent_before_child_when_both_are_in_hlc_prefix() {
        val session = record(SyncEntityType.EXERCISE_SESSION, "session", Hlc.of(1_000L, 0, 1L))
        val route = record(SyncEntityType.ROUTE_POINT, "route", Hlc.of(1_001L, 0, 1L))

        val selected = selectRecordsSince(
            candidates = listOf(
                SyncCandidate(route, dependencyRank = 190),
                SyncCandidate(session, dependencyRank = 20),
            ),
            peerLastHlcSeen = Hlc.ZERO,
            limit = 2,
        )

        assertEquals(listOf("session", "route"), selected.map { it.pk })
    }

    private fun record(type: SyncEntityType, pk: String, hlc: Hlc) = SyncRecord(
        type = type,
        pk = pk,
        hlc = hlc,
        deletedAt = null,
        payload = emptyMap(),
    )
}
