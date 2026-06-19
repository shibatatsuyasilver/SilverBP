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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.ui.components.categoryLabel
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.MetricAccent
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(
    onEdit: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<BpReading?>(null) }
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
            Text(stringResource(R.string.no_readings))
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
                key = { idx -> "group-${state.grouped[idx].date}" },
            ) { groupIdx ->
                val group = state.grouped[groupIdx]
                DaySectionCard(
                    group = group,
                    guideline = state.guideline,
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_reading_confirm_title)) },
            text = {
                Text(
                    "${target.systolic} / ${target.diastolic} (${fmt.format(target.timestamp)})\n\n" +
                        stringResource(R.string.delete_reading_confirm_message),
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
 * Filter (date range + sort) trigger for the BP history list. Rendered in the
 * Coach hub's TopAppBar while the 紀錄 sub-tab is active; shares the same
 * [HistoryViewModel] instance as [HistoryScreen] so changes apply immediately.
 */
@Composable
fun HistoryFilterAction(vm: HistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showFilterMenu = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.a11y_filter_readings),
            )
        }
        FilterMenu(
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
private fun DaySectionCard(
    group: DayGroup,
    guideline: HypertensionGuideline,
    onEdit: (BpReading) -> Unit,
    onLongPress: (BpReading) -> Unit,
) {
    // Day header: relative/absolute day title + count, then one surface card per
    // reading — mirroring the unified history timeline idiom.
    val dateFmt = DateTimeFormatter.ofPattern(stringResource(R.string.history_date_format), Locale.getDefault())
    val today = remember { LocalDate.now() }
    val dateLabel = when (group.date) {
        today -> stringResource(R.string.range_today)
        else -> group.date.format(dateFmt)
    }

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        DaySectionHeader(
            title = dateLabel,
            count = group.readings.size,
            meanText = stringResource(R.string.history_day_mean, group.meanSystolic, group.meanDiastolic),
        )
        group.readings.forEach { reading ->
            ReadingRow(
                reading = reading,
                guideline = guideline,
                onClick = { onEdit(reading) },
                onLongClick = { onLongPress(reading) },
            )
        }
    }
}

/** Day-group header: day title on the left, mean + "N 筆" count on the right. */
@Composable
private fun DaySectionHeader(title: String, count: Int, meanText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.tight, top = AppSpacing.tight, bottom = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                meanText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.history_readings_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadingRow(
    reading: BpReading,
    guideline: HypertensionGuideline,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val cat = classify(reading.systolic, reading.diastolic, guideline)
    val color = colorFor(cat)
    val fmt = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
            .withZone(java.time.ZoneId.systemDefault())
    }
    val pulseText = reading.pulse?.let { stringResource(R.string.history_reading_pulse, it) }
    TimelineRecordRow(
        icon = Icons.Filled.Favorite,
        tint = MetricAccent.Bp,
        valueText = "${reading.systolic} / ${reading.diastolic}",
        categoryText = categoryLabel(cat),
        categoryColor = color,
        contextText = pulseText,
        timeText = fmt.format(reading.timestamp),
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * The shared visual chrome for one BP record, styled as a surface card to match the
 * unified history timeline: a leading [MetricAccent.Bp] heart tile, the big value,
 * a category dot + label, and a trailing time + chevron. The icon tile always uses
 * the BP accent (never the reading's category colour).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRecordRow(
    icon: ImageVector,
    tint: Color,
    valueText: String,
    categoryText: String,
    categoryColor: Color,
    contextText: String?,
    timeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppSpacing.touchTarget + 26.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEADING: the BP accent icon tile (never varies by category).
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.size(AppSpacing.itemGap + AppSpacing.tight))

            // MIDDLE: value, category dot + label.
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(categoryColor),
                    )
                    Spacer(Modifier.size(AppSpacing.tight + 2.dp))
                    Text(
                        categoryText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (contextText != null) {
                        Text(
                            " · $contextText",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.size(AppSpacing.itemGap))

            // TRAILING: time then chevron, inline at the row's end.
            Text(
                timeText,
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
}

@Composable
private fun FilterMenu(
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
                leadingIcon = {
                    RadioButton(
                        selected = currentRange == range,
                        onClick = null,
                    )
                },
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
                leadingIcon = {
                    RadioButton(
                        selected = currentSort == sort,
                        onClick = null,
                    )
                },
                onClick = {
                    onSort(sort)
                    onDismiss()
                },
            )
        }
    }
}
