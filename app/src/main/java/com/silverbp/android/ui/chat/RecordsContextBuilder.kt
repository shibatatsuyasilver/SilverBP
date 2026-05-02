package com.silverbp.android.ui.chat

import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.coach.LifestyleModule
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.components.classify
import com.silverbp.android.ui.components.chineseLabel
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Produces a compact markdown summary of the user's stored records to inject
 * into the chat system prompt. Recomputed per user turn — all underlying
 * sources are already-cached Flows so this is cheap.
 *
 * Token budget: ~400 tokens. Keep section bodies short. If usage shows
 * truncation on Gemma E2B's 4K context, drop "30 日趨勢" first.
 */
class RecordsContextBuilder(
    private val bp: BpRepository = ServiceLocator.bpRepository,
    private val exercise: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val achievements: AchievementStore = ServiceLocator.achievementStore,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val coachRepo: CoachRepository = ServiceLocator.coachRepository,
) {
    suspend fun build(
        now: Instant = Instant.now(),
        /**
         * When true, append Coach-only sections: sleep / diet / medication adherence
         * and the current plan summary. Off by default so the Chat tab's prompt
         * stays the same length as before this feature.
         */
        forCoach: Boolean = false,
    ): String {
        val sb = StringBuilder()
        val zone = ZoneId.systemDefault()

        sb.appendLine("# 使用者目前資料 (供你回答時參考)")
        sb.appendLine()

        appendProfile(sb)
        appendLatestBp(sb, zone)
        appendBpStats(sb, now)
        appendExercise(sb, now, zone)
        appendAchievements(sb)
        if (forCoach) {
            appendSleep(sb, now, zone)
            appendDiet(sb, now, zone)
            appendMedicationAdherence(sb, now)
            appendCurrentPlan(sb, now)
        }

        return sb.toString().trim()
    }

    private suspend fun appendProfile(sb: StringBuilder) {
        val s = runCatching { settings.flow.first() }.getOrNull() ?: return
        sb.appendLine("## 個人化")
        sb.appendLine("- 高血壓指引: ${s.guideline.raw}")
        sb.appendLine("- 每日步數目標: ${s.dailyStepGoal}")
        sb.appendLine("- 辨識後端: ${s.recognitionBackend.raw}")
        sb.appendLine()
    }

    private suspend fun appendLatestBp(sb: StringBuilder, zone: ZoneId) {
        val latest = runCatching { bp.observeLatest().first() }.getOrNull()
        sb.appendLine("## 最新血壓")
        if (latest == null) {
            sb.appendLine("- 尚無紀錄")
        } else {
            val cat = chineseLabel(classify(latest.systolic, latest.diastolic))
            val ts = TS_FMT.withZone(zone).format(latest.timestamp)
            val pulse = latest.pulse?.let { "，脈搏 $it" } ?: ""
            sb.appendLine("- $ts — ${latest.systolic}/${latest.diastolic} mmHg ($cat)$pulse")
            if (latest.note.isNotBlank()) sb.appendLine("- 備註: ${latest.note}")
        }
        sb.appendLine()
    }

    private suspend fun appendBpStats(sb: StringBuilder, now: Instant) {
        val sevenDaysAgo = now.minusSeconds(7 * 24 * 3600L)
        val thirtyDaysAgo = now.minusSeconds(30 * 24 * 3600L)
        val recent7 = runCatching {
            bp.observeRange(sevenDaysAgo, now).first()
        }.getOrNull().orEmpty()
        val recent30 = runCatching {
            bp.observeRange(thirtyDaysAgo, now).first()
        }.getOrNull().orEmpty()

        sb.appendLine("## 血壓統計")
        if (recent7.isEmpty()) {
            sb.appendLine("- 7 日內無紀錄")
        } else {
            val sys = recent7.map { it.systolic.toDouble() }
            val dia = recent7.map { it.diastolic.toDouble() }
            val sysMean = StatsEngine.mean(sys)
            val sysSd = StatsEngine.standardDeviation(sys)
            val diaMean = StatsEngine.mean(dia)
            val diaSd = StatsEngine.standardDeviation(dia)
            sb.appendLine(
                "- 7 日 (n=${recent7.size}): SBP ${"%.1f".format(sysMean)}±${"%.1f".format(sysSd)}, " +
                    "DBP ${"%.1f".format(diaMean)}±${"%.1f".format(diaSd)} mmHg"
            )
        }
        if (recent30.isNotEmpty()) {
            val morningSys = recent30.filter { it.partOfDay == PartOfDay.Morning }
                .map { it.systolic.toDouble() }
            val eveningSys = recent30.filter { it.partOfDay == PartOfDay.Evening }
                .map { it.systolic.toDouble() }
            val surge = StatsEngine.morningSurge(morningSys, eveningSys)
            if (surge != null) {
                sb.appendLine(
                    "- 30 日晨起 vs 夜晚 SBP 差: ${"%+.1f".format(surge)} mmHg" +
                        " (n 早=${morningSys.size}, n 晚=${eveningSys.size})"
                )
            }
            sb.appendLine("- 30 日紀錄筆數: ${recent30.size}")
        }
        sb.appendLine()
    }

    private suspend fun appendExercise(sb: StringBuilder, now: Instant, zone: ZoneId) {
        val sevenDaysAgo = now.minusSeconds(7 * 24 * 3600L)
        val recent: List<ExerciseSession> = runCatching {
            exercise.observeRange(sevenDaysAgo, now).first()
        }.getOrNull().orEmpty()

        sb.appendLine("## 運動")
        val achievementStats = achievements.state.value.stats
        sb.appendLine(
            "- 今日步數: ${achievementStats.todaySteps} / 目標 ${achievementStats.dailyStepGoal}",
        )
        sb.appendLine("- 連續達標天數: ${achievementStats.currentStreakDays} 天")
        if (recent.isEmpty()) {
            sb.appendLine("- 7 日內無運動紀錄")
        } else {
            sb.appendLine("- 7 日運動次數: ${recent.size}")
            // Cap at 7 to keep the prompt within Gemma E2B's ~4K context budget.
            val sessions = recent.sortedByDescending { it.startedAt }.take(7)
            sb.appendLine("- 7 日內紀錄:")
            for (s in sessions) {
                sb.appendLine("  • ${formatSession(s, zone)}")
            }
        }
        sb.appendLine()
    }

    private fun formatSession(s: ExerciseSession, zone: ZoneId): String {
        val ts = TS_FMT.withZone(zone).format(s.startedAt)
        val durMin = (s.endedAt.toEpochMilli() - s.startedAt.toEpochMilli()) / 60_000
        val km = "%.2f".format(s.distanceMeters / 1000.0)
        val pace = s.averagePaceSecPerKm
            ?.let { "%.1f".format(it / 60.0) + " 分/km" }
            ?: "配速 —"
        val steps = s.stepCount?.let { ", ${"%,d".format(it)} 步" } ?: ""
        return "$ts ${s.kind.raw} ${km}km / ${durMin} 分 ($pace$steps)"
    }

    private fun appendAchievements(sb: StringBuilder) {
        val recent = achievements.state.value.recent.take(3)
        if (recent.isEmpty()) return
        sb.appendLine("## 最近徽章")
        for (m in recent) {
            val ts = TS_FMT.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(m.unlockedAtMillis))
            sb.appendLine("- ${m.kind.kindRaw} (解鎖於 $ts)")
        }
        sb.appendLine()
    }

    private suspend fun appendSleep(sb: StringBuilder, now: Instant, zone: ZoneId) {
        val sevenDaysAgoMillis = now.minusSeconds(7 * 24 * 3600L).toEpochMilli()
        val rows = runCatching {
            coachRepo.sleepRange(sevenDaysAgoMillis, now.toEpochMilli())
        }.getOrNull().orEmpty()
        sb.appendLine("## 睡眠 (7 日)")
        if (rows.isEmpty()) {
            sb.appendLine("- 7 日內無紀錄")
        } else {
            val meanMin = rows.map { it.durationMin }.average()
            val meanH = "%.1f".format(meanMin / 60.0)
            sb.appendLine("- 平均 $meanH 小時 (n=${rows.size})")
        }
        sb.appendLine()
    }

    private suspend fun appendDiet(sb: StringBuilder, now: Instant, zone: ZoneId) {
        val sevenDaysAgoMillis = now.minusSeconds(7 * 24 * 3600L).toEpochMilli()
        val rows = runCatching {
            coachRepo.dietRange(sevenDaysAgoMillis, now.toEpochMilli())
        }.getOrNull().orEmpty()
        sb.appendLine("## 飲食 (7 日)")
        if (rows.isEmpty()) {
            sb.appendLine("- 7 日內無紀錄")
        } else {
            val high = rows.count { it.sodiumLevelRaw == "high" }
            val mid = rows.count { it.sodiumLevelRaw == "mid" }
            val low = rows.count { it.sodiumLevelRaw == "low" }
            val avgVeg = rows.map { it.vegServings }.average()
            sb.appendLine("- 鈉攝取: 低=$low / 中=$mid / 高=$high")
            sb.appendLine("- 平均蔬菜份數: ${"%.1f".format(avgVeg)}")
        }
        sb.appendLine()
    }

    private suspend fun appendMedicationAdherence(sb: StringBuilder, now: Instant) {
        val sevenDaysAgoMillis = now.minusSeconds(7 * 24 * 3600L).toEpochMilli()
        val ratio = runCatching {
            coachRepo.medicationAdherence(sevenDaysAgoMillis, now.toEpochMilli())
        }.getOrNull() ?: 0f
        sb.appendLine("## 服藥 (7 日)")
        sb.appendLine("- 完成率: ${(ratio * 100).toInt()}%")
        sb.appendLine()
    }

    private suspend fun appendCurrentPlan(sb: StringBuilder, now: Instant) {
        val plan = runCatching { coachRepo.currentPlan(now.toEpochMilli()) }.getOrNull() ?: return
        sb.appendLine("## 本週計畫")
        sb.appendLine("- Phase: ${plan.phase.raw}, ruleVersion=${plan.ruleVersion}")
        val byModule = plan.tasks.groupBy { it.module }
        for (module in LifestyleModule.entries) {
            val tasks = byModule[module].orEmpty()
            if (tasks.isEmpty()) continue
            val done = tasks.count { it.completedAtMillis != null }
            sb.appendLine("- ${module.raw}: $done / ${tasks.size}")
        }
        sb.appendLine()
    }

    companion object {
        private val TS_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.TAIWAN)
    }
}

/**
 * Default chat persona prepended to the records summary. Keep terse and
 * structure-free.
 *
 * Why a single dense paragraph and not bullets/examples: Gemma cloud
 * (`gemma-4-31b-it`) mirrors the structure of the prompt — bullet directives
 * trigger bullet "checklists" in the response, examples trigger English
 * meta-labels. A flat sentence cuts noise. As defence in depth,
 * [GeminiCloudChatRecognizer] also post-processes the response to strip
 * checklist tails / quoted self-summaries / repeated answers, and on
 * Gemini 2.5 Flash/Pro we disable thinking via `thinkingConfig.thinkingBudget=0`.
 */
const val CHAT_SYSTEM_PERSONA: String =
    "你是 SilverBp 健康助理。依下方各區段 (## 最新血壓 / ## 血壓統計 / ## 運動 / ## 最近徽章) 之資料以繁體中文簡短回答；僅在對應區段明確顯示無紀錄時才回「目前沒有相關紀錄」。不開處方、不下診斷，數值異常時提醒就診。"
