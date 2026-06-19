package com.silverbp.android.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.GlucoseCategory
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.ui.components.ExpressiveFilterChip
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.formatGlucoseValue
import com.silverbp.android.ui.components.glucoseCategoryLabel
import com.silverbp.android.ui.components.glucoseColorFor
import com.silverbp.android.ui.components.glucoseUnitLabel
import com.silverbp.android.ui.components.measureContextLabel
import com.silverbp.android.ui.components.smoothLinePath
import com.silverbp.android.ui.theme.AppSpacing

/**
 * Member-scoped glucose insights, the BP [InsightsScreen] sibling. Range chips
 * (7/30/90/All) drive a glucose trend chart (values over time, points coloured by
 * the reading's category) plus headline stats (fasting / after-meal means, low
 * events) and a timing-distribution chart. Single-member only this phase (see
 * [GlucoseInsightsViewModel]).
 */
@Composable
fun GlucoseInsightsScreen(
    modifier: Modifier = Modifier,
    vm: GlucoseInsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.height(AppSpacing.itemGap))
        GlucoseRangeChips(state.range, vm::setRange)
        GlucoseStatsCards(state)

        ChartCard(stringResource(R.string.glucose_trend_title)) {
            GlucoseTrendChart(
                readings = state.readings,
                unit = state.unit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ChartCard(stringResource(R.string.glucose_context_distribution_title)) {
            ContextDistributionBars(
                distribution = state.contextDistribution,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Medical disclaimer (mirrors the BP flow; glucose is not a medical device).
        Text(
            stringResource(R.string.glucose_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GlucoseRangeChips(current: InsightsRange, onSelect: (InsightsRange) -> Unit) {
    val pairs = listOf(
        InsightsRange.Last7 to stringResource(R.string.range_7d),
        InsightsRange.Last30 to stringResource(R.string.range_30d),
        InsightsRange.Last90 to stringResource(R.string.range_90d),
        InsightsRange.All to stringResource(R.string.range_all),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        pairs.forEach { (r, label) ->
            ExpressiveFilterChip(
                label = label,
                selected = current == r,
                onClick = { onSelect(r) },
            )
        }
    }
}

@Composable
private fun GlucoseStatsCards(state: GlucoseInsightsUiState) {
    val unitLabel = glucoseUnitLabel(state.unit)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        StatChip(
            label = stringResource(R.string.glucose_avg_fasting),
            value = state.fastingMeanMgdl?.let {
                stringResource(R.string.glucose_value_unit, formatGlucoseValue(it, state.unit), unitLabel)
            } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatChip(
            label = stringResource(R.string.glucose_avg_post_meal),
            value = state.afterMealMeanMgdl?.let {
                stringResource(R.string.glucose_value_unit, formatGlucoseValue(it, state.unit), unitLabel)
            } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatChip(
            label = stringResource(R.string.glucose_low_events),
            value = state.lowEventCount.toString(),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    StandardCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    StandardCard(
        modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        title = title,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        content()
    }
}

/**
 * Glucose trend: a smooth value-over-time line with each reading drawn as a
 * category-coloured point. The x-axis spans the range-filtered readings in time
 * order; the y-axis auto-scales to the data (padded). Follows the Canvas idiom of
 * the BP charts (gridlines, [smoothLinePath]) but glucose-specific so it leaves
 * the shared BP charts untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlucoseTrendChart(
    readings: List<GlucoseReading>,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier,
) {
    if (readings.size < 2) {
        EmptyGlucoseChart(stringResource(R.string.glucose_insights_empty), modifier.height(220.dp))
        return
    }
    val classifier = GlucoseClassifier()
    // Plot values in the user's preferred unit so the y-axis ticks read naturally.
    val values = readings.map { it.valueIn(unit) }
    val pointColors = readings.map { glucoseColorFor(classifier.classify(it.valueMgdl, it.measureContext)) }
    val padding = if (unit == GlucoseUnit.Mmol) 1.0 else 20.0
    val yMin = (values.min() - padding).coerceAtLeast(0.0)
    val yMax = values.max() + padding

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = gridColor, fontSize = 10.sp)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Legend: glucose categories present in the data (name + colour dot).
        val present = readings.map { classifier.classify(it.valueMgdl, it.measureContext) }.toSet()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GlucoseCategory.entries.filter { it in present }.forEach { cat ->
                LegendDot(glucoseColorFor(cat), glucoseCategoryLabel(cat))
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(8.dp),
        ) {
            val plotR = (size.width - 36f).coerceAtLeast(1f)
            val plotT = 8f
            val plotB = (size.height - 8f).coerceAtLeast(plotT + 1f)
            fun xOf(i: Int) = if (values.size <= 1) 0f else i.toFloat() / (values.size - 1) * plotR
            fun yOf(v: Double): Float {
                if (yMax <= yMin) return (plotT + plotB) / 2f
                val t = (v - yMin) / (yMax - yMin)
                return (plotB - t * (plotB - plotT)).toFloat()
            }

            // y-axis gridlines + ticks (4 bands)
            for (g in 0..3) {
                val v = yMin + (yMax - yMin) * (3 - g) / 3.0
                val gy = yOf(v)
                drawLine(gridColor.copy(alpha = 0.15f), Offset(0f, gy), Offset(plotR, gy), strokeWidth = 1f)
                val lay = measurer.measure(formatGlucoseValue(
                    if (unit == GlucoseUnit.Mmol) v * GlucoseUnit.MMOL_TO_MGDL else v, unit,
                ), tickStyle)
                drawText(lay, topLeft = Offset(plotR + 4f, gy - lay.size.height / 2f))
            }

            val pts = values.mapIndexed { i, v -> Offset(xOf(i), yOf(v)) }
            drawPath(
                smoothLinePath(pts),
                color = lineColor,
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            pts.forEachIndexed { i, p ->
                drawCircle(pointColors[i].copy(alpha = 0.95f), radius = 6f, center = p)
            }
        }
    }
}

/**
 * Timing distribution: one labelled horizontal bar per [MeasureContext] present,
 * width proportional to its share. Simpler than a donut and reads clearly on the
 * five contexts; counts always shown beside the label (never colour-only).
 */
@Composable
private fun ContextDistributionBars(
    distribution: Map<MeasureContext, Int>,
    modifier: Modifier = Modifier,
) {
    val total = distribution.values.sum()
    if (total == 0) {
        EmptyGlucoseChart(stringResource(R.string.glucose_insights_empty), modifier.height(120.dp))
        return
    }
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        MeasureContext.entries.forEach { context ->
            val n = distribution[context] ?: 0
            if (n == 0) return@forEach
            val fraction = n.toFloat() / total
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    measureContextLabel(context),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(72.dp),
                    maxLines = 1,
                )
                Box(modifier = Modifier.weight(1f).height(14.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = trackColor,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
                        )
                        drawRoundRect(
                            color = barColor,
                            size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "$n (${(fraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun EmptyGlucoseChart(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
