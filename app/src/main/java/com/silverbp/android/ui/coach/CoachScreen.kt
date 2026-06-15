package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.ui.coach.components.ModuleCard
import com.silverbp.android.ui.coach.components.NarrationBlock
import com.silverbp.android.ui.coach.components.TodayTaskCard
import com.silverbp.android.ui.coach.components.WeeklyProgressCard
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.exercise.rememberExercisePermissionState
import com.silverbp.android.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(
    onOpenWeeklyReport: () -> Unit = {},
    onOpenWeeklyPlan: () -> Unit = {},
    onOpenLogDiet: () -> Unit = {},
    onOpenLogSleep: () -> Unit = {},
    onOpenLogMedication: () -> Unit = {},
    onStartExercise: () -> Unit = {},
    vm: CoachViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // "去散步" starts a Walking session directly. We still request the location /
    // notification / activity-recognition perms the foreground tracking service
    // needs (starting it without location permission crashes on modern Android),
    // but intentionally NO pre-workout BP gate — tapping the task goes straight
    // to walking.
    val (_, requestPerm) = rememberExercisePermissionState()

    fun startWalk() {
        requestPerm {
            ServiceLocator.exerciseController.start(ActivityKind.Walking)
            onStartExercise()
        }
    }

    // Re-pull HC sleep/nutrition each time the Coach tab is shown, so data
    // logged overnight appears without a full app restart.
    LifecycleResumeEffect(Unit) {
        vm.refreshHealthConnectBackfills()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    stringResource(R.string.coach_screen_title),
                    fontWeight = FontWeight.SemiBold,
                )
            })
        },
    ) { padding ->
        when (val s = state) {
            CoachUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is CoachUiState.Ready -> ReadyContent(
                state = s,
                onStartExercise = ::startWalk,
                onOpenWeeklyReport = onOpenWeeklyReport,
                onOpenWeeklyPlan = onOpenWeeklyPlan,
                onOpenLogDiet = onOpenLogDiet,
                onOpenLogSleep = onOpenLogSleep,
                onOpenLogMedication = onOpenLogMedication,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun ReadyContent(
    state: CoachUiState.Ready,
    onStartExercise: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenWeeklyPlan: () -> Unit,
    onOpenLogDiet: () -> Unit,
    onOpenLogSleep: () -> Unit,
    onOpenLogMedication: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        // Today's primary task / safety-hold alert lives at the top of the tab.
        TodayTaskCard(task = state.todayTask, onStartExercise = onStartExercise)

        // 各模組進度 — a tidy section header (mirrors the Today / 紀錄 day headers)
        // followed by the per-module adherence-ring cards.
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
            CoachSectionHeader(title = stringResource(R.string.coach_modules_title))
            state.modules.forEach { row ->
                ModuleCard(
                    row = row,
                    onTap = when (row.moduleKey) {
                        ModuleKey.Diet -> onOpenLogDiet
                        ModuleKey.Sleep -> onOpenLogSleep
                        ModuleKey.Medication -> onOpenLogMedication
                        ModuleKey.Exercise -> null // Exercise has its own dedicated tab.
                    },
                )
            }
        }

        WeeklyProgressCard(state = state.weeklyProgress)

        // 本週計畫 / 報告 grouped into one card-surface action block so the two
        // weekly entry points read as a cohesive unit with generous touch targets.
        // No title — the two labelled buttons are self-describing, and a heading
        // here would duplicate the WeeklyProgressCard's "本週趨勢" directly above.
        StandardCard {
            OutlinedButton(
                onClick = onOpenWeeklyPlan,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.coach_view_weekly_plan))
            }
            OutlinedButton(
                onClick = onOpenWeeklyReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.coach_view_weekly_report))
            }
        }

        NarrationBlock(state = state.narration)
    }
}

/**
 * Section header for the Coach tab, mirroring the Today / 紀錄 day-section
 * headers: a full-width row with a bold title. Pure UI — no state.
 */
@Composable
private fun CoachSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.tight, top = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}
