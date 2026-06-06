package com.silverbp.android.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silverbp.android.R
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.ui.components.categoryLabel
import com.silverbp.android.ui.components.categoryShortLabel
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.components.smoothLinePath
import com.silverbp.android.ui.theme.CategoryCrisis
import com.silverbp.android.ui.theme.CategoryElevated
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.CategoryStage1
import com.silverbp.android.ui.theme.CategoryStage2
import com.silverbp.android.ui.theme.DbpLine
import com.silverbp.android.ui.theme.SbpLine
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun TimeSeriesChart(readings: List<BpReading>, modifier: Modifier = Modifier) {
    if (readings.size < 2) {
        EmptyChart(stringResource(R.string.chart_no_data_short), modifier.height(220.dp))
        return
    }
    // Average SBP/DBP per weekday (Mon=0 … Sun=6) over the already range-filtered
    // readings: the x-axis is a fixed Mon→Sun and the range chips change the
    // per-weekday averages.
    val zone = ZoneId.systemDefault()
    val sysSum = DoubleArray(7)
    val diaSum = DoubleArray(7)
    val counts = IntArray(7)
    readings.forEach { r ->
        val i = r.timestamp.atZone(zone).dayOfWeek.value - 1
        sysSum[i] += r.systolic
        diaSum[i] += r.diastolic
        counts[i]++
    }
    val present = (0..6).filter { counts[it] > 0 }
    val sysAvg = present.map { sysSum[it] / counts[it] }
    val diaAvg = present.map { diaSum[it] / counts[it] }
    val yMin = (diaAvg.min() - 10).coerceAtLeast(40.0).toInt()
    val yMax = (sysAvg.max() + 10).coerceAtMost(220.0).toInt()

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = gridColor, fontSize = 11.sp)
    val locale = Locale.getDefault()
    val dayLabels = (0..6).map {
        DayOfWeek.of(it + 1).getDisplayName(java.time.format.TextStyle.SHORT, locale)
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(8.dp),
        ) {
            val xPad = 10f
            val labelH = 18.dp.toPx()
            val plotH = (size.height - labelH).coerceAtLeast(1f)
            fun xOf(slot: Int) = xPad + slot * ((size.width - 2 * xPad) / 6f)
            fun yOf(v: Double) = mapY(v, yMin, yMax, plotH)

            // faint horizontal gridlines
            for (g in 0..3) {
                val gy = plotH * g / 3f
                drawLine(gridColor.copy(alpha = 0.15f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }

            // weekday labels (Mon → Sun)
            dayLabels.forEachIndexed { i, lbl ->
                val layout = measurer.measure(lbl, labelStyle)
                val lx = (xOf(i) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(lx, plotH + (labelH - layout.size.height) / 2f))
            }

            val sysPts = present.mapIndexed { k, slot -> Offset(xOf(slot), yOf(sysAvg[k])) }
            val diaPts = present.mapIndexed { k, slot -> Offset(xOf(slot), yOf(diaAvg[k])) }
            val stroke = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (present.size >= 2) {
                drawPath(smoothLinePath(sysPts), color = SbpLine, style = stroke)
                drawPath(smoothLinePath(diaPts), color = DbpLine, style = stroke)
            } else {
                sysPts.forEach { drawCircle(SbpLine, 7f, it) }
                diaPts.forEach { drawCircle(DbpLine, 7f, it) }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LegendDot(SbpLine, stringResource(R.string.chart_legend_systolic))
            LegendDot(DbpLine, stringResource(R.string.chart_legend_diastolic))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScatterChart(
    readings: List<BpReading>,
    guideline: HypertensionGuideline,
    modifier: Modifier = Modifier,
) {
    if (readings.size < 5) {
        EmptyChart(stringResource(R.string.chart_need_5), modifier.height(220.dp))
        return
    }
    // Axes follow the actual data (padded) so hypertensive-crisis readings
    // (systolic > 190, common for elderly users) don't fall off-canvas.
    val xs = readings.map { it.diastolic }
    val ys = readings.map { it.systolic }
    // Widen the diastolic axis so the 60 / 80 / 100 ticks always show, while
    // still expanding past them for outliers.
    val xMin = minOf(xs.min() - 5, 55).coerceAtLeast(30)
    val xMax = maxOf(xs.max() + 5, 105).coerceAtMost(170)
    val yMin = (ys.min() - 10).coerceAtLeast(40)
    val yMax = (ys.max() + 10).coerceAtMost(270)
    // Per-reading category colour, precomputed in composable scope (colorFor is a
    // @Composable and can't run inside the Canvas DrawScope).
    val pointColors = readings.map { colorFor(classify(it.systolic, it.diastolic, guideline)) }

    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = axisColor, fontSize = 10.sp)
    val sysTitle = "${stringResource(R.string.chart_legend_systolic)} ${stringResource(R.string.mmhg)}"
    val diaTitle = "${stringResource(R.string.chart_legend_diastolic)} ${stringResource(R.string.mmhg)}"

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BpCategory.entries.forEach { cat -> LegendDot(colorFor(cat), categoryLabel(cat)) }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            val gap = 4f
            val diaLay = measurer.measure(diaTitle, tickStyle)
            val tickH = measurer.measure("100", tickStyle).size.height.toFloat()
            val plotL = 4f
            val plotR = (size.width - 40f).coerceAtLeast(plotL + 1f)
            val plotT = 22f
            // Reserve a row for the x-tick values + a separate row below for the
            // 舒張壓 title so they never overlap (robust to large font scales).
            val plotB = (size.height - (tickH + gap + diaLay.size.height + gap)).coerceAtLeast(plotT + 1f)
            fun px(d: Int) = plotL + (d - xMin).toFloat() / (xMax - xMin) * (plotR - plotL)
            fun py(s: Int) = plotB - (s - yMin).toFloat() / (yMax - yMin) * (plotB - plotT)

            // systolic gridlines + ticks (solid horizontal, every 25)
            var ty = ((yMin + 24) / 25) * 25
            while (ty <= yMax) {
                val yy = py(ty)
                drawLine(axisColor.copy(alpha = 0.18f), Offset(plotL, yy), Offset(plotR, yy), strokeWidth = 1f)
                val lay = measurer.measure(ty.toString(), tickStyle)
                drawText(lay, topLeft = Offset(plotR + 6f, yy - lay.size.height / 2f))
                ty += 25
            }
            // diastolic gridlines + ticks (dashed vertical, every 20)
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            var tx = ((xMin + 19) / 20) * 20
            while (tx <= xMax) {
                val xx = px(tx)
                drawLine(axisColor.copy(alpha = 0.18f), Offset(xx, plotT), Offset(xx, plotB), strokeWidth = 1f, pathEffect = dash)
                val lay = measurer.measure(tx.toString(), tickStyle)
                drawText(lay, topLeft = Offset(xx - lay.size.width / 2f, plotB + gap))
                tx += 20
            }
            // axis titles
            val sysLay = measurer.measure(sysTitle, tickStyle)
            drawText(sysLay, topLeft = Offset((size.width - sysLay.size.width).coerceAtLeast(0f), 2f))
            drawText(diaLay, topLeft = Offset(0f, plotB + gap + tickH + gap))
            // category-coloured points
            readings.forEachIndexed { i, r ->
                drawCircle(pointColors[i].copy(alpha = 0.9f), radius = 7f, center = Offset(px(r.diastolic), py(r.systolic)))
            }
        }
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
        Canvas(modifier = Modifier.size(120.dp).padding(8.dp)) {
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
        Spacer(Modifier.size(20.dp))
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

/**
 * Morning/Evening × BP-category heatmap. Two rows (早晨 / 傍晚) over the six BP
 * categories; each cell is tinted by [colorFor] and saturates with its reading
 * count. [counts] is precomputed by InsightsViewModel with the user's guideline.
 */
@Composable
fun DaypartCategoryHeatmap(
    counts: Map<Pair<PartOfDay, BpCategory>, Int>,
    modifier: Modifier = Modifier,
) {
    val categories = BpCategory.entries
    val rows = listOf(PartOfDay.Morning, PartOfDay.Evening)
    val maxCount = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val gap = 6.dp
    val rowLabelWidth = 44.dp
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        // Column headers: a blank slot above the row labels, then category names.
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Spacer(Modifier.width(rowLabelWidth))
            categories.forEach { cat ->
                Text(
                    categoryShortLabel(cat),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        rows.forEach { part ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (part == PartOfDay.Morning) R.string.part_morning else R.string.part_evening,
                    ),
                    modifier = Modifier.width(rowLabelWidth),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                categories.forEach { cat ->
                    val n = counts[part to cat] ?: 0
                    val alpha = if (n == 0) 0.10f
                        else (0.30f + 0.55f * (n.toFloat() / maxCount)).coerceIn(0.30f, 0.90f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorFor(cat).copy(alpha = alpha)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (n > 0) {
                            Text(
                                n.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
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
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun mapY(y: Double, min: Int, max: Int, h: Float): Float {
    if (max <= min) return h / 2
    val t = (y - min) / (max - min).toDouble()
    return h - (t * h).toFloat()
}
