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
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.ui.components.categoryLabel
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
import com.silverbp.android.ui.components.formatGlucoseValue
import com.silverbp.android.ui.components.glucoseCategoryLabel
import com.silverbp.android.ui.components.glucoseColorFor
import com.silverbp.android.ui.components.glucoseUnitLabel
import com.silverbp.android.ui.components.measureContextLabel
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.BpRedSbp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Teal type-hue for the 血糖 (glucose) icon tile, mirroring the iOS timeline. */
private val GlucoseTileTint = Color(0xFF15B5B0)

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
    vm: UnifiedHistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var deleteBpTarget by remember { mutableStateOf<BpReading?>(null) }
    var deleteGlucoseTarget by remember { mutableStateOf<GlucoseReading?>(null) }
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
                    onEditBp = { reading -> onEditBp(reading.id.toString()) },
                    onLongPressBp = { reading -> deleteBpTarget = reading },
                    onEditGlucose = { reading -> onEditGlucose(reading.id.toString()) },
                    onLongPressGlucose = { reading -> deleteGlucoseTarget = reading },
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
    onEditBp: (BpReading) -> Unit,
    onLongPressBp: (BpReading) -> Unit,
    onEditGlucose: (GlucoseReading) -> Unit,
    onLongPressGlucose: (GlucoseReading) -> Unit,
) {
    // Day header: 今天 / 昨天 / formatted date · N 筆, mirroring the iOS timeline's
    // RecordDaySectionHeader. Relative-day resolution is presentation-only (compares
    // the group's date to today); the grouping/merge itself is untouched.
    val dateFmt = DateTimeFormatter.ofPattern(stringResource(R.string.history_date_format), Locale.getDefault())
    val today = remember { LocalDate.now() }
    val dateLabel = when (group.date) {
        today -> stringResource(R.string.range_today)
        today.minusDays(1) -> stringResource(R.string.combined_history_yesterday)
        else -> group.date.format(dateFmt)
    }
    val totalCount = group.bpReadings.size + group.glucoseReadings.size
    val unitLabel = glucoseUnitLabel(glucoseUnit)

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        DaySectionHeader(title = dateLabel, count = totalCount)

        // Blood-pressure rows (each its own surface card, matching iOS).
        group.bpReadings.forEach { reading ->
            BpReadingRow(
                reading = reading,
                guideline = guideline,
                onClick = { onEditBp(reading) },
                onLongClick = { onLongPressBp(reading) },
            )
        }

        // Blood-glucose rows.
        group.glucoseReadings.forEach { reading ->
            GlucoseReadingRow(
                reading = reading,
                unit = glucoseUnit,
                unitLabel = unitLabel,
                onClick = { onEditGlucose(reading) },
                onLongClick = { onLongPressGlucose(reading) },
            )
        }
    }
}

/** Day-group header: relative/absolute day title on the left, "N 筆" count on the right. */
@Composable
private fun DaySectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.tight, top = AppSpacing.tight, bottom = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.history_readings_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The shared visual chrome for one timeline record, styled as a surface card to
 * match the iOS MergedTimelineRow: a leading tinted type-icon tile, a small type
 * label, the big value + unit, a category dot + label, and a trailing time +
 * chevron. Callers supply the pre-classified colour/label and pre-formatted text;
 * this composable owns no data or behaviour beyond the click wiring it is handed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRecordRow(
    icon: ImageVector,
    tint: Color,
    typeLabel: String,
    valueText: String,
    unitText: String,
    categoryText: String,
    categoryColor: Color,
    contextText: String?,
    timeText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.cardCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 74.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = AppSpacing.cardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEADING: tinted type-icon tile.
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.size(AppSpacing.screenH))

            // MIDDLE: type label, value + unit, category dot + label.
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        valueText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (unitText.isNotEmpty()) {
                        Spacer(Modifier.size(AppSpacing.tight))
                        Text(
                            unitText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(categoryColor),
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Text(
                        categoryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (contextText != null) {
                        Spacer(Modifier.size(AppSpacing.itemGap))
                        Text(
                            contextText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.size(AppSpacing.itemGap))

            // TRAILING: time over a chevron.
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
                Text(
                    timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = stringResource(R.string.a11y_view_reading_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
    val pulseText = reading.pulse?.let { stringResource(R.string.history_reading_pulse, it) }
    TimelineRecordRow(
        icon = Icons.Filled.Favorite,
        tint = BpRedSbp,
        typeLabel = stringResource(R.string.combined_section_bp),
        valueText = "${reading.systolic} / ${reading.diastolic}",
        unitText = "",
        categoryText = categoryLabel(cat),
        categoryColor = color,
        contextText = pulseText,
        timeText = fmt.format(reading.timestamp),
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

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
    TimelineRecordRow(
        icon = Icons.Filled.WaterDrop,
        tint = GlucoseTileTint,
        typeLabel = stringResource(R.string.combined_section_glucose),
        valueText = formatGlucoseValue(reading.valueMgdl, unit),
        unitText = unitLabel,
        categoryText = glucoseCategoryLabel(cat),
        categoryColor = color,
        contextText = measureContextLabel(reading.measureContext),
        timeText = fmt.format(reading.timestamp),
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
