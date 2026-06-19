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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.Arm
import com.silverbp.android.core.Member
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Posture
import com.silverbp.android.core.Source
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.HeroCard
import com.silverbp.android.ui.components.HeroForeground
import com.silverbp.android.ui.components.HeroForegroundDim
import com.silverbp.android.ui.components.HeroLabel
import com.silverbp.android.ui.components.HeroPulsePill
import com.silverbp.android.ui.components.SegmentedControl
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.member.MemberPalette
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.BpRedSbp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmReadingScreen(
    readingIdArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: ConfirmReadingViewModel = viewModel(),
) {
    LaunchedEffect(readingIdArg) { vm.initWith(readingIdArg) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val crisisWarning by vm.crisisWarning.collectAsStateWithLifecycle()
    val activeMembers by vm.activeMembers.collectAsStateWithLifecycle()

    val isEditing = remember(readingIdArg) {
        readingIdArg != null && readingIdArg != "new" && readingIdArg != "draft" &&
            runCatching { UUID.fromString(readingIdArg) }.isSuccess
    }
    val titleText = stringResource(
        if (isEditing) R.string.confirm_reading_title_edit else R.string.confirm_reading_title_new,
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        enabled = draft.isValid && !saving,
                        onClick = { vm.save(onSaved) },
                    ) {
                        Text(
                            stringResource(R.string.save),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        }
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
            saveError?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // HERO — the captured S/D values as a vivid indigo-gradient card,
            // mirroring the Today screen's BpHeroCard so the verify step feels
            // continuous with the rest of the app.
            BpValueHero(
                systolic = draft.systolic,
                diastolic = draft.diastolic,
                pulse = draft.pulse,
            )

            draft.photo?.let { bmp ->
                StandardCard(
                    contentPadding = AppSpacing.itemGap,
                    cornerRadius = AppSpacing.cardCorner,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppSpacing.itemGap)),
                    )
                }
            }

            StandardCard(title = stringResource(R.string.confirm_reading_section_reading)) {
                NumberField(
                    label = stringResource(R.string.systolic_full),
                    value = draft.systolic,
                    emphasizedColor = BpRedSbp,
                    onChange = { v -> vm.update { it.copy(systolic = v) } },
                )
                HorizontalDivider()
                NumberField(
                    label = stringResource(R.string.diastolic_full),
                    value = draft.diastolic,
                    emphasizedColor = MaterialTheme.colorScheme.onSurface,
                    onChange = { v -> vm.update { it.copy(diastolic = v) } },
                )
                HorizontalDivider()
                NumberField(
                    label = stringResource(R.string.pulse) + " (bpm)",
                    value = draft.pulse ?: 0,
                    emphasizedColor = MaterialTheme.colorScheme.onSurface,
                    onChange = { v -> vm.update { it.copy(pulse = v.takeIf { x -> x > 0 }) } },
                )
                HorizontalDivider()
                TimestampRow(
                    timestamp = draft.timestamp,
                    onSetTimestamp = { newTs -> vm.update { it.copy(timestamp = newTs) } },
                )
                if (draft.source == Source.CameraGemma) {
                    HorizontalDivider()
                    ConfidenceRow(draft.confidence)
                }
            }

            // Attribution row — reassign THIS reading before saving (does not
            // change the global member selection). Hidden for single-member installs.
            if (activeMembers.size > 1) {
                StandardCard(title = stringResource(R.string.member_reading_owner_label)) {
                    MemberAttributionRow(
                        members = activeMembers,
                        selectedId = draft.memberId,
                        onSelect = { id -> vm.update { it.copy(memberId = id) } },
                    )
                }
            }

            StandardCard(
                title = stringResource(R.string.confirm_reading_section_tags),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
            ) {
                SegmentedRow(
                    title = stringResource(R.string.confirm_reading_tag_time_of_day),
                    options = listOf(
                        PartOfDay.Morning to stringResource(R.string.part_morning),
                        PartOfDay.Evening to stringResource(R.string.part_evening),
                    ),
                    selected = draft.partOfDay,
                    onSelect = { v -> vm.update { it.copy(partOfDay = v) } },
                )
                SegmentedRow(
                    title = stringResource(R.string.confirm_reading_tag_arm),
                    options = listOf(
                        Arm.Left to stringResource(R.string.arm_left),
                        Arm.Right to stringResource(R.string.arm_right),
                    ),
                    selected = draft.arm,
                    onSelect = { v -> vm.update { it.copy(arm = v) } },
                )
                SegmentedRow(
                    title = stringResource(R.string.confirm_reading_tag_posture),
                    options = listOf(
                        Posture.Sitting to stringResource(R.string.posture_sitting),
                        Posture.Supine to stringResource(R.string.posture_supine),
                        Posture.Standing to stringResource(R.string.posture_standing),
                    ),
                    selected = draft.posture,
                    onSelect = { v -> vm.update { it.copy(posture = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.before_medication),
                    checked = draft.beforeMedication,
                    onChange = { v -> vm.update { it.copy(beforeMedication = v) } },
                )
                SwitchRow(
                    label = stringResource(R.string.irregular_heartbeat),
                    checked = draft.irregularHeartbeat,
                    onChange = { v -> vm.update { it.copy(irregularHeartbeat = v) } },
                )
            }

            StandardCard(title = stringResource(R.string.confirm_reading_section_notes)) {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    placeholder = { Text(stringResource(R.string.confirm_reading_notes_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }

            if (!draft.isValid) {
                Text(
                    stringResource(R.string.confirm_reading_validation_error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Prominent primary save — the Expressive CTA that mirrors the
            // TopAppBar save action (same vm.save call), giving the senior user a
            // big, obvious confirmation target at the end of the form.
            ExpressivePrimaryButton(
                text = stringResource(R.string.save),
                onClick = { vm.save(onSaved) },
                enabled = draft.isValid && !saving,
                icon = Icons.Filled.Check,
                fillWidth = true,
            )

            Spacer(Modifier.size(AppSpacing.itemGap))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_reading_confirm_title)) },
            text = { Text(stringResource(R.string.delete_reading_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete(onSaved)
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Crisis-range on-save acknowledgement. The reading is already saved; this
    // is non-diagnostic safety guidance and acknowledging returns to the caller.
    if (crisisWarning) {
        AlertDialog(
            onDismissRequest = { vm.clearCrisisWarning(); onSaved() },
            title = { Text(stringResource(R.string.bp_crisis_warning_title)) },
            text = { Text(stringResource(R.string.bp_crisis_warning_body)) },
            confirmButton = {
                TextButton(onClick = { vm.clearCrisisWarning(); onSaved() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

/**
 * HERO — the current S/D (and pulse) draft values shown in the shared [HeroCard]
 * gradient surface, mirroring the Today screen's BP hero so the verify/edit step
 * reads as the same reading the user just captured. Pure display of the draft;
 * all editing happens in the NumberFields below.
 *
 * Empty values (0) render as an em-dash so the hero still parses while the user
 * is filling in a brand-new manual reading.
 */
@Composable
private fun BpValueHero(
    systolic: Int,
    diastolic: Int,
    pulse: Int?,
) {
    HeroCard {
        HeroLabel(text = stringResource(R.string.confirm_reading_section_reading))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${if (systolic == 0) "—" else systolic} / ${if (diastolic == 0) "—" else diastolic}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = HeroForeground,
            )
            Text(
                stringResource(R.string.mmhg),
                style = MaterialTheme.typography.bodyMedium,
                color = HeroForegroundDim,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        pulse?.takeIf { it > 0 }?.let {
            HeroPulsePill(text = "${stringResource(R.string.pulse)} $it")
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    emphasizedColor: Color,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = if (value == 0) "" else value.toString(),
            onValueChange = { v ->
                val cleaned = v.filter { it.isDigit() }.take(3)
                onChange(cleaned.toIntOrNull() ?: 0)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = emphasizedColor,
            ),
            modifier = Modifier.size(width = 96.dp, height = 56.dp),
        )
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
        val state = rememberDatePickerState(
            initialSelectedDateMillis = timestamp.toEpochMilli(),
        )
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
                        }) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceRow(confidence: Double) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.confidence),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        val color = when {
            confidence >= 0.85 -> MaterialTheme.colorScheme.primary
            confidence >= 0.60 -> Color(0xFFFF9500)
            else -> MaterialTheme.colorScheme.error
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (1..5).forEach { i ->
                val filled = confidence * 5 >= i.toDouble() - 0.5
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (filled) color else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "${(confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun <T> SegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(AppSpacing.itemGap))
        val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        SegmentedControl(
            options = options.map { it.second },
            selectedIndex = selectedIndex,
            onSelect = { idx -> onSelect(options[idx].first) },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
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
                                Modifier.border(
                                    width = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
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
