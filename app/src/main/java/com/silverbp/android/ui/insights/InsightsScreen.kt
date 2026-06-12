package com.silverbp.android.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.insights.charts.DaypartCategoryHeatmap
import com.silverbp.android.ui.insights.charts.DistributionDonut
import com.silverbp.android.ui.insights.charts.ScatterChart
import com.silverbp.android.ui.insights.charts.TimeSeriesChart
import com.silverbp.android.ui.theme.AppSpacing

@Composable
fun InsightsScreen(
    onOpenReport: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: InsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.height(AppSpacing.itemGap))
        RangeChips(state.range, vm::setRange)
        StatsCards(state)
        ChartCard(stringResource(R.string.trend_title)) {
            TimeSeriesChart(readings = state.readings, modifier = Modifier.fillMaxWidth())
        }
        ChartCard(stringResource(R.string.scatter_title)) {
            ScatterChart(readings = state.readings, guideline = state.guideline, modifier = Modifier.fillMaxWidth())
        }
        ChartCard(stringResource(R.string.distribution_title)) {
            DistributionDonut(distribution = state.distribution, modifier = Modifier.fillMaxWidth())
        }
        ChartCard(stringResource(R.string.heatmap_title)) {
            DaypartCategoryHeatmap(counts = state.daypartCategory, modifier = Modifier.fillMaxWidth())
        }

        OutlinedButton(
            onClick = onOpenReport,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenH),
        ) {
            Icon(Icons.Filled.Description, null)
            Spacer(Modifier.size(AppSpacing.itemGap))
            Text(stringResource(R.string.report_screen_title))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RangeChips(current: InsightsRange, onSelect: (InsightsRange) -> Unit) {
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
private fun StatsCards(state: InsightsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenH)
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        StatChip(stringResource(R.string.insights_stat_mean), "${state.meanSystolic.toInt()}/${state.meanDiastolic.toInt()}", Modifier.weight(1f).fillMaxHeight())
        StatChip(stringResource(R.string.sd), "%.1f".format(state.sdSystolic), Modifier.weight(1f).fillMaxHeight())
        StatChip(stringResource(R.string.arv), "%.1f".format(state.arvSystolic), Modifier.weight(1f).fillMaxHeight())
    }
    if (state.morningSurge != null) {
        val surgeColor = if (state.morningSurge >= 35) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        StandardCard(
            modifier = Modifier.padding(horizontal = AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
        ) {
            Text(stringResource(R.string.morning_surge), style = MaterialTheme.typography.labelMedium)
            Text(
                "%.1f mmHg".format(state.morningSurge),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = surgeColor,
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    StandardCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
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
