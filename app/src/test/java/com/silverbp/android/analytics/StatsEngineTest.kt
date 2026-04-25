package com.silverbp.android.analytics

import com.silverbp.android.core.BpReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StatsEngineTest {

    @Test fun `mean of empty list is zero`() {
        assertEquals(0.0, StatsEngine.mean(emptyList()), 1e-9)
    }

    @Test fun `mean computes simple average`() {
        assertEquals(20.0, StatsEngine.mean(listOf(10.0, 20.0, 30.0)), 1e-9)
    }

    @Test fun `standardDeviation uses sample n-1 divisor`() {
        // SD of [2,4,4,4,5,5,7,9] with n-1 = 2.138...
        val xs = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        assertEquals(2.138, StatsEngine.standardDeviation(xs), 0.001)
    }

    @Test fun `standardDeviation returns zero for single-element list`() {
        assertEquals(0.0, StatsEngine.standardDeviation(listOf(120.0)), 1e-9)
    }

    @Test fun `averageRealVariability of consecutive diffs`() {
        // diffs |2-1|+|3-2|+|5-3| = 1+1+2 = 4 / 3 = 1.333
        val xs = listOf(1.0, 2.0, 3.0, 5.0)
        assertEquals(4.0 / 3.0, StatsEngine.averageRealVariability(xs), 1e-9)
    }

    @Test fun `morningSurge null when either side empty`() {
        assertNull(StatsEngine.morningSurge(emptyList(), listOf(120.0)))
        assertNull(StatsEngine.morningSurge(listOf(120.0), emptyList()))
    }

    @Test fun `morningSurge returns delta`() {
        val surge = StatsEngine.morningSurge(listOf(140.0, 138.0), listOf(120.0, 124.0))
        assertEquals(17.0, surge!!, 1e-9)
    }

    @Test fun `movingAverage rolls correctly`() {
        val xs = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val ma = StatsEngine.movingAverage(xs, 3)
        assertEquals(listOf(2.0, 3.0, 4.0), ma)
    }

    @Test fun `movingAverage returns empty when window too large`() {
        assertTrue(StatsEngine.movingAverage(listOf(1.0, 2.0), 5).isEmpty())
    }

    @Test fun `whiteCoatHint flags large office-home gap`() {
        val office = listOf(reading(150, 95))
        val home = listOf(reading(125, 80))
        val hint = StatsEngine.whiteCoatHint(office, home)
        assertTrue(hint is StatsEngine.WhiteCoatHint.WhiteCoat)
    }

    @Test fun `whiteCoatHint flags masked when home exceeds office`() {
        val office = listOf(reading(120, 75))
        val home = listOf(reading(145, 90))
        val hint = StatsEngine.whiteCoatHint(office, home)
        assertTrue(hint is StatsEngine.WhiteCoatHint.Masked)
    }

    @Test fun `whiteCoatHint null when within margin`() {
        val office = listOf(reading(130, 82))
        val home = listOf(reading(125, 78))
        assertNull(StatsEngine.whiteCoatHint(office, home))
    }

    private fun reading(sys: Int, dia: Int) = BpReading(
        systolic = sys, diastolic = dia, timestamp = Instant.parse("2026-01-01T08:00:00Z"),
    )
}
