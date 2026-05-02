package com.silverbp.android.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExerciseMathTest {

    @Test
    fun `paceSecPerKm returns null below the 30m noise floor`() {
        assertNull(ExerciseMath.paceSecPerKm(durationMillis = 60_000, distanceMeters = 0.0))
        assertNull(ExerciseMath.paceSecPerKm(durationMillis = 60_000, distanceMeters = 29.9))
    }

    @Test
    fun `paceSecPerKm returns null on non-positive duration`() {
        assertNull(ExerciseMath.paceSecPerKm(durationMillis = 0, distanceMeters = 1_000.0))
    }

    @Test
    fun `paceSecPerKm computes 6 minutes per km`() {
        val pace = ExerciseMath.paceSecPerKm(
            durationMillis = 6 * 60 * 1000,
            distanceMeters = 1_000.0,
        )
        assertNotNull(pace)
        assertEquals(360.0, pace!!, 0.5)
    }

    @Test
    fun `formatPace handles null and zero`() {
        assertEquals("—", ExerciseMath.formatPace(null))
        assertEquals("—", ExerciseMath.formatPace(0.0))
        assertEquals("—", ExerciseMath.formatPace(Double.NaN))
    }

    @Test
    fun `formatPace produces minutes and seconds`() {
        assertEquals("5'42\"/km", ExerciseMath.formatPace(342.0))
        assertEquals("6'00\"/km", ExerciseMath.formatPace(360.0))
    }

    @Test
    fun `formatDuration handles minute and hour boundaries`() {
        assertEquals("0:00", ExerciseMath.formatDuration(0))
        assertEquals("0:59", ExerciseMath.formatDuration(59_000))
        assertEquals("1:00", ExerciseMath.formatDuration(60_000))
        assertEquals("12:34", ExerciseMath.formatDuration(12 * 60_000 + 34_000))
        assertEquals("1:00:00", ExerciseMath.formatDuration(3_600_000))
        assertEquals("1:23:45", ExerciseMath.formatDuration(3600_000 + 23 * 60_000 + 45_000))
    }

    @Test
    fun `formatDistance switches to km at 1 km`() {
        assertEquals("0 m", ExerciseMath.formatDistance(0.0))
        assertEquals("456 m", ExerciseMath.formatDistance(456.0))
        assertEquals("999 m", ExerciseMath.formatDistance(999.0))
        assertEquals("1.00 km", ExerciseMath.formatDistance(1_000.0))
        assertEquals("1.23 km", ExerciseMath.formatDistance(1_234.0))
    }

    @Test
    fun `haversineMeters approximates Taipei to Kaohsiung`() {
        // Taipei 101 ≈ (25.0330, 121.5654); Kaohsiung 85 sky ≈ (22.6112, 120.3014).
        // Geodesic distance ≈ 296 km.
        val meters = ExerciseMath.haversineMeters(25.0330, 121.5654, 22.6112, 120.3014)
        assertTrue("got $meters", abs(meters - 296_000.0) < 5_000.0)
    }

    @Test
    fun `haversineMeters returns zero for identical points`() {
        val meters = ExerciseMath.haversineMeters(25.0, 121.0, 25.0, 121.0)
        assertEquals(0.0, meters, 1e-6)
    }
}
