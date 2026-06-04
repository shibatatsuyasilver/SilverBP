package com.silverbp.android.ui.coach

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.silverbp.android.R
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachLogSleepScreen(onClose: () -> Unit) {
    val repo = remember { ServiceLocator.coachRepository }
    val scope = rememberCoroutineScope()
    val dayStart = remember { todayDayStartMillis() }

    var hours by remember { mutableFloatStateOf(7f) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<SleepSyncResult?>(null) }

    LaunchedEffect(dayStart) {
        repo.sleepForDay(dayStart)?.let {
            hours = (it.durationMin / 60f).coerceIn(4f, 12f)
        }
    }

    // Pull the last 14 days of sleep from Health Connect on demand and report
    // the outcome, so a silent permission/empty failure is no longer invisible.
    val doSync = {
        if (!syncing) {
            syncing = true
            scope.launch {
                val bridge = ServiceLocator.healthConnectBridge
                syncStatus = runCatching {
                    if (!bridge.hasSleepReadPermission()) {
                        SleepSyncResult.NeedsPermission
                    } else {
                        val zone = ZoneId.systemDefault()
                        val today = LocalDate.now(zone)
                        val entries = bridge.querySleep(today.minusDays(13), today, zone)
                        when {
                            entries == null -> SleepSyncResult.Failed
                            entries.isEmpty() -> SleepSyncResult.NoData
                            else -> {
                                val now = System.currentTimeMillis()
                                entries.forEach {
                                    repo.upsertSleep(
                                        SleepLogEntity(
                                            dayStart = it.dayStartMillis,
                                            durationMin = it.durationMin,
                                            sourceRaw = "hc",
                                            updatedAt = now,
                                        )
                                    )
                                }
                                SleepSyncResult.Synced(entries.size)
                            }
                        }
                    }
                }.getOrDefault(SleepSyncResult.Failed)
                repo.sleepForDay(dayStart)?.let {
                    hours = (it.durationMin / 60f).coerceIn(4f, 12f)
                }
                syncing = false
            }
        }
        Unit
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        // Don't trust the callback payload (Android 15 HC quirk) — just re-sync,
        // which re-checks the granted permission directly.
        doSync()
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

            SectionCard(stringResource(R.string.coach_sleep_sync_section)) {
                when (val status = syncStatus) {
                    SleepSyncResult.NeedsPermission -> {
                        Text(
                            stringResource(R.string.coach_sleep_sync_needs_permission),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = {
                                val bridge = ServiceLocator.healthConnectBridge
                                permLauncher.launch(
                                    bridge.sleepReadPermissions + bridge.backgroundReadPermissions,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.coach_sleep_sync_grant)) }
                    }
                    is SleepSyncResult.Synced -> Text(
                        stringResource(R.string.coach_sleep_sync_synced, status.nights),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SleepSyncResult.NoData -> Text(
                        stringResource(R.string.coach_sleep_sync_nodata),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SleepSyncResult.Failed -> Text(
                        stringResource(R.string.coach_sleep_sync_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> Unit
                }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = doSync,
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (syncing) R.string.coach_sleep_syncing
                            else R.string.coach_sleep_sync_button,
                        ),
                    )
                }
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

private sealed interface SleepSyncResult {
    data class Synced(val nights: Int) : SleepSyncResult
    data object NoData : SleepSyncResult
    data object NeedsPermission : SleepSyncResult
    data object Failed : SleepSyncResult
}
