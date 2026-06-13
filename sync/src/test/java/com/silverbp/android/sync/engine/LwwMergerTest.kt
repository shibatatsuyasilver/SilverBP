package com.silverbp.android.sync.engine

import com.silverbp.android.sync.protocol.SyncRecordSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6 LWW gate unit tests. The gate decides whether an inbound [SyncRecord]
 * dominates the local copy; these cases pin the newer-wins / stale-rejected /
 * tombstone-both-directions / equal-hlc-tie behaviour the audit (B6) requires.
 */
class LwwMergerTest {

    private val node = 0xABCDEF12_3456_789AuL.toLong()

    private fun rec(hlc: Hlc, tombstone: Boolean = false) = SyncRecord(
        type = SyncEntityType.BP_READING,
        pk = "row-1",
        hlc = hlc,
        deletedAt = if (tombstone) hlc.physicalMs else null,
        payload = if (tombstone) emptyMap() else mapOf(1 to SyncValue.Int64(120)),
    )

    /** Records the sink received, in order. */
    private class CapturingSink : SyncRecordSink {
        val applied = mutableListOf<SyncRecord>()
        override suspend fun apply(record: SyncRecord) { applied += record }
    }

    private fun merger(local: Hlc?, sink: SyncRecordSink) =
        LwwMerger(inner = sink, localHlc = { local })

    @Test
    fun newer_record_applies() = runTest {
        val sink = CapturingSink()
        val local = Hlc.of(1_000L, 0, node)
        val incoming = rec(Hlc.of(2_000L, 0, node))
        val applied = merger(local, sink).apply(incoming)

        assertTrue("newer hlc must apply", applied)
        assertEquals(1, sink.applied.size)
        assertEquals(incoming, sink.applied.single())
    }

    @Test
    fun stale_record_is_rejected() = runTest {
        val sink = CapturingSink()
        val local = Hlc.of(2_000L, 0, node)
        val incoming = rec(Hlc.of(1_000L, 0, node)) // older
        val applied = merger(local, sink).apply(incoming)

        assertFalse("stale hlc must be rejected", applied)
        assertTrue("sink must not be touched", sink.applied.isEmpty())
    }

    @Test
    fun equal_hlc_is_rejected_stale_on_equal() = runTest {
        // Equal HLC = byte-identical state already held locally; re-applying is
        // wasteful and the documented tie rule is `>` not `>=`.
        val sink = CapturingSink()
        val same = Hlc.of(1_500L, 3, node)
        val applied = merger(same, sink).apply(rec(same))

        assertFalse("equal hlc must not re-apply", applied)
        assertTrue(sink.applied.isEmpty())
    }

    @Test
    fun no_local_trace_always_applies() = runTest {
        val sink = CapturingSink()
        val incoming = rec(Hlc.ZERO) // even ZERO applies when there's no local row
        val applied = merger(null, sink).apply(incoming)

        assertTrue("absent local copy → always apply", applied)
        assertEquals(incoming, sink.applied.single())
    }

    @Test
    fun newer_tombstone_deletes() = runTest {
        // A delete that post-dates the local live row must win.
        val sink = CapturingSink()
        val local = Hlc.of(1_000L, 0, node)
        val tomb = rec(Hlc.of(3_000L, 0, node), tombstone = true)
        val applied = merger(local, sink).apply(tomb)

        assertTrue("newer tombstone must apply", applied)
        assertTrue(sink.applied.single().isTombstone)
    }

    @Test
    fun stale_tombstone_does_not_resurrect_delete() = runTest {
        // The headline B6 bug: a stale tombstone must NOT delete a newer local
        // edit. local hlc (the newer edit) dominates the older delete.
        val sink = CapturingSink()
        val local = Hlc.of(5_000L, 0, node)
        val staleTomb = rec(Hlc.of(2_000L, 0, node), tombstone = true)
        val applied = merger(local, sink).apply(staleTomb)

        assertFalse("stale tombstone must be rejected", applied)
        assertTrue(sink.applied.isEmpty())
    }

    @Test
    fun newer_live_record_overrides_older_tombstone() = runTest {
        // Reverse direction: a fresh edit/re-add must win over an older delete
        // whose hlc the caller folded into the local high-water mark.
        val sink = CapturingSink()
        val localTombstoneHlc = Hlc.of(2_000L, 0, node)
        val freshLive = rec(Hlc.of(4_000L, 0, node))
        val applied = merger(localTombstoneHlc, sink).apply(freshLive)

        assertTrue("newer live record must override older tombstone", applied)
        assertEquals(freshLive, sink.applied.single())
    }

    @Test
    fun logical_counter_breaks_same_physical_ties() = runTest {
        // Same physical ms, higher logical = newer (HLC total order).
        val sink = CapturingSink()
        val local = Hlc.of(1_000L, 4, node)
        val incoming = rec(Hlc.of(1_000L, 5, node))
        assertTrue(merger(local, sink).apply(incoming))
        // and the reverse is stale
        val sink2 = CapturingSink()
        assertFalse(merger(Hlc.of(1_000L, 5, node), sink2).apply(rec(Hlc.of(1_000L, 4, node))))
    }
}
