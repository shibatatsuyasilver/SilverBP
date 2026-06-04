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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.silverbp.android.core.BpReading
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onEdit: (String) -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<BpReading?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMsg = stringResource(R.string.reading_deleted)
    val undoLabel = stringResource(R.string.delete_reading_undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_history)) },
                actions = {
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
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
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
                        onEdit = { reading -> onEdit(reading.id.toString()) },
                        onLongPress = { reading -> deleteTarget = reading },
                    )
                }
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

@Composable
private fun DaySectionCard(
    group: DayGroup,
    onEdit: (BpReading) -> Unit,
    onLongPress: (BpReading) -> Unit,
) {
    val dateFmt = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.TAIWAN)
    StandardCard(
        title = group.date.format(dateFmt),
        titleTrailing = {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        stringResource(R.string.history_readings_count, group.readings.size),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
        },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(
            stringResource(R.string.history_day_mean, group.meanSystolic, group.meanDiastolic),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        group.readings.forEachIndexed { idx, reading ->
            ReadingRow(
                reading = reading,
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
private fun ReadingRow(reading: BpReading, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val cat = classify(reading.systolic, reading.diastolic)
    val color = colorFor(cat)
    val fmt = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
            .withZone(java.time.ZoneId.systemDefault())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${reading.systolic} / ${reading.diastolic}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                reading.pulse?.let {
                    Text(
                        stringResource(R.string.history_reading_pulse, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    fmt.format(reading.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = stringResource(R.string.a11y_view_reading_details),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
