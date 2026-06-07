package com.silverbp.android.ui.exercise.machine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing

/** Amber caution colour for the "machine estimate / unreliable" notes. */
private val EstimateCaution = Color(0xFFEF6C00)

/**
 * Editable confirm form for an OCR'd gym-machine workout. Pre-filled from the
 * staged readout; the user adjusts anything the model misread, then saves an
 * ExerciseSession (source = Ocr). Calories + heart rate carry visible
 * "estimate / may be blank" notes — never auto-trusted (mirrors how the food
 * flow flags sodium).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineConfirmScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    vm: MachineConfirmViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.init() }
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.machine_confirm_title), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                },
                actions = {
                    TextButton(onClick = { vm.save(onSaved) }) {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
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
            // Machine kind
            StandardCard(title = stringResource(R.string.machine_field_kind)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight)) {
                    ActivityKind.machineKinds.forEach { kind ->
                        FilterChip(
                            selected = s.kind == kind,
                            onClick = { vm.update { it.copy(kind = kind) } },
                            label = { Text(stringResource(machineKindLabelRes(kind))) },
                        )
                    }
                }
            }

            // Duration (min / sec)
            StandardCard(title = stringResource(R.string.machine_field_duration)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = s.durationMinutes,
                        onValueChange = { v -> vm.update { it.copy(durationMinutes = v.filter(Char::isDigit)) } },
                        label = { Text(stringResource(R.string.machine_field_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.durationSeconds,
                        onValueChange = { v -> vm.update { it.copy(durationSeconds = v.filter(Char::isDigit)) } },
                        label = { Text(stringResource(R.string.machine_field_seconds)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Distance + unit
            StandardCard(title = stringResource(R.string.machine_field_distance)) {
                OutlinedTextField(
                    value = s.distanceValue,
                    onValueChange = { v -> vm.update { it.copy(distanceValue = v) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.padding(top = AppSpacing.tight),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight),
                ) {
                    DistanceUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = s.distanceUnit == unit,
                            onClick = { vm.update { it.copy(distanceUnit = unit) } },
                            label = { Text(stringResource(unitLabelRes(unit))) },
                        )
                    }
                }
            }

            // Calories (estimate)
            StandardCard(title = stringResource(R.string.machine_field_calories)) {
                OutlinedTextField(
                    value = s.calories,
                    onValueChange = { v -> vm.update { it.copy(calories = v.filter(Char::isDigit)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.machine_estimate_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = EstimateCaution,
                    modifier = Modifier.padding(top = AppSpacing.tight),
                )
            }

            // Heart rate (often blank / unreliable)
            StandardCard(title = stringResource(R.string.machine_field_heartrate)) {
                OutlinedTextField(
                    value = s.heartRate,
                    onValueChange = { v -> vm.update { it.copy(heartRate = v.filter(Char::isDigit)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.machine_hr_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = EstimateCaution,
                    modifier = Modifier.padding(top = AppSpacing.tight),
                )
            }

            // Note
            StandardCard(title = stringResource(R.string.machine_field_note)) {
                OutlinedTextField(
                    value = s.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun machineKindLabelRes(kind: ActivityKind): Int = when (kind) {
    ActivityKind.Treadmill -> R.string.exercise_kind_treadmill
    ActivityKind.IndoorBike -> R.string.exercise_kind_indoor_bike
    ActivityKind.Elliptical -> R.string.exercise_kind_elliptical
    ActivityKind.Rower -> R.string.exercise_kind_rower
    ActivityKind.StairClimber -> R.string.exercise_kind_stair_climber
    // Non-machine kinds never reach this picker.
    else -> R.string.exercise_kind_treadmill
}

private fun unitLabelRes(unit: DistanceUnit): Int = when (unit) {
    DistanceUnit.Km -> R.string.machine_unit_km
    DistanceUnit.Mi -> R.string.machine_unit_mi
    DistanceUnit.M -> R.string.machine_unit_m
    DistanceUnit.Floors -> R.string.machine_unit_floors
    DistanceUnit.Steps -> R.string.machine_unit_steps
}
