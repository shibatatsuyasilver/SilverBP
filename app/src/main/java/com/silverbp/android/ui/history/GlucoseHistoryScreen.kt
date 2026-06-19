package com.silverbp.android.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.ui.components.ExpressiveAssistChip
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.formatGlucoseValue
import com.silverbp.android.ui.components.glucoseCategoryLabel
import com.silverbp.android.ui.components.glucoseColorFor
import com.silverbp.android.ui.components.glucoseUnitLabel
import com.silverbp.android.ui.components.measureContextLabel
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.MetricAccent
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Member-scoped glucose history, the BP [HistoryScreen] sibling: a date-grouped
 * list of readings (value + unit + timing + category colour) with tap-to-edit and
 * long-press delete (Snackbar undo). Shares one [GlucoseHistoryViewModel] with the
 * DataHub TopAppBar filter action so range/sort changes drive the same list.
 */
@Composable
fun GlucoseHistoryScreen(
    onEdit: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    vm: GlucoseHistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<GlucoseReading?>(null) }
    val scope = rememberCoroutineScope()
    val deletedMsg = stringResource(R.string.reading_deleted)
    val undoLabel = stringResource(R.string.delete_reading_undo)

    val contentModifier = modifier.fillMaxSize()
    when {
        state.isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error -> Box(contentModifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.error_load_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.grouped.isEmpty() -> Box(contentModifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.glucose_history_empty))
        }
        else -> LazyColumn(
            modifier = contentModifier,
            contentPadding = PaddingValues(
                horizontal = AppSpacing.screenH,
                vertical = AppSpacing.screenV,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            items(
                count = state.grouped.size,
                key = { idx -> "glucose-group-${state.grouped[idx].date}" },
            ) { groupIdx ->
                val group = state.grouped[groupIdx]
                GlucoseDaySectionCard(
                    group = group,
                    unit = state.unit,
                    onEdit = { reading -> onEdit(reading.id.toString()) },
                    onLongPress = { reading -> deleteTarget = reading },
                )
            }
        }
    }

    deleteTarget?.let { target ->
        val fmt = remember {
            DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
                .withZone(java.time.ZoneId.systemDefault())
        }
        val valueLabel = "${formatGlucoseValue(target.valueMgdl, state.unit)} ${glucoseUnitLabel(state.unit)}"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.glucose_delete_confirm_title)) },
            text = {
                Text(
                    "$valueLabel (${fmt.format(target.timestamp)})\n\n" +
                        stringResource(R.string.glucose_delete_confirm_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.id)
                    deleteTarget = null
                    scope.launch {
                        val res = snackbarHostState.showSnackbar(
                            message = deletedMsg,
                            actionLabel = undoLabel,
                        )
                        if (res == SnackbarResult.ActionPerformed) vm.restore(target)
                    }
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Filter (date range + sort) trigger for the glucose history list. Rendered in the
 * DataHub TopAppBar while the 紀錄 sub-tab is active on the 血糖 measurement type;
 * shares the same [GlucoseHistoryViewModel] instance as [GlucoseHistoryScreen].
 */
@Composable
fun GlucoseHistoryFilterAction(vm: GlucoseHistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showFilterMenu = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.a11y_filter_readings),
            )
        }
        // Same range/sort options as the BP history filter; a local copy because
        // the BP FilterMenu is file-private to HistoryScreen.kt.
        GlucoseFilterMenu(
            expanded = showFilterMenu,
            onDismiss = { showFilterMenu = false },
            currentRange = state.range,
            currentSort = state.sort,
            onRange = vm::setRange,
            onSort = vm::setSort,
        )
    }
}

@Composable
private fun GlucoseFilterMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentRange: DateRange,
    currentSort: SortOrder,
    onRange: (DateRange) -> Unit,
    onSort: (SortOrder) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.history_filter_date), style = MaterialTheme.typography.labelSmall) },
            onClick = {},
            enabled = false,
        )
        val ranges = listOf(
            DateRange.All to R.string.range_all,
            DateRange.Today to R.string.range_today,
            DateRange.ThisWeek to R.string.range_this_week,
            DateRange.ThisMonth to R.string.range_this_month,
            DateRange.Last30 to R.string.range_last_30,
            DateRange.Last90 to R.string.range_last_90,
        )
        ranges.forEach { (range, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                leadingIcon = { RadioButton(selected = currentRange == range, onClick = null) },
                onClick = {
                    onRange(range)
                    onDismiss()
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.history_filter_sort), style = MaterialTheme.typography.labelSmall) },
            onClick = {},
            enabled = false,
        )
        listOf(
            SortOrder.Newest to R.string.sort_newest,
            SortOrder.Oldest to R.string.sort_oldest,
        ).forEach { (sort, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                leadingIcon = { RadioButton(selected = currentSort == sort, onClick = null) },
                onClick = {
                    onSort(sort)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun GlucoseDaySectionCard(
    group: GlucoseDayGroup,
    unit: GlucoseUnit,
    onEdit: (GlucoseReading) -> Unit,
    onLongPress: (GlucoseReading) -> Unit,
) {
    val dateFmt = DateTimeFormatter.ofPattern(stringResource(R.string.history_date_format), Locale.getDefault())
    val unitLabel = glucoseUnitLabel(unit)
    StandardCard(
        title = group.date.format(dateFmt),
        titleTrailing = {
            ExpressiveAssistChip(
                label = stringResource(R.string.history_readings_count, group.readings.size),
                onClick = {},
            )
        },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(
            stringResource(
                R.string.glucose_value_unit,
                formatGlucoseValue(group.meanMgdl, unit),
                unitLabel,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        group.readings.forEachIndexed { idx, reading ->
            GlucoseRow(
                reading = reading,
                unit = unit,
                unitLabel = unitLabel,
                onClick = { onEdit(reading) },
                onLongClick = { onLongPress(reading) },
            )
            if (idx < group.readings.size - 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlucoseRow(
    reading: GlucoseReading,
    unit: GlucoseUnit,
    unitLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val classifier = remember { GlucoseClassifier() }
    val cat = classifier.classify(reading.valueMgdl, reading.measureContext)
    val categoryColor = glucoseColorFor(cat)
    val fmt = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
            .withZone(java.time.ZoneId.systemDefault())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.touchTarget + 26.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = AppSpacing.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LEADING: the metric's fixed accent icon tile (never varies by category) —
        // same MetricAccent.Glucose drop tile as the unified timeline row.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MetricAccent.Glucose.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.WaterDrop,
                contentDescription = null,
                tint = MetricAccent.Glucose,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(Modifier.size(AppSpacing.itemGap + AppSpacing.tight))

        // MIDDLE: value + unit, category dot + label, measure context.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatGlucoseValue(reading.valueMgdl, unit),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(AppSpacing.tight))
                Text(
                    unitLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(categoryColor),
                )
                Spacer(Modifier.size(AppSpacing.tight + 2.dp))
                Text(
                    glucoseCategoryLabel(cat),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    " · ${measureContextLabel(reading.measureContext)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.size(AppSpacing.itemGap))

        // TRAILING: time then chevron, inline at the row's end.
        Text(
            fmt.format(reading.timestamp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(AppSpacing.tight))
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = stringResource(R.string.a11y_view_reading_details),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
