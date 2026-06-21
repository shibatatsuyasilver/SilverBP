package com.silverbp.android.ui.exercise

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.exercise.RouteProjection
import com.silverbp.android.exercise.SessionLive

/**
 * Offline, no-GL fallback for the live tracking map. Draws the route polyline +
 * start/current markers on a plain Compose [Canvas] using the same
 * equirectangular [RouteProjection] the notification's RouteBitmapRenderer
 * uses. Mirrors iOS BPExercise's MapSnapshotRenderer (we draw the polyline
 * ourselves) so the session screen still shows the route on devices where the
 * Google Maps GL renderer paints black — the new-renderer bug observed on
 * vivo / Android 16 / Adreno (no auth failure; the map's TextureView just
 * presents an empty buffer). No street tiles, but it can never fail to draw.
 */
@Composable
fun RouteCanvasMap(live: SessionLive, modifier: Modifier = Modifier) {
    val accent = colorForKind(live.kind)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val startColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ringColor = MaterialTheme.colorScheme.surface
    val points = live.routePoints

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(color = bg)
            // Faint grid so the surface reads as a map canvas, not a blank (or
            // worse, a "broken black map") void while GPS is still acquiring.
            val step = 48.dp.toPx()
            var gx = step
            while (gx < size.width) {
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                gx += step
            }
            var gy = step
            while (gy < size.height) {
                drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                gy += step
            }
            when {
                points.size < 2 -> if (points.size == 1) drawCurrentMarker(center, accent, ringColor)
                else -> {
                    val padPx = 24.dp.toPx()
                    val rect = RouteProjection.PixelRect(
                        left = padPx,
                        top = padPx,
                        width = (size.width - 2 * padPx).coerceAtLeast(1f),
                        height = (size.height - 2 * padPx).coerceAtLeast(1f),
                    )
                    val latLons = points.map { RouteProjection.LatLon(it.lat, it.lon) }
                    val box = RouteProjection.inflate(RouteProjection.bbox(latLons)!!)
                    val coords = RouteProjection.project(latLons, box, rect)

                    val path = Path().apply {
                        moveTo(coords[0], coords[1])
                        var i = 2
                        while (i < coords.size) {
                            lineTo(coords[i], coords[i + 1])
                            i += 2
                        }
                    }
                    drawPath(
                        path = path,
                        color = accent,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawCircle(color = startColor, radius = 5.dp.toPx(), center = Offset(coords[0], coords[1]))
                    val last = coords.size - 2
                    drawCurrentMarker(Offset(coords[last], coords[last + 1]), accent, ringColor)
                }
            }
        }
        if (points.isEmpty()) {
            Text(
                text = stringResource(R.string.exercise_waiting_gps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Filled accent dot with a contrasting ring — the user's current position. */
private fun DrawScope.drawCurrentMarker(center: Offset, color: Color, ring: Color) {
    val r = 7.dp.toPx()
    drawCircle(color = color, radius = r, center = center)
    drawCircle(color = ring, radius = r, center = center, style = Stroke(width = 2.5.dp.toPx()))
}
