package com.silverbp.android.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.coach.MedicationReminderScheduler
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationKind
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.SettingsDivider
import com.silverbp.android.ui.components.SettingsGroup
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgePrimary
import kotlinx.coroutines.launch

/**
 * Lists every saved medication / supplement grouped by [MedicationKind].
 * Entry point from [CoachLogMedicationScreen]'s top-bar Manage action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationManageScreen(
    onClose: () -> Unit,
    onAddNew: () -> Unit,
    onEdit: (medicationId: String) -> Unit,
) {
    val medicationRepo = remember { ServiceLocator.medicationRepository }
    val currentMemberStore = remember { ServiceLocator.currentMemberStore }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentMemberId by currentMemberStore.flow.collectAsStateWithLifecycle(initialValue = "")
    val meds by remember(medicationRepo, currentMemberId) {
        medicationRepo.observeForMember(currentMemberId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val schedules by remember(medicationRepo, currentMemberId) {
        medicationRepo.observeSchedulesForMember(currentMemberId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val schedulesByMed = remember(schedules) { schedules.groupBy { it.medicationId } }

    var pendingDelete by remember { mutableStateOf<MedicationEntity?>(null) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.medication_manage_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (meds.isEmpty()) {
            EmptyState(padding, onAddNew)
            return@Scaffold
        }

        val medsByKind = remember(meds) { meds.groupBy { it.kind } }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            // Add CTA — Expressive primary button mirroring the restyled flagship's
            // list-header actions (replaces the old plain FloatingActionButton).
            item(key = "add-cta") {
                ExpressivePrimaryButton(
                    text = stringResource(R.string.medication_add),
                    onClick = onAddNew,
                    icon = Icons.Filled.Add,
                    fillWidth = true,
                )
            }

            val medsList = medsByKind[MedicationKind.MEDICATION].orEmpty()
            if (medsList.isNotEmpty()) {
                item(key = "section-medications") {
                    SettingsGroup(stringResource(R.string.medication_section_medications)) {
                        medsList.forEachIndexed { index, med ->
                            if (index > 0) SettingsDivider()
                            MedicationRow(
                                med = med,
                                schedules = schedulesByMed[med.id].orEmpty(),
                                onEdit = { onEdit(med.id) },
                                onDelete = { pendingDelete = med },
                            )
                        }
                    }
                }
            }
            val supplementsList = medsByKind[MedicationKind.SUPPLEMENT].orEmpty()
            if (supplementsList.isNotEmpty()) {
                item(key = "section-supplements") {
                    SettingsGroup(stringResource(R.string.medication_section_supplements)) {
                        supplementsList.forEachIndexed { index, med ->
                            if (index > 0) SettingsDivider()
                            MedicationRow(
                                med = med,
                                schedules = schedulesByMed[med.id].orEmpty(),
                                onEdit = { onEdit(med.id) },
                                onDelete = { pendingDelete = med },
                            )
                        }
                    }
                }
            }
            // Catch-all for unknown kinds (forward-compat).
            val otherKinds = medsByKind.filterKeys {
                it != MedicationKind.MEDICATION && it != MedicationKind.SUPPLEMENT
            }
            otherKinds.forEach { (kind, list) ->
                item(key = "section-$kind") {
                    SettingsGroup(kind) {
                        list.forEachIndexed { index, med ->
                            if (index > 0) SettingsDivider()
                            MedicationRow(
                                med = med,
                                schedules = schedulesByMed[med.id].orEmpty(),
                                onEdit = { onEdit(med.id) },
                                onDelete = { pendingDelete = med },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.medication_delete_confirm_title)) },
            text = { Text(stringResource(R.string.medication_delete_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        // Cancel queued workers BEFORE the FK CASCADE wipes
                        // schedule rows so we still know what to cancel.
                        MedicationReminderScheduler.cancelForMedication(context, target.id)
                        medicationRepo.delete(target.id)
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues, onAddNew: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(AppSpacing.screenH),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.medication_manage_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(AppSpacing.sectionGap))
            ExpressivePrimaryButton(
                text = stringResource(R.string.medication_add),
                onClick = onAddNew,
                icon = Icons.Filled.Add,
            )
        }
    }
}

@Composable
private fun MedicationRow(
    med: MedicationEntity,
    schedules: List<MedicationScheduleEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppSpacing.touchTarget)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Leading tinted module-identity tile (服藥 = ForgePrimary), mirroring the
        // Coach medication log rows so the medication family stays one colour.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(ForgePrimary.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Medication,
                contentDescription = null,
                tint = ForgePrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                med.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (med.dose.isNotBlank()) {
                Text(
                    med.dose,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val summary = formatScheduleSummary(schedules)
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.medication_no_schedules),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.medication_edit_title),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
            )
        }
    }
}
