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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.core.GlucoseCategory
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.core.Member
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.HeroCard
import com.silverbp.android.ui.components.HeroForeground
import com.silverbp.android.ui.components.HeroForegroundDim
import com.silverbp.android.ui.components.HeroLabel
import com.silverbp.android.ui.components.HeroStatusPill
import com.silverbp.android.ui.components.SegmentedControl
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.member.MemberPalette
import com.silverbp.android.ui.paywall.GateReason
import com.silverbp.android.ui.paywall.LocalPaywallController
import com.silverbp.android.ui.theme.AppSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Editable confirm form for a glucose reading — the glucose analogue of
 * [ConfirmReadingScreen]. Value (with mg/dL ↔ mmol/L toggle honouring the unit
 * the draft was created in), measure-context picker, date/time, member
 * attribution, note. On a hypoglycaemic save it surfaces the low-glucose warning
 * dialog before returning; the free-10 gate opens the paywall via
 * [LocalPaywallController].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmGlucoseScreen(
    readingIdArg: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: ConfirmGlucoseViewModel = viewModel(),
) {
    LaunchedEffect(readingIdArg) { vm.initWith(readingIdArg) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()
    val activeMembers by vm.activeMembers.collectAsStateWithLifecycle()
    val liveCategory by vm.liveCategory.collectAsStateWithLifecycle()
    val lowWarning by vm.lowWarning.collectAsStateWithLifecycle()
    val needsUnitConfirmation by vm.needsUnitConfirmation.collectAsStateWithLifecycle()
    val gateRequested by vm.gateRequested.collectAsStateWithLifecycle()

    val paywall = LocalPaywallController.current
    // Free-10 gate fired → open the paywall (graceful preview, not a hard gate).
    LaunchedEffect(gateRequested) {
        if (gateRequested > 0) paywall.show(GateReason.GlucoseLimit)
    }

    val isEditing = remember(readingIdArg) {
        readingIdArg != null && readingIdArg != "new" && readingIdArg != "draft" &&
            runCatching { UUID.fromString(readingIdArg) }.isSuccess
    }
    val titleText = stringResource(
        if (isEditing) R.string.glucose_confirm_title_edit else R.string.glucose_confirm_title,
    )
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.glucose_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        enabled = draft.isValid && !saving && !needsUnitConfirmation,
                        onClick = { vm.save(onSaved) },
                    ) {
                        Text(stringResource(R.string.glucose_save), fontWeight = FontWeight.SemiBold)
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
            saveError?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // HERO — the captured glucose value + live category, surfaced as a
            // tinted-tile StandardCard mirroring the Today screen's glucose mini
            // card so the verify step stays visually continuous.
            GlucoseValueHero(
                valueText = draft.valueText,
                unit = draft.displayUnit,
                category = liveCategory,
            )

            // #16 — unit ambiguity gate: the meter didn't clearly show a unit (or the
            // read confidence was low), so the parser inferred/defaulted it. Make the
            // senior user explicitly confirm mg/dL vs mmol/L — a wrong unit is an ~18×
            // error — before Save re-enables.
            if (needsUnitConfirmation) {
                UnitConfirmWarning(
                    unit = draft.displayUnit,
                    onConfirm = { vm.confirmUnit() },
                )
            }

            StandardCard(title = stringResource(R.string.glucose_value_label)) {
                ValueRow(
                    valueText = draft.valueText,
                    category = liveCategory,
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

            StandardCard(title = stringResource(R.string.context_label)) {
                ContextPicker(
                    selected = draft.measureContext,
                    onSelect = { v -> vm.update { it.copy(measureContext = v) } },
                )
            }

            // Attribution row — reassign THIS reading before saving (does not change
            // the global member selection). Hidden for single-member installs.
            if (activeMembers.size > 1) {
                StandardCard(title = stringResource(R.string.member_reading_owner_label)) {
                    MemberAttributionRow(
                        members = activeMembers,
                        selectedId = draft.memberId,
                        onSelect = { id -> vm.update { it.copy(memberId = id) } },
                    )
                }
            }

            StandardCard(title = stringResource(R.string.glucose_note)) {
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    placeholder = { Text(stringResource(R.string.glucose_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
            }

            // Medical disclaimer (same posture as the BP flow — not a medical device).
            Text(
                stringResource(R.string.glucose_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Prominent primary save — the Expressive CTA that mirrors the
            // TopAppBar save action (same vm.save call), giving the senior user a
            // big, obvious confirmation target at the end of the form.
            ExpressivePrimaryButton(
                text = stringResource(R.string.glucose_save),
                onClick = { vm.save(onSaved) },
                enabled = draft.isValid && !saving && !needsUnitConfirmation,
                icon = Icons.Filled.Check,
                fillWidth = true,
            )

            Spacer(Modifier.size(AppSpacing.itemGap))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.glucose_delete_confirm_title)) },
            text = { Text(stringResource(R.string.glucose_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete(onSaved)
                }) {
                    Text(stringResource(R.string.glucose_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Low-glucose on-save warning — the acute case (roadmap §4-1). The reading is
    // already saved; acknowledging returns to the previous screen.
    lowWarning?.let {
        AlertDialog(
            onDismissRequest = { vm.clearLowWarning(); onSaved() },
            title = { Text(stringResource(R.string.glucose_low_warning_title)) },
            text = { Text(stringResource(R.string.glucose_low_warning_body)) },
            confirmButton = {
                TextButton(onClick = { vm.clearLowWarning(); onSaved() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

/**
 * HERO — the captured glucose value, its unit, and the live category, shown in the
 * shared [HeroCard] gradient surface, mirroring the Today screen's glucose hero so
 * the verify/edit step reads as the same reading the user just captured. Pure
 * display of the draft; editing happens in the ValueRow below.
 *
 * The live category is surfaced as a [HeroStatusPill] whose dot keeps the category
 * colour (status colour = dot/label only). Empty values render as an em-dash.
 */
@Composable
private fun GlucoseValueHero(
    valueText: String,
    unit: GlucoseUnit,
    category: GlucoseCategory?,
) {
    val unitLabel = stringResource(
        when (unit) {
            GlucoseUnit.Mgdl -> R.string.glucose_unit_mgdl
            GlucoseUnit.Mmol -> R.string.glucose_unit_mmol
        },
    )
    HeroCard {
        HeroLabel(
            text = stringResource(R.string.glucose_value_label),
            trailing = category?.let {
                {
                    HeroStatusPill(
                        text = stringResource(categoryLabelRes(it)),
                        dotColor = categoryColor(it),
                    )
                }
            },
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = valueText.ifBlank { "—" },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = HeroForeground,
            )
            Text(
                unitLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = HeroForegroundDim,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

/**
 * #16 — ambiguity warning shown when the captured unit was inferred/defaulted or the
 * read confidence was low. Spells out the unit currently assumed and offers an explicit
 * "this is correct" acknowledgement; switching the unit below also clears the gate.
 */
@Composable
private fun UnitConfirmWarning(unit: GlucoseUnit, onConfirm: () -> Unit) {
    val unitLabel = stringResource(
        when (unit) {
            GlucoseUnit.Mgdl -> R.string.glucose_unit_mgdl
            GlucoseUnit.Mmol -> R.string.glucose_unit_mmol
        },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.glucose_unit_confirm_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            stringResource(R.string.glucose_unit_confirm_body, unitLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.glucose_unit_confirm_action, unitLabel),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ValueRow(
    valueText: String,
    category: GlucoseCategory?,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.glucose_value_label), style = MaterialTheme.typography.bodyLarge)
            category?.let {
                Text(
                    stringResource(categoryLabelRes(it)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = categoryColor(it),
                )
            }
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = { v ->
                // mg/dL is integer; mmol/L allows one decimal — keep digits + a dot.
                val cleaned = v.filter { it.isDigit() || it == '.' }.take(6)
                onChange(cleaned)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = category?.let { categoryColor(it) } ?: MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.size(width = 110.dp, height = 56.dp),
        )
    }
}

@Composable
private fun UnitToggleRow(unit: GlucoseUnit, onSelect: (GlucoseUnit) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.glucose_unit_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        val options = listOf(
            GlucoseUnit.Mgdl to stringResource(R.string.glucose_unit_mgdl),
            GlucoseUnit.Mmol to stringResource(R.string.glucose_unit_mmol),
        )
        val selectedIndex = options.indexOfFirst { it.first == unit }.coerceAtLeast(0)
        SegmentedControl(
            options = options.map { it.second },
            selectedIndex = selectedIndex,
            onSelect = { idx -> onSelect(options[idx].first) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ContextPicker(selected: MeasureContext, onSelect: (MeasureContext) -> Unit) {
    val options = listOf(
        MeasureContext.Fasting to stringResource(R.string.context_fasting),
        MeasureContext.BeforeMeal to stringResource(R.string.context_before_meal),
        MeasureContext.AfterMeal to stringResource(R.string.context_after_meal),
        MeasureContext.Bedtime to stringResource(R.string.context_bedtime),
        MeasureContext.Random to stringResource(R.string.context_random),
    )
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    SegmentedControl(
        options = options.map { it.second },
        selectedIndex = selectedIndex,
        onSelect = { idx -> onSelect(options[idx].first) },
        modifier = Modifier.fillMaxWidth(),
    )
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
                        }) { Text(stringResource(R.string.glucose_save)) }
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

private fun categoryLabelRes(c: GlucoseCategory): Int = when (c) {
    GlucoseCategory.VeryLow -> R.string.category_verylow
    GlucoseCategory.Low -> R.string.category_low
    GlucoseCategory.Normal -> R.string.category_normal
    GlucoseCategory.Elevated -> R.string.category_elevated
    GlucoseCategory.High -> R.string.category_high
}

private fun categoryColor(c: GlucoseCategory): Color = when (c) {
    GlucoseCategory.VeryLow -> Color(0xFFD32F2F)
    GlucoseCategory.Low -> Color(0xFFF57C00)
    GlucoseCategory.Normal -> Color(0xFF2E7D32)
    GlucoseCategory.Elevated -> Color(0xFFF9A825)
    GlucoseCategory.High -> Color(0xFFD32F2F)
}
