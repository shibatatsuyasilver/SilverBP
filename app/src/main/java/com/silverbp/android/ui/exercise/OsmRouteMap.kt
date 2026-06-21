package com.silverbp.android.ui.exercise

import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.silverbp.android.exercise.SessionLive
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Live tracking map rendered with osmdroid (OpenStreetMap). Draws real street
 * tiles with standard Android 2D rendering — NOT Google Maps' GL renderer — so
 * it shows streets on every device and never hits the new-renderer black-screen
 * bug that paints the GoogleMap black on some GPUs/ROMs (vivo / Android 16 /
 * Adreno). Mirrors iOS BPExercise's RouteMapView: street basemap + route
 * polyline + start/current markers, camera following the latest fix.
 */
@Composable
fun OsmRouteMap(live: SessionLive, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val accentArgb = colorForKind(live.kind).toArgb()
    val points = live.routePoints.map { GeoPoint(it.lat, it.lon) }

    val mapView = remember {
        // osmdroid's OSM tile policy requires a unique user-agent before any
        // fetch; keep the tile cache in app-internal storage so no storage
        // permission is needed on API 29+.
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = context.packageName
        val base = File(context.cacheDir, "osmdroid").apply { mkdirs() }
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        // Build the tile provider WITHOUT osmdroid's NetworkAvailabliltyCheck.
        // That check uses the deprecated ConnectivityManager.getActiveNetworkInfo(),
        // which reports "offline" on Android 16 / vivo, so osmdroid silently skips
        // every tile download (it initializes and sizes its cache, but fetches
        // nothing). In osmdroid 6.1.20 this 5-arg constructor is the public
        // entry point that lets us leave the check null, so the downloader fetches.
        val provider = MapTileProviderBasic(
            SimpleRegisterReceiver(context),
            null,
            TileSourceFactory.MAPNIK,
            context,
            null,
        )
        MapView(context, provider).apply {
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(17.0)
            // Taipei 101 default until the first GPS fix recenters the camera.
            controller.setCenter(GeoPoint(25.0330, 121.5654))
        }
    }

    DisposableEffect(lifecycleOwner) {
        // Start the tile-request loop now: navigating to this screen while the
        // activity is already RESUMED won't re-fire ON_RESUME, so without this
        // osmdroid never begins downloading tiles (map stays blank).
        mapView.onResume()
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.overlays.clear()
                if (points.size >= 2) {
                    mv.overlays.add(
                        Polyline(mv).apply {
                            setPoints(points)
                            outlinePaint.color = accentArgb
                            outlinePaint.strokeWidth = 14f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                    )
                }
                points.firstOrNull()?.let { start ->
                    mv.overlays.add(
                        Marker(mv).apply {
                            position = start
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "起點"
                        }
                    )
                }
                points.lastOrNull()?.let { now ->
                    mv.overlays.add(
                        Marker(mv).apply {
                            position = now
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "目前位置"
                        }
                    )
                    mv.controller.animateTo(now)
                }
                mv.invalidate()
            },
        )
        // OpenStreetMap tile usage requires visible attribution.
        Text(
            text = "© OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 6.dp),
        )
    }
}
