package com.silverbp.android.exercise

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure formatting + math helpers for the exercise feature. Live distance
 * accumulation uses [haversineMeters] inside the LiveStore — keeping it pure
 * Kotlin lets the LiveStore logic be unit-tested without Android stubs.
 */
object ExerciseMath {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * Average pace in seconds-per-kilometre. Returns null if [distanceMeters]
     * is below the 30 m noise floor — pace based on a few GPS jitters is
     * meaningless and the iOS app suppresses it the same way.
     */
    fun paceSecPerKm(durationMillis: Long, distanceMeters: Double): Double? {
        if (distanceMeters < 30.0 || durationMillis <= 0) return null
        val km = distanceMeters / 1000.0
        return (durationMillis / 1000.0) / km
    }

    /** Format pace as `m'ss"/km`, or "—" when [secPerKm] is null/non-finite. */
    fun formatPace(secPerKm: Double?): String {
        if (secPerKm == null || !secPerKm.isFinite() || secPerKm <= 0.0) return "—"
        val totalSec = secPerKm.roundToLong()
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d'%02d\"/km".format(minutes, seconds)
    }

    /** Format milliseconds as `mm:ss` (or `h:mm:ss` once over an hour). */
    fun formatDuration(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    /** Format meters as `1.23 km` (≥ 1 km) or `456 m` (< 1 km). */
    fun formatDistance(meters: Double): String =
        if (meters >= 1000.0) "%.2f km".format(meters / 1000.0)
        else "${meters.roundToInt()} m"

    /**
     * Great-circle distance in meters. Used in unit tests; runtime path goes
     * through SphericalUtil to avoid duplicating maintenance.
     */
    fun haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }
}
