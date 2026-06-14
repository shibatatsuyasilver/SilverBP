package com.silverbp.android.ui.confirm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.BmiCategory
import com.silverbp.android.core.Member
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.ui.components.SectionCard
import com.silverbp.android.ui.member.MemberPalette
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Editable confirm form for a weight reading — the weight analogue of
 * [ConfirmGlucoseScreen]. Value (with kg ↔ lb toggle honouring the unit the draft
 * was created in, defaulting to the user's preferred unit), date/time, member
 * attribution, note. Manual entry only this round (no scale OCR). On a successful
 * save, if the attributed member has a height set, the resulting BMI + Taiwan
 * category is shown before returning; weight is never premium-gated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmWeightScreen(
    readingIdArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: ConfirmWeightViewModel = viewModel(),
) {
    LaunchedEffect(readingIdArg) { vm.initWith(readingIdArg) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val activeMembers by vm.activeMembers.collectAsStateWithLifecycle()
    val savedBmi by vm.savedBmi.collectAsStateWithLifecycle()

    val isEditing = remember(readingIdArg) {
        readingIdArg != null && readingIdArg != "new" && readingIdArg != "draft" &&
            runCatching { UUID.fromString(readingIdArg) }.isSuccess
    }
    val titleText = stringResource(
        if (isEditing) R.string.weight_confirm_title_edit else R.string.weight_confirm_title,
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.weight_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        enabled = draft.isValid && !saving,
                        onClick = { vm.save(onSaved) },
                    ) {
                        Text(stringResource(R.string.weight_save), fontWeight = FontWeight.SemiBold)
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            saveError?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionCard(stringResource(R.string.weight_value_label)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ValueRow(
                        valueText = draft.valueText,
                        onChange = { v -> vm.update { it.copy(valueText = v) } },
                    )
                    HorizontalDivider()
                    UnitToggleRow(
                        unit = draft.displayUnit,
                        onSelect = { vm.setUnit(it) },
                    )
                    HorizontalDivider()
                    TimestampRow(
                        timestamp = draft.timestamp,
                        onSetTimestamp = { newTs -> vm.update { it.copy(timestamp = newTs) } },
                    )
                }
            }

            // Attribution row — reassign THIS reading before saving (does not change
            // the global member selection). Hidden for single-member installs.
            if (activeMembers.size > 1) {
                SectionCard(stringResource(R.string.member_reading_owner_label)) {
                    MemberAttributionRow(
                        members = activeMembers,
                        selectedId = draft.memberId,
                        onSelect = { id -> vm.update { it.copy(memberId = id) } },
                    )
                }
            }

            SectionCard(stringResource(R.string.weight_note)) {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    placeholder = { Text(stringResource(R.string.weight_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }

            // Medical disclaimer (same posture as the BP/glucose flows).
            Text(
                stringResource(R.string.weight_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(8.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.weight_delete_confirm_title)) },
            text = { Text(stringResource(R.string.weight_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete(onSaved)
                }) {
                    Text(stringResource(R.string.weight_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // BMI result on save — shown only when the attributed member has a height set
    // (otherwise the VM routes onSaved straight through). The reading is already
    // saved; acknowledging returns to the previous screen.
    savedBmi?.let { result ->
        val bmiText = String.format(Locale.US, "%.1f", result.bmi)
        AlertDialog(
            onDismissRequest = { vm.clearSavedBmi(); onSaved() },
            title = { Text(stringResource(R.string.bmi_value, bmiText)) },
            text = { Text(stringResource(bmiCategoryLabelRes(result.category))) },
            confirmButton = {
                TextButton(onClick = { vm.clearSavedBmi(); onSaved() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

@Composable
private fun ValueRow(
    valueText: String,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.weight_value_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = valueText,
            onValueChange = { v ->
                // Scales read to one decimal — keep digits + a single dot.
                val cleaned = v.filter { it.isDigit() || it == '.' }.take(6)
                onChange(cleaned)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.size(width = 110.dp, height = 56.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitToggleRow(unit: WeightUnit, onSelect: (WeightUnit) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.weight_unit_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        SingleChoiceSegmentedButtonRow {
            val options = listOf(
                WeightUnit.Kg to stringResource(R.string.weight_unit_kg),
                WeightUnit.Lb to stringResource(R.string.weight_unit_lb),
            )
            options.forEachIndexed { idx, (value, label) ->
                SegmentedButton(
                    selected = value == unit,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimestampRow(
    timestamp: Instant,
    onSetTimestamp: (Instant) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember {
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN).withZone(zone)
    }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.confirm_reading_timestamp_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = { showDate = true }) {
            Text(fmt.format(timestamp))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = timestamp.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis
                    if (ms != null) {
                        pendingDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                        showDate = false
                        showTime = true
                    } else {
                        showDate = false
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTime) {
        val current = remember { timestamp.atZone(zone).toLocalTime() }
        val state = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true,
        )
        androidx.compose.ui.window.Dialog(onDismissRequest = { showTime = false }) {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TimePicker(state = state)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTime = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            val date = pendingDate ?: timestamp.atZone(zone).toLocalDate()
                            val newTs = LocalDateTime.of(date, LocalTime.of(state.hour, state.minute))
                                .atZone(zone)
                                .toInstant()
                            onSetTimestamp(newTs)
                            showTime = false
                            pendingDate = null
                        }) { Text(stringResource(R.string.weight_save)) }
                    }
                }
            }
        }
    }
}

/** Horizontal row of selectable member avatars to attribute THIS reading. */
@Composable
private fun MemberAttributionRow(
    members: List<Member>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        members.forEach { member ->
            val id = member.id.toString()
            val selected = id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(id) }
                    .padding(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MemberPalette.colorFor(member.colorIndex))
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        member.displayName.ifBlank { stringResource(R.string.member_me) }.take(1).uppercase(),
                        color = MemberPalette.onColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    member.displayName.ifBlank { stringResource(R.string.member_me) },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun bmiCategoryLabelRes(c: BmiCategory): Int = when (c) {
    BmiCategory.Underweight -> R.string.bmi_underweight
    BmiCategory.Normal -> R.string.bmi_normal
    BmiCategory.Overweight -> R.string.bmi_overweight
    BmiCategory.Obese -> R.string.bmi_obese
}
