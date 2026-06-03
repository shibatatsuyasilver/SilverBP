package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.ui.exercise.components.StatsCard
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val vm: ExerciseDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ExerciseDetailViewModel(sessionId) }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exercise_summary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    state.session?.let {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        val s = state.session
        if (s == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.exercise_history_empty)) }
            return@Scaffold
        }

        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN).withZone(zone)

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(fmt.format(s.startedAt), style = MaterialTheme.typography.titleMedium)

            if (state.points.size >= 2) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    StaticRouteMap(session = s, points = state.points, modifier = Modifier.fillMaxSize())
                }
            }

            StatsCard(
                distance = ExerciseMath.formatDistance(s.distanceMeters),
                distanceLabel = stringResource(R.string.exercise_distance),
                duration = ExerciseMath.formatDuration(s.activeDurationMillis),
                durationLabel = stringResource(R.string.exercise_duration),
                pace = ExerciseMath.formatPace(s.averagePaceSecPerKm),
                paceLabel = stringResource(R.string.exercise_avg_pace),
                steps = s.stepCount?.toString(),
                stepsLabel = stringResource(R.string.exercise_steps),
                showSteps = s.kind != ActivityKind.Cycling,
            )

            if (s.note.isNotBlank()) {
                Text(s.note, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.exercise_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        vm.delete(onDeleted)
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
