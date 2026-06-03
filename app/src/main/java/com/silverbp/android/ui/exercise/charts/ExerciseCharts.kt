package com.silverbp.android.ui.exercise.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.ui.exercise.colorForKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EmptyHint = Color(0xFF8E8E93)
private val GridColor = Color(0xFFBDBDBD)

/**
 * Stacked daily-distance bar chart. Each day stacks every activity kind's
 * metres in [ActivityKind.entries] order, each scaled against a shared "nice
 * ceiling" Y-axis. The multi-colour stack replaces the previous single-tint
 * design so the chart no longer turns solid red just because the user
 * picked the Running chip before starting a session.
 */
@Composable
fun DailyDistanceStackedBarChart(
    daily: List<Pair<LocalDate, Map<ActivityKind, Double>>>,
    modifier: Modifier = Modifier,
    emptyLabel: String,
) {
    if (daily.isEmpty() || daily.all { (_, perKind) -> perKind.values.all { it <= 0.0 } }) {
        EmptyChart(emptyLabel, modifier.height(200.dp))
        return
    }
    val maxMeters = daily.maxOf { (_, perKind) -> perKind.values.sum() }.coerceAtLeast(1.0)
    val kindColors = ActivityKind.entries.associateWith { colorForKind(it) }
    val niceMax = niceCeilingMeters(maxMeters)
    val topLabel = formatDistanceTick(niceMax)
    val bottomLabel = "0"
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = EmptyHint, fontSize = 10.sp)
    val topLayout = measurer.measure(topLabel, labelStyle)
    val bottomLayout = measurer.measure(bottomLabel, labelStyle)

    val locale = Locale.getDefault()
    // Show weekday labels under every bar for short ranges; sparser for long ones.
    val xLabelEvery = when {
        daily.size <= 14 -> 1
        daily.size <= 31 -> 3
        daily.size <= 92 -> 7
        else -> 14
    }
    val xLayouts: List<androidx.compose.ui.text.TextLayoutResult?> = daily.mapIndexed { i, (date, _) ->
        if (i % xLabelEvery == 0) {
            val text = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale)
            measurer.measure(text, labelStyle)
        } else null
    }
    val xLabelHeight = xLayouts.filterNotNull().maxOfOrNull { it.size.height }?.toFloat() ?: 0f

    Canvas(modifier = modifier.height(200.dp).padding(8.dp)) {
        val gutter = topLayout.size.width.toFloat() + 6f
        val chartLeft = gutter
        val chartRight = size.width
        val chartWidth = chartRight - chartLeft
        val chartTop = topLayout.size.height / 2f
        val xLabelGap = if (xLabelHeight > 0f) 4f else 0f
        val chartBottom = size.height - xLabelHeight - xLabelGap - 1f

        drawLine(
            color = GridColor.copy(alpha = 0.4f),
            start = Offset(chartLeft, chartBottom),
            end = Offset(chartRight, chartBottom),
            strokeWidth = 1f,
        )
        drawLine(
            color = GridColor.copy(alpha = 0.25f),
            start = Offset(chartLeft, chartTop),
            end = Offset(chartRight, chartTop),
            strokeWidth = 1f,
        )

        drawText(
            textLayoutResult = topLayout,
            topLeft = Offset(chartLeft - topLayout.size.width - 6f, 0f),
        )
        drawText(
            textLayoutResult = bottomLayout,
            topLeft = Offset(
                chartLeft - bottomLayout.size.width - 6f,
                chartBottom - bottomLayout.size.height,
            ),
        )

        val n = daily.size
        val slot = chartWidth / n
        val barWidth = (slot * 0.7f).coerceAtLeast(2f)
        val plotHeight = chartBottom - chartTop
        daily.forEachIndexed { i, (_, perKind) ->
            val left = chartLeft + i * slot + (slot - barWidth) / 2f
            // Stack every kind from the bottom up, in enum order, so colours
            // are consistent across days.
            var stackedH = 0f
            ActivityKind.entries.forEach { kind ->
                val meters = perKind[kind] ?: 0.0
                if (meters <= 0.0) return@forEach
                val segH = (meters / niceMax * plotHeight).toFloat().coerceAtLeast(1f)
                drawRect(
                    color = kindColors.getValue(kind),
                    topLeft = Offset(left, chartBottom - stackedH - segH),
                    size = Size(barWidth, segH),
                )
                stackedH += segH
            }
            xLayouts[i]?.let { layout ->
                val centerX = chartLeft + i * slot + slot / 2f
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        centerX - layout.size.width / 2f,
                        chartBottom + xLabelGap,
                    ),
                )
            }
        }
    }
}

/** Round meters up to a clean tick (10 m / 100 m / 500 m / 1 km depending on magnitude). */
private fun niceCeilingMeters(meters: Double): Double = when {
    meters <= 0.0 -> 1000.0
    meters < 100.0 -> ceil(meters / 10.0) * 10.0
    meters < 1_000.0 -> ceil(meters / 100.0) * 100.0
    meters < 10_000.0 -> ceil(meters / 500.0) * 500.0
    else -> ceil(meters / 1_000.0) * 1_000.0
}

/** Reuse [ExerciseMath.formatDistance] so axis ticks match the stats row. */
private fun formatDistanceTick(meters: Double): String = ExerciseMath.formatDistance(meters)

/**
 * Pace line chart split by activity kind. Walking and Running render as
 * separate green/red lines on a shared time + pace axis; either line is
 * skipped when its kind has no eligible session. Empty when neither kind
 * has at least two samples needed to draw a line.
 */
@Composable
fun PaceLineChart(
    seriesByKind: Map<ActivityKind, List<Pair<Instant, Double>>>,
    modifier: Modifier = Modifier,
    emptyLabel: String,
) {
    val sortedByKind: Map<ActivityKind, List<Pair<Instant, Double>>> = ActivityKind.entries
        .associateWith { kind -> seriesByKind[kind].orEmpty().sortedBy { it.first } }
        .filterValues { it.isNotEmpty() }

    val drawable = sortedByKind.filterValues { it.size >= 2 }
    val allPoints = sortedByKind.values.flatten()
    if (drawable.isEmpty() || allPoints.isEmpty()) {
        EmptyChart(emptyLabel, modifier.height(180.dp))
        return
    }

    val minTime = allPoints.minOf { it.first.toEpochMilli() }
    val maxTime = allPoints.maxOf { it.first.toEpochMilli() }
    val values = allPoints.map { it.second }
    val yMin = (values.min() * 0.9)
    val yMax = (values.max() * 1.1).coerceAtLeast(yMin + 1.0)

    // Self-describing axes — no prose caption. Y = pace ticks (top = faster,
    // because the line is Y-flipped) with an explicit 「快▲」 direction marker;
    // X = date ticks; a colour legend names walking vs running.
    val zone = ZoneId.systemDefault()
    val dateFmt = remember { DateTimeFormatter.ofPattern("M/d") }
    val startDate = Instant.ofEpochMilli(minTime).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(maxTime).atZone(zone).toLocalDate()
    val startText = startDate.format(dateFmt)
    val endText = endDate.format(dateFmt)
    val sameDay = startDate == endDate

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = EmptyHint, fontSize = 10.sp)
    val fastLayout = measurer.measure(ExerciseMath.formatPace(values.min()), axisStyle)
    val slowLayout = measurer.measure(ExerciseMath.formatPace(values.max()), axisStyle)
    val dirLayout = measurer.measure("快 ▲", axisStyle)
    val startLayout = measurer.measure(startText, axisStyle)
    val endLayout = measurer.measure(endText, axisStyle)
    val yAxisW = maxOf(
        fastLayout.size.width, slowLayout.size.width, dirLayout.size.width,
    ).toFloat() + 6f
    val xLabelHeight = maxOf(startLayout.size.height, endLayout.size.height).toFloat()
    val gap = 4f

    Canvas(modifier = modifier.height(180.dp).padding(8.dp)) {
        val plotLeft = yAxisW
        val plotW = size.width - plotLeft
        val chartBottom = size.height - xLabelHeight - gap
        fun px(t: Long): Float =
            if (maxTime > minTime) plotLeft + ((t - minTime).toFloat() / (maxTime - minTime)) * plotW
            else plotLeft + plotW / 2f
        fun py(v: Double): Float = mapYFlipped(v, yMin, yMax, chartBottom)

        // Y axis: direction marker + fastest/slowest pace ticks.
        drawText(textLayoutResult = dirLayout, topLeft = Offset(0f, 0f))
        drawText(
            textLayoutResult = fastLayout,
            topLeft = Offset(0f, dirLayout.size.height.toFloat() + 2f),
        )
        drawText(
            textLayoutResult = slowLayout,
            topLeft = Offset(0f, chartBottom - slowLayout.size.height),
        )

        drawable.forEach { (kind, samples) ->
            val color = colorForKind(kind)
            val path = Path()
            samples.forEachIndexed { i, (t, v) ->
                val x = px(t.toEpochMilli())
                val y = py(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 4f))
            samples.forEach { (t, v) ->
                drawCircle(color, radius = 4f, center = Offset(px(t.toEpochMilli()), py(v)))
            }
        }

        val labelY = chartBottom + gap
        if (sameDay) {
            drawText(
                textLayoutResult = startLayout,
                topLeft = Offset(plotLeft + (plotW - startLayout.size.width) / 2f, labelY),
            )
        } else {
            drawText(textLayoutResult = startLayout, topLeft = Offset(plotLeft, labelY))
            drawText(
                textLayoutResult = endLayout,
                topLeft = Offset(size.width - endLayout.size.width, labelY),
            )
        }
    }
    // Colour legend names each line — the chart needs no explanatory sentence.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        drawable.keys.forEach { kind ->
            val c = colorForKind(kind)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(c) }
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(kindLabelRes(kind)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun KindDistributionDonut(
    counts: Map<ActivityKind, Int>,
    modifier: Modifier = Modifier,
    emptyLabel: String,
) {
    val total = counts.values.sum().toFloat()
    if (total == 0f) {
        EmptyChart(emptyLabel, modifier.height(160.dp))
        return
    }
    val order: List<Pair<ActivityKind, Color>> = ActivityKind.entries.map { it to colorForKind(it) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(140.dp).padding(8.dp)) {
            var startAngle = -90f
            order.forEach { (kind, color) ->
                val n = counts[kind] ?: 0
                if (n == 0) return@forEach
                val sweep = 360f * n / total
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.minDimension, size.minDimension),
                    style = Stroke(width = size.minDimension * 0.22f),
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            order.forEach { (kind, color) ->
                val n = counts[kind] ?: 0
                if (n == 0) return@forEach
                val pct = (n / total * 100f).toInt()
                val label = stringResource(kindLabelRes(kind))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(color) }
                    Spacer(Modifier.size(6.dp))
                    Text("$label  $n ($pct%)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Heatmap of per-day exercise volume. Each cell's hue is the day's dominant
 * activity kind (the kind with the most metres that day) and the cell's alpha
 * grows with total distance vs the range max — so a busy walking day stays
 * green instead of being painted darkest just because it's the busiest cell
 * on the grid.
 */
@Composable
fun ContributionCalendar(
    daily: List<Pair<LocalDate, Map<ActivityKind, Double>>>,
    modifier: Modifier = Modifier,
    emptyLabel: String,
) {
    if (daily.isEmpty() || daily.all { (_, perKind) -> perKind.values.all { it <= 0.0 } }) {
        EmptyChart(emptyLabel, modifier.height(160.dp))
        return
    }
    val sorted = daily.sortedBy { it.first }
    val maxMeters = sorted.maxOf { (_, perKind) -> perKind.values.sum() }.coerceAtLeast(1.0)
    val firstDay = sorted.first().first
    // Java DayOfWeek: Monday=1..Sunday=7. Row 0 is Monday, row 6 is Sunday.
    val padBefore = firstDay.dayOfWeek.value - 1
    val totalCells = padBefore + sorted.size
    val numColumns = (totalCells + 6) / 7

    val locale = Locale.getDefault()
    val dayLabels = (1..7).map { dow ->
        DayOfWeek.of(dow).getDisplayName(java.time.format.TextStyle.NARROW, locale)
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = EmptyHint, fontSize = 10.sp)
    val dayLayouts = dayLabels.map { measurer.measure(it, labelStyle) }
    val labelColW = dayLayouts.maxOf { it.size.width }.toFloat()

    val kindColors = ActivityKind.entries.associateWith { colorForKind(it) }
    val emptyCellColor = Color(0xFFEFEFEF)

    Canvas(modifier = modifier.height(160.dp).padding(8.dp)) {
        val gap = 3f
        val gridLeft = labelColW + 6f
        val gridWidth = (size.width - gridLeft).coerceAtLeast(1f)
        val cellW = ((gridWidth - (numColumns - 1) * gap) / numColumns).coerceAtLeast(1f)
        val cellH = ((size.height - 6 * gap) / 7f).coerceAtLeast(1f)

        // Day-of-week labels (vertically centered with each row)
        dayLayouts.forEachIndexed { row, layout ->
            val cellTop = row * (cellH + gap)
            val labelTop = cellTop + (cellH - layout.size.height) / 2f
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(labelColW - layout.size.width, labelTop),
            )
        }

        // Cells: iterate over every grid slot so empty days are visible too.
        val total = numColumns * 7
        for (idx in 0 until total) {
            val col = idx / 7
            val row = idx % 7
            val dayIdx = idx - padBefore
            if (dayIdx !in sorted.indices) continue
            val (_, perKind) = sorted[dayIdx]
            val sum = perKind.values.sum()
            val color = if (sum <= 0.0) {
                emptyCellColor
            } else {
                // Tint by the day's dominant kind (most metres), alpha by total
                // volume vs the range max. Blending N colours muddies into grey,
                // so the dominant-kind hue stays legible.
                val dominant = perKind.maxByOrNull { it.value }!!.key
                val intensity = (sum / maxMeters).toFloat().coerceIn(0.25f, 1f)
                kindColors.getValue(dominant).copy(alpha = intensity)
            }
            drawRect(
                color = color,
                topLeft = Offset(gridLeft + col * (cellW + gap), row * (cellH + gap)),
                size = Size(cellW, cellH),
            )
        }
    }
}

@Composable
private fun EmptyChart(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = EmptyHint)
    }
}

private fun mapX(x: Long, min: Long, max: Long, w: Float): Float {
    if (max <= min) return w / 2
    return ((x - min).toFloat() / (max - min).toFloat()) * w
}

private fun mapYFlipped(y: Double, min: Double, max: Double, h: Float): Float {
    if (max <= min) return h / 2
    val t = (y - min) / (max - min)
    // Flip: smaller y (faster pace) → top of canvas.
    return (t * h).toFloat()
}

private fun kindLabelRes(kind: ActivityKind): Int = when (kind) {
    ActivityKind.Walking -> R.string.exercise_kind_walking
    ActivityKind.Running -> R.string.exercise_kind_running
    ActivityKind.BriskWalking -> R.string.exercise_kind_brisk_walking
    ActivityKind.Cycling -> R.string.exercise_kind_cycling
}
