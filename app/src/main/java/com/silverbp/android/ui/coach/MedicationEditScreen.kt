package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.silverbp.android.R
import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.coach.MedicationReminderScheduler
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationKind
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private data class EditableSchedule(
    val rowKey: String = UUID.randomUUID().toString(),
    val existingId: String?,
    val daysOfWeekMask: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicationEditScreen(
    medicationId: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val medsDao = remember { ServiceLocator.database.medicationDao() }
    val scheduleDao = remember { ServiceLocator.database.medicationScheduleDao() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(MedicationKind.MEDICATION) }
    val schedules: SnapshotStateList<EditableSchedule> =
        remember { mutableStateListOf<EditableSchedule>() }
    var loaded by remember { mutableStateOf(medicationId == null) }

    LaunchedEffect(medicationId) {
        if (medicationId != null) {
            val med = medsDao.findById(medicationId)
            if (med != null) {
                name = med.name
                dose = med.dose
                kind = med.kind
            }
            val rows = scheduleDao.forMedication(medicationId)
            schedules.clear()
            schedules.addAll(
                rows.map {
                    EditableSchedule(
                        existingId = it.id,
                        daysOfWeekMask = it.daysOfWeekMask,
                        hour = it.hour,
                        minute = it.minute,
                        enabled = it.enabled,
                    )
                }
            )
            loaded = true
        }
    }

    val canSave = name.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (medicationId == null) {
                            stringResource(R.string.medication_edit_title_new)
                        } else {
                            stringResource(R.string.medication_edit_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave && loaded,
                        onClick = {
                            scope.launch {
                                val medId = medicationId ?: UUID.randomUUID().toString()
                                medsDao.upsert(
                                    MedicationEntity(
                                        id = medId,
                                        name = name.trim(),
                                        dose = dose.trim(),
                                        kind = kind,
                                    )
                                )
                                // Replace-all keeps the editor logic simple
                                // and the worker cancellation deterministic:
                                // rescheduleForMedication cancels stale ids.
                                scheduleDao.deleteForMedication(medId)
                                scheduleDao.upsertAll(
                                    schedules.map {
                                        MedicationScheduleEntity(
                                            id = it.existingId ?: UUID.randomUUID().toString(),
                                            medicationId = medId,
                                            daysOfWeekMask = it.daysOfWeekMask,
                                            hour = it.hour,
                                            minute = it.minute,
                                            enabled = it.enabled,
                                        )
                                    }
                                )
                                MedicationReminderScheduler.rescheduleForMedication(context, medId)
                                onSaved()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.medication_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.medication_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Column {
                Text(
                    stringResource(R.string.medication_kind),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(4.dp))
                val kinds = listOf(
                    MedicationKind.MEDICATION to R.string.medication_kind_medication,
                    MedicationKind.SUPPLEMENT to R.string.medication_kind_supplement,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { idx, (value, labelRes) ->
                        SegmentedButton(
                            selected = value == kind,
                            onClick = { kind = value },
                            shape = SegmentedButtonDefaults.itemShape(idx, kinds.size),
                        ) { Text(stringResource(labelRes)) }
                    }
                }
            }

            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it },
                label = { Text(stringResource(R.string.medication_dose_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.medication_schedules),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (schedules.isEmpty()) {
                Text(
                    stringResource(R.string.medication_no_schedules),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            schedules.forEachIndexed { index, row ->
                ScheduleEditCard(
                    row = row,
                    onChange = { updated -> schedules[index] = updated },
                    onRemove = { schedules.removeAt(index) },
                )
            }

            OutlinedButton(
                onClick = {
                    schedules.add(
                        EditableSchedule(
                            existingId = null,
                            daysOfWeekMask = DayOfWeekMask.ALL,
                            hour = 8,
                            minute = 0,
                            enabled = true,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.medication_add_schedule))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditCard(
    row: EditableSchedule,
    onChange: (EditableSchedule) -> Unit,
    onRemove: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { dow ->
                    val selected = DayOfWeekMask.contains(row.daysOfWeekMask, dow)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onChange(
                                row.copy(daysOfWeekMask = DayOfWeekMask.toggle(row.daysOfWeekMask, dow))
                            )
                        },
                        label = {
                            Text(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showTimePicker = true }) {
                    Text(formatTime(row.hour, row.minute))
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.medication_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(8.dp))
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { onChange(row.copy(enabled = it)) },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.medication_remove_schedule),
                    )
                }
            }
            if (DayOfWeekMask.isEmpty(row.daysOfWeekMask)) {
                Text(
                    stringResource(R.string.medication_summary_no_days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = row.hour,
            initialMinute = row.minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = state)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            onChange(row.copy(hour = state.hour, minute = state.minute))
                            showTimePicker = false
                        }) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
}

