package com.silverbp.android.ui.chat

import android.util.Log
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.PartOfDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val TAG = "RecordsContextBuilder"

/** Approximate token count using a mixed Chinese/Latin heuristic (1 token ≈ 2 CJK or 4 Latin chars). */
private fun String.approxTokens(): Int {
    var cjk = 0; var other = 0
    for (ch in this) if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F) cjk++ else other++
    return (cjk + 1) / 2 + (other + 3) / 4
}

/** Character threshold above which the optional "## 30 日趨勢" section is dropped. */
private const val CHAR_BUDGET = 1200

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")
    .withZone(ZoneId.systemDefault())

/**
 * Builds a Markdown context block summarising the user's recent BP records.
 * Target: ≤ ~400 tokens (~800–1200 chars mixed content) so Gemma 4 E2B's 4K
 * context isn't crowded out by the records prefix.
 *
 * If the full output exceeds [CHAR_BUDGET] characters the "## 30 日趨勢" section
 * is dropped first — it is the most verbose and least essential for short Q&A.
 */
object RecordsContextBuilder {

    fun build(readings: List<BpReading>): String {
        val now = Instant.now()
        val cutoff30 = now.minus(30, ChronoUnit.DAYS)
        val last30 = readings.filter { it.timestamp.isAfter(cutoff30) }.sortedBy { it.timestamp }
        val recent5 = readings.sortedByDescending { it.timestamp }.take(5)

        val fullOutput = buildString {
            appendSection("## 最近血壓記錄（最新 5 筆）", recentReadingsSection(recent5))
            appendSection("## 30 日統計", statsSection(last30))
            appendSection("## 30 日趨勢", trendSection(last30))
        }.trim()

        val output = if (fullOutput.length > CHAR_BUDGET) {
            buildString {
                appendSection("## 最近血壓記錄（最新 5 筆）", recentReadingsSection(recent5))
                appendSection("## 30 日統計", statsSection(last30))
            }.trim()
        } else {
            fullOutput
        }

        Log.d(TAG, "chars=${output.length} approxTokens=${output.approxTokens()} dropped30dTrend=${output.length != fullOutput.length}")
        return output
    }

    private fun recentReadingsSection(readings: List<BpReading>): String = buildString {
        if (readings.isEmpty()) {
            appendLine("（無記錄）")
            return@buildString
        }
        for (r in readings) {
            val date = dateFmt.format(r.timestamp)
            val tod = if (r.partOfDay == PartOfDay.Morning) "早" else "晚"
            val pulse = r.pulse?.let { "，脈搏 $it" } ?: ""
            appendLine("- $date $tod：${r.systolic}/${r.diastolic} mmHg$pulse")
        }
    }

    private fun statsSection(last30: List<BpReading>): String = buildString {
        if (last30.isEmpty()) {
            appendLine("（30 日內無記錄）")
            return@buildString
        }
        val sys = last30.map { it.systolic.toDouble() }
        val dia = last30.map { it.diastolic.toDouble() }
        val meanSys = StatsEngine.mean(sys).roundToInt()
        val meanDia = StatsEngine.mean(dia).roundToInt()
        val sdSys = StatsEngine.standardDeviation(sys).let { "%.1f".format(it) }
        appendLine("共 ${last30.size} 筆，平均 $meanSys/$meanDia mmHg，收縮壓 SD $sdSys mmHg")
    }

    private fun trendSection(last30: List<BpReading>): String = buildString {
        if (last30.size < 2) {
            appendLine("（資料不足）")
            return@buildString
        }
        val morningSys = last30.filter { it.partOfDay == PartOfDay.Morning }.map { it.systolic.toDouble() }
        val eveningSys = last30.filter { it.partOfDay == PartOfDay.Evening }.map { it.systolic.toDouble() }
        val surge = StatsEngine.morningSurge(morningSys, eveningSys)
        if (surge != null) {
            val label = when {
                surge > 20 -> "晨間血壓明顯偏高"
                surge < -10 -> "晚間血壓偏高"
                else -> "晨晚差異正常"
            }
            appendLine("晨晚差 ${"%.0f".format(surge)} mmHg（$label），共 ${last30.size} 筆")
        } else {
            appendLine("晨晚資料不足，無法計算晨間血壓差。共 ${last30.size} 筆。")
        }
    }

    private fun StringBuilder.appendSection(header: String, body: String) {
        appendLine(header)
        append(body)
        appendLine()
    }
}
