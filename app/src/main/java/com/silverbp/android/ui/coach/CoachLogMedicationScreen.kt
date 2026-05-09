package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val medsDao = remember { ServiceLocator.database.medicationDao() }
    val scheduleDao = remember { ServiceLocator.database.medicationScheduleDao() }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val dayStart = remember { today.atStartOfDay(zone).toInstant().toEpochMilli() }

    val meds by medsDao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val schedules by scheduleDao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
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
            TopAppBar(
                title = { Text(stringResource(R.string.coach_log_medication_title)) },
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
                        .padding(16.dp),
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
                        .padding(16.dp),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(12.dp))
            Button(onClick = onCta) { Text(cta) }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                timeStr,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.medication.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (row.medication.dose.isNotBlank()) {
                    Text(
                        row.medication.dose,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(kindLabelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Switch(
                checked = local,
                onCheckedChange = {
                    local = it
                    onChange(it)
                },
            )
        }
    }
}
