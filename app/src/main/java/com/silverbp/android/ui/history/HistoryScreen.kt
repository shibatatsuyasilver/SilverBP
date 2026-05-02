package com.silverbp.android.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.BpReading
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.colorFor
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_history)) },
                actions = {
                    Box {
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(Icons.Filled.FilterList, contentDescription = null)
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
        }
    ) { padding ->
        if (state.grouped.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.no_readings))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                state.grouped.forEach { group ->
                    stickyHeader(key = "header-${group.date}") {
                        DaySectionHeader(group)
                    }
                    items(
                        count = group.readings.size,
                        key = { idx -> group.readings[idx].id.toString() },
                    ) { idx ->
                        val reading = group.readings[idx]
                        ReadingRow(
                            reading = reading,
                            onClick = { onEdit(reading.id.toString()) },
                        )
                        if (idx < group.readings.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySectionHeader(group: DayGroup) {
    val dateFmt = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.TAIWAN)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    group.date.format(dateFmt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "均 ${group.meanSystolic}/${group.meanDiastolic}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            "${group.readings.size} 筆",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ReadingRow(reading: BpReading, onClick: () -> Unit) {
    val cat = classify(reading.systolic, reading.diastolic)
    val color = colorFor(cat)
    val fmt = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
            .withZone(java.time.ZoneId.systemDefault())
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${reading.systolic} / ${reading.diastolic}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                reading.pulse?.let {
                    Text(
                        "脈 $it",
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
            contentDescription = null,
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
            text = { Text("日期", style = MaterialTheme.typography.labelSmall) },
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
            text = { Text("排序", style = MaterialTheme.typography.labelSmall) },
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
