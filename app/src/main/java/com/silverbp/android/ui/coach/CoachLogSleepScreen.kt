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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachLogSleepScreen(onClose: () -> Unit) {
    val repo = remember { ServiceLocator.coachRepository }
    val scope = rememberCoroutineScope()
    val dayStart = remember { todayDayStartMillis() }

    var hours by remember { mutableFloatStateOf(7f) }

    LaunchedEffect(dayStart) {
        repo.sleepForDay(dayStart)?.let {
            hours = (it.durationMin / 60f).coerceIn(4f, 12f)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.coach_log_sleep_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.coach_log_sleep_duration)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%.1f h".format(hours),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.size(8.dp))
                Slider(
                    value = hours,
                    onValueChange = { hours = it },
                    valueRange = 4f..12f,
                    steps = 15,                 // 0.5h granularity
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        repo.upsertSleep(
                            SleepLogEntity(
                                dayStart = dayStart,
                                durationMin = (hours * 60f).toInt(),
                                sourceRaw = "manual",
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
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
