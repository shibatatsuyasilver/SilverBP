package com.silverbp.android.ui.exercise

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.silverbp.android.exercise.SessionLive

/**
 * Live tracking map rendered with Google Maps (maps-compose). Mirrors the
 * post-workout [StaticRouteMap] (same GoogleMap + Polyline + Marker stack) but
 * the camera *follows* the latest GPS fix instead of fitting the whole route.
 *
 * Replaces the osmdroid `OsmRouteMap`. The safety net for the new-renderer
 * black-screen bug on some GPUs/ROMs (vivo / Android 16 / Adreno) is not in this
 * composable: [SessionMap] offers a one-tap toggle to the GL-free
 * [RouteCanvasMap], and that choice is persisted per-device by
 * [ExerciseSessionViewModel]. The renderer is pinned to LEGACY in
 * `SilverBpApplication` so streets render on the affected devices today.
 *
 * Recomposition is driven by the parent collecting the [SessionLive] StateFlow,
 * so `routePoints` grows on each GPS fix and the camera-follow effect re-runs —
 * no manual MapView lifecycle handling is needed (maps-compose manages it).
 */
@Composable
fun GoogleLiveRouteMap(live: SessionLive, modifier: Modifier = Modifier) {
    val accent = colorForKind(live.kind)
    val latLngs = live.routePoints.map { LatLng(it.lat, it.lon) }
    val current = latLngs.lastOrNull()

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(current ?: TAIPEI_101, LIVE_FOLLOW_ZOOM)
    }

    // Follow the latest fix. The first fix snaps (move) so the map jumps to the
    // user the instant GPS acquires; subsequent fixes animate for a smooth
    // trail. `centered` is remembered state so the move-vs-animate decision
    // survives recomposition.
    val centered = remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        val now = current ?: return@LaunchedEffect
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.fromLatLngZoom(now, LIVE_FOLLOW_ZOOM),
        )
        if (centered.value) {
            cameraState.animate(update, durationMs = 600)
        } else {
            cameraState.move(update)
            centered.value = true
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        uiSettings = MapUiSettings(
            // Live map stays interactive (pan/zoom); the next GPS fix re-centers.
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = false,
        ),
    ) {
        if (latLngs.size >= 2) {
            Polyline(
                points = latLngs,
                color = accent,
                width = 12f,
            )
        }
        latLngs.firstOrNull()?.let {
            Marker(state = MarkerState(position = it), title = "Start")
        }
        current?.let {
            Marker(state = MarkerState(position = it), title = "Current")
        }
    }
}

// Taipei 101 default until the first GPS fix recenters the camera (same as the
// old osmdroid map). osmdroid framed at z19 @256px tiles; Google's higher-DPI
// tiles read about the same at z18 — tune on-device if needed.
private val TAIPEI_101 = LatLng(25.0330, 121.5654)
private const val LIVE_FOLLOW_ZOOM = 18f
