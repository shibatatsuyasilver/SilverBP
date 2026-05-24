package com.silverbp.android.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteProjectionTest {

    private val rect = RouteProjection.PixelRect(left = 0f, top = 0f, width = 100f, height = 100f)

    @Test
    fun `bbox returns null for empty list`() {
        assertNull(RouteProjection.bbox(emptyList()))
    }

    @Test
    fun `bbox of single point is degenerate`() {
        val box = RouteProjection.bbox(listOf(RouteProjection.LatLon(25.0, 121.5)))
        assertNotNull(box)
        assertEquals(25.0, box!!.minLat, 1e-9)
        assertEquals(25.0, box.maxLat, 1e-9)
        assertEquals(121.5, box.minLon, 1e-9)
        assertEquals(121.5, box.maxLon, 1e-9)
    }

    @Test
    fun `project on empty list returns empty array`() {
        val box = RouteProjection.Bbox(0.0, 0.0, 0.0, 0.0)
        assertEquals(0, RouteProjection.project(emptyList(), box, rect).size)
    }

    @Test
    fun `inflate enforces minimum span of 80m on each axis`() {
        // Single-point degenerate bbox at lat=0.
        val box = RouteProjection.Bbox(minLat = 0.0, maxLat = 0.0, minLon = 0.0, maxLon = 0.0)
        val inflated = RouteProjection.inflate(box)
        // 80 m / 111_320 m-per-deg-lat ≈ 0.000719 deg total span.
        val latSpanMeters = (inflated.maxLat - inflated.minLat) * 111_320.0
        // At lat=0, deg-per-lon == deg-per-lat.
        val lonSpanMeters = (inflated.maxLon - inflated.minLon) * 111_320.0
        assertEquals(RouteProjection.MIN_BBOX_METERS, latSpanMeters, 0.1)
        assertEquals(RouteProjection.MIN_BBOX_METERS, lonSpanMeters, 0.1)
    }

    @Test
    fun `inflate applies cos correction at lat 60 degrees`() {
        // Single point at lat=60° — both axes inflate to 80m. lon spans twice
        // as many degrees as lat because 1 deg lon at lat=60° == half the
        // meters of 1 deg lat (cos(60°) == 0.5).
        val box = RouteProjection.Bbox(minLat = 60.0, maxLat = 60.0, minLon = 0.0, maxLon = 0.0)
        val inflated = RouteProjection.inflate(box)
        val lonSpanDeg = inflated.maxLon - inflated.minLon
        val latSpanDeg = inflated.maxLat - inflated.minLat
        // 1/cos(60°) == 2.
        assertEquals(2.0, lonSpanDeg / latSpanDeg, 0.01)
    }

    @Test
    fun `inflate leaves a bbox already above 80m on both axes alone`() {
        // 1 km × 1 km at lat=0.
        val deg = 1_000.0 / 111_320.0
        val box = RouteProjection.Bbox(minLat = -deg / 2, maxLat = deg / 2, minLon = -deg / 2, maxLon = deg / 2)
        val inflated = RouteProjection.inflate(box)
        assertEquals(box.minLat, inflated.minLat, 1e-12)
        assertEquals(box.maxLat, inflated.maxLat, 1e-12)
        assertEquals(box.minLon, inflated.minLon, 1e-12)
        assertEquals(box.maxLon, inflated.maxLon, 1e-12)
    }

    @Test
    fun `project two points on same latitude shares y and spans width`() {
        // Two points 100 m apart at the equator. After bbox + inflate, lon
        // span (100m) limits projection on a square 100×100 drawable so the
        // points span the full width and share a y at the vertical centre.
        val lonDelta = 100.0 / 111_320.0
        val points = listOf(
            RouteProjection.LatLon(lat = 0.0, lon = 0.0),
            RouteProjection.LatLon(lat = 0.0, lon = lonDelta),
        )
        val box = RouteProjection.inflate(RouteProjection.bbox(points)!!)
        val coords = RouteProjection.project(points, box, rect)
        assertEquals(4, coords.size)
        val x1 = coords[0]; val y1 = coords[1]
        val x2 = coords[2]; val y2 = coords[3]
        assertEquals("y identical for equal lats", y1, y2, 1e-3f)
        assertEquals("y at vertical centre", rect.height / 2f, y1, 1e-3f)
        assertEquals("x1 at left edge", rect.left, x1, 1e-3f)
        assertEquals("x2 at right edge", rect.left + rect.width, x2, 1e-3f)
    }

    @Test
    fun `project keeps all points within drawable bounds`() {
        // Pathological-shape route: long diagonal segments that test scale-fit.
        val points = listOf(
            RouteProjection.LatLon(25.0330, 121.5654),  // Taipei
            RouteProjection.LatLon(25.0500, 121.5400),
            RouteProjection.LatLon(25.0200, 121.5800),
            RouteProjection.LatLon(25.0400, 121.5654),
        )
        val box = RouteProjection.inflate(RouteProjection.bbox(points)!!)
        val coords = RouteProjection.project(points, box, rect)
        for (i in coords.indices step 2) {
            val x = coords[i]; val y = coords[i + 1]
            assertTrue("x=$x within [${rect.left}, ${rect.left + rect.width}]",
                x >= rect.left - 1e-3f && x <= rect.left + rect.width + 1e-3f)
            assertTrue("y=$y within [${rect.top}, ${rect.top + rect.height}]",
                y >= rect.top - 1e-3f && y <= rect.top + rect.height + 1e-3f)
        }
    }

    @Test
    fun `project lat increases upward (y decreases)`() {
        // Point further north should render with a smaller y coordinate.
        val points = listOf(
            RouteProjection.LatLon(0.0, 0.0),
            RouteProjection.LatLon(0.001, 0.0),  // further north
        )
        val box = RouteProjection.inflate(RouteProjection.bbox(points)!!)
        val coords = RouteProjection.project(points, box, rect)
        assertTrue("northern point above southern", coords[3] < coords[1])
    }

    @Test
    fun `project single point lands at drawable centre`() {
        val points = listOf(RouteProjection.LatLon(60.0, 30.0))
        val box = RouteProjection.inflate(RouteProjection.bbox(points)!!)
        val coords = RouteProjection.project(points, box, rect)
        assertEquals(2, coords.size)
        // Inflated symmetric around the single point, so it projects to centre.
        assertEquals(rect.width / 2f, coords[0], 1e-3f)
        assertEquals(rect.height / 2f, coords[1], 1e-3f)
    }
}
