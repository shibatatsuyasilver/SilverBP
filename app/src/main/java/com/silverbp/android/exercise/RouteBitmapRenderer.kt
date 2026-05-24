package com.silverbp.android.exercise

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Offline (no Maps SDK, no network) renderer that turns a list of GPS
 * [RoutePoint]s into a bitmap suitable for use as a notification big-picture
 * or large-icon. Pure [Canvas]/[Paint] drawing — runs in milliseconds and
 * works on a device with no internet or no Play Services.
 *
 * The bitmap background is opaque so it remains legible behind dark lock
 * screen wallpapers.
 */
internal object RouteBitmapRenderer {

    private const val BG_COLOR = 0xFFF3F4F6.toInt()      // slate-50, opaque
    private const val START_DOT_COLOR = 0xFF6B7280.toInt()  // gray-500
    private const val CURRENT_RING_COLOR = Color.WHITE
    private const val WALK_STROKE_COLOR = 0xFF2E7D32.toInt() // green-800
    private const val RUN_STROKE_COLOR = 0xFFD32F2F.toInt()  // red-700
    private const val PAUSED_STROKE_ALPHA = 0x80              // 50 %

    private const val STROKE_WIDTH_DP = 4f
    private const val START_RADIUS_DP = 5f
    private const val CURRENT_RADIUS_DP = 7f
    private const val CURRENT_RING_WIDTH_DP = 2.5f
    private const val PADDING_DP = 12f

    data class Params(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val kind: ActivityKind,
        val runState: RunState,
    )

    /** Big route preview (notification big picture). */
    fun render(points: List<RoutePoint>, params: Params): Bitmap =
        renderInternal(points, params, drawStartMarker = true)

    /**
     * Small square preview for [androidx.core.app.NotificationCompat.Builder.setLargeIcon]
     * and the Live Updates progress tracker icon. Omits the start marker so the
     * route stays legible at low resolution.
     */
    fun renderThumbnail(points: List<RoutePoint>, sizePx: Int, density: Float, kind: ActivityKind, runState: RunState): Bitmap {
        val params = Params(
            widthPx = sizePx,
            heightPx = sizePx,
            density = density,
            kind = kind,
            runState = runState,
        )
        return renderInternal(points, params, drawStartMarker = false)
    }

    private fun renderInternal(
        points: List<RoutePoint>,
        params: Params,
        drawStartMarker: Boolean,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(
            params.widthPx.coerceAtLeast(1),
            params.heightPx.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(BG_COLOR)

        val padPx = PADDING_DP * params.density
        val drawable = RouteProjection.PixelRect(
            left = padPx,
            top = padPx,
            width = (params.widthPx - 2 * padPx).coerceAtLeast(1f),
            height = (params.heightPx - 2 * padPx).coerceAtLeast(1f),
        )

        val strokeColor = strokeColorFor(params.kind, params.runState)

        when {
            points.isEmpty() -> {
                // Pre-GPS state — just a dot at the centre so the surface
                // doesn't look like an empty card.
                val cx = params.widthPx / 2f
                val cy = params.heightPx / 2f
                canvas.drawCircle(cx, cy, START_RADIUS_DP * params.density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = START_DOT_COLOR
                })
                return bmp
            }
            points.size == 1 -> {
                // One sample — just the current-position marker at the centre.
                val cx = params.widthPx / 2f
                val cy = params.heightPx / 2f
                drawCurrentMarker(canvas, cx, cy, strokeColor, params.density)
                return bmp
            }
        }

        val latLons = points.map { RouteProjection.LatLon(it.lat, it.lon) }
        val bbox = RouteProjection.inflate(RouteProjection.bbox(latLons)!!)
        val coords = RouteProjection.project(latLons, bbox, drawable)

        val path = Path().apply {
            moveTo(coords[0], coords[1])
            var i = 2
            while (i < coords.size) {
                lineTo(coords[i], coords[i + 1])
                i += 2
            }
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            strokeWidth = STROKE_WIDTH_DP * params.density
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, strokePaint)

        if (drawStartMarker) {
            val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = START_DOT_COLOR
                style = Paint.Style.FILL
            }
            canvas.drawCircle(coords[0], coords[1], START_RADIUS_DP * params.density, startPaint)
        }

        val lastIdx = coords.size - 2
        drawCurrentMarker(canvas, coords[lastIdx], coords[lastIdx + 1], strokeColor, params.density)

        return bmp
    }

    private fun drawCurrentMarker(canvas: Canvas, cx: Float, cy: Float, color: Int, density: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color or 0xFF000000.toInt()  // force fully opaque for the marker
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, CURRENT_RADIUS_DP * density, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = CURRENT_RING_COLOR
            style = Paint.Style.STROKE
            strokeWidth = CURRENT_RING_WIDTH_DP * density
        }
        canvas.drawCircle(cx, cy, CURRENT_RADIUS_DP * density, ring)
    }

    /** Walk = green, Run = red; desaturate via alpha when paused. */
    fun strokeColorFor(kind: ActivityKind, runState: RunState): Int {
        val base = when (kind) {
            ActivityKind.Walking -> WALK_STROKE_COLOR
            ActivityKind.Running -> RUN_STROKE_COLOR
        }
        val paused = runState == RunState.Paused || runState == RunState.AutoPaused
        return if (paused) (base and 0x00FFFFFF) or (PAUSED_STROKE_ALPHA shl 24) else base
    }
}
