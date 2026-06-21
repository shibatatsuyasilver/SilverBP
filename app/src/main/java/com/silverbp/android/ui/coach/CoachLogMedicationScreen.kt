package com.silverbp.android.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.coach.MedicationActionReceiver
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationKind
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.CheckCircle
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgePrimary
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private data class TodayDose(
    val medication: MedicationEntity,
    val schedule: MedicationScheduleEntity,
    val dayStart: Long,
) {
    val doseId: String get() = MedicationActionReceiver.doseId(dayStart, schedule.id)
}

/**
 * Today's scheduled doses, one row per (medication × schedule) firing planned
 * for today's day-of-week. Switch toggles persist [MedicationDoseEntity] under
 * a deterministic id ([MedicationActionReceiver.doseId]) so a notification's
 * "Mark as taken" action and this in-app Switch operate on the same row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachLogMedicationScreen(onClose: () -> Unit, onManage: () -> Unit) {
    val coachRepo = remember { ServiceLocator.coachRepository }
    val medicationRepo = remember { ServiceLocator.medicationRepository }
    val currentMemberStore = remember { ServiceLocator.currentMemberStore }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val dayStart = remember { today.atStartOfDay(zone).toInstant().toEpochMilli() }

    val currentMemberId by currentMemberStore.flow.collectAsStateWithLifecycle(initialValue = "")
    val meds by remember(medicationRepo, currentMemberId) {
        medicationRepo.observeForMember(currentMemberId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val schedules by remember(medicationRepo, currentMemberId) {
        medicationRepo.observeSchedulesForMember(currentMemberId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val doses by coachRepo.observeDosesForDay(dayStart).collectAsStateWithLifecycle(initialValue = emptyList())

    val todayDoses = remember(meds, schedules, dayStart, today) {
        val medsById = meds.associateBy { it.id }
        schedules
            .asSequence()
            .filter { it.enabled }
            .filter { DayOfWeekMask.contains(it.daysOfWeekMask, today.dayOfWeek) }
            .mapNotNull { sched ->
                val med = medsById[sched.medicationId] ?: return@mapNotNull null
                TodayDose(med, sched, dayStart)
            }
            .sortedWith(
                compareBy(
                    { it.schedule.hour },
                    { it.schedule.minute },
                    { it.medication.name },
                ),
            )
            .toList()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.coach_log_medication_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onManage) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.medication_manage_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            meds.isEmpty() -> {
                EmptyState(
                    text = stringResource(R.string.coach_log_medication_empty),
                    cta = stringResource(R.string.medication_add),
                    onCta = onManage,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(AppSpacing.screenH),
                )
            }
            todayDoses.isEmpty() -> {
                EmptyState(
                    text = stringResource(R.string.medication_log_no_today_doses),
                    cta = stringResource(R.string.medication_manage_action),
                    onCta = onManage,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(AppSpacing.screenH),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        horizontal = AppSpacing.screenH,
                        vertical = AppSpacing.screenV,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                ) {
                    items(todayDoses, key = { it.doseId }) { row ->
                        val taken = doses.firstOrNull { it.id == row.doseId }?.taken ?: false
                        DoseRow(
                            row = row,
                            taken = taken,
                            onChange = { takenNow ->
                                scope.launch {
                                    coachRepo.upsertDose(
                                        MedicationDoseEntity(
                                            id = row.doseId,
                                            dayStart = row.dayStart,
                                            medicationId = row.medication.id,
                                            scheduledHour = row.schedule.hour,
                                            scheduledMinute = row.schedule.minute,
                                            scheduleId = row.schedule.id,
                                            taken = takenNow,
                                            updatedAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    text: String,
    cta: String,
    onCta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ForgePrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Medication,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = ForgePrimary,
                )
            }
            Spacer(Modifier.size(AppSpacing.sectionGap))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(AppSpacing.sectionGap))
            ExpressivePrimaryButton(text = cta, onClick = onCta)
        }
    }
}

@Composable
private fun DoseRow(
    row: TodayDose,
    taken: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var local by remember(taken) { mutableStateOf(taken) }
    LaunchedEffect(taken) { local = taken }
    val timeStr = "%02d:%02d".format(row.schedule.hour, row.schedule.minute)
    val kindLabelRes = if (row.medication.kind == MedicationKind.SUPPLEMENT) {
        R.string.medication_kind_supplement
    } else {
        R.string.medication_kind_medication
    }
    StandardCard(
        contentPadding = AppSpacing.sectionGap,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading tinted time tile keeps the scheduled hour prominent and
            // anchors the row to the Today/UnifiedHistory card idiom.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ForgePrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    timeStr,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ForgePrimary,
                )
            }
            Spacer(Modifier.size(AppSpacing.screenH))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.medication.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (row.medication.dose.isNotBlank()) {
                    Text(
                        row.medication.dose,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(kindLabelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(AppSpacing.tight))
            val checkCd = if (local) {
                "${row.medication.name} ${stringResource(R.string.coach_log_medication_taken)}"
            } else {
                "${row.medication.name} ${stringResource(R.string.medication_action_taken)}"
            }
            CheckCircle(
                checked = local,
                onCheckedChange = {
                    local = it
                    onChange(it)
                },
                contentDescription = checkCd,
            )
        }
    }
}
