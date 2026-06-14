package com.silverbp.android.reporting

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.silverbp.android.R
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BmiCalculator
import com.silverbp.android.core.BmiCategory
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.core.WeightReading
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
        // Body-weight readings for the same member/range (v20). Empty → the report
        // omits the weight summary/table, unchanged. Defaults empty so existing
        // callers / tests keep the original report. The cover gains a weight
        // summary (latest + BMI + change) and, when [includeDetail], a weight
        // detail table follows the glucose table.
        weightReadings: List<WeightReading> = emptyList(),
        // Member height in cm for BMI on the weight summary (Taiwan thresholds).
        // null → the BMI line is omitted; the rest of the weight summary still
        // prints. Defaults null so existing callers are unaffected.
        memberHeightCm: Int? = null,
    ): File {
        val doc = PdfDocument()
        try {
            drawCover(doc, readings, from, to, memberName, glucoseReadings, weightReadings, memberHeightCm)
            if (includeDetail && readings.isNotEmpty()) drawTable(doc, readings)
            if (includeDetail && glucoseReadings.isNotEmpty()) drawGlucoseTable(doc, glucoseReadings)
            if (includeDetail && weightReadings.isNotEmpty()) drawWeightTable(doc, weightReadings)
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
        weightReadings: List<WeightReading> = emptyList(),
        memberHeightCm: Int? = null,
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

        // Weight summary (v20) — only when the member has weight readings, so a
        // BP/glucose-only report is byte-for-byte unchanged. kg is canonical on the
        // doctor-facing report; BMI uses the member's height + Taiwan thresholds.
        if (weightReadings.isNotEmpty()) {
            y += 18f
            val section = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            canvas.drawText(context.getString(R.string.pdf_weight_section_title), 60f, y, section); y += 22f
            canvas.drawText(context.getString(R.string.pdf_weight_count, weightReadings.size), 60f, y, mono); y += 18f

            // Ascending-by-time so first = earliest, last = latest in the range.
            val sorted = weightReadings.sortedBy { it.timestamp }
            val latest = sorted.last()
            canvas.drawText(
                context.getString(R.string.pdf_weight_latest, "%.1f".format(latest.weightKg)),
                60f, y, mono,
            ); y += 18f

            if (memberHeightCm != null && memberHeightCm > 0) {
                val bmi = BmiCalculator.bmi(latest.weightKg, memberHeightCm)
                val cat = bmiCategoryLabel(BmiCalculator.category(latest.weightKg, memberHeightCm))
                canvas.drawText(
                    context.getString(R.string.pdf_weight_bmi, "%.1f".format(bmi), cat),
                    60f, y, mono,
                ); y += 18f
            }

            if (sorted.size > 1) {
                val delta = latest.weightKg - sorted.first().weightKg
                val sign = if (delta >= 0) "+" else ""
                canvas.drawText(
                    context.getString(R.string.pdf_weight_change, "$sign%.1f".format(delta)),
                    60f, y, mono,
                ); y += 18f
            }
        }

        y = (pageHeight - 60).toFloat()
        canvas.drawText(context.getString(R.string.pdf_report_generated_at, dateFmt.format(Instant.now())), 60f, y, muted)

        doc.finishPage(page)
    }

    private fun drawTable(doc: PdfDocument, readings: List<BpReading>) {
        val sorted = readings.sortedBy { it.timestamp }
        val pages = (sorted.size + rowsPerPage - 1) / rowsPerPage
        for (i in 0 until pages) {
            val page = doc.startPage(pageInfo(2 + i))
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

    private fun drawWeightTable(doc: PdfDocument, readings: List<WeightReading>) {
        val sorted = readings.sortedBy { it.timestamp }
        val pages = (sorted.size + rowsPerPage - 1) / rowsPerPage
        for (i in 0 until pages) {
            // Page number 70+ keeps weight tables after the glucose tables (50..)
            // and before the disclaimer (99), without colliding with either.
            val page = doc.startPage(pageInfo(70 + i))
            val canvas = page.canvas
            val header = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val cell = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 10f; typeface = Typeface.MONOSPACE }
            var y = 60f
            canvas.drawText(context.getString(R.string.pdf_weight_detail_header, i + 1, pages), 60f, y, header); y += 24f
            canvas.drawText(context.getString(R.string.pdf_weight_table_header), 60f, y, header); y += 18f
            val from = i * rowsPerPage
            val to = (from + rowsPerPage).coerceAtMost(sorted.size)
            for (r in sorted.subList(from, to)) {
                val ts = dateFmt.format(r.timestamp).padEnd(18)
                // Canonical kg on the doctor-facing report regardless of the user's
                // display-unit preference.
                val value = "%.1f".format(r.weightKg).padEnd(8)
                val note = r.note.take(40)
                canvas.drawText("$ts$value$note", 60f, y, cell)
                y += 16f
            }
            doc.finishPage(page)
        }
    }

    private fun bmiCategoryLabel(category: BmiCategory): String = context.getString(
        when (category) {
            BmiCategory.Underweight -> R.string.bmi_underweight
            BmiCategory.Normal -> R.string.bmi_normal
            BmiCategory.Overweight -> R.string.bmi_overweight
            BmiCategory.Obese -> R.string.bmi_obese
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
