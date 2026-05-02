package com.silverbp.android.ui.coach

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Today's doses checklist. We don't yet have a "scheduled hour" UI for users
 * to declare regimens — PR2 simply lists each saved [MedicationEntity] as a
 * single dose at hour 8 (default morning), and the user toggles taken/not.
 *
 * PR3 will add a regimen editor (hour, dose count) once the dosage workflow
 * is reviewed with the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachLogMedicationScreen(onClose: () -> Unit) {
    val coachRepo = remember { ServiceLocator.coachRepository }
    val medsDao = remember { ServiceLocator.database.medicationDao() }
    val scope = rememberCoroutineScope()
    val dayStart = remember { todayDayStartMillis() }

    val meds by medsDao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val doses by coachRepo.observeDosesForDay(dayStart).collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coach_log_medication_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        if (meds.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.coach_log_medication_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(meds, key = { it.id }) { med ->
                    val existing = doses.firstOrNull { it.medicationId == med.id }
                    val initialTaken = existing?.taken ?: false
                    DoseRow(
                        med = med,
                        taken = initialTaken,
                        onChange = { takenNow ->
                            scope.launch {
                                coachRepo.upsertDose(
                                    MedicationDoseEntity(
                                        id = existing?.id ?: UUID.randomUUID().toString(),
                                        dayStart = dayStart,
                                        medicationId = med.id,
                                        scheduledHour = existing?.scheduledHour ?: 8,
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

@Composable
private fun DoseRow(med: MedicationEntity, taken: Boolean, onChange: (Boolean) -> Unit) {
    var local by remember(taken) { mutableStateOf(taken) }
    LaunchedEffect(taken) { local = taken }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(med.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (med.dose.isNotBlank()) {
                    Text(med.dose, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
