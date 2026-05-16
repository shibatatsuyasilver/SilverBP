package com.silverbp.android.ui.insights.charts

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.ui.components.categoryLabel
import com.silverbp.android.ui.theme.CategoryCrisis
import com.silverbp.android.ui.theme.CategoryElevated
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.CategoryStage1
import com.silverbp.android.ui.theme.CategoryStage2
import com.silverbp.android.ui.theme.DbpLine
import com.silverbp.android.ui.theme.NormalZone
import com.silverbp.android.ui.theme.SbpLine
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val EmptyHint = androidx.compose.ui.graphics.Color(0xFF8E8E93)

@Composable
fun TimeSeriesChart(readings: List<BpReading>, modifier: Modifier = Modifier) {
    if (readings.size < 2) {
        EmptyChart(stringResource(R.string.chart_no_data_short), modifier.height(220.dp))
        return
    }
    val sorted = readings.sortedBy { it.timestamp }
    val minTime = sorted.first().timestamp.toEpochMilli()
    val maxTime = sorted.last().timestamp.toEpochMilli()
    val ySys = sorted.map { it.systolic }
    val yDia = sorted.map { it.diastolic }
    val yMin = (yDia.min() - 10).coerceAtLeast(40)
    val yMax = (ySys.max() + 10).coerceAtMost(220)
    Canvas(modifier = modifier.height(220.dp).padding(8.dp)) {
        // Normal zone band 60–130
        val zoneTop = mapY(130.0, yMin, yMax, size.height)
        val zoneBottom = mapY(60.0, yMin, yMax, size.height)
        drawRect(
            color = NormalZone.copy(alpha = 0.10f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, zoneTop),
            size = androidx.compose.ui.geometry.Size(size.width, zoneBottom - zoneTop),
        )

        fun pathFor(values: List<Int>): Path {
            val p = Path()
            sorted.forEachIndexed { i, r ->
                val x = mapX(r.timestamp.toEpochMilli(), minTime, maxTime, size.width)
                val y = mapY(values[i].toDouble(), yMin, yMax, size.height)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawPath(pathFor(ySys), color = SbpLine, style = Stroke(width = 4f))
        drawPath(pathFor(yDia), color = DbpLine, style = Stroke(width = 4f))

        sorted.forEachIndexed { i, r ->
            val x = mapX(r.timestamp.toEpochMilli(), minTime, maxTime, size.width)
            drawCircle(SbpLine, radius = 4f, center = androidx.compose.ui.geometry.Offset(x, mapY(r.systolic.toDouble(), yMin, yMax, size.height)))
            drawCircle(DbpLine, radius = 4f, center = androidx.compose.ui.geometry.Offset(x, mapY(r.diastolic.toDouble(), yMin, yMax, size.height)))
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot(SbpLine, stringResource(R.string.chart_legend_systolic))
        LegendDot(DbpLine, stringResource(R.string.chart_legend_diastolic))
    }
}

@Composable
fun ScatterChart(readings: List<BpReading>, modifier: Modifier = Modifier) {
    if (readings.size < 5) {
        EmptyChart(stringResource(R.string.chart_need_5), modifier.height(220.dp))
        return
    }
    val xMin = 50; val xMax = 110; val yMin = 80; val yMax = 190
    Canvas(modifier = modifier.height(220.dp).padding(8.dp)) {
        readings.forEach { r ->
            val color = if (r.partOfDay.raw == "morning") SbpLine else DbpLine
            val cx = mapX(r.diastolic.toLong(), xMin.toLong(), xMax.toLong(), size.width)
            val cy = mapY(r.systolic.toDouble(), yMin, yMax, size.height)
            drawCircle(color.copy(alpha = 0.7f), radius = 6f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        }
        val effect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(EmptyHint.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 1f, pathEffect = effect)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot(SbpLine, stringResource(R.string.chart_legend_morning))
        LegendDot(DbpLine, stringResource(R.string.chart_legend_evening))
    }
}

@Composable
fun DistributionDonut(distribution: Map<BpCategory, Int>, modifier: Modifier = Modifier) {
    val total = distribution.values.sum().toFloat()
    if (total == 0f) {
        EmptyChart(stringResource(R.string.chart_no_data), modifier.height(180.dp))
        return
    }
    val order: List<Pair<BpCategory, Color>> = listOf(
        BpCategory.Normal to CategoryNormal,
        BpCategory.Elevated to CategoryElevated,
        BpCategory.Stage1 to CategoryStage1,
        BpCategory.Stage2 to CategoryStage2,
        BpCategory.HypertensiveCrisis to CategoryCrisis,
        BpCategory.Hypotension to CategoryHypotension,
    )
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(160.dp).padding(8.dp)) {
            var startAngle = -90f
            order.forEach { (cat, color) ->
                val n = distribution[cat] ?: 0
                if (n == 0) return@forEach
                val sweep = 360f * n / total
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.minDimension, size.minDimension),
                    style = Stroke(width = size.minDimension * 0.22f),
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.size(12.dp))
        Column {
            order.forEach { (cat, color) ->
                val n = distribution[cat] ?: 0
                if (n == 0) return@forEach
                val pct = (n / total * 100f).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).padding(end = 0.dp)) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("${categoryLabel(cat)}  $n  ($pct%)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun HeatmapChart(readings: List<BpReading>, modifier: Modifier = Modifier) {
    if (readings.size < 7) {
        EmptyChart(stringResource(R.string.chart_need_7), modifier.height(180.dp))
        return
    }
    val zone = ZoneId.systemDefault()
    val cells = HashMap<Pair<Int, Int>, MutableList<Int>>(168)
    readings.forEach { r ->
        val zdt = r.timestamp.atZone(zone)
        val key = (zdt.dayOfWeek.value % 7) to zdt.hour
        cells.getOrPut(key) { mutableListOf() }.add(r.systolic)
    }
    val means = cells.mapValues { it.value.average() }
    val gMin = means.values.minOrNull() ?: 90.0
    val gMax = means.values.maxOrNull() ?: 160.0
    Canvas(modifier = modifier.height(180.dp).padding(8.dp)) {
        val cellW = size.width / 24f
        val cellH = size.height / 7f
        for (d in 0 until 7) for (h in 0 until 24) {
            val v = means[d to h] ?: continue
            val t = ((v - gMin) / (gMax - gMin).coerceAtLeast(0.001)).toFloat().coerceIn(0f, 1f)
            val color = lerp(CategoryNormal, CategoryStage2, t)
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(h * cellW + 1f, d * cellH + 1f),
                size = androidx.compose.ui.geometry.Size(cellW - 2f, cellH - 2f),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
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

private fun mapY(y: Double, min: Int, max: Int, h: Float): Float {
    if (max <= min) return h / 2
    val t = (y - min) / (max - min).toDouble()
    return h - (t * h).toFloat()
}
