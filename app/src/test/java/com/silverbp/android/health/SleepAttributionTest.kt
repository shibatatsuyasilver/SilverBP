package com.silverbp.android.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Guards the overnight-sleep attribution that previously broke: a session
 * spanning midnight must count entirely on the wake-up day, not be split.
 */
class SleepAttributionTest {

    private val taipei = ZoneId.of("Asia/Taipei") // UTC+8

    // 23:00 Taipei May 29 -> 07:00 Taipei May 30 (an 8h overnight sleep).
    private val bedTime = Instant.parse("2026-05-29T15:00:00Z")  // 23:00 +08
    private val wakeTime = Instant.parse("2026-05-29T23:00:00Z") // 07:00 +08 next day

    @Test fun `no stages uses full in-bed span`() {
        assertEquals(480, sleepMinutesFromIntervals(bedTime, wakeTime, emptyList()))
    }

    @Test fun `stages present sum only the asleep intervals`() {
        // 1h before midnight asleep + 6h after = 7h asleep (1h awake excluded).
        val seg1 = bedTime to Instant.parse("2026-05-29T16:00:00Z")            // 23:00-00:00 (1h)
        val seg2 = Instant.parse("2026-05-29T17:00:00Z") to wakeTime           // 01:00-07:00 (6h)
        assertEquals(420, sleepMinutesFromIntervals(bedTime, wakeTime, listOf(seg1, seg2)))
    }

    @Test fun `overnight sleep is attributed to the wake-up day, not the bed day`() {
        val bedDay = LocalDate.of(2026, 5, 29).atStartOfDay(taipei).toInstant().toEpochMilli()
        val wakeDay = LocalDate.of(2026, 5, 30).atStartOfDay(taipei).toInstant().toEpochMilli()
        assertEquals(wakeDay, wakeDayStartMillis(wakeTime, taipei))
        assertNotEquals(bedDay, wakeDayStartMillis(wakeTime, taipei))
    }
}
