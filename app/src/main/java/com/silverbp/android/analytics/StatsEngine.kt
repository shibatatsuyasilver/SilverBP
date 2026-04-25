package com.silverbp.android.analytics

import com.silverbp.android.core.BpReading
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure-math statistics engine. Direct port of iOS BPAnalytics.StatsEngine.
 * All inputs are domain primitives — no I/O, no Android dependencies.
 */
object StatsEngine {

    /** Arithmetic average. Returns 0.0 for empty input. */
    fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

    /** Sample standard deviation (n-1). Returns 0.0 for n <= 1. */
    fun standardDeviation(xs: List<Double>): Double {
        if (xs.size <= 1) return 0.0
        val m = mean(xs)
        val variance = xs.sumOf { (it - m).pow(2) } / (xs.size - 1)
        return sqrt(variance)
    }

    /**
     * Average Real Variability (ARV) — mean of |consecutive differences|.
     * Captures temporal volatility. Reference: Mena et al., J Hypertens 2005.
     */
    fun averageRealVariability(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until xs.size) total += abs(xs[i] - xs[i - 1])
        return total / (xs.size - 1)
    }

    /** Morning surge = mean(morning SBP) − mean(evening SBP). null if either set is empty. */
    fun morningSurge(morning: List<Double>, evening: List<Double>): Double? {
        if (morning.isEmpty() || evening.isEmpty()) return null
        return mean(morning) - mean(evening)
    }

    /** Sliding-window average. Returns empty if window > xs.size or window <= 0. */
    fun movingAverage(xs: List<Double>, window: Int): List<Double> {
        if (window <= 0 || xs.size < window) return emptyList()
        return (window - 1 until xs.size).map { i ->
            xs.subList(i - window + 1, i + 1).average()
        }
    }

    sealed class WhiteCoatHint {
        data class WhiteCoat(val deltaSystolic: Double, val deltaDiastolic: Double) : WhiteCoatHint()
        data class Masked(val deltaSystolic: Double, val deltaDiastolic: Double) : WhiteCoatHint()
    }

    /**
     * Detect ≥20 mmHg systolic or ≥10 mmHg diastolic divergence between
     * office and home readings. Returns null if no clinically-significant gap.
     */
    fun whiteCoatHint(office: List<BpReading>, home: List<BpReading>): WhiteCoatHint? {
        if (office.isEmpty() || home.isEmpty()) return null
        val officeSys = mean(office.map { it.systolic.toDouble() })
        val homeSys = mean(home.map { it.systolic.toDouble() })
        val officeDia = mean(office.map { it.diastolic.toDouble() })
        val homeDia = mean(home.map { it.diastolic.toDouble() })
        val ds = officeSys - homeSys
        val dd = officeDia - homeDia
        return when {
            ds >= 20 || dd >= 10 -> WhiteCoatHint.WhiteCoat(ds, dd)
            ds <= -20 || dd <= -10 -> WhiteCoatHint.Masked(-ds, -dd)
            else -> null
        }
    }
}
