package com.silverbp.android.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onEdit: (String) -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_history)) },
                actions = {
                    IconButton(onClick = {
                        vm.setSort(if (state.sort == SortOrder.Newest) SortOrder.Oldest else SortOrder.Newest)
                    }) {
                        Icon(Icons.Filled.SwapVert, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RangeChips(state.range, vm::setRange)
            Spacer(Modifier.size(8.dp))
            if (state.grouped.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_readings))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.grouped, key = { it.date.toString() }) { group ->
                        DayGroupCard(group, onEdit, onDelete = { vm.delete(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeChips(current: DateRange, onSelect: (DateRange) -> Unit) {
    val chips = listOf(
        DateRange.All to R.string.range_all,
        DateRange.Today to R.string.range_today,
        DateRange.ThisWeek to R.string.range_this_week,
        DateRange.ThisMonth to R.string.range_this_month,
        DateRange.Last30 to R.string.range_last_30,
        DateRange.Last90 to R.string.range_last_90,
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(chips, key = { it.first.name }) { (range, labelRes) ->
            FilterChip(
                selected = current == range,
                onClick = { onSelect(range) },
                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
private fun DayGroupCard(
    group: DayGroup,
    onEdit: (String) -> Unit,
    onDelete: (java.util.UUID) -> Unit,
) {
    val dateFmt = DateTimeFormatter.ofPattern("MM/dd EEE", Locale.TAIWAN)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.date.format(dateFmt), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            AssistChip(
                onClick = {},
                label = { Text("均 ${group.meanSystolic}/${group.meanDiastolic}", style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
        Spacer(Modifier.size(4.dp))
        group.readings.forEach { reading ->
            ReadingRow(reading, onEdit = { onEdit(reading.id.toString()) }, onDelete = { onDelete(reading.id) })
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun ReadingRow(reading: BpReading, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cat = classify(reading.systolic, reading.diastolic)
    val color = colorFor(cat)
    val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN).withZone(java.time.ZoneId.systemDefault())
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(fmt.format(reading.timestamp), modifier = Modifier.size(width = 56.dp, height = 24.dp), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(8.dp))
        Text(
            "${reading.systolic} / ${reading.diastolic}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        reading.pulse?.let {
            Spacer(Modifier.size(8.dp))
            Text("脈 $it", style = MaterialTheme.typography.bodySmall)
        }
    }
}
