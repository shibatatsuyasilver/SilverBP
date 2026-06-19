package com.silverbp.android.ui.strength

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.strength.LiveExercise
import com.silverbp.android.strength.SetLog
import com.silverbp.android.strength.StrengthRunState
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.HeroCard
import com.silverbp.android.ui.components.HeroForeground
import com.silverbp.android.ui.components.HeroLabel
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.PillShape
import kotlinx.coroutines.delay

@Composable
fun WorkoutSessionScreen(
    onFinished: () -> Unit,
    onClose: () -> Unit,
    vm: WorkoutSessionViewModel = viewModel(),
) {
    val live by vm.state.collectAsStateWithLifecycle()

    // System back is a deliberate exit that leaves the live session in the
    // store — no data is destroyed; the library's resume dialog offers re-entry.
    BackHandler { onClose() }

    val workout = live
    if (workout == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.strength_session_empty))
        }
        return
    }

    // A Finished-but-unsaved snapshot (user backed out of the summary, or
    // re-entered via the resume dialog) belongs on the summary screen — the
    // snapshot can no longer be edited here.
    if (workout.runState == StrengthRunState.Finished) {
        LaunchedEffect(Unit) { onFinished() }
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
            fontWeight = FontWeight.SemiBold,
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
            shape = RoundedCornerShape(AppSpacing.cardCorner),
            modifier = Modifier.fillMaxWidth(),
        )

        if (current.skipped) {
            Text(
                stringResource(R.string.strength_session_skipped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ExpressiveSecondaryButton(
                text = stringResource(R.string.strength_session_skip),
                onClick = { vm.skipExercise(current.exercise.id) },
                fillWidth = true,
            )
        }

        NavRow(
            canPrev = workout.currentIndex > 0,
            canNext = workout.currentIndex < workout.exercises.lastIndex,
            onPrev = { vm.setCurrentIndex(workout.currentIndex - 1) },
            onNext = { vm.setCurrentIndex(workout.currentIndex + 1) },
        )

        ExpressivePrimaryButton(
            // Marks the run Finished; the Finished forward above then
            // navigates to the summary — single navigation path.
            text = stringResource(R.string.strength_session_finish),
            onClick = { vm.finish() },
            icon = Icons.Filled.Done,
            fillWidth = true,
        )

        Spacer(Modifier.height(AppSpacing.itemGap))
    }
}

@Composable
private fun ProgressHeader(
    completedSets: Int,
    totalSets: Int,
    elapsedMillis: Long,
) {
    HeroCard {
        HeroLabel(
            text = stringResource(R.string.strength_session_progress, completedSets, totalSets),
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(PillShape)
                        .background(HeroForeground.copy(alpha = 0.18f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = HeroForeground,
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        ExerciseMath.formatDuration(elapsedMillis),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = HeroForeground,
                    )
                }
            },
        )
        LinearProgressIndicator(
            progress = { if (totalSets == 0) 0f else completedSets.toFloat() / totalSets },
            color = HeroForeground,
            trackColor = HeroForeground.copy(alpha = 0.24f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(PillShape),
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
        title = stringResource(exercise.exercise.bodyPart.labelRes),
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
            .clip(RoundedCornerShape(AppSpacing.cardCorner))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = AppSpacing.cardPadding, vertical = AppSpacing.itemGap + AppSpacing.tight)
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
            // Completed status chip; tap to undo (keeps the toggle callback).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f))
                    .clickable { onToggleComplete(set.setNumber, false) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.size(AppSpacing.tight + AppSpacing.tight))
                Text(
                    stringResource(R.string.strength_session_completed_set),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        } else {
            ExpressiveSecondaryButton(
                text = stringResource(R.string.strength_session_complete_set),
                onClick = { onToggleComplete(set.setNumber, true) },
            )
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
                shape = RoundedCornerShape(AppSpacing.cardCorner),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.strength_session_weight)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(AppSpacing.cardCorner),
                modifier = Modifier.weight(1f),
            )
        }
        ExpressivePrimaryButton(
            text = stringResource(R.string.strength_session_add_set),
            onClick = {
                val r = reps.toIntOrNull() ?: return@ExpressivePrimaryButton
                if (r <= 0) return@ExpressivePrimaryButton
                onAddSet(r, weight.toDoubleOrNull())
                reps = ""
                weight = ""
            },
            icon = Icons.Filled.Add,
            enabled = reps.toIntOrNull()?.let { it > 0 } == true,
            fillWidth = true,
        )
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
        ExpressiveSecondaryButton(
            text = stringResource(R.string.strength_session_prev),
            onClick = onPrev,
            enabled = canPrev,
            modifier = Modifier.weight(1f),
        )
        ExpressiveSecondaryButton(
            text = stringResource(R.string.strength_session_next),
            onClick = onNext,
            enabled = canNext,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun setSummary(set: SetLog): String {
    val w = set.weightKg
    return if (w != null) "%d × %.1f kg".format(set.reps, w)
    else ServiceLocator.context.getString(R.string.workout_set_reps, set.reps)
}
