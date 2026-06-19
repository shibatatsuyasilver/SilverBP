package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.HeroShape
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            AppTopBar(
                title = stringResource(R.string.exercise_summary_title),
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
        // Activity-type identity colour (NOT a MetricAccent) — keeps each kind's hue.
        val accent = colorForKind(s.kind)

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            Text(
                fmt.format(s.startedAt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.points.size >= 2) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(HeroShape),
                ) {
                    StaticRouteMap(session = s, points = state.points, modifier = Modifier.fillMaxSize())
                }
            }

            // Stat grid — distance / duration / pace / steps, mirroring the
            // restyled Insights stat cards. Activity-kind accent identifies the kind.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                StatCell(
                    value = ExerciseMath.formatDistance(s.distanceMeters),
                    label = stringResource(R.string.exercise_distance),
                    accent = accent,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StatCell(
                    value = ExerciseMath.formatDuration(s.activeDurationMillis),
                    label = stringResource(R.string.exercise_duration),
                    accent = accent,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                StatCell(
                    value = ExerciseMath.formatPace(s.averagePaceSecPerKm),
                    label = stringResource(R.string.exercise_avg_pace),
                    accent = accent,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                // Cycling has no step sensor; hide the steps slot so it doesn't
                // read as a misleading "0 steps". A spacer keeps pace left-aligned.
                if (s.kind != ActivityKind.Cycling) {
                    StatCell(
                        value = s.stepCount?.toString() ?: "—",
                        label = stringResource(R.string.exercise_steps),
                        accent = accent,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            if (s.note.isNotBlank()) {
                StandardCard(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
                ) {
                    Text(
                        s.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

@Composable
private fun StatCell(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier,
        accent = accent,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
