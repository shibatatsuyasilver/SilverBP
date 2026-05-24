package com.silverbp.android.exercise

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin equirectangular projection of (lat, lon) route points onto a
 * pixel rect. Factored out of [RouteBitmapRenderer] so the math can be
 * unit-tested on the JVM without the Android Bitmap class.
 *
 * Why not Google Maps' SphericalUtil: the notification surface needs to
 * render every second on the main thread, must work offline, and cannot pull
 * in the Maps SDK on a process without the GL context loaded. A flat
 * equirectangular projection with cos(centerLat) correction is sufficient
 * for the < 100 km bounding box a single workout produces.
 */
internal object RouteProjection {

    /** Smallest span (along either axis) the projection will respect; any
     *  smaller and a near-stationary workout would render as one pixel. */
    const val MIN_BBOX_METERS = 80.0
    private const val METERS_PER_DEG_LAT = 111_320.0

    data class LatLon(val lat: Double, val lon: Double)

    data class Bbox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    )

    /** Pixel rect inside which the projection should land. */
    data class PixelRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /** Inclusive lat/lon bounding box of [points], or null if [points] is empty. */
    fun bbox(points: List<LatLon>): Bbox? {
        if (points.isEmpty()) return null
        var minLat = points[0].lat
        var maxLat = minLat
        var minLon = points[0].lon
        var maxLon = minLon
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        return Bbox(minLat, maxLat, minLon, maxLon)
    }

    /**
     * Inflate so each axis spans at least [MIN_BBOX_METERS] meters, symmetric
     * around the center. Necessary so a stationary or one-point workout
     * doesn't collapse to a single pixel.
     */
    fun inflate(box: Bbox): Bbox {
        val centerLat = (box.minLat + box.maxLat) / 2.0
        val centerLon = (box.minLon + box.maxLon) / 2.0
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(centerLat))

        val latSpanMeters = (box.maxLat - box.minLat) * METERS_PER_DEG_LAT
        val lonSpanMeters = (box.maxLon - box.minLon) * metersPerDegLon

        val halfLatDeg = (max(latSpanMeters, MIN_BBOX_METERS) / 2.0) / METERS_PER_DEG_LAT
        val halfLonDeg = if (metersPerDegLon > 0)
            (max(lonSpanMeters, MIN_BBOX_METERS) / 2.0) / metersPerDegLon
        else 0.0

        return Bbox(
            minLat = centerLat - halfLatDeg,
            maxLat = centerLat + halfLatDeg,
            minLon = centerLon - halfLonDeg,
            maxLon = centerLon + halfLonDeg,
        )
    }

    /**
     * Project [points] into [drawable] using the supplied [box]. Equal
     * meters-per-pixel on both axes (after correcting longitude by
     * cos(centerLat)) so the polyline isn't horizontally stretched at higher
     * latitudes. Returns a flat FloatArray of `(x0, y0, x1, y1, …)`. All
     * output coordinates land within [drawable] (the projection is centred
     * along the non-limiting axis).
     */
    fun project(points: List<LatLon>, box: Bbox, drawable: PixelRect): FloatArray {
        if (points.isEmpty()) return FloatArray(0)
        val centerLatRad = Math.toRadians((box.minLat + box.maxLat) / 2.0)
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(centerLatRad)
        val latSpanMeters = (box.maxLat - box.minLat) * METERS_PER_DEG_LAT
        val lonSpanMeters = (box.maxLon - box.minLon) * metersPerDegLon

        val scaleX: Double = if (lonSpanMeters > 0) drawable.width / lonSpanMeters else Double.POSITIVE_INFINITY
        val scaleY: Double = if (latSpanMeters > 0) drawable.height / latSpanMeters else Double.POSITIVE_INFINITY
        val scale: Double = min(scaleX, scaleY)
        val projW = (lonSpanMeters * scale).toFloat()
        val projH = (latSpanMeters * scale).toFloat()
        val offsetX = drawable.left + (drawable.width - projW) / 2f
        val offsetY = drawable.top + (drawable.height - projH) / 2f

        val out = FloatArray(points.size * 2)
        for (i in points.indices) {
            val p = points[i]
            val dxMeters = (p.lon - box.minLon) * metersPerDegLon
            // Latitude axis is inverted: north (higher lat) renders at lower y.
            val dyMeters = (box.maxLat - p.lat) * METERS_PER_DEG_LAT
            out[i * 2] = offsetX + (dxMeters * scale).toFloat()
            out[i * 2 + 1] = offsetY + (dyMeters * scale).toFloat()
        }
        return out
    }
}
