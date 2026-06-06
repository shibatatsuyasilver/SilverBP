package com.silverbp.android.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/**
 * Catmull-Rom (tension 1/6) → cubic-bezier through [points] for a smooth line.
 *
 * Shared by the Insights blood-pressure trend ([com.silverbp.android.ui.
 * insights.charts.TimeSeriesChart]) and the Exercise pace chart so both draw
 * the identical smooth curve. Pair with
 * `Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)` for the
 * shared look.
 */
internal fun smoothLinePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
        val p0 = points[if (i - 1 < 0) i else i - 1]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[if (i + 2 > points.lastIndex) i + 1 else i + 2]
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    return path
}
