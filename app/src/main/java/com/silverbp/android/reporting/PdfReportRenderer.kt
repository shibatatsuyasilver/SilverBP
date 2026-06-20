package com.silverbp.android.reporting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.silverbp.android.R
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GuidelineClassifier
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.core.MeasureContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A4 PDF report. Mirrors iOS BPReporting.PDFReportRenderer:
 *  - cover (title + metrics)
 *  - readings table (35 rows/page)
 *  - disclaimer (注意事項)
 *
 * Charts page is intentionally omitted in v1 (Compose graphics-layer
 * capture is wired in Step 14). Cover stats use [StatsEngine].
 */
class PdfReportRenderer(private val context: Context) {

    private val pageWidth = 595   // A4 @ 72dpi
    private val pageHeight = 842
    private val rowsPerPage = 35
    private val zone = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.TAIWAN).withZone(zone)
    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(zone)
    private val glucoseClassifier = GlucoseClassifier()

    fun render(
        readings: List<BpReading>,
        from: Instant,
        to: Instant,
        // Whose readings these are — printed on the cover so the doctor knows the
        // subject. Empty → cover omits the line (single-user installs are unaffected).
        memberName: String = "",
        // Premium tiering (Phase 3): when false (free tier) the per-reading detail
        // table pages are skipped, leaving the cover summary + disclaimer only.
        // Defaults true so existing callers / tests keep the full report.
        includeDetail: Boolean = true,
        // Blood-glucose readings for the same member/range (v19). Empty → the
        // report is BP-only, unchanged. Defaults empty so existing callers /
        // tests keep the original report. The cover gains a glucose summary and,
        // when [includeDetail], a glucose detail table follows the BP table.
        glucoseReadings: List<GlucoseReading> = emptyList(),
    ): File {
        val doc = PdfDocument()
        try {
            drawCover(doc, readings, from, to, memberName, glucoseReadings)
            // Charts page (premium-only, same gate as the detail tables). Mirrors
            // iOS page order: cover → charts → BP table → glucose table → disclaimer.
            if (includeDetail && readings.isNotEmpty()) drawChartsPage(doc, readings)
            if (includeDetail && readings.isNotEmpty()) drawTable(doc, readings)
            if (includeDetail && glucoseReadings.isNotEmpty()) drawGlucoseTable(doc, glucoseReadings)
            drawDisclaimer(doc)
        } finally {
            // doc closed below after writing
        }

        val outDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val name = "BP_${ymd.format(from)}-${ymd.format(to)}.pdf"
        val out = File(outDir, name)
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun drawCover(
        doc: PdfDocument,
        readings: List<BpReading>,
        from: Instant,
        to: Instant,
        memberName: String,
        glucoseReadings: List<GlucoseReading> = emptyList(),
    ) {
        val page = doc.startPage(pageInfo(1))
        val canvas = page.canvas
        val title = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 12f }
        val mono = Paint(body).apply { typeface = Typeface.MONOSPACE }
        val muted = Paint(body).apply { color = Color.DKGRAY; textSize = 11f }

        var y = 80f
        canvas.drawText(context.getString(R.string.pdf_report_title), 60f, y, title); y += 36f
        if (memberName.isNotBlank()) {
            canvas.drawText(context.getString(R.string.pdf_report_subject, memberName), 60f, y, body); y += 24f
        }
        canvas.drawText(context.getString(R.string.pdf_report_range, dateFmt.format(from), dateFmt.format(to)), 60f, y, body); y += 24f
        canvas.drawText(context.getString(R.string.pdf_report_count, readings.size), 60f, y, mono); y += 18f

        if (readings.isNotEmpty()) {
            val sys = readings.map { it.systolic.toDouble() }
            val dia = readings.map { it.diastolic.toDouble() }
            val pulse = readings.mapNotNull { it.pulse?.toDouble() }
            val meanS = StatsEngine.mean(sys).toInt()
            val meanD = StatsEngine.mean(dia).toInt()
            val sdS = StatsEngine.standardDeviation(sys)
            val arvS = StatsEngine.averageRealVariability(sys)
            val meanP = if (pulse.isNotEmpty()) StatsEngine.mean(pulse).toInt() else null
            canvas.drawText(context.getString(R.string.pdf_report_mean, meanS, meanD), 60f, y, mono); y += 18f
            canvas.drawText(context.getString(R.string.pdf_report_sd, sdS), 60f, y, mono); y += 18f
            canvas.drawText("ARV            %.1f mmHg".format(arvS), 60f, y, mono); y += 18f
            if (meanP != null) {
                canvas.drawText(context.getString(R.string.pdf_report_pulse_mean, meanP), 60f, y, mono); y += 18f
            }
        }

        // Glucose summary (v19) — only when the member has glucose readings, so a
        // BP-only report is byte-for-byte unchanged.
        if (glucoseReadings.isNotEmpty()) {
            y += 18f
            val section = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            canvas.drawText(context.getString(R.string.pdf_glucose_section_title), 60f, y, section); y += 22f
            canvas.drawText(context.getString(R.string.pdf_glucose_count, glucoseReadings.size), 60f, y, mono); y += 18f

            val fasting = glucoseReadings.filter {
                it.measureContext == MeasureContext.Fasting || it.measureContext == MeasureContext.BeforeMeal
            }.map { it.valueMgdl }
            val postMeal = glucoseReadings.filter { it.measureContext == MeasureContext.AfterMeal }
                .map { it.valueMgdl }
            if (fasting.isNotEmpty()) {
                canvas.drawText(
                    context.getString(R.string.pdf_glucose_fasting_mean, StatsEngine.mean(fasting).toInt(), fasting.size),
                    60f, y, mono,
                ); y += 18f
            }
            if (postMeal.isNotEmpty()) {
                canvas.drawText(
                    context.getString(R.string.pdf_glucose_postmeal_mean, StatsEngine.mean(postMeal).toInt(), postMeal.size),
                    60f, y, mono,
                ); y += 18f
            }
            val lowEvents = glucoseReadings.count {
                glucoseClassifier.classify(it.valueMgdl, it.measureContext).isHypoglycemic
            }
            if (lowEvents > 0) {
                canvas.drawText(context.getString(R.string.pdf_glucose_low_events, lowEvents), 60f, y, mono); y += 18f
            }
        }

        y = (pageHeight - 60).toFloat()
        canvas.drawText(context.getString(R.string.pdf_report_generated_at, dateFmt.format(Instant.now())), 60f, y, muted)

        doc.finishPage(page)
    }

    // MARK: charts page (mirrors iOS PDFReportRenderer.drawCharts)
    //
    // Two charts on one A4 page:
    //  1. BP trend line — SBP (red) + DBP (blue) with point markers, a light green
    //     normal-range band (60–130 mmHg) behind, axes auto-scaled with ~10 padding.
    //  2. Category-distribution donut — one sector per BP category with readings,
    //     using the app's category palette + a legend with counts.
    //
    // Category colors mirror ui/theme/Color.kt (CategoryNormal … CategoryHypotension);
    // kept as plain ARGB ints here so this non-Compose class stays Compose-free.
    private val catNormal = Color.parseColor("#FF34C759")      // green
    private val catElevated = Color.parseColor("#FFFFCC00")    // yellow
    private val catStage1 = Color.parseColor("#FFFF9500")      // orange
    private val catStage2 = Color.parseColor("#FFFF3B30")      // red
    private val catCrisis = Color.parseColor("#FFAF52DE")      // purple
    private val catHypotension = Color.parseColor("#FF007AFF") // blue

    private val seriesRed = Color.parseColor("#FFFF3B30")
    private val seriesBlue = Color.parseColor("#FF007AFF")

    private fun drawChartsPage(doc: PdfDocument, readings: List<BpReading>) {
        val page = doc.startPage(pageInfo(2))
        val canvas = page.canvas
        val title = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // 1) BP trend (title at top margin, chart below it).
        canvas.drawText(context.getString(R.string.pdf_chart_trend_title), 60f, 80f, title)
        drawTrendChart(canvas, readings, left = 60f, top = 100f, width = (pageWidth - 120).toFloat(), height = 300f)

        // 2) Category distribution donut.
        canvas.drawText(context.getString(R.string.pdf_chart_distribution_title), 60f, 440f, title)
        drawDistributionDonut(canvas, readings, left = 60f, top = 460f, width = (pageWidth - 120).toFloat(), height = 280f)

        doc.finishPage(page)
    }

    private fun drawTrendChart(
        canvas: Canvas,
        readings: List<BpReading>,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val axis = Paint().apply { isAntiAlias = true; color = Color.LTGRAY; strokeWidth = 1f }
        val labelPaint = Paint().apply { isAntiAlias = true; color = Color.DKGRAY; textSize = 9f }
        val legendPaint = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 10f }

        // Plot area leaves room for the y-axis labels on the left and a legend on top.
        val legendH = 18f
        val plotLeft = left + 36f
        val plotTop = top + legendH
        val plotRight = left + width
        val plotBottom = top + height - 18f // room for x-axis labels

        // Legend (top-left): red 收縮壓 / blue 舒張壓.
        val swatch = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        var lx = left
        val ly = top + 8f
        swatch.color = seriesRed
        canvas.drawRect(lx, ly - 8f, lx + 10f, ly + 2f, swatch)
        canvas.drawText(context.getString(R.string.pdf_chart_legend_systolic), lx + 14f, ly, legendPaint)
        lx += 14f + legendPaint.measureText(context.getString(R.string.pdf_chart_legend_systolic)) + 16f
        swatch.color = seriesBlue
        canvas.drawRect(lx, ly - 8f, lx + 10f, ly + 2f, swatch)
        canvas.drawText(context.getString(R.string.pdf_chart_legend_diastolic), lx + 14f, ly, legendPaint)

        // Y-axis range auto-scaled with ~10 padding, never below 0.
        val sorted = readings.sortedBy { it.timestamp }
        val values = sorted.flatMap { listOf(it.systolic, it.diastolic) }
        var minY = (values.minOrNull() ?: 60) - 10
        var maxY = (values.maxOrNull() ?: 180) + 10
        if (minY < 0) minY = 0
        if (maxY <= minY) maxY = minY + 20

        fun yPos(v: Int): Float =
            plotBottom - (v - minY).toFloat() / (maxY - minY).toFloat() * (plotBottom - plotTop)

        // Green normal-range band (60–130 mmHg) at ~8% opacity, clamped to the plot.
        val bandTopV = minOf(130, maxY)
        val bandBottomV = maxOf(60, minY)
        if (bandTopV > bandBottomV) {
            val bandPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                color = catNormal
                alpha = 20 // ~8% of 255
            }
            canvas.drawRect(plotLeft, yPos(bandTopV), plotRight, yPos(bandBottomV), bandPaint)
        }

        // Axes.
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axis)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axis)

        // Horizontal gridlines + y labels (4 steps).
        val steps = 4
        for (s in 0..steps) {
            val v = minY + (maxY - minY) * s / steps
            val gy = yPos(v)
            canvas.drawLine(plotLeft, gy, plotRight, gy, axis)
            canvas.drawText(v.toString(), left, gy + 3f, labelPaint)
        }

        // <2 readings → axes/band/legend only (graceful skip of the trend lines).
        if (sorted.size < 2) return

        fun xPos(i: Int): Float =
            plotLeft + i.toFloat() / (sorted.size - 1).toFloat() * (plotRight - plotLeft)

        val linePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        val pointPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

        // SBP (red) and DBP (blue): polyline + point markers.
        drawSeries(canvas, sorted.map { it.systolic }, ::xPos, ::yPos, seriesRed, linePaint, pointPaint)
        drawSeries(canvas, sorted.map { it.diastolic }, ::xPos, ::yPos, seriesBlue, linePaint, pointPaint)

        // X-axis end labels (oldest → newest) using the cover's date format, date-only.
        val xFmt = DateTimeFormatter.ofPattern("MM/dd", Locale.TAIWAN).withZone(zone)
        canvas.drawText(xFmt.format(sorted.first().timestamp), plotLeft, plotBottom + 12f, labelPaint)
        val lastLabel = xFmt.format(sorted.last().timestamp)
        canvas.drawText(lastLabel, plotRight - labelPaint.measureText(lastLabel), plotBottom + 12f, labelPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        ys: List<Int>,
        xPos: (Int) -> Float,
        yPos: (Int) -> Float,
        tint: Int,
        linePaint: Paint,
        pointPaint: Paint,
    ) {
        linePaint.color = tint
        pointPaint.color = tint
        val path = Path()
        ys.forEachIndexed { i, v ->
            val x = xPos(i)
            val y = yPos(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
        ys.forEachIndexed { i, v -> canvas.drawCircle(xPos(i), yPos(v), 2.2f, pointPaint) }
    }

    private fun drawDistributionDonut(
        canvas: Canvas,
        readings: List<BpReading>,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        // Taiwan 2022 thresholds — same classifier the report disclaimer references
        // and what local clinicians expect.
        val cls = GuidelineClassifier(HypertensionGuideline.Taiwan2022)
        val buckets = readings.groupingBy { cls.classify(it.systolic, it.diastolic) }.eachCount()

        // Canonical category order + palette + label (omit empty categories).
        data class Slice(val category: BpCategory, val label: String, val color: Int, val count: Int)
        val order = listOf(
            Triple(BpCategory.Normal, R.string.pdf_chart_cat_normal, catNormal),
            Triple(BpCategory.Elevated, R.string.pdf_chart_cat_elevated, catElevated),
            Triple(BpCategory.Stage1, R.string.pdf_chart_cat_stage1, catStage1),
            Triple(BpCategory.Stage2, R.string.pdf_chart_cat_stage2, catStage2),
            Triple(BpCategory.HypertensiveCrisis, R.string.pdf_chart_cat_crisis, catCrisis),
            Triple(BpCategory.Hypotension, R.string.pdf_chart_cat_hypotension, catHypotension),
        )
        val slices = order.mapNotNull { (cat, labelRes, color) ->
            val count = buckets[cat] ?: 0
            if (count > 0) Slice(cat, context.getString(labelRes), color, count) else null
        }
        if (slices.isEmpty()) return

        // Donut on the left half, legend on the right.
        val total = slices.sumOf { it.count }.toFloat()
        val outerR = (minOf(width * 0.42f, height) / 2f).coerceAtMost(110f)
        val cx = left + outerR + 10f
        val cy = top + height / 2f
        val innerR = outerR * 0.55f // matches iOS innerRadius ratio

        val oval = RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        val sectorPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        var startAngle = -90f // start at 12 o'clock like the iOS donut
        for (s in slices) {
            val sweep = s.count / total * 360f
            sectorPaint.color = s.color
            canvas.drawArc(oval, startAngle, sweep, true, sectorPaint)
            startAngle += sweep
        }
        // Punch out the center to make it a donut.
        val holePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.WHITE }
        canvas.drawCircle(cx, cy, innerR, holePaint)

        // Legend (right): swatch + "name  count".
        val legendPaint = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
        val swatch = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val legendX = cx + outerR + 24f
        var ly = cy - (slices.size - 1) * 9f
        for (s in slices) {
            swatch.color = s.color
            canvas.drawRect(legendX, ly - 9f, legendX + 11f, ly + 2f, swatch)
            canvas.drawText("${s.label}  ${s.count}", legendX + 16f, ly, legendPaint)
            ly += 18f
        }
    }

    private fun drawTable(doc: PdfDocument, readings: List<BpReading>) {
        val sorted = readings.sortedBy { it.timestamp }
        val pages = (sorted.size + rowsPerPage - 1) / rowsPerPage
        for (i in 0 until pages) {
            // Page 2 is the charts page (drawn before this), so the BP detail
            // table starts at logical page 3.
            val page = doc.startPage(pageInfo(3 + i))
            val canvas = page.canvas
            val header = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val cell = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 10f; typeface = Typeface.MONOSPACE }
            var y = 60f
            canvas.drawText(context.getString(R.string.pdf_report_detail_header, i + 1, pages), 60f, y, header); y += 24f
            canvas.drawText(context.getString(R.string.pdf_report_table_header), 60f, y, header); y += 18f
            val from = i * rowsPerPage
            val to = (from + rowsPerPage).coerceAtMost(sorted.size)
            for (r in sorted.subList(from, to)) {
                val ts = dateFmt.format(r.timestamp).padEnd(18)
                val bp = "${r.systolic}/${r.diastolic}".padEnd(10)
                val pulse = (r.pulse?.toString() ?: "-").padEnd(6)
                val note = r.note.take(40)
                canvas.drawText("$ts$bp$pulse$note", 60f, y, cell)
                y += 16f
            }
            doc.finishPage(page)
        }
    }

    private fun drawGlucoseTable(doc: PdfDocument, readings: List<GlucoseReading>) {
        val sorted = readings.sortedBy { it.timestamp }
        val pages = (sorted.size + rowsPerPage - 1) / rowsPerPage
        for (i in 0 until pages) {
            // Page number 50+ keeps glucose tables after the BP detail pages
            // (2..) and before the disclaimer (99), without colliding with either.
            val page = doc.startPage(pageInfo(50 + i))
            val canvas = page.canvas
            val header = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val cell = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 10f; typeface = Typeface.MONOSPACE }
            var y = 60f
            canvas.drawText(context.getString(R.string.pdf_glucose_detail_header, i + 1, pages), 60f, y, header); y += 24f
            canvas.drawText(context.getString(R.string.pdf_glucose_table_header), 60f, y, header); y += 18f
            val from = i * rowsPerPage
            val to = (from + rowsPerPage).coerceAtMost(sorted.size)
            for (r in sorted.subList(from, to)) {
                val ts = dateFmt.format(r.timestamp).padEnd(18)
                // Canonical mg/dL on the doctor-facing report regardless of the
                // user's display-unit preference.
                val value = "${r.valueMgdl.toInt()}".padEnd(7)
                val timing = contextLabel(r.measureContext).padEnd(13)
                val note = r.note.take(34)
                canvas.drawText("$ts$value$timing$note", 60f, y, cell)
                y += 16f
            }
            doc.finishPage(page)
        }
    }

    private fun contextLabel(context: MeasureContext): String = this.context.getString(
        when (context) {
            MeasureContext.Fasting -> R.string.context_fasting
            MeasureContext.BeforeMeal -> R.string.context_before_meal
            MeasureContext.AfterMeal -> R.string.context_after_meal
            MeasureContext.Bedtime -> R.string.context_bedtime
            MeasureContext.Random -> R.string.context_random
        },
    )

    private fun drawDisclaimer(doc: PdfDocument) {
        val page = doc.startPage(pageInfo(99))
        val canvas = page.canvas
        val title = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val body = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
        var y = 80f
        canvas.drawText(context.getString(R.string.pdf_disclaimer_title), 60f, y, title); y += 28f
        disclaimerParagraphs().forEach { para ->
            y = drawWrapped(canvas, para, 60f, y, body, pageWidth - 120f)
            y += 8f
        }
        doc.finishPage(page)
    }

    private fun drawWrapped(canvas: android.graphics.Canvas, text: String, x: Float, startY: Float, paint: Paint, maxW: Float): Float {
        var y = startY
        val rect = RectF()
        var i = 0
        val chars = text.toCharArray()
        while (i < chars.size) {
            // greedy fit
            var fit = chars.size - i
            while (fit > 0) {
                val w = paint.measureText(chars, i, fit)
                if (w <= maxW) break
                fit--
            }
            if (fit == 0) fit = 1
            canvas.drawText(chars, i, fit, x, y, paint)
            y += paint.textSize * 1.4f
            i += fit
        }
        return y
    }

    private fun pageInfo(num: Int) = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, num).create()

    // Resolved per render (not cached at class load) so the report follows the
    // current app language.
    private fun disclaimerParagraphs() = listOf(
        context.getString(R.string.pdf_disclaimer_1),
        context.getString(R.string.pdf_disclaimer_2),
        context.getString(R.string.pdf_disclaimer_3),
        context.getString(R.string.pdf_disclaimer_4),
    )
}
