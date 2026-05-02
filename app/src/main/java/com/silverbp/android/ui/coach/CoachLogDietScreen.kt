package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachLogDietScreen(onClose: () -> Unit) {
    val repo = remember { ServiceLocator.coachRepository }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dayStart = remember { todayDayStartMillis() }

    var sodium by remember { mutableStateOf("mid") }
    var veg by remember { mutableIntStateOf(3) }

    LaunchedEffect(dayStart) {
        repo.dietForDay(dayStart)?.let {
            sodium = it.sodiumLevelRaw
            veg = it.vegServings
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coach_log_diet_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.coach_log_diet_sodium)) {
                SodiumOption(
                    selected = sodium,
                    value = "low",
                    labelRes = R.string.coach_log_diet_sodium_low,
                    onSelect = { sodium = "low" },
                )
                SodiumOption(
                    selected = sodium,
                    value = "mid",
                    labelRes = R.string.coach_log_diet_sodium_mid,
                    onSelect = { sodium = "mid" },
                )
                SodiumOption(
                    selected = sodium,
                    value = "high",
                    labelRes = R.string.coach_log_diet_sodium_high,
                    onSelect = { sodium = "high" },
                )
            }

            SectionCard(stringResource(R.string.coach_log_diet_veg)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (veg > 0) veg -= 1 }, enabled = veg > 0) { Text("−") }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        veg.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { if (veg < 12) veg += 1 }) { Text("+") }
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        repo.upsertDiet(
                            DietCheckEntity(
                                dayStart = dayStart,
                                sodiumLevelRaw = sodium,
                                vegServings = veg,
                                sourceRaw = "manual",
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        snackbar.showSnackbar(message = "")
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.coach_log_save))
            }
        }
    }
}

@Composable
private fun SodiumOption(selected: String, value: String, labelRes: Int, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = onSelect)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun todayDayStartMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
