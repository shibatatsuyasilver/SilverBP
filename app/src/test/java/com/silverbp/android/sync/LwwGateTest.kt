package com.silverbp.android.sync

import com.silverbp.android.sync.engine.Hlc
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the last-writer-wins decision that gates [CombinedRoomSyncSink].
 * Covers the data-loss vectors from the release audit (P0-3 + tombstone P1):
 * a stale peer/backup record must not overwrite a newer local row, and a stale
 * tombstone must not delete a newer live row.
 */
class LwwGateTest {

    private val node = 0xABCDL
    private fun hlc(ms: Long, logical: Int = 0) = Hlc.of(ms, logical, node).packed

    @Test
    fun applies_when_no_local_record() {
        assertTrue(lwwShouldApply(hlc(1_000), localLiveHlc = null, localTombstoneHlc = null))
    }

    @Test
    fun applies_when_strictly_newer_than_live_row() {
        assertTrue(lwwShouldApply(hlc(2_000), localLiveHlc = hlc(1_000), localTombstoneHlc = null))
    }

    @Test
    fun rejects_stale_record_against_newer_live_row() {
        // The P0-3 vector: an old copy must not REPLACE-overwrite a newer edit.
        assertFalse(lwwShouldApply(hlc(1_000), localLiveHlc = hlc(2_000), localTombstoneHlc = null))
    }

    @Test
    fun rejects_equal_hlc_is_idempotent() {
        // Equal HLC = same write seen twice → no-op (not strictly greater).
        assertFalse(lwwShouldApply(hlc(1_000), localLiveHlc = hlc(1_000), localTombstoneHlc = null))
    }

    @Test
    fun rejects_stale_record_against_newer_tombstone() {
        // Tombstone P1 vector: a live record older than a delete must not resurrect.
        assertFalse(lwwShouldApply(hlc(1_000), localLiveHlc = null, localTombstoneHlc = hlc(2_000)))
    }

    @Test
    fun applies_resurrect_when_newer_than_tombstone() {
        // A genuinely newer create after an older delete should win (add-wins by HLC).
        assertTrue(lwwShouldApply(hlc(3_000), localLiveHlc = null, localTombstoneHlc = hlc(2_000)))
    }

    @Test
    fun uses_the_greater_of_live_and_tombstone() {
        // Must beat BOTH: newer than the live row but not the tombstone → reject.
        assertFalse(lwwShouldApply(hlc(2_500), localLiveHlc = hlc(2_000), localTombstoneHlc = hlc(3_000)))
        // Newer than both → apply.
        assertTrue(lwwShouldApply(hlc(3_500), localLiveHlc = hlc(2_000), localTombstoneHlc = hlc(3_000)))
    }

    @Test
    fun logical_counter_breaks_same_millisecond_ties() {
        // Same physical ms, higher logical counter wins.
        assertTrue(lwwShouldApply(hlc(1_000, logical = 5), localLiveHlc = hlc(1_000, logical = 4), localTombstoneHlc = null))
        assertFalse(lwwShouldApply(hlc(1_000, logical = 4), localLiveHlc = hlc(1_000, logical = 5), localTombstoneHlc = null))
    }
}
