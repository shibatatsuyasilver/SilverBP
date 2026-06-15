package com.silverbp.android.ui.coach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.silverbp.android.R
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgePrimary
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            StandardCard {
                SectionHeader(
                    icon = Icons.Filled.Bedtime,
                    title = stringResource(R.string.coach_log_sleep_duration),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%.1f h".format(hours),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Slider(
                    value = hours,
                    onValueChange = { hours = it },
                    valueRange = 4f..12f,
                    steps = 15,                 // 0.5h granularity
                )
            }

            StandardCard {
                SectionHeader(
                    icon = Icons.Filled.Sync,
                    title = stringResource(R.string.coach_sleep_sync_section),
                )
                when (val status = syncStatus) {
                    SleepSyncResult.NeedsPermission -> {
                        Text(
                            stringResource(R.string.coach_sleep_sync_needs_permission),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = {
                                val bridge = ServiceLocator.healthConnectBridge
                                permLauncher.launch(
                                    bridge.sleepReadPermissions + bridge.backgroundReadPermissions,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(AppSpacing.cardCorner),
                        ) { Text(stringResource(R.string.coach_sleep_sync_grant)) }
                    }
                    is SleepSyncResult.Synced -> Text(
                        stringResource(R.string.coach_sleep_sync_synced, status.nights),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SleepSyncResult.NoData -> Text(
                        stringResource(R.string.coach_sleep_sync_nodata),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SleepSyncResult.Failed -> Text(
                        stringResource(R.string.coach_sleep_sync_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> Unit
                }
                OutlinedButton(
                    onClick = doSync,
                    enabled = !syncing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(AppSpacing.cardCorner),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(AppSpacing.cardCorner),
            ) {
                Text(
                    stringResource(R.string.coach_log_save),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Section heading shared by the sleep cards: a small tinted icon tile next to a
 * semibold title, mirroring the Today/UnifiedHistory card idiom. UI-only.
 */
@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ForgePrimary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = ForgePrimary,
            )
        }
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private sealed interface SleepSyncResult {
    data class Synced(val nights: Int) : SleepSyncResult
    data object NoData : SleepSyncResult
    data object NeedsPermission : SleepSyncResult
    data object Failed : SleepSyncResult
}
