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
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.capture.WeightCaptureSessionHolder
import com.silverbp.android.core.Member
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.SectionCard
import com.silverbp.android.ui.member.MemberPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Editable confirm form for a body-weight reading — the weight analogue of
 * [ConfirmGlucoseScreen] (manual entry only this phase; no camera/photo). Numeric
 * value with a kg ↔ lb toggle that honours the unit the value was entered in,
 * date/time, per-reading member attribution, and a note. Canonical storage is
 * always kilograms ([WeightReading.kgFrom] / [WeightReading.valueIn]); the initial
 * display unit comes from [com.silverbp.android.settings.UserSettings.weightUnit].
 *
 * Self-contained (no ViewModel file this phase): the editable draft is held in
 * Compose state and persisted directly through [ServiceLocator.weightRepository].
 * The Save button is guarded against double-taps; a failed save surfaces inline
 * and keeps the draft so the user can retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmWeightScreen(
    readingIdArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val repo = remember { ServiceLocator.weightRepository }
    val scope = rememberCoroutineScope()

    val activeMembers by ServiceLocator.memberRepository.observeActive()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val isEditing = remember(readingIdArg) {
        readingIdArg != null && readingIdArg != "new" && readingIdArg != "draft" &&
            runCatching { UUID.fromString(readingIdArg) }.isSuccess
    }

    var draft by remember { mutableStateOf(WeightDraftUi()) }
    var editingId by remember { mutableStateOf<UUID?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Initialise: edit an existing row, else a blank draft for the current member
    // with the user's preferred display unit. Guarded so recomposition doesn't
    // re-load over edits in flight.
    LaunchedEffect(readingIdArg) {
        if (loaded) return@LaunchedEffect
        loaded = true
        val preferredUnit = WeightUnit.fromRaw(ServiceLocator.userSettings.flow.first().weightUnit)
        // Camera/OCR hand-off: consume the staged scale-photo draft (value + unit
        // + photo) instead of opening blank. Falls back to a blank manual draft if
        // nothing was staged (e.g. recognition produced no value).
        if (readingIdArg == "draft") {
            val staged = WeightCaptureSessionHolder.take()
            draft = if (staged != null) {
                WeightDraftUi(
                    valueText = staged.valueText,
                    displayUnit = staged.displayUnit,
                    timestamp = staged.timestamp,
                    source = staged.source,
                    note = staged.note,
                    memberId = staged.memberId.ifBlank { ServiceLocator.currentMemberStore.current() },
                    photoFilename = staged.photoFilename,
                )
            } else {
                WeightDraftUi(
                    timestamp = Instant.now(),
                    displayUnit = preferredUnit,
                    memberId = ServiceLocator.currentMemberStore.current(),
                )
            }
            editingId = null
            return@LaunchedEffect
        }
        val id = readingIdArg?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val existing = id?.let { repo.findById(it) }
        if (existing != null) {
            draft = WeightDraftUi.fromReading(existing)
            editingId = existing.id
        } else {
            draft = WeightDraftUi(
                timestamp = Instant.now(),
                displayUnit = preferredUnit,
                memberId = ServiceLocator.currentMemberStore.current(),
            )
            editingId = null
        }
    }

    fun save() {
        if (saving) return
        saving = true
        saveError = null
        scope.launch {
            try {
                val base = draft.toReading()
                val reading = editingId?.let { base.copy(id = it, memberId = draft.memberId) } ?: base
                repo.upsert(reading)
                saving = false
                onSaved()
            } catch (e: Throwable) {
                saving = false
                saveError = e.message
            }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.weight_confirm_title)) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    TextButton(
                        enabled = draft.isValid && !saving,
                        onClick = { save() },
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
                        onChange = { v -> draft = draft.copy(valueText = v) },
                    )
                    HorizontalDivider()
                    UnitToggleRow(
                        unit = draft.displayUnit,
                        onSelect = { draft = draft.convertedTo(it) },
                    )
                    HorizontalDivider()
                    TimestampRow(
                        timestamp = draft.timestamp,
                        onSetTimestamp = { newTs -> draft = draft.copy(timestamp = newTs) },
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
                        onSelect = { id -> draft = draft.copy(memberId = id) },
                    )
                }
            }

            SectionCard(stringResource(R.string.weight_note_label)) {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { v -> draft = draft.copy(note = v) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }

            Spacer(Modifier.size(8.dp))
        }
    }
}

/**
 * Mutable in-memory working copy of a weight reading. The value is held as raw
 * text in the [displayUnit] the user is editing in (kg or lb) so partial input
 * ("72.", "") never snaps; the canonical kg is computed only via [toReading].
 */
private data class WeightDraftUi(
    val valueText: String = "",
    val displayUnit: WeightUnit = WeightUnit.Kg,
    val timestamp: Instant = Instant.now(),
    val source: WeightSource = WeightSource.Manual,
    val note: String = "",
    val memberId: String = "",
    /** Carried from a camera/OCR draft so the scale photo is saved with the reading. */
    val photoFilename: String? = null,
) {
    /** Parsed numeric value in [displayUnit], or null while empty/partial. */
    val parsedValue: Double?
        get() = valueText.replace(",", ".").toDoubleOrNull()

    /** Plausible human-body ranges per unit (kg ~2–500, lb ~4–1100). */
    val isValid: Boolean
        get() = parsedValue?.let { v ->
            when (displayUnit) {
                WeightUnit.Kg -> v in 2.0..500.0
                WeightUnit.Lb -> v in 4.0..1100.0
            }
        } ?: false

    /** Re-express the typed value in [target] so the kg ↔ lb toggle converts in place. */
    fun convertedTo(target: WeightUnit): WeightDraftUi {
        if (target == displayUnit) return this
        val v = parsedValue ?: return copy(displayUnit = target)
        val newValue = when (target) {
            WeightUnit.Kg -> WeightUnit.lbToKg(v)
            WeightUnit.Lb -> WeightUnit.kgToLb(v)
        }
        return copy(displayUnit = target, valueText = formatValue(newValue))
    }

    fun toReading() = WeightReading(
        memberId = memberId,
        valueKg = WeightReading.kgFrom(parsedValue ?: 0.0, displayUnit),
        displayUnit = displayUnit,
        timestamp = timestamp,
        source = source,
        note = note,
        photoFilename = photoFilename,
    )

    companion object {
        fun fromReading(r: WeightReading) = WeightDraftUi(
            valueText = formatValue(r.valueIn(r.displayUnit)),
            displayUnit = r.displayUnit,
            timestamp = r.timestamp,
            source = r.source,
            note = r.note,
            memberId = r.memberId,
            photoFilename = r.photoFilename,
        )

        /** One-decimal display for both units — body weight is meaningful to ~0.1. */
        fun formatValue(value: Double): String = String.format(Locale.US, "%.1f", value)
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
                // Keep digits plus a single decimal point.
                val cleaned = v.filter { it.isDigit() || it == '.' }.take(6)
                onChange(cleaned)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
