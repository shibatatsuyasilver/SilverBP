package com.silverbp.android.reporting

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.HypertensionGuideline
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Smoke test for the guideline-aware report: [PdfReportRenderer.render] must
 * produce a valid multi-page PDF (cover + charts at minimum) for every
 * [HypertensionGuideline], since the distribution donut now classifies by the
 * caller-supplied guideline. Guards against a crash/regression when the report
 * is generated under any guideline.
 */
@RunWith(AndroidJUnit4::class)
class PdfReportGuidelineRenderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun sampleReadings(): List<BpReading> {
        val now = Instant.now()
        fun r(s: Int, d: Int, daysAgo: Long) =
            BpReading(systolic = s, diastolic = d, timestamp = now.minus(daysAgo, ChronoUnit.DAYS))
        // Spread across categories so every guideline yields a non-empty donut.
        return listOf(
            r(122, 76, 1), r(121, 75, 2),
            r(135, 82, 3), r(134, 83, 4),
            r(148, 93, 5), r(146, 91, 6),
            r(170, 105, 7),
        )
    }

    @Test
    fun rendersValidMultiPagePdfForEveryGuideline() {
        val to = Instant.now()
        val from = to.minus(30, ChronoUnit.DAYS)
        for (guideline in HypertensionGuideline.entries) {
            val pdf = PdfReportRenderer(context).render(
                sampleReadings(),
                from = from,
                to = to,
                memberName = "Test Subject",
                includeDetail = true,
                guideline = guideline,
            )
            assertTrue("No PDF for $guideline", pdf.exists() && pdf.length() > 0)
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    // cover (0) + charts (1) at minimum.
                    assertTrue("Charts page missing for $guideline", renderer.pageCount >= 2)
                }
            }
            pdf.delete()
        }
    }
}
