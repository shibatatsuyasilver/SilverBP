package com.silverbp.android.ui.exercise

import android.annotation.SuppressLint
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.silverbp.android.R
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.exercise.RunState
import com.silverbp.android.exercise.SessionLive

@Composable
fun ExerciseSessionScreen(
    onFinished: () -> Unit,
    onClose: () -> Unit,
    vm: ExerciseSessionViewModel = viewModel(),
) {
    val live by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    KeepScreenOn()

    if (error != null) {
        // Tracking aborted (e.g. location permission revoked at start) — explain
        // why instead of silently bouncing back to the home screen.
        SessionErrorScreen(onClose = { vm.clearError(); onClose() })
        return
    }

    if (live == null) {
        // No active session — service may have failed to start. Bounce back.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            SessionMap(
                live = live!!,
                modifier = Modifier.fillMaxSize(),
            )
            GpsSignalBanner(
                live = live!!,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            )
        }

        SessionStats(live!!)

        SessionControls(
            isPaused = live!!.runState != RunState.Running,
            onPause = vm::pause,
            onResume = vm::resume,
            onStop = {
                if (vm.stop()) onFinished()
            },
        )
    }
}

@Composable
private fun SessionErrorScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.exercise_location_permission_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.exercise_location_denied),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/** Faint banner shown when no fresh GPS fix has arrived for a while mid-run. */
@Composable
private fun GpsSignalBanner(live: SessionLive, modifier: Modifier = Modifier) {
    val stale by produceState(
        initialValue = false,
        live.lastSampleAtMillis,
        live.runState,
    ) {
        while (true) {
            value = live.runState == RunState.Running &&
                (System.currentTimeMillis() - live.lastSampleAtMillis) > GPS_STALE_THRESHOLD_MS
            kotlinx.coroutines.delay(2_000L)
        }
    }
    if (!stale) return
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 4.dp,
    ) {
        Text(
            stringResource(R.string.exercise_gps_weak),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private const val GPS_STALE_THRESHOLD_MS = 15_000L

private const val FOLLOW_ZOOM = 17f

@SuppressLint("MissingPermission")
@Composable
private fun SessionMap(live: SessionLive, modifier: Modifier = Modifier) {
    val pathPoints = live.routePoints.map { LatLng(it.lat, it.lon) }
    val context = LocalContext.current
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            pathPoints.firstOrNull() ?: LatLng(25.0330, 121.5654), // Taipei 101 default
            FOLLOW_ZOOM,
        )
    }
    val liveLatest by rememberUpdatedState(live)
    var followCamera by rememberSaveable { mutableStateOf(true) }

    // Seed the camera with last-known location so the first frame centers on the
    // user instead of the Taipei 101 fallback while waiting for the first GPS sample.
    LaunchedEffect(Unit) {
        if (liveLatest.routePoints.isNotEmpty()) return@LaunchedEffect
        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null && liveLatest.routePoints.isEmpty()) {
                    cameraState.position = CameraPosition.fromLatLngZoom(
                        LatLng(loc.latitude, loc.longitude),
                        FOLLOW_ZOOM,
                    )
                }
            }
    }

    LaunchedEffect(pathPoints.size, followCamera) {
        if (!followCamera) return@LaunchedEffect
        val last = pathPoints.lastOrNull() ?: return@LaunchedEffect
        cameraState.animate(
            CameraUpdateFactory.newLatLngZoom(last, FOLLOW_ZOOM),
            durationMs = 600,
        )
    }

    LaunchedEffect(cameraState.isMoving) {
        if (cameraState.isMoving &&
            cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            followCamera = false
        }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(isMyLocationEnabled = true),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false,
            ),
        ) {
            if (pathPoints.size >= 2) {
                Polyline(
                    points = pathPoints,
                    color = colorForKind(live.kind),
                    width = 14f,
                )
            }
            pathPoints.firstOrNull()?.let { start ->
                Marker(state = MarkerState(position = start), title = "Start")
            }
            pathPoints.lastOrNull()?.let { current ->
                Marker(state = MarkerState(position = current), title = "Now")
            }
        }
        if (!followCamera) {
            SmallFloatingActionButton(
                onClick = { followCamera = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.exercise_recenter),
                )
            }
        }
    }
}

@Composable
private fun SessionStats(live: SessionLive) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatCell(
            value = ExerciseMath.formatDistance(live.accumulatedDistanceMeters),
            label = stringResource(R.string.exercise_distance),
        )
        StatCell(
            value = ExerciseMath.formatDuration(live.activeDurationMillis),
            label = stringResource(R.string.exercise_duration),
        )
        StatCell(
            value = ExerciseMath.formatPace(live.paceSecPerKm),
            label = stringResource(R.string.exercise_pace),
        )
        StatCell(
            value = (live.stepCount ?: 0).toString(),
            label = stringResource(R.string.exercise_steps),
        )
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionControls(
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = if (isPaused) onResume else onPause,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
            ),
        ) {
            Icon(if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (isPaused) R.string.exercise_resume else R.string.exercise_pause))
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Filled.Stop, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.exercise_stop))
        }
    }
}

/** Set FLAG_KEEP_SCREEN_ON while the session is in foreground. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
