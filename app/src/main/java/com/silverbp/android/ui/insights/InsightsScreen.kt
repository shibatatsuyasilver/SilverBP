package com.silverbp.android.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.ui.insights.charts.DistributionDonut
import com.silverbp.android.ui.insights.charts.HeatmapChart
import com.silverbp.android.ui.insights.charts.ScatterChart
import com.silverbp.android.ui.insights.charts.TimeSeriesChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    onOpenReport: () -> Unit = {},
    vm: InsightsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_insights)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RangeChips(state.range, vm::setRange)
            StatsCards(state)
            ChartCard(stringResource(R.string.trend_title)) {
                TimeSeriesChart(readings = state.readings, modifier = Modifier.fillMaxWidth())
            }
            ChartCard(stringResource(R.string.scatter_title)) {
                ScatterChart(readings = state.readings, modifier = Modifier.fillMaxWidth())
            }
            ChartCard(stringResource(R.string.distribution_title)) {
                DistributionDonut(distribution = state.distribution, modifier = Modifier.fillMaxWidth())
            }
            ChartCard(stringResource(R.string.heatmap_title)) {
                HeatmapChart(readings = state.readings, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenReport,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Filled.Description, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.report_screen_title))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RangeChips(current: InsightsRange, onSelect: (InsightsRange) -> Unit) {
    val pairs = listOf(
        InsightsRange.Last7 to "7 天",
        InsightsRange.Last30 to "30 天",
        InsightsRange.Last90 to "90 天",
        InsightsRange.All to "全部",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip("平均", "${state.meanSystolic.toInt()}/${state.meanDiastolic.toInt()}", Modifier.weight(1f))
        StatChip("SD", "%.1f".format(state.sdSystolic), Modifier.weight(1f))
        StatChip("ARV", "%.1f".format(state.arvSystolic), Modifier.weight(1f))
    }
    if (state.morningSurge != null) {
        val surgeColor = if (state.morningSurge >= 35) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.morning_surge), style = MaterialTheme.typography.labelMedium)
                Text(
                    "%.1f mmHg".format(state.morningSurge),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = surgeColor,
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
