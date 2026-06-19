package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.silverbp.android.ui.components.ExpressiveFilterChip
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.SegmentedControl
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.exercise.colorForModule
import com.silverbp.android.ui.theme.AppSpacing
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
    val medicationRepo = remember { ServiceLocator.medicationRepository }
    val currentMemberStore = remember { ServiceLocator.currentMemberStore }
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
            val editData = medicationRepo.findForCurrentMember(medicationId)
            val med = editData?.medication
            if (med != null) {
                name = med.name
                dose = med.dose
                kind = med.kind
            }
            schedules.clear()
            schedules.addAll(
                editData?.schedules.orEmpty().map {
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

    // Shared save action — invoked by both the TopAppBar action and the
    // prominent Expressive CTA at the foot of the form (same persistence path).
    val onSave: () -> Unit = {
        scope.launch {
            val medId = medicationId ?: UUID.randomUUID().toString()
            val memberId = currentMemberStore.current()
            medicationRepo.saveForCurrentMember(
                MedicationEntity(
                    id = medId,
                    name = name.trim(),
                    dose = dose.trim(),
                    kind = kind,
                    memberId = memberId,
                ),
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
        Unit
    }

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
                        onClick = onSave,
                    ) {
                        Text(
                            stringResource(R.string.medication_save),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            // Basic details — name, kind, dose grouped in one field section.
            StandardCard(title = stringResource(R.string.medication_edit_title)) {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    val kinds = listOf(
                        MedicationKind.MEDICATION to R.string.medication_kind_medication,
                        MedicationKind.SUPPLEMENT to R.string.medication_kind_supplement,
                    )
                    val selectedKindIndex = kinds.indexOfFirst { it.first == kind }.coerceAtLeast(0)
                    SegmentedControl(
                        options = kinds.map { stringResource(it.second) },
                        selectedIndex = selectedKindIndex,
                        onSelect = { idx -> kind = kinds[idx].first },
                    )
                }

                OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it },
                    label = { Text(stringResource(R.string.medication_dose_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // Schedules — each reminder slot is its own field card.
            StandardCard(
                title = stringResource(R.string.medication_schedules),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
            ) {
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
                    Spacer(Modifier.size(AppSpacing.itemGap))
                    Text(stringResource(R.string.medication_add_schedule))
                }
            }

            // Prominent primary save — the Expressive CTA mirrors the TopAppBar
            // save action (same onSave path), giving the senior user a big,
            // obvious confirmation target at the end of the form.
            ExpressivePrimaryButton(
                text = stringResource(R.string.medication_save),
                onClick = onSave,
                enabled = canSave && loaded,
                icon = Icons.Filled.Check,
                fillWidth = true,
            )

            Spacer(Modifier.size(AppSpacing.itemGap))
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
    // Medication is a coach MODULE — its identity tint comes from the module
    // colour helper (NOT MetricAccent), surfaced as the card's leading stripe.
    StandardCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        accent = colorForModule(ModuleKey.Medication),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
            DayOfWeek.values().forEach { dow ->
                val selected = DayOfWeekMask.contains(row.daysOfWeekMask, dow)
                ExpressiveFilterChip(
                    label = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    selected = selected,
                    onClick = {
                        onChange(
                            row.copy(daysOfWeekMask = DayOfWeekMask.toggle(row.daysOfWeekMask, dow))
                        )
                    },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text(formatTime(row.hour, row.minute))
            }
            Spacer(Modifier.size(AppSpacing.itemGap))
            Text(
                stringResource(R.string.medication_enabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(AppSpacing.itemGap))
            Switch(
                checked = row.enabled,
                onCheckedChange = { onChange(row.copy(enabled = it)) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.medication_remove_schedule),
                    tint = MaterialTheme.colorScheme.error,
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
