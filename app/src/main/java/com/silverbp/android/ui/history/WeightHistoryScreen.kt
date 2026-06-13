package com.silverbp.android.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Member-scoped weight history, the [GlucoseHistoryScreen] sibling adapted for
 * body weight: a date-grouped list of readings (value in the user's preferred
 * [WeightUnit] + time) with tap-to-edit and long-press delete (Snackbar undo).
 * Shares one [WeightHistoryViewModel] with the DataHub TopAppBar filter action so
 * range/sort changes drive the same list. Manual-entry phase — no camera/photo.
 */
@Composable
fun WeightHistoryScreen(
    onEdit: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    vm: WeightHistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<WeightReading?>(null) }
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
            Text(stringResource(R.string.weight_no_data))
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
                key = { idx -> "weight-group-${state.grouped[idx].date}" },
            ) { groupIdx ->
                val group = state.grouped[groupIdx]
                WeightDaySectionCard(
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
        val valueLabel = "${formatWeightValue(target.valueKg, state.unit)} ${weightUnitLabel(state.unit)}"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text("$valueLabel (${fmt.format(target.timestamp)})") },
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
 * Filter (date range + sort) trigger for the weight history list. Rendered in the
 * DataHub TopAppBar while the 紀錄 sub-tab is active on the 體重 measurement type;
 * shares the same [WeightHistoryViewModel] instance as [WeightHistoryScreen].
 */
@Composable
fun WeightHistoryFilterAction(vm: WeightHistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showFilterMenu = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.a11y_filter_readings),
            )
        }
        // Same range/sort options as the glucose history filter; a local copy
        // because GlucoseFilterMenu is file-private to GlucoseHistoryScreen.kt.
        WeightFilterMenu(
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
private fun WeightFilterMenu(
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
private fun WeightDaySectionCard(
    group: WeightDayGroup,
    unit: WeightUnit,
    onEdit: (WeightReading) -> Unit,
    onLongPress: (WeightReading) -> Unit,
) {
    val dateFmt = DateTimeFormatter.ofPattern(stringResource(R.string.history_date_format), Locale.getDefault())
    val unitLabel = weightUnitLabel(unit)
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
            "${formatWeightValue(group.meanKg, unit)} $unitLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        group.readings.forEachIndexed { idx, reading ->
            WeightRow(
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
private fun WeightRow(
    reading: WeightReading,
    unit: WeightUnit,
    unitLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
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
                "${formatWeightValue(reading.valueKg, unit)} $unitLabel",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                fmt.format(reading.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = stringResource(R.string.a11y_view_reading_details),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Localized unit label for a [WeightUnit] (mirrors [com.silverbp.android.ui.components.glucoseUnitLabel]). */
@Composable
private fun weightUnitLabel(unit: WeightUnit): String = stringResource(
    when (unit) {
        WeightUnit.Kg -> R.string.weight_unit_kg
        WeightUnit.Lb -> R.string.weight_unit_lb
    },
)

/**
 * Formats a canonical kg value for display in [unit] to one decimal place (the
 * scale convention for both kg and lb), the [com.silverbp.android.ui.components.formatGlucoseValue]
 * analogue. Reads back via [WeightReading.valueIn]'s conversion so display
 * matches the value the user saved.
 */
private fun formatWeightValue(valueKg: Double, unit: WeightUnit): String =
    "%.1f".format(
        when (unit) {
            WeightUnit.Kg -> valueKg
            WeightUnit.Lb -> WeightUnit.kgToLb(valueKg)
        },
    )
