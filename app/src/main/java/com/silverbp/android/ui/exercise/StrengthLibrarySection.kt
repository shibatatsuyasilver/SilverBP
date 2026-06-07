package com.silverbp.android.ui.exercise

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    fun startStrength(item: ExerciseCatalogItem) {
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
}
