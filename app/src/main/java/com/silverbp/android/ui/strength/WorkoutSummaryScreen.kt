package com.silverbp.android.ui.strength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.strength.DifficultyFeedback
import com.silverbp.android.ui.components.SectionCard
import com.silverbp.android.ui.exercise.PostWorkoutBpCard

@Composable
fun WorkoutSummaryScreen(
    onSaved: () -> Unit,
    onDiscard: () -> Unit,
    onMeasureBp: () -> Unit = {},
    vm: WorkoutSummaryViewModel = viewModel(),
) {
    val saving by vm.saving.collectAsStateWithLifecycle()
    val hasRecentPostBp by vm.hasRecentPostBp.collectAsStateWithLifecycle()
    val live = vm.live
    var selected by remember { mutableStateOf<DifficultyFeedback?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refreshHasRecentPostBp()
        onPauseOrDispose { }
    }

    if (live == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.strength_session_empty))
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.strength_summary_title),
            style = MaterialTheme.typography.titleLarge,
        )

        SectionCard(title = stringResource(R.string.strength_summary_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.strength_summary_total_sets, live.completedSets),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(
                        R.string.strength_summary_duration,
                        ExerciseMath.formatDuration(System.currentTimeMillis() - live.startedAtMillis),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Text(
            stringResource(R.string.strength_summary_feeling),
            style = MaterialTheme.typography.titleMedium,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DifficultyOption(
                label = stringResource(R.string.strength_summary_too_easy),
                selected = selected == DifficultyFeedback.TooEasy,
                onClick = { selected = DifficultyFeedback.TooEasy },
                modifier = Modifier.weight(1f),
            )
            DifficultyOption(
                label = stringResource(R.string.strength_summary_just_right),
                selected = selected == DifficultyFeedback.JustRight,
                onClick = { selected = DifficultyFeedback.JustRight },
                modifier = Modifier.weight(1f),
            )
            DifficultyOption(
                label = stringResource(R.string.strength_summary_too_hard),
                selected = selected == DifficultyFeedback.TooHard,
                onClick = { selected = DifficultyFeedback.TooHard },
                modifier = Modifier.weight(1f),
            )
        }

        PostWorkoutBpCard(
            hasRecentPostBp = hasRecentPostBp,
            onMeasureBp = onMeasureBp,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { vm.discard(onDiscard) },
                modifier = Modifier.weight(1f),
                enabled = !saving,
            ) { Text(stringResource(R.string.strength_summary_discard)) }
            Button(
                onClick = { selected?.let { vm.save(it, onSaved) } },
                modifier = Modifier.weight(1f),
                enabled = !saving && selected != null,
            ) { Text(stringResource(R.string.strength_summary_save)) }
        }
    }
}

@Composable
private fun DifficultyOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(56.dp),
        ) { Text(label) }
    }
}
