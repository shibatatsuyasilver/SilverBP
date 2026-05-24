package com.silverbp.android.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.silverbp.android.R
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.RoutePoint
import com.silverbp.android.ui.exercise.components.StatsCard

@Composable
fun ExerciseSummaryScreen(
    onSaved: () -> Unit,
    onDiscard: () -> Unit,
    vm: ExerciseSummaryViewModel = viewModel(),
) {
    val saving by vm.saving.collectAsStateWithLifecycle()
    val session = vm.session
    val points = vm.points
    var note by remember { mutableStateOf(session?.note ?: "") }

    if (session == null) {
        // No snapshot — bounce back.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.exercise_history_empty))
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
            stringResource(R.string.exercise_summary_title),
            style = MaterialTheme.typography.titleLarge,
        )

        if (points.size >= 2) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                StaticRouteMap(session, points, modifier = Modifier.fillMaxSize())
            }
        }

        StatsCard(
            distance = ExerciseMath.formatDistance(session.distanceMeters),
            distanceLabel = stringResource(R.string.exercise_distance),
            duration = ExerciseMath.formatDuration(
                java.time.Duration.between(session.startedAt, session.endedAt).toMillis()
            ),
            durationLabel = stringResource(R.string.exercise_duration),
            pace = ExerciseMath.formatPace(session.averagePaceSecPerKm),
            paceLabel = stringResource(R.string.exercise_avg_pace),
            steps = session.stepCount?.toString(),
            stepsLabel = stringResource(R.string.exercise_steps),
        )

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.exercise_note_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )

        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { vm.discard(onDiscard) },
                modifier = Modifier.weight(1f),
                enabled = !saving,
            ) { Text(stringResource(R.string.exercise_discard)) }
            Button(
                onClick = { vm.save(note, onSaved) },
                modifier = Modifier.weight(1f),
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorForKind(session.kind),
                ),
            ) { Text(stringResource(R.string.exercise_save)) }
        }
    }
}

@Composable
internal fun StaticRouteMap(
    session: ExerciseSession,
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
) {
    val latLngs = points.map { LatLng(it.lat, it.lon) }
    val bounds = remember(latLngs) {
        if (latLngs.isEmpty()) null
        else LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
    }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            latLngs.firstOrNull() ?: LatLng(25.0330, 121.5654),
            14f,
        )
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            scrollGesturesEnabled = false,
            zoomGesturesEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = false,
        ),
        onMapLoaded = {
            bounds?.let {
                cameraState.move(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(it, 80)
                )
            }
        },
    ) {
        if (latLngs.size >= 2) {
            Polyline(
                points = latLngs,
                color = colorForKind(session.kind),
                width = 12f,
            )
        }
        latLngs.firstOrNull()?.let {
            Marker(state = MarkerState(position = it), title = "Start")
        }
        latLngs.lastOrNull()?.let {
            Marker(state = MarkerState(position = it), title = "End")
        }
    }
}
