package com.silverbp.android.ui.strength

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.strength.LiveExercise
import com.silverbp.android.strength.SetLog
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.delay

@Composable
fun WorkoutSessionScreen(
    onFinished: () -> Unit,
    vm: WorkoutSessionViewModel = viewModel(),
) {
    val live by vm.state.collectAsStateWithLifecycle()

    val workout = live
    if (workout == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.strength_session_empty))
        }
        return
    }

    val current = workout.currentExercise
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.strength_session_empty))
        }
        return
    }

    var elapsedMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(workout.startedAtMillis) {
        while (true) {
            elapsedMillis = System.currentTimeMillis() - workout.startedAtMillis
            delay(1000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        ProgressHeader(
            completedSets = workout.completedSets,
            totalSets = workout.totalSets,
            elapsedMillis = elapsedMillis,
        )

        Text(
            current.exercise.name,
            style = MaterialTheme.typography.titleLarge,
        )

        SetsCard(
            exercise = current,
            onAddSet = { reps, weight -> vm.addSet(current.exercise.id, reps, weight) },
            onToggleComplete = { setNumber, completed ->
                vm.markSetComplete(current.exercise.id, setNumber, completed)
            },
        )

        OutlinedTextField(
            value = workout.note,
            onValueChange = vm::addNote,
            label = { Text(stringResource(R.string.strength_session_notes_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (current.skipped) {
            Text(
                stringResource(R.string.strength_session_skipped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(
                onClick = { vm.skipExercise(current.exercise.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.strength_session_skip)) }
        }

        NavRow(
            canPrev = workout.currentIndex > 0,
            canNext = workout.currentIndex < workout.exercises.lastIndex,
            onPrev = { vm.setCurrentIndex(workout.currentIndex - 1) },
            onNext = { vm.setCurrentIndex(workout.currentIndex + 1) },
        )

        Button(
            onClick = {
                vm.finish()
                onFinished()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(AppSpacing.cardCorner),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                stringResource(R.string.strength_session_finish),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        Spacer(Modifier.height(AppSpacing.itemGap))
    }
}

@Composable
private fun ProgressHeader(
    completedSets: Int,
    totalSets: Int,
    elapsedMillis: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.strength_session_progress, completedSets, totalSets),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                ExerciseMath.formatDuration(elapsedMillis),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (totalSets == 0) 0f else completedSets.toFloat() / totalSets },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(AppSpacing.tight)),
        )
    }
}

@Composable
private fun SetsCard(
    exercise: LiveExercise,
    onAddSet: (reps: Int, weightKg: Double?) -> Unit,
    onToggleComplete: (setNumber: Int, completed: Boolean) -> Unit,
) {
    StandardCard(
        title = exercise.exercise.bodyPart.labelZh,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        exercise.sets.forEach { set ->
            SetRow(set = set, onToggleComplete = onToggleComplete)
        }
        SetInput(onAddSet = onAddSet)
    }
}

@Composable
private fun SetRow(
    set: SetLog,
    onToggleComplete: (setNumber: Int, completed: Boolean) -> Unit,
) {
    val rowModifier = if (set.isCompleted) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.tight * 3))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = AppSpacing.itemGap, vertical = AppSpacing.tight)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                stringResource(R.string.strength_session_set_number, set.setNumber),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (set.isCompleted) FontWeight.SemiBold else FontWeight.Normal,
                color = if (set.isCompleted) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                setSummary(set),
                style = MaterialTheme.typography.bodySmall,
                color = if (set.isCompleted) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (set.isCompleted) {
            TextButton(
                onClick = { onToggleComplete(set.setNumber, false) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.size(AppSpacing.tight))
                Text(stringResource(R.string.strength_session_completed_set))
            }
        } else {
            Button(
                onClick = { onToggleComplete(set.setNumber, true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) { Text(stringResource(R.string.strength_session_complete_set)) }
        }
    }
}

@Composable
private fun SetInput(onAddSet: (reps: Int, weightKg: Double?) -> Unit) {
    var reps by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = reps,
                onValueChange = { reps = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.strength_session_reps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.strength_session_weight)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = {
                val r = reps.toIntOrNull() ?: return@Button
                if (r <= 0) return@Button
                onAddSet(r, weight.toDoubleOrNull())
                reps = ""
                weight = ""
            },
            enabled = reps.toIntOrNull()?.let { it > 0 } == true,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.strength_session_add_set)) }
    }
}

@Composable
private fun NavRow(
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        OutlinedButton(
            onClick = onPrev,
            enabled = canPrev,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.strength_session_prev)) }
        OutlinedButton(
            onClick = onNext,
            enabled = canNext,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.strength_session_next)) }
    }
}

private fun setSummary(set: SetLog): String {
    val w = set.weightKg
    return if (w != null) "%d × %.1f kg".format(set.reps, w) else "%d 次".format(set.reps)
}
