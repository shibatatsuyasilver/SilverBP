package com.silverbp.android.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlcTest {

    @Test
    fun packed_string_roundtrip_preserves_components() {
        val hlc = Hlc.of(physicalMs = 0x0123_4567_89ABL, logical = 0xCDEF, nodeId = 0x1122_3344_5566_7788L)
        assertEquals(0x0123_4567_89ABL, hlc.physicalMs)
        assertEquals(0xCDEF, hlc.logical)
        assertEquals(0x1122_3344_5566_7788L, hlc.nodeId)
        assertEquals(32, hlc.packed.length)
    }

    @Test
    fun lex_order_matches_causal_order_within_same_node() {
        val node = 0xFFL
        val a = Hlc.of(1_700_000_000_000L, 0, node)
        val b = Hlc.of(1_700_000_000_000L, 1, node)
        val c = Hlc.of(1_700_000_000_001L, 0, node)
        assertTrue(a < b)
        assertTrue(b < c)
        assertTrue(a < c)
    }

    @Test
    fun zero_is_lex_min() {
        val real = Hlc.of(1_700_000_000_000L, 0, 1L)
        assertTrue(Hlc.ZERO < real)
    }

    @Test
    fun next_strictly_increases_when_clock_stalls() {
        var fakeNow = 1_700_000_000_000L
        val clock = HlcClock(nodeId = 42L, now = { fakeNow })
        val a = clock.next()
        val b = clock.next()
        val c = clock.next()
        assertTrue(a < b)
        assertTrue(b < c)
        // physical stayed flat, so logical must climb
        assertEquals(a.physicalMs, b.physicalMs)
        assertEquals(0, a.logical)
        assertEquals(1, b.logical)
        assertEquals(2, c.logical)
        // moving the clock forward resets logical
        fakeNow = 1_700_000_000_005L
        val d = clock.next()
        assertEquals(0, d.logical)
        assertTrue(c < d)
    }

    @Test
    fun observe_bumps_max_so_subsequent_writes_dominate() {
        var fakeNow = 1_700_000_000_000L
        val clock = HlcClock(nodeId = 1L, now = { fakeNow })
        val peerHlc = Hlc.of(1_700_000_000_500L, 7, 99L) // peer is "ahead"
        clock.observe(peerHlc)
        val mine = clock.next()
        assertTrue("local issue must beat observed peer", peerHlc < mine)
        assertEquals(1L, mine.nodeId)
    }

    @Test
    fun nodeId_disambiguates_at_same_physical_and_logical() {
        val a = Hlc.of(1_700_000_000_000L, 0, 1L)
        val b = Hlc.of(1_700_000_000_000L, 0, 2L)
        assertNotEquals(a, b)
        assertTrue(a < b)
    }

    @Test
    fun zero_constant_is_canonical() {
        // Hlc is a value class so reference identity (assertSame) is not
        // meaningful — boxing creates new objects. We verify equality
        // semantics + the canonical packed form.
        val z = Hlc.ZERO
        assertEquals(Hlc("0".repeat(32)), z)
        assertEquals("0".repeat(32), z.packed)
    }
}
