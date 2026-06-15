package com.silverbp.android.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.insights.charts.DaypartCategoryHeatmap
import com.silverbp.android.ui.insights.charts.DistributionDonut
import com.silverbp.android.ui.insights.charts.MultiMemberDistribution
import com.silverbp.android.ui.insights.charts.MultiMemberHeatmap
import com.silverbp.android.ui.insights.charts.MultiMemberScatterChart
import com.silverbp.android.ui.insights.charts.MultiMemberTimeSeriesChart
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
    // Compare on with <2 members selected has nothing to overlay/compare — show
    // the hint instead of every chart (roadmap edge case).
    val compareReady = state.compareMode && state.series.size >= 2

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.height(AppSpacing.itemGap))
        CompareToggleRow(state.compareMode, vm::setCompareMode)
        if (state.compareMode) {
            CompareMemberChips(state.activeMembers, state.selectedMemberIds, vm::toggleMember)
        }
        RangeChips(state.range, vm::setRange)
        StatsCards(state)

        if (state.compareMode && !compareReady) {
            // Compare on but fewer than two selected — single hint replaces the charts.
            CompareHint()
        } else {
            ChartCard(stringResource(R.string.trend_title)) {
                if (compareReady) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                        CompareMetricToggle(state.compareMetric, vm::setCompareMetric)
                        MultiMemberTimeSeriesChart(
                            series = state.series,
                            metric = state.compareMetric,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    TimeSeriesChart(readings = state.readings, modifier = Modifier.fillMaxWidth())
                }
            }
            ChartCard(stringResource(R.string.scatter_title)) {
                if (compareReady) {
                    MultiMemberScatterChart(series = state.series, modifier = Modifier.fillMaxWidth())
                } else {
                    ScatterChart(readings = state.readings, guideline = state.guideline, modifier = Modifier.fillMaxWidth())
                }
            }
            ChartCard(stringResource(R.string.distribution_title)) {
                if (compareReady) {
                    MultiMemberDistribution(series = state.series, modifier = Modifier.fillMaxWidth())
                } else {
                    DistributionDonut(distribution = state.distribution, modifier = Modifier.fillMaxWidth())
                }
            }
            ChartCard(stringResource(R.string.heatmap_title)) {
                if (compareReady) {
                    MultiMemberHeatmap(series = state.series, modifier = Modifier.fillMaxWidth())
                } else {
                    DaypartCategoryHeatmap(counts = state.daypartCategory, modifier = Modifier.fillMaxWidth())
                }
            }
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
private fun CompareToggleRow(compareMode: Boolean, onChange: (Boolean) -> Unit) {
    // Whole row is one toggleable unit so TalkBack announces "Compare members,
    // switch, on/off" rather than an unlabeled control (audit M31 class).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = compareMode, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = AppSpacing.screenH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.compare_members_toggle),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = compareMode, onCheckedChange = null)
    }
}

@Composable
private fun CompareMemberChips(
    members: List<ActiveMember>,
    selected: Set<java.util.UUID>,
    onToggle: (java.util.UUID) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        members.forEach { member ->
            val name = member.name.ifBlank { stringResource(R.string.member_me) }
            FilterChip(
                selected = member.id in selected,
                onClick = { onToggle(member.id) },
                label = { Text(name, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { ColorDot(member.color) },
            )
        }
    }
}

@Composable
private fun CompareMetricToggle(metric: TrendMetric, onSelect: (TrendMetric) -> Unit) {
    val options = listOf(
        TrendMetric.Systolic to stringResource(R.string.compare_metric_systolic),
        TrendMetric.Diastolic to stringResource(R.string.compare_metric_diastolic),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, (value, label) ->
            SegmentedButton(
                selected = value == metric,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompareHint() {
    StandardCard(modifier = Modifier.padding(horizontal = AppSpacing.screenH)) {
        Text(
            stringResource(R.string.compare_select_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    if (state.compareMode) {
        // Compare mode: one compact row per member (colour dot + name + mean
        // SBP/DBP). The single mean/SD/ARV row and the morning-surge card are
        // single-member-only. Hidden entirely until ≥2 members are selected so
        // it tracks the chart area (the hint covers that state below).
        if (state.series.size >= 2) {
            StandardCard(
                modifier = Modifier.padding(horizontal = AppSpacing.screenH),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
            ) {
                state.series.forEach { member -> CompareStatRow(member) }
            }
        }
        return
    }
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
            Text(
                stringResource(R.string.morning_surge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "%.1f mmHg".format(state.morningSurge),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = surgeColor,
            )
        }
    }
}

@Composable
private fun CompareStatRow(member: MemberSeries) {
    val name = member.name.ifBlank { stringResource(R.string.member_me) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        ColorDot(member.color)
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            stringResource(
                R.string.compare_stats_mean,
                member.insights.meanSystolic.toInt(),
                member.insights.meanDiastolic.toInt(),
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Member identity swatch. Names always accompany the dot (never colour-only). */
@Composable
private fun ColorDot(color: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(color) }
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
