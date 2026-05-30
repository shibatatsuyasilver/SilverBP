package com.silverbp.android.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.silverbp.android.ui.coach.components.ModuleCard
import com.silverbp.android.ui.coach.components.NarrationBlock
import com.silverbp.android.ui.coach.components.TodayTaskCard
import com.silverbp.android.ui.coach.components.WeeklyProgressCard

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
                onStartExercise = onStartExercise,
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TodayTaskCard(task = state.todayTask, onStartExercise = onStartExercise)

        Text(
            stringResource(R.string.coach_modules_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
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

        WeeklyProgressCard(state = state.weeklyProgress)

        OutlinedButton(
            onClick = onOpenWeeklyPlan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.coach_view_weekly_plan))
        }

        OutlinedButton(
            onClick = onOpenWeeklyReport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.coach_view_weekly_report))
        }

        NarrationBlock(state = state.narration)
    }
}
