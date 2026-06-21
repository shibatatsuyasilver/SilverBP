package com.silverbp.android.ui.exercise

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
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
import org.osmdroid.views.overlay.Overlay
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
            minZoomLevel = LIVE_MAP_MIN_ZOOM
            maxZoomLevel = LIVE_MAP_MAX_ZOOM
            controller.setZoom(LIVE_MAP_FOLLOW_ZOOM)
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
                    mv.overlays.add(RouteMarkerOverlay(start, accentArgb, MarkerKind.Start))
                }
                points.lastOrNull()?.let { now ->
                    mv.overlays.add(RouteMarkerOverlay(now, accentArgb, MarkerKind.Current))
                    if (mv.zoomLevelDouble < LIVE_MAP_FOLLOW_ZOOM - 0.1) {
                        mv.controller.setZoom(LIVE_MAP_FOLLOW_ZOOM)
                    }
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

private enum class MarkerKind { Start, Current }

private class RouteMarkerOverlay(
    private val position: GeoPoint,
    private val accentArgb: Int,
    private val kind: MarkerKind,
) : Overlay() {
    private val point = Point()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        mapView.projection.toPixels(position, point)
        val density = mapView.resources.displayMetrics.density
        val x = point.x.toFloat()
        val y = point.y.toFloat()

        if (kind == MarkerKind.Current) {
            fillPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(x, y, 12f * density, fillPaint)
            strokePaint.color = 0x66000000
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(x, y, 12f * density, strokePaint)
            fillPaint.color = accentArgb
            canvas.drawCircle(x, y, 7f * density, fillPaint)
        } else {
            fillPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(x, y, 8f * density, fillPaint)
            strokePaint.color = accentArgb
            strokePaint.strokeWidth = 3f * density
            canvas.drawCircle(x, y, 8f * density, strokePaint)
        }
    }
}

private const val LIVE_MAP_FOLLOW_ZOOM = 19.2
private const val LIVE_MAP_MIN_ZOOM = 16.0
private const val LIVE_MAP_MAX_ZOOM = 20.0
