package com.silverbp.android.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.StatTile
import com.silverbp.android.ui.components.bmiCategoryLabel
import com.silverbp.android.ui.components.bmiColorFor
import com.silverbp.android.ui.components.formatBmi
import com.silverbp.android.ui.components.formatWeightValue
import com.silverbp.android.ui.components.smoothLinePath
import com.silverbp.android.ui.components.weightUnitLabel
import com.silverbp.android.ui.theme.AppSpacing

/**
 * Member-scoped weight insights, the [GlucoseInsightsScreen] sibling and the third
 * 分析 type. Range chips (7/30/90/All) drive three headline stat cards — 最新
 * (latest weight in range), 變化 (signed change vs the earliest in range), and BMI
 * (latest weight + the member's height; a "set height" hint when unset) — over a
 * 體重趨勢 time-series line chart (per the owner screenshot). Single-member only
 * this phase (see [WeightInsightsViewModel]).
 */
@Composable
fun WeightInsightsScreen(
    modifier: Modifier = Modifier,
    vm: WeightInsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.height(AppSpacing.itemGap))
        WeightRangeChips(state.range, vm::setRange)
        WeightStatsCards(state)

        ChartCard(stringResource(R.string.weight_trend_title)) {
            WeightTrendChart(
                readings = state.readings,
                unit = state.unit,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Medical disclaimer (mirrors the BP / glucose flows).
        Text(
            stringResource(R.string.weight_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WeightRangeChips(current: InsightsRange, onSelect: (InsightsRange) -> Unit) {
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
            FilterChip(
                selected = current == r,
                onClick = { onSelect(r) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun WeightStatsCards(state: WeightInsightsUiState) {
    val unitLabel = weightUnitLabel(state.unit)
    // Stitch "Insights Analytics" stat-pill row: 最新 / 變化 / BMI as three equal
    // StatTile pills (the shared polished tile) at equal heights.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        // 最新 — most recent weight in range.
        StatTile(
            label = stringResource(R.string.weight_latest_label),
            value = state.latestKg?.let {
                stringResource(R.string.weight_value_unit, formatWeightValue(it, state.unit), unitLabel)
            } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        // 變化 — signed change vs the earliest reading in range. The +/− is layout
        // (not copy); formatWeightValue already renders the magnitude in the unit.
        StatTile(
            label = stringResource(R.string.weight_change_label),
            value = state.changeKg?.let { changeKg ->
                val sign = if (changeKg > 0) "+" else if (changeKg < 0) "−" else ""
                val magnitude = formatWeightValue(kotlin.math.abs(changeKg), state.unit)
                stringResource(R.string.weight_value_unit, "$sign$magnitude", unitLabel)
            } ?: "—",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        // BMI — latest weight + the member's height; a "set height" hint when unset.
        StatTile(
            label = stringResource(R.string.bmi_label),
            value = state.bmi?.let { formatBmi(it) } ?: "—",
            sub = state.bmiCategory?.let { bmiCategoryLabel(it) }
                ?: stringResource(R.string.bmi_need_height),
            subColor = state.bmiCategory?.let { bmiColorFor(it) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
 * Weight trend: a smooth value-over-time line in the user's unit. Follows the
 * Canvas idiom of [GlucoseTrendChart] (gridlines, [smoothLinePath]) but weight-
 * specific so the shared BP charts stay untouched. Single accent colour — weight
 * has no per-point category like glucose; the BMI band lives in the stat card.
 */
@Composable
private fun WeightTrendChart(
    readings: List<WeightReading>,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    if (readings.size < 2) {
        EmptyWeightChart(stringResource(R.string.weight_insights_empty), modifier.height(220.dp))
        return
    }
    // Plot values in the user's preferred unit so the y-axis ticks read naturally.
    val values = readings.map { it.valueIn(unit) }
    val padding = if (unit == WeightUnit.Lb) 4.0 else 2.0
    val yMin = (values.min() - padding).coerceAtLeast(0.0)
    val yMax = values.max() + padding

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = gridColor, fontSize = 10.sp)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(8.dp),
    ) {
        val plotR = (size.width - 40f).coerceAtLeast(1f)
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
            val lay = measurer.measure("%.1f".format(v), tickStyle)
            drawText(lay, topLeft = Offset(plotR + 4f, gy - lay.size.height / 2f))
        }

        val pts = values.mapIndexed { i, v -> Offset(xOf(i), yOf(v)) }
        drawPath(
            smoothLinePath(pts),
            color = lineColor,
            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        pts.forEach { p ->
            drawCircle(lineColor.copy(alpha = 0.95f), radius = 6f, center = p)
        }
    }
}

@Composable
private fun EmptyWeightChart(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
