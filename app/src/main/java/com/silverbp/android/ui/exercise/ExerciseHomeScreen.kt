package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.settings.UserSettings
import com.silverbp.android.ui.achievements.MedalUnlockBannerHost
import com.silverbp.android.ui.exercise.components.MedalShowcaseCard
import com.silverbp.android.ui.exercise.components.RecentSessionsCard
import com.silverbp.android.ui.exercise.components.TodayStepsCard

@Composable
fun ExerciseHomeScreen(
    onStartSession: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenMedals: () -> Unit,
    vm: ExerciseHomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val (perm, requestPerm) = rememberExercisePermissionState()
    val achievementState by ServiceLocator.achievementStore.state.collectAsStateWithLifecycle()
    val settings by ServiceLocator.userSettings.flow
        .collectAsStateWithLifecycle(initialValue = UserSettings())

    LifecycleResumeEffect(Unit) {
        ServiceLocator.achievementStore.launchRefresh()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KindPicker(
                selected = state.selectedKind,
                onSelect = vm::selectKind,
            )

            StartButton(
                kind = state.selectedKind,
                enabled = !perm.locationDenied,
                onClick = {
                    requestPerm {
                        ServiceLocator.exerciseController.start(state.selectedKind)
                        onStartSession()
                    }
                },
            )

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

            RecentSessionsCard(
                sessions = state.recent,
                onSessionClick = { onOpenDetail(it.id.toString()) },
            )

            Spacer(Modifier.height(8.dp))
        }

        MedalUnlockBannerHost(
            onTap = onOpenMedals,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun KindPicker(
    selected: ActivityKind,
    onSelect: (ActivityKind) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.tab_exercise),
                style = MaterialTheme.typography.titleMedium,
            )
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selected == ActivityKind.Walking,
                    onClick = { onSelect(ActivityKind.Walking) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null) },
                    label = { Text(stringResource(R.string.exercise_kind_walking)) },
                )
                FilterChip(
                    selected = selected == ActivityKind.Running,
                    onClick = { onSelect(ActivityKind.Running) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsRun, null) },
                    label = { Text(stringResource(R.string.exercise_kind_running)) },
                )
            }
        }
    }
}

@Composable
private fun StartButton(
    kind: ActivityKind,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = colorForKind(kind)
    val labelRes = if (kind == ActivityKind.Walking)
        R.string.exercise_start_walking else R.string.exercise_start_running
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, null)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
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
