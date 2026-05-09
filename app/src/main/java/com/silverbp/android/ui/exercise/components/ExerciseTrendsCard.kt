package com.silverbp.android.ui.exercise.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.ui.exercise.ExerciseHomeUiState
import com.silverbp.android.ui.exercise.ExerciseRange
import com.silverbp.android.ui.exercise.charts.ContributionCalendar
import com.silverbp.android.ui.exercise.charts.DailyDistanceStackedBarChart
import com.silverbp.android.ui.exercise.charts.KindDistributionDonut
import com.silverbp.android.ui.exercise.charts.PaceLineChart
import com.silverbp.android.ui.exercise.colorForKind

@Composable
fun ExerciseTrendsCard(
    state: ExerciseHomeUiState,
    onSelectRange: (ExerciseRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.exercise_trends_title),
                style = MaterialTheme.typography.titleMedium,
            )

            RangeChips(state.range, onSelectRange)

            StatsRow(state)

            ChartSubCard(stringResource(R.string.exercise_trends_distance)) {
                DailyDistanceStackedBarChart(
                    daily = state.dailyDistanceByKind,
                    walkingColor = colorForKind(ActivityKind.Walking),
                    runningColor = colorForKind(ActivityKind.Running),
                    modifier = Modifier.fillMaxWidth(),
                    emptyLabel = stringResource(R.string.exercise_trends_empty),
                )
                KindLegend()
            }
            ChartSubCard(stringResource(R.string.exercise_trends_pace)) {
                PaceLineChart(
                    seriesByKind = state.paceSeriesByKind,
                    modifier = Modifier.fillMaxWidth(),
                    emptyLabel = stringResource(R.string.exercise_trends_empty),
                )
                KindLegend()
            }
            ChartSubCard(stringResource(R.string.exercise_trends_kind)) {
                KindDistributionDonut(
                    counts = state.kindCounts,
                    modifier = Modifier.fillMaxWidth(),
                    walkingLabel = stringResource(R.string.exercise_kind_walking),
                    runningLabel = stringResource(R.string.exercise_kind_running),
                    emptyLabel = stringResource(R.string.exercise_trends_empty),
                )
            }
            ChartSubCard(stringResource(R.string.exercise_trends_heatmap)) {
                ContributionCalendar(
                    daily = state.dailyDistanceByKind,
                    modifier = Modifier.fillMaxWidth(),
                    emptyLabel = stringResource(R.string.exercise_trends_empty),
                )
                KindLegend()
            }
        }
    }
}

@Composable
private fun RangeChips(current: ExerciseRange, onSelect: (ExerciseRange) -> Unit) {
    val pairs = listOf(
        ExerciseRange.Last7 to stringResource(R.string.exercise_range_7d),
        ExerciseRange.Last30 to stringResource(R.string.exercise_range_30d),
        ExerciseRange.Last90 to stringResource(R.string.exercise_range_90d),
        ExerciseRange.All to stringResource(R.string.exercise_range_all),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
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
private fun StatsRow(state: ExerciseHomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip(
            stringResource(R.string.exercise_total_distance),
            ExerciseMath.formatDistance(state.totalDistanceMeters),
            Modifier.weight(1f),
        )
        StatChip(
            stringResource(R.string.exercise_total_duration),
            ExerciseMath.formatDuration(state.totalDurationMillis),
            Modifier.weight(1f),
        )
        StatChip(
            stringResource(R.string.exercise_session_count),
            state.sessionCount.toString(),
            Modifier.weight(1f),
        )
        StatChip(
            stringResource(R.string.exercise_week_steps),
            state.weekStepCount.toString(),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun KindLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(
            color = colorForKind(ActivityKind.Walking),
            label = stringResource(R.string.exercise_kind_walking),
        )
        LegendDot(
            color = colorForKind(ActivityKind.Running),
            label = stringResource(R.string.exercise_kind_running),
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ChartSubCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

