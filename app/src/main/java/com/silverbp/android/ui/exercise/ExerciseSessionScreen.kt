package com.silverbp.android.ui.exercise

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.exercise.RunState
import com.silverbp.android.exercise.SessionLive
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.PillShape

@Composable
fun ExerciseSessionScreen(
    onFinished: () -> Unit,
    onClose: () -> Unit,
    vm: ExerciseSessionViewModel = viewModel(),
) {
    val live by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val useCanvas by vm.liveMapUseCanvas.collectAsStateWithLifecycle()
    KeepScreenOn()

    if (error != null) {
        // Tracking aborted (e.g. location permission revoked at start) — explain
        // why instead of silently bouncing back to the home screen.
        SessionErrorScreen(onClose = { vm.clearError(); onClose() })
        return
    }

    if (live == null) {
        // No live session in memory. This is the notification deep-link landing
        // after a process kill cleared the singleton store — rehydrate from the
        // on-disk checkpoint and re-attach the service before bouncing. Only a
        // genuinely empty recovery (no checkpoint) falls through to onClose, so
        // tapping the ongoing-run notification reliably returns to the live map.
        var attempted by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!attempted) {
                attempted = true
                vm.attemptRestoreFromCheckpoint(onNothing = onClose)
            }
        }
        SessionLoading()
        return
    }

    val current = live!!
    Box(modifier = Modifier.fillMaxSize()) {
        SessionMap(
            live = current,
            useCanvas = useCanvas,
            onToggleCanvas = vm::setLiveMapUseCanvas,
            modifier = Modifier.fillMaxSize(),
        )

        SessionStats(
            live = current,
            accent = colorForKind(current.kind),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        GpsSignalBanner(
            live = current,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 92.dp),
        )
        SessionControls(
            isPaused = current.runState != RunState.Running,
            onPause = vm::pause,
            onResume = vm::resume,
            onStop = {
                if (vm.stop()) onFinished()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
        )
    }
}

@Composable
private fun SessionErrorScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AppSpacing.screenH),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StandardCard {
            Text(
                stringResource(R.string.exercise_location_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.exercise_location_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpressiveSecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = onClose,
                fillWidth = true,
            )
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

@Composable
private fun SessionMap(
    live: SessionLive,
    useCanvas: Boolean,
    onToggleCanvas: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live street map via Google Maps (maps-compose) — the same stack as the
    // post-workout summary map ([StaticRouteMap]). The toggle switches to a
    // GL-free, tiles-free route view ([RouteCanvasMap]): the safety net for the
    // new-renderer black-screen bug on some GPUs/ROMs (vivo / Android 16 /
    // Adreno) and for when there's no data signal. The choice is persisted
    // per-device by [ExerciseSessionViewModel], so a user who hits the black map
    // flips once and stays on the route view across sessions.
    Box(modifier) {
        if (useCanvas) {
            RouteCanvasMap(live, Modifier.fillMaxSize())
        } else {
            GoogleLiveRouteMap(live, Modifier.fillMaxSize())
        }
        SmallFloatingActionButton(
            onClick = { onToggleCanvas(!useCanvas) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 104.dp),
        ) {
            Icon(
                imageVector = if (useCanvas) Icons.Filled.Map else Icons.Filled.Timeline,
                contentDescription = stringResource(
                    if (useCanvas) R.string.exercise_show_map else R.string.exercise_show_route,
                ),
            )
        }
        if (!useCanvas && live.routePoints.isEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = MaterialTheme.shapes.small,
                tonalElevation = 4.dp,
            ) {
                Text(
                    text = stringResource(R.string.exercise_waiting_gps),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Centered spinner while we rehydrate an orphaned session from its checkpoint. */
@Composable
private fun SessionLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SessionStats(live: SessionLive, accent: Color, modifier: Modifier = Modifier) {
    // One floating overlay over the full-screen map. This replaces the old lower
    // card section so the session reads as a single live map, not stacked screens.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(42.dp)
                    .background(accent, PillShape),
            )
            StatCell(
                value = ExerciseMath.formatDistance(live.accumulatedDistanceMeters),
                label = stringResource(R.string.exercise_distance),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = ExerciseMath.formatDuration(live.activeDurationMillis),
                label = stringResource(R.string.exercise_duration),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = ExerciseMath.formatPace(live.paceSecPerKm),
                label = stringResource(R.string.exercise_pace),
                modifier = Modifier.weight(1f),
            )
            // Cycling has no step sensor; omit the steps cell so it doesn't show 0.
            if (live.kind != ActivityKind.Cycling) {
                StatCell(
                    value = (live.stepCount ?: 0).toString(),
                    label = stringResource(R.string.exercise_steps),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun SessionControls(
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        // Pause/resume — Expressive lime CTA with the signature press shape-morph.
        ExpressiveSecondaryButton(
            text = stringResource(if (isPaused) R.string.exercise_resume else R.string.exercise_pause),
            onClick = if (isPaused) onResume else onPause,
            icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            modifier = Modifier.weight(1f),
            fillWidth = true,
        )
        // Stop — destructive, so it keeps the error role (not an Expressive CTA
        // colour). Restyled to the same 56dp Expressive pill shape + tokens.
        Button(
            onClick = onStop,
            shape = PillShape,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Filled.Stop, null)
            Spacer(Modifier.size(AppSpacing.itemGap))
            Text(
                stringResource(R.string.exercise_stop),
                style = MaterialTheme.typography.labelLarge,
            )
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
