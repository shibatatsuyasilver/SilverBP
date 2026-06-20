package com.silverbp.android.ui.insights

import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.Member
import com.silverbp.android.ui.history.DateRange
import com.silverbp.android.ui.member.MemberPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Pins the pure compare helpers that every downstream chart/screen agent codes
 * against: [computeMemberInsights] (single ⇄ compare share one pipeline) and
 * [buildSeries] (deterministic colour-collision reassignment + per-member
 * guideline routing).
 */
class InsightsViewModelTest {

    private fun reading(sys: Int, dia: Int, hoursAgo: Long, atHour: Int? = null): BpReading {
        // Either a relative "hoursAgo" (range filtering) or a fixed local hour
        // (daypart bucketing). atHour wins when supplied.
        val ts = if (atHour != null) {
            Instant.now().truncatedTo(ChronoUnit.DAYS).plus(atHour.toLong(), ChronoUnit.HOURS)
        } else {
            Instant.now().minus(hoursAgo, ChronoUnit.HOURS)
        }
        return BpReading(systolic = sys, diastolic = dia, timestamp = ts)
    }

    @Test
    fun `computeMemberInsights filters by date range`() {
        val all = listOf(
            reading(120, 80, hoursAgo = 1),       // within the last 30 days
            reading(140, 90, hoursAgo = 24 * 40), // 40 days ago — outside Last30
        )
        val insights = computeMemberInsights(all, DateRange.Last30, HypertensionGuideline.Taiwan2022)
        assertEquals(1, insights.readings.size)
        assertEquals(120.0, insights.meanSystolic, 1e-9)
    }

    @Test
    fun `computeMemberInsights All range keeps everything`() {
        val all = listOf(
            reading(120, 80, hoursAgo = 1),
            reading(140, 90, hoursAgo = 24 * 100),
        )
        val insights = computeMemberInsights(all, DateRange.All, HypertensionGuideline.Taiwan2022)
        assertEquals(2, insights.readings.size)
        assertEquals(130.0, insights.meanSystolic, 1e-9)
        assertEquals(85.0, insights.meanDiastolic, 1e-9)
    }

    @Test
    fun `computeMemberInsights reproduces the inline single-member pipeline`() {
        val all = listOf(
            reading(118, 78, hoursAgo = 2),
            reading(132, 84, hoursAgo = 30),
            reading(145, 92, hoursAgo = 50),
        )
        val range = DateRange.All
        val guideline = HypertensionGuideline.Taiwan2022
        val insights = computeMemberInsights(all, range, guideline)

        // Re-derive the same numbers the way the VM body always has.
        val sys = all.map { it.systolic.toDouble() }
        val dia = all.map { it.diastolic.toDouble() }
        assertEquals(sys.average(), insights.meanSystolic, 1e-9)
        assertEquals(dia.average(), insights.meanDiastolic, 1e-9)
        // Distribution sums to the reading count (all classified, none dropped).
        assertEquals(all.size, insights.distribution.values.sum())
        // Readings come back time-sorted ascending.
        assertEquals(
            all.sortedBy { it.timestamp }.map { it.timestamp },
            insights.readings.map { it.timestamp },
        )
    }

    @Test
    fun `computeMemberInsights classifies with the supplied guideline`() {
        // 135/85 is Stage1 under Taiwan2022 (>=130) but Elevated under Esh2023 (>=130 dia 85).
        val all = listOf(reading(135, 85, hoursAgo = 1))
        val taiwan = computeMemberInsights(all, DateRange.All, HypertensionGuideline.Taiwan2022)
        val esh = computeMemberInsights(all, DateRange.All, HypertensionGuideline.Esh2023)
        assertEquals(1, taiwan.distribution[BpCategory.Stage1])
        assertEquals(1, esh.distribution[BpCategory.Elevated])
    }

    @Test
    fun `computeMemberInsights morningSurge null when a daypart is empty`() {
        // Only morning readings → evening set empty → surge null.
        val all = listOf(reading(140, 90, hoursAgo = 0, atHour = 7))
        val insights = computeMemberInsights(all, DateRange.All, HypertensionGuideline.Taiwan2022)
        assertNull(insights.morningSurge)
    }

    @Test
    fun `buildSeries keeps distinct colours when colorIndex differs`() {
        val a = member(colorIndex = 0)
        val b = member(colorIndex = 1)
        val series = buildSeries(listOf(a, b), listOf(emptyList(), emptyList()), DateRange.All)
        assertEquals(MemberPalette.colorFor(0), series[0].color)
        assertEquals(MemberPalette.colorFor(1), series[1].color)
    }

    @Test
    fun `buildSeries reassigns a distinct colour on collision`() {
        // Both members carry colorIndex 0 → second must be reassigned away from it.
        val a = member(colorIndex = 0)
        val b = member(colorIndex = 0)
        val series = buildSeries(listOf(a, b), listOf(emptyList(), emptyList()), DateRange.All)
        assertEquals(MemberPalette.colorFor(0), series[0].color)
        assertNotEquals(series[0].color, series[1].color)
        // Reassigned to the first still-unused palette colour (index 1).
        assertEquals(MemberPalette.colorFor(1), series[1].color)
    }

    @Test
    fun `buildSeries reassignment is deterministic and order-stable`() {
        val a = member(colorIndex = 2)
        val b = member(colorIndex = 2)
        val c = member(colorIndex = 2)
        val series = buildSeries(
            listOf(a, b, c),
            listOf(emptyList(), emptyList(), emptyList()),
            DateRange.All,
        )
        // First keeps its own; the next two take the first two unused palette slots.
        assertEquals(MemberPalette.colorFor(2), series[0].color)
        assertEquals(MemberPalette.colorFor(0), series[1].color)
        assertEquals(MemberPalette.colorFor(1), series[2].color)
        // All three distinct.
        assertEquals(3, series.map { it.color }.toSet().size)
    }

    @Test
    fun `buildSeries routes each member's own guideline`() {
        // 135/85 → Stage1 under Taiwan2022, Elevated under Esh2023.
        val readings = listOf(reading(135, 85, hoursAgo = 1))
        val taiwanMember = member(colorIndex = 0, guideline = HypertensionGuideline.Taiwan2022)
        val eshMember = member(colorIndex = 1, guideline = HypertensionGuideline.Esh2023)
        val series = buildSeries(
            listOf(taiwanMember, eshMember),
            listOf(readings, readings),
            DateRange.All,
        )
        assertEquals(1, series[0].insights.distribution[BpCategory.Stage1])
        assertEquals(1, series[1].insights.distribution[BpCategory.Elevated])
    }

    @Test
    fun `buildSeries carries identity fields raw for the screen to localize`() {
        val owner = member(colorIndex = 0, displayName = "", isOwner = true)
        val named = member(colorIndex = 1, displayName = "Mom", isOwner = false)
        val series = buildSeries(listOf(owner, named), listOf(emptyList(), emptyList()), DateRange.All)
        // Owner name stays blank (screen applies the localized "Me" fallback).
        assertEquals("", series[0].name)
        assert(series[0].isOwner)
        assertEquals("Mom", series[1].name)
    }

    private fun member(
        colorIndex: Int,
        guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022,
        displayName: String = "M$colorIndex",
        isOwner: Boolean = false,
    ) = Member(
        id = UUID.randomUUID(),
        displayName = displayName,
        isOwner = isOwner,
        guideline = guideline,
        colorIndex = colorIndex,
    )
}
