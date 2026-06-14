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
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.components.formatGlucoseValue
import com.silverbp.android.ui.components.formatWeightValue
import com.silverbp.android.ui.components.glucoseColorFor
import com.silverbp.android.ui.components.glucoseUnitLabel
import com.silverbp.android.ui.components.measureContextLabel
import com.silverbp.android.ui.components.weightUnitLabel
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Unified, member-scoped history (紀錄 segment of the Data hub). One card per
 * calendar day lists BOTH the blood-pressure and the blood-glucose entries logged
 * that day (owner decision §4), each row coloured by its own category classifier
 * and tappable to its type's confirm-edit screen. The visual idiom mirrors the
 * per-type [HistoryScreen]/[GlucoseHistoryScreen] (LazyColumn, date-header day
 * cards, divider-separated rows, long-press delete with Snackbar undo); the row
 * composables are kept local here so each type's edit/delete callbacks thread
 * straight through. Range/sort live in the shared [UnifiedHistoryViewModel] and
 * are driven from the DataHub TopAppBar via [UnifiedHistoryFilterAction].
 */
@Composable
fun UnifiedHistoryScreen(
    onEditBp: (String) -> Unit,
    onEditGlucose: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    // Default no-op so DataHub compiles unchanged until the confirm track wires the
    // weight confirm-edit route.
    onEditWeight: (String) -> Unit = {},
    vm: UnifiedHistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleteBpTarget by remember { mutableStateOf<BpReading?>(null) }
    var deleteGlucoseTarget by remember { mutableStateOf<GlucoseReading?>(null) }
    var deleteWeightTarget by remember { mutableStateOf<WeightReading?>(null) }
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
            Text(stringResource(R.string.combined_history_empty))
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
                key = { idx -> "combined-group-${state.grouped[idx].date}" },
            ) { groupIdx ->
                val group = state.grouped[groupIdx]
                CombinedDaySectionCard(
                    group = group,
                    guideline = state.guideline,
                    glucoseUnit = state.glucoseUnit,
                    weightUnit = state.weightUnit,
                    onEditBp = { reading -> onEditBp(reading.id.toString()) },
                    onLongPressBp = { reading -> deleteBpTarget = reading },
                    onEditGlucose = { reading -> onEditGlucose(reading.id.toString()) },
                    onLongPressGlucose = { reading -> deleteGlucoseTarget = reading },
                    onEditWeight = { reading -> onEditWeight(reading.id.toString()) },
                    onLongPressWeight = { reading -> deleteWeightTarget = reading },
                )
            }
        }
    }

    deleteBpTarget?.let { target ->
        val fmt = remember {
            DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
                .withZone(java.time.ZoneId.systemDefault())
        }
        AlertDialog(
            onDismissRequest = { deleteBpTarget = null },
            title = { Text(stringResource(R.string.delete_reading_confirm_title)) },
            text = {
                Text(
                    "${target.systolic} / ${target.diastolic} (${fmt.format(target.timestamp)})\n\n" +
                        stringResource(R.string.delete_reading_confirm_message),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBp(target.id)
                    deleteBpTarget = null
                    scope.launch {
                        val res = snackbarHostState.showSnackbar(
                            message = deletedMsg,
                            actionLabel = undoLabel,
                        )
                        if (res == SnackbarResult.ActionPerformed) vm.restoreBp(target)
                    }
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBpTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteGlucoseTarget?.let { target ->
        val fmt = remember {
            DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
                .withZone(java.time.ZoneId.systemDefault())
        }
        val valueLabel = "${formatGlucoseValue(target.valueMgdl, state.glucoseUnit)} ${glucoseUnitLabel(state.glucoseUnit)}"
        AlertDialog(
            onDismissRequest = { deleteGlucoseTarget = null },
            title = { Text(stringResource(R.string.glucose_delete_confirm_title)) },
            text = {
                Text(
                    "$valueLabel (${fmt.format(target.timestamp)})\n\n" +
                        stringResource(R.string.glucose_delete_confirm_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGlucose(target.id)
                    deleteGlucoseTarget = null
                    scope.launch {
                        val res = snackbarHostState.showSnackbar(
                            message = deletedMsg,
                            actionLabel = undoLabel,
                        )
                        if (res == SnackbarResult.ActionPerformed) vm.restoreGlucose(target)
                    }
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGlucoseTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleteWeightTarget?.let { target ->
        val fmt = remember {
            DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
                .withZone(java.time.ZoneId.systemDefault())
        }
        val valueLabel = "${formatWeightValue(target.weightKg, state.weightUnit)} ${weightUnitLabel(state.weightUnit)}"
        AlertDialog(
            onDismissRequest = { deleteWeightTarget = null },
            title = { Text(stringResource(R.string.weight_delete_confirm_title)) },
            text = {
                Text(
                    "$valueLabel (${fmt.format(target.timestamp)})\n\n" +
                        stringResource(R.string.weight_delete_confirm_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteWeight(target.id)
                    deleteWeightTarget = null
                    scope.launch {
                        val res = snackbarHostState.showSnackbar(
                            message = deletedMsg,
                            actionLabel = undoLabel,
                        )
                        if (res == SnackbarResult.ActionPerformed) vm.restoreWeight(target)
                    }
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteWeightTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Filter (date range + sort) trigger for the unified history list. Rendered in the
 * DataHub TopAppBar while the 紀錄 segment is active; shares the same
 * [UnifiedHistoryViewModel] instance as [UnifiedHistoryScreen] so changes apply to
 * both BP and glucose entries at once. A local DropdownMenu copy because the BP
 * FilterMenu is file-private to HistoryScreen.kt.
 */
@Composable
fun UnifiedHistoryFilterAction(vm: UnifiedHistoryViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showFilterMenu = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = stringResource(R.string.a11y_filter_readings),
            )
        }
        UnifiedFilterMenu(
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
private fun UnifiedFilterMenu(
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
private fun CombinedDaySectionCard(
    group: CombinedDayGroup,
    guideline: HypertensionGuideline,
    glucoseUnit: GlucoseUnit,
    weightUnit: WeightUnit,
    onEditBp: (BpReading) -> Unit,
    onLongPressBp: (BpReading) -> Unit,
    onEditGlucose: (GlucoseReading) -> Unit,
    onLongPressGlucose: (GlucoseReading) -> Unit,
    onEditWeight: (WeightReading) -> Unit,
    onLongPressWeight: (WeightReading) -> Unit,
) {
    val dateFmt = DateTimeFormatter.ofPattern(stringResource(R.string.history_date_format), Locale.getDefault())
    val dateLabel = group.date.format(dateFmt)
    StandardCard(
        title = dateLabel,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        // Blood-pressure section (only when the day has BP readings).
        if (group.bpReadings.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.combined_section_bp),
                summary = group.bpMean?.let { (sys, dia) ->
                    stringResource(R.string.combined_day_bp_summary, group.bpReadings.size, sys, dia)
                },
            )
            group.bpReadings.forEachIndexed { idx, reading ->
                BpReadingRow(
                    reading = reading,
                    guideline = guideline,
                    onClick = { onEditBp(reading) },
                    onLongClick = { onLongPressBp(reading) },
                )
                if (idx < group.bpReadings.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        // Divider between the two type sections when both are present.
        if (group.bpReadings.isNotEmpty() && group.glucoseReadings.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Blood-glucose section (only when the day has glucose readings).
        if (group.glucoseReadings.isNotEmpty()) {
            val unitLabel = glucoseUnitLabel(glucoseUnit)
            SectionHeader(
                title = stringResource(R.string.combined_section_glucose),
                summary = group.glucoseMean?.let { mean ->
                    stringResource(
                        R.string.combined_day_glucose_summary,
                        group.glucoseReadings.size,
                        formatGlucoseValue(mean, glucoseUnit),
                        unitLabel,
                    )
                },
            )
            group.glucoseReadings.forEachIndexed { idx, reading ->
                GlucoseReadingRow(
                    reading = reading,
                    unit = glucoseUnit,
                    unitLabel = unitLabel,
                    onClick = { onEditGlucose(reading) },
                    onLongClick = { onLongPressGlucose(reading) },
                )
                if (idx < group.glucoseReadings.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        // Divider before the weight section when an earlier section is present.
        if ((group.bpReadings.isNotEmpty() || group.glucoseReadings.isNotEmpty()) &&
            group.weightReadings.isNotEmpty()
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Body-weight section (only when the day has weight readings).
        if (group.weightReadings.isNotEmpty()) {
            val weightUnitLabel = weightUnitLabel(weightUnit)
            SectionHeader(
                title = stringResource(R.string.combined_section_weight),
                summary = group.weightMean?.let { mean ->
                    stringResource(
                        R.string.combined_day_weight_summary,
                        group.weightReadings.size,
                        formatWeightValue(mean, weightUnit),
                        weightUnitLabel,
                    )
                },
            )
            group.weightReadings.forEachIndexed { idx, reading ->
                WeightReadingRow(
                    reading = reading,
                    unit = weightUnit,
                    unitLabel = weightUnitLabel,
                    onClick = { onEditWeight(reading) },
                    onLongClick = { onLongPressWeight(reading) },
                )
                if (idx < group.weightReadings.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Per-type sub-header inside a combined day card: type name + one-line day summary. */
@Composable
private fun SectionHeader(title: String, summary: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BpReadingRow(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlucoseReadingRow(
    reading: GlucoseReading,
    unit: GlucoseUnit,
    unitLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val classifier = remember { GlucoseClassifier() }
    val cat = classifier.classify(reading.valueMgdl, reading.measureContext)
    val color = glucoseColorFor(cat)
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
                stringResource(
                    R.string.glucose_value_unit,
                    formatGlucoseValue(reading.valueMgdl, unit),
                    unitLabel,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    measureContextLabel(reading.measureContext),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
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

/**
 * Weight row of a combined day card. No category colour dot — weight's BMI band
 * needs the member's height (unavailable here per-row); the BMI surfaces on the
 * Today card and weight insights where the height is resolved. Long-press deletes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeightReadingRow(
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
                stringResource(
                    R.string.weight_value_unit,
                    formatWeightValue(reading.weightKg, unit),
                    unitLabel,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                fmt.format(reading.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = stringResource(R.string.a11y_view_reading_details),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
