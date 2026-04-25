package com.silverbp.android.reporting

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BpReading
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

    fun render(readings: List<BpReading>, from: Instant, to: Instant): File {
        val doc = PdfDocument()
        try {
            drawCover(doc, readings, from, to)
            if (readings.isNotEmpty()) drawTable(doc, readings)
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

    private fun drawCover(doc: PdfDocument, readings: List<BpReading>, from: Instant, to: Instant) {
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
        canvas.drawText("居家血壓記錄報告", 60f, y, title); y += 36f
        canvas.drawText("區間  ${dateFmt.format(from)}  ~  ${dateFmt.format(to)}", 60f, y, body); y += 24f
        canvas.drawText("讀數筆數  ${readings.size}", 60f, y, mono); y += 18f

        if (readings.isNotEmpty()) {
            val sys = readings.map { it.systolic.toDouble() }
            val dia = readings.map { it.diastolic.toDouble() }
            val pulse = readings.mapNotNull { it.pulse?.toDouble() }
            val meanS = StatsEngine.mean(sys).toInt()
            val meanD = StatsEngine.mean(dia).toInt()
            val sdS = StatsEngine.standardDeviation(sys)
            val arvS = StatsEngine.averageRealVariability(sys)
            val meanP = if (pulse.isNotEmpty()) StatsEngine.mean(pulse).toInt() else null
            canvas.drawText("平均           $meanS / $meanD mmHg", 60f, y, mono); y += 18f
            canvas.drawText("SBP 標準差     %.1f mmHg".format(sdS), 60f, y, mono); y += 18f
            canvas.drawText("ARV            %.1f mmHg".format(arvS), 60f, y, mono); y += 18f
            if (meanP != null) {
                canvas.drawText("脈搏平均       $meanP bpm", 60f, y, mono); y += 18f
            }
        }

        y = (pageHeight - 60).toFloat()
        canvas.drawText("由 SilverBP 產生於 ${dateFmt.format(Instant.now())}", 60f, y, muted)

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
            canvas.drawText("讀數明細 (頁 ${i + 1} / $pages)", 60f, y, header); y += 24f
            canvas.drawText("時間              SBP/DBP   脈搏  備註", 60f, y, header); y += 18f
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

    private fun drawDisclaimer(doc: PdfDocument) {
        val page = doc.startPage(pageInfo(99))
        val canvas = page.canvas
        val title = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val body = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 11f }
        var y = 80f
        canvas.drawText("注意事項", 60f, y, title); y += 28f
        DISCLAIMER_PARAGRAPHS.forEach { para ->
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

    companion object {
        private val DISCLAIMER_PARAGRAPHS = listOf(
            "本 App 僅作為個人健康紀錄輔助工具,並非醫療裝置 (Medical Device)。任何讀數異常或健康疑慮,請諮詢醫療專業人員。",
            "指標說明:平均為算術平均;SD 為樣本標準差,反映讀數離散度;ARV (Average Real Variability) 為連續讀數差絕對值的平均,可較佳反映時序變異 (Mena et al., J Hypertens 2005;Wei et al., JAHA 2018)。",
            "居家血壓 (HBPM) 高血壓門檻參考:台灣 2022 與 ACC/AHA 2017 為 ≥130/80 mmHg;ESH 2023 為 ≥140/90 mmHg,治療目標 <130/80。",
            "Powered by Gemma 3n vision model (Apache License 2.0)。",
        )
    }
}
