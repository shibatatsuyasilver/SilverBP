package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.coach.CoachEngine
import com.silverbp.android.coach.WorkoutBpGate
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Reads the last 24 h of BP and asks the engine whether a workout may start.
 * Conservative by construction (see [CoachEngine.shouldAllowWorkout]): no data
 * yields [WorkoutBpGate.Caution], never a silent allow.
 */
suspend fun evaluateWorkoutBpGate(): WorkoutBpGate {
    val now = Instant.now()
    val from = now.minus(24, ChronoUnit.HOURS)
    val recent = ServiceLocator.bpRepository.observeRange(from, now).first()
    return CoachEngine.shouldAllowWorkout(recent, now)
}

/**
 * Small reusable pre-workout gate dialog. [gate] is the engine verdict:
 *  - [WorkoutBpGate.Block]   → warn; only a de-emphasised "仍要開始" can proceed.
 *  - [WorkoutBpGate.Caution] → surface the reason; a clear "開始" proceeds.
 *  - [WorkoutBpGate.Allow]   → never reaches here (caller starts directly).
 *
 * [onMeasure] lets the user go measure BP first; [onProceed] starts the session.
 */
@Composable
fun WorkoutBpGateDialog(
    gate: WorkoutBpGate,
    onProceed: () -> Unit,
    onMeasure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isBlock = gate is WorkoutBpGate.Block
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isBlock) R.string.bpworkout_gate_block_title
                    else R.string.bpworkout_gate_caution_title,
                ),
            )
        },
        text = { gate.reason?.let { Text(it) } },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMeasure) {
                    Text(stringResource(R.string.bpworkout_gate_measure))
                }
                if (isBlock) {
                    // De-emphasise "start anyway" for a crisis-level reading.
                    TextButton(onClick = onProceed) {
                        Text(stringResource(R.string.bpworkout_gate_start_anyway))
                    }
                } else {
                    OutlinedButton(onClick = onProceed) {
                        Text(stringResource(R.string.bpworkout_gate_start))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bpworkout_gate_cancel))
            }
        },
    )
}

/**
 * Post-workout BP affordance for the summary screens. When a recent reading
 * exists it is auto-linked on save (see the summary ViewModels) and this card
 * confirms the link; otherwise it offers a "量運動後血壓" button.
 */
@Composable
fun PostWorkoutBpCard(
    hasRecentPostBp: Boolean,
    onMeasureBp: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasRecentPostBp) {
                Text(
                    stringResource(R.string.bpworkout_post_linked),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                OutlinedButton(onClick = onMeasureBp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bpworkout_post_measure))
                }
            }
        }
    }
}
