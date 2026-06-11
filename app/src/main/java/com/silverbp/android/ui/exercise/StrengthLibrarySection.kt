package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.coach.WorkoutBpGate
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.strength.ExerciseCatalogItem
import com.silverbp.android.ui.strength.LibraryScreen
import com.silverbp.android.ui.strength.StrengthExerciseDetailScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 動作庫 section of the training hub. Hosts [LibraryScreen] and, when an item is
 * tapped, [StrengthExerciseDetailScreen] in a lightweight in-screen back stack
 * (a single nullable selected-id state — no NavHost needed for two levels).
 *
 * Starting a workout resolves the chosen catalog item, seeds the live store with
 * a single-exercise workout, then defers navigation to the root strength session
 * route via [onStartStrengthSession]. Multi-exercise routine building is a noted
 * FOLLOW-UP; single-exercise start is intentional for now.
 */
@Composable
fun StrengthLibrarySection(
    onStartStrengthSession: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Resolved item + BP-gate verdict awaiting confirmation (CAUTION/BLOCK only).
    var pendingStrength by remember { mutableStateOf<Pair<ExerciseCatalogItem, WorkoutBpGate>?>(null) }
    // Item whose start collided with a live (Running / Finished-but-unsaved)
    // session in the store — resolved via [ResumeWorkoutDialog].
    var pendingResume by remember { mutableStateOf<ExerciseCatalogItem?>(null) }

    fun startStrength(item: ExerciseCatalogItem) {
        // A live session must never be silently replaced (its logged sets would
        // be wiped) — ask the user to resume it or discard it explicitly.
        if (ServiceLocator.strengthWorkoutLiveStore.flow.value != null) {
            pendingResume = item
            return
        }
        ServiceLocator.strengthWorkoutLiveStore.start(listOf(item))
        onStartStrengthSession()
    }

    val current = selectedId
    if (current == null) {
        LibraryScreen(
            onOpenDetail = { id -> selectedId = id },
        )
    } else {
        StrengthExerciseDetailScreen(
            exerciseId = current,
            onBack = { selectedId = null },
            onStartWorkout = { id ->
                scope.launch {
                    val item = ServiceLocator.exerciseLibraryRepository
                        .observeAll().first()
                        .firstOrNull { it.id == id } ?: return@launch
                    // BP gate before starting: ALLOW starts directly; otherwise
                    // confirm via the dialog (or measure first).
                    when (val gate = evaluateWorkoutBpGate()) {
                        WorkoutBpGate.Allow -> startStrength(item)
                        else -> pendingStrength = item to gate
                    }
                }
            },
        )
    }

    pendingStrength?.let { (item, gate) ->
        WorkoutBpGateDialog(
            gate = gate,
            onProceed = {
                pendingStrength = null
                startStrength(item)
            },
            onMeasure = { pendingStrength = null },
            onDismiss = { pendingStrength = null },
        )
    }

    pendingResume?.let { item ->
        ResumeWorkoutDialog(
            onResume = {
                pendingResume = null
                // Re-enter without restarting; the session route forwards a
                // Finished-but-unsaved snapshot to the summary by itself.
                onStartStrengthSession()
            },
            onDiscardAndStart = {
                pendingResume = null
                ServiceLocator.strengthWorkoutLiveStore.clear()
                ServiceLocator.strengthWorkoutLiveStore.start(listOf(item))
                onStartStrengthSession()
            },
            onDismiss = { pendingResume = null },
        )
    }
}

/**
 * Confirm dialog shown when starting a workout while another live session still
 * exists. Resuming navigates back into the live session untouched; discarding
 * is the only path that clears logged sets, and it is always explicit.
 */
@Composable
private fun ResumeWorkoutDialog(
    onResume: () -> Unit,
    onDiscardAndStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.strength_resume_title)) },
        text = { Text(stringResource(R.string.strength_resume_message)) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) {
                    Text(stringResource(R.string.strength_resume_continue))
                }
                OutlinedButton(onClick = onDiscardAndStart) {
                    Text(stringResource(R.string.strength_resume_discard))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.strength_resume_cancel))
            }
        },
    )
}
