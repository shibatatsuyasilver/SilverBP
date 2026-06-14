package com.silverbp.android.ui.member

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.Member
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.HeightPickerField
import com.silverbp.android.ui.components.YearPickerField
import kotlinx.coroutines.launch

/**
 * Add/edit a family member. Modal bottom sheet shown over
 * [MemberManagementScreen]. [member] null → add mode (a fresh non-owner row);
 * non-null → edit mode (the owner is editable too, but its owner flag and
 * archive state aren't touched here). Persists via [com.silverbp.android.core.member.MemberRepository]
 * from [ServiceLocator] — no ViewModel, matching MedicationEditScreen's idiom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemberEditorSheet(
    member: Member?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val repo = remember { ServiceLocator.memberRepository }
    val scope = rememberCoroutineScope()

    // Editor-local state seeded once from the member argument. The sheet is
    // recreated per add/edit invocation (keyed by the caller), so a plain
    // remember is enough — no SavedStateHandle needed for a transient sheet.
    var displayName by remember { mutableStateOf(member?.displayName ?: "") }
    // Birth year / height are picked from range-constrained wheels; null = not set.
    var birthYear by remember { mutableStateOf(member?.birthYear) }
    var heightCm by remember { mutableStateOf(member?.heightCm) }
    var hasDiabetes by remember { mutableStateOf(member?.hasDiabetes ?: false) }
    var hasCKD by remember { mutableStateOf(member?.hasCKD ?: false) }
    var hasASCVD by remember { mutableStateOf(member?.hasASCVD ?: false) }
    var guideline by remember {
        mutableStateOf(member?.guideline ?: HypertensionGuideline.Taiwan2022)
    }
    var colorIndex by remember { mutableStateOf(member?.colorIndex ?: 0) }
    var saving by remember { mutableStateOf(false) }

    // The wheel pickers can only yield in-range values (or null when cleared), so
    // there is nothing to validate; save is gated only on the in-flight save.
    val canSave = !saving

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (member == null) R.string.member_add else R.string.member_edit),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            // Name (empty allowed: owner falls back to localized "Me").
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.member_name_label)) },
                placeholder = { Text(stringResource(R.string.member_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Birth year (optional). Wheel picker 1900..now; Clear restores "not set".
            YearPickerField(
                value = birthYear,
                onChange = { birthYear = it },
                label = stringResource(R.string.member_birth_year),
                modifier = Modifier.fillMaxWidth(),
            )

            // Height in cm (optional). Drives the per-member BMI on the weight card.
            // Wheel picker 50..250 cm; Clear restores "not set".
            HeightPickerField(
                value = heightCm,
                onChange = { heightCm = it },
                label = stringResource(R.string.member_height_label),
                modifier = Modifier.fillMaxWidth(),
            )

            // Identity colour (0..7 fixed palette swatches).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.member_color),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MemberPalette.colors.forEachIndexed { index, color ->
                        val selected = index == colorIndex
                        // selectable gives TalkBack the radio role + selected state;
                        // contentDescription names each swatch so all 8 are announced.
                        val swatchCd = stringResource(R.string.member_color_swatch, index + 1)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(color, CircleShape)
                                .then(
                                    if (selected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else Modifier
                                )
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { colorIndex = index },
                                )
                                .semantics { contentDescription = swatchCd },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MemberPalette.onColor,
                                )
                            }
                        }
                    }
                }
            }

            // Hypertension guideline (per-member). Radio list, mirrors Settings.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.member_guideline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                HypertensionGuideline.entries.forEach { g ->
                    val labelRes = when (g) {
                        HypertensionGuideline.Taiwan2022 -> R.string.guideline_taiwan2022
                        HypertensionGuideline.AccAha2017 -> R.string.guideline_acc_aha_2017
                        HypertensionGuideline.Esh2023 -> R.string.guideline_esh_2023
                        HypertensionGuideline.Jnc8 -> R.string.guideline_jnc_8
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { guideline = g }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = guideline == g, onClick = { guideline = g })
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(labelRes))
                    }
                }
            }

            // Conditions affecting interpretation.
            Column {
                MemberToggleRow(
                    label = stringResource(R.string.member_has_diabetes),
                    checked = hasDiabetes,
                    onChange = { hasDiabetes = it },
                )
                MemberToggleRow(
                    label = stringResource(R.string.member_has_ckd),
                    checked = hasCKD,
                    onChange = { hasCKD = it },
                )
                MemberToggleRow(
                    label = stringResource(R.string.member_has_ascvd),
                    checked = hasASCVD,
                    onChange = { hasASCVD = it },
                )
            }

            Button(
                onClick = {
                    if (!canSave) return@Button
                    saving = true
                    scope.launch {
                        val existing = member
                        val toSave = if (existing != null) {
                            existing.copy(
                                displayName = displayName.trim(),
                                birthYear = birthYear,
                                heightCm = heightCm,
                                hasDiabetes = hasDiabetes,
                                hasCKD = hasCKD,
                                hasASCVD = hasASCVD,
                                guideline = guideline,
                                colorIndex = colorIndex,
                                updatedAt = java.time.Instant.now(),
                            )
                        } else {
                            // New member: a non-owner row appended after the
                            // current members (sortOrder = count). isOwner stays
                            // false — the single-owner invariant is the repo's job.
                            Member(
                                displayName = displayName.trim(),
                                isOwner = false,
                                birthYear = birthYear,
                                heightCm = heightCm,
                                hasDiabetes = hasDiabetes,
                                hasCKD = hasCKD,
                                hasASCVD = hasASCVD,
                                guideline = guideline,
                                colorIndex = colorIndex,
                                sortOrder = repo.count(),
                            )
                        }
                        repo.upsert(toSave)
                        onDismiss()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.member_save))
            }
        }
    }
}

@Composable
private fun MemberToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
