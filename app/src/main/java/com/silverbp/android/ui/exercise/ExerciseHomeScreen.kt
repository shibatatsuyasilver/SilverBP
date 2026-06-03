package com.silverbp.android.ui.exercise

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.coach.WorkoutBpGate
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.strength.StrengthWorkoutSession
import com.silverbp.android.ui.achievements.MedalUnlockBannerHost
import com.silverbp.android.ui.exercise.components.ExerciseTrendsCard
import com.silverbp.android.ui.exercise.components.MedalShowcaseCard
import com.silverbp.android.ui.exercise.components.RecentSessionsCard
import com.silverbp.android.ui.exercise.components.TodayStepsCard
import com.silverbp.android.ui.theme.ForgeOnSecondary
import com.silverbp.android.ui.theme.ForgeSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Sub-sections of the training hub hosted inside the Exercise tab. */
private enum class HubSection(val labelRes: Int) {
    Plan(R.string.hub_section_plan),
    Library(R.string.hub_section_library),
    History(R.string.hub_section_history),
}

@Composable
fun ExerciseHomeScreen(
    onStartSession: () -> Unit,
    onStartStrengthSession: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenMedals: () -> Unit,
    vm: ExerciseHomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var section by remember { mutableIntStateOf(0) }

    val recoverable by vm.recoverable.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        ServiceLocator.achievementStore.launchRefresh()
        vm.refreshWeekSteps()
        vm.checkRecoverable()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (recoverable != null) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RecoverSessionCard(
                        onResume = {
                            vm.resumeRecoverable()
                            onStartSession()
                        },
                        onDiscard = vm::discardRecoverable,
                    )
                }
            }
            SecondaryTabRow(selectedTabIndex = section) {
                HubSection.entries.forEachIndexed { idx, s ->
                    Tab(
                        selected = section == idx,
                        onClick = { section = idx },
                        text = { Text(stringResource(s.labelRes)) },
                    )
                }
            }

            when (HubSection.entries[section]) {
                HubSection.Plan -> PlanSection(
                    state = state,
                    vm = vm,
                    onStartSession = onStartSession,
                    onPickStrength = { section = HubSection.Library.ordinal },
                    onOpenMedals = onOpenMedals,
                )
                HubSection.Library -> StrengthLibrarySection(
                    onStartStrengthSession = onStartStrengthSession,
                )
                HubSection.History -> HistorySection(
                    state = state,
                    onOpenDetail = onOpenDetail,
                )
            }
        }

        MedalUnlockBannerHost(
            onTap = onOpenMedals,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun RecoverSessionCard(onResume: () -> Unit, onDiscard: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.exercise_recover_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.exercise_recover_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.exercise_discard))
                }
                Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.exercise_resume))
                }
            }
        }
    }
}

@Composable
private fun PlanSection(
    state: ExerciseHomeUiState,
    vm: ExerciseHomeViewModel,
    onStartSession: () -> Unit,
    onPickStrength: () -> Unit,
    onOpenMedals: () -> Unit,
) {
    val (perm, requestPerm) = rememberExercisePermissionState()
    val achievementState by ServiceLocator.achievementStore.state.collectAsStateWithLifecycle()
    val settings by ServiceLocator.userSettings.flow
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    var showPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Cardio kind + non-ALLOW gate awaiting the user's confirmation dialog.
    // ALLOW never lands here — it starts directly (no dialog).
    var pendingCardio by remember { mutableStateOf<Pair<ActivityKind, WorkoutBpGate>?>(null) }

    fun startCardio(kind: ActivityKind) {
        requestPerm {
            ServiceLocator.exerciseController.start(kind)
            onStartSession()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = { showPicker = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = ForgeSecondary,
                contentColor = ForgeOnSecondary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.hub_start_training),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        TodayTaskCard(state = state)

        if (perm.locationDenied || !perm.hasFineLocation) {
            PermissionHint(
                denied = perm.locationDenied,
                onOpenSettings = perm::openAppSettings,
            )
        }

        TodayStepsCard(
            todaySteps = achievementState.stats.todaySteps,
            dailyGoal = settings.dailyStepGoal,
        )

        MedalShowcaseCard(
            state = achievementState,
            onViewAll = onOpenMedals,
        )

        ExerciseTrendsCard(
            state = state,
            onSelectRange = vm::setRange,
        )

        Spacer(Modifier.height(8.dp))
    }

    if (showPicker) {
        StartPickerDialog(
            onDismiss = { showPicker = false },
            onPickCardio = { kind ->
                showPicker = false
                // BP gate before starting: ALLOW starts directly; CAUTION/BLOCK
                // surface the dialog so the user confirms (or measures first).
                scope.launch {
                    when (val gate = evaluateWorkoutBpGate()) {
                        WorkoutBpGate.Allow -> startCardio(kind)
                        else -> pendingCardio = kind to gate
                    }
                }
            },
            onPickStrength = {
                showPicker = false
                onPickStrength()
            },
        )
    }

    pendingCardio?.let { (kind, gate) ->
        WorkoutBpGateDialog(
            gate = gate,
            onProceed = {
                pendingCardio = null
                startCardio(kind)
            },
            onMeasure = { pendingCardio = null },
            onDismiss = { pendingCardio = null },
        )
    }
}

@Composable
private fun TodayTaskCard(state: ExerciseHomeUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.hub_today_task_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val task = state.todayExerciseTask
            when {
                task != null -> {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge)
                    task.targetValue?.let { v ->
                        Text(
                            stringResource(R.string.hub_today_task_minutes, v.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.hasPlan -> Text(
                    stringResource(R.string.hub_today_task_rest),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Text(
                    stringResource(R.string.hub_today_task_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StartPickerDialog(
    onDismiss: () -> Unit,
    onPickCardio: (ActivityKind) -> Unit,
    onPickStrength: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_start_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityKind.entries.forEach { kind ->
                    PickerRow(
                        icon = iconForKind(kind),
                        label = stringResource(labelResForKind(kind)),
                        onClick = { onPickCardio(kind) },
                    )
                }
                PickerRow(
                    icon = Icons.Filled.FitnessCenter,
                    label = stringResource(R.string.hub_pick_strength),
                    onClick = onPickStrength,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, null)
        Spacer(Modifier.size(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HistorySection(
    state: ExerciseHomeUiState,
    onOpenDetail: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.hub_history_cardio),
            style = MaterialTheme.typography.titleMedium,
        )
        RecentSessionsCard(
            sessions = state.recent,
            onSessionClick = { onOpenDetail(it.id.toString()) },
        )

        Text(
            stringResource(R.string.hub_history_strength),
            style = MaterialTheme.typography.titleMedium,
        )
        StrengthHistoryCard(sessions = state.strengthSessions)

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StrengthHistoryCard(sessions: List<StrengthWorkoutSession>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.hub_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                sessions.forEach { s -> StrengthSessionRow(s) }
            }
        }
    }
}

@Composable
private fun StrengthSessionRow(session: StrengthWorkoutSession) {
    val fmt = DateTimeFormatter
        .ofPattern("MM/dd HH:mm", Locale.TAIWAN)
        .withZone(ZoneId.systemDefault())
    val durationMs = (session.endedAt - session.startedAt).coerceAtLeast(0L)
    Column {
        Text(
            fmt.format(Instant.ofEpochMilli(session.startedAt)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            ExerciseMath.formatDuration(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconForKind(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.Walking -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityKind.Running -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityKind.BriskWalking -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityKind.Cycling -> Icons.AutoMirrored.Filled.DirectionsBike
}

private fun labelResForKind(kind: ActivityKind): Int = when (kind) {
    ActivityKind.Walking -> R.string.exercise_kind_walking
    ActivityKind.Running -> R.string.exercise_kind_running
    ActivityKind.BriskWalking -> R.string.exercise_kind_brisk_walking
    ActivityKind.Cycling -> R.string.exercise_kind_cycling
}

@Composable
private fun PermissionHint(denied: Boolean, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.exercise_location_permission_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (denied) stringResource(R.string.exercise_location_denied)
                else stringResource(R.string.exercise_location_permission_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (denied) {
                OutlinedButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.exercise_settings_open_app_settings))
                }
            }
        }
    }
}
