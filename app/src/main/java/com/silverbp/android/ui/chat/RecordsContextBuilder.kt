package com.silverbp.android.ui.chat

import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.analytics.StatsEngine
import com.silverbp.android.coach.CoachPrompts
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.coach.LifestyleModule
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.ui.components.categoryLabel
import com.silverbp.android.ui.components.classify
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
 *
 * Locale-aware: section headers and field labels follow [Locale.getDefault],
 * delegating to [CoachPrompts.Records]. The English variant uses the same
 * structure so the chat persona's section references (## Latest reading / etc.)
 * line up with what the LLM actually sees.
 */
class RecordsContextBuilder(
    private val bp: BpRepository = ServiceLocator.bpRepository,
    private val exercise: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val achievements: AchievementStore = ServiceLocator.achievementStore,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val coachRepo: CoachRepository = ServiceLocator.coachRepository,
    private val glucose: GlucoseRepository = ServiceLocator.glucoseRepository,
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

        sb.appendLine(CoachPrompts.Records.header)
        sb.appendLine()

        appendProfile(sb)
        appendLatestBp(sb, zone)
        appendBpStats(sb, now)
        appendGlucose(sb, now)
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
        sb.appendLine(CoachPrompts.Records.sectionProfile)
        sb.appendLine(CoachPrompts.Records.guidelineLine(s.guideline.raw))
        sb.appendLine(CoachPrompts.Records.stepGoalLine(s.dailyStepGoal))
        sb.appendLine(CoachPrompts.Records.backendLine(s.recognitionBackend.raw))
        sb.appendLine()
    }

    private suspend fun appendLatestBp(sb: StringBuilder, zone: ZoneId) {
        // Chat / Coach context is owner-only by design (roadmap §3): the prompt
        // summarises the device owner's records, not other family members'.
        val ownerId = ServiceLocator.memberRepository.ownerId()
        val latest = runCatching { bp.observeLatest(ownerId).first() }.getOrNull()
        sb.appendLine(CoachPrompts.Records.sectionLatestBp)
        if (latest == null) {
            sb.appendLine("- ${CoachPrompts.Records.noRecord}")
        } else {
            val cat = categoryLabel(classify(latest.systolic, latest.diastolic))
            val ts = TS_FMT.withZone(zone).format(latest.timestamp)
            sb.appendLine(
                CoachPrompts.Records.latestBpLine(
                    timestamp = ts,
                    sys = latest.systolic,
                    dia = latest.diastolic,
                    category = cat,
                    pulse = latest.pulse,
                ),
            )
            if (latest.note.isNotBlank()) sb.appendLine(CoachPrompts.Records.noteLine(latest.note))
        }
        sb.appendLine()
    }

    private suspend fun appendBpStats(sb: StringBuilder, now: Instant) {
        val sevenDaysAgo = now.minusSeconds(7 * 24 * 3600L)
        val thirtyDaysAgo = now.minusSeconds(30 * 24 * 3600L)
        val ownerId = ServiceLocator.memberRepository.ownerId()
        val recent7 = runCatching {
            bp.observeRange(ownerId, sevenDaysAgo, now).first()
        }.getOrNull().orEmpty()
        val recent30 = runCatching {
            bp.observeRange(ownerId, thirtyDaysAgo, now).first()
        }.getOrNull().orEmpty()

        sb.appendLine(CoachPrompts.Records.sectionBpStats)
        if (recent7.isEmpty()) {
            sb.appendLine("- ${CoachPrompts.Records.noRecord7Days}")
        } else {
            val sys = recent7.map { it.systolic.toDouble() }
            val dia = recent7.map { it.diastolic.toDouble() }
            val sysMean = StatsEngine.mean(sys)
            val sysSd = StatsEngine.standardDeviation(sys)
            val diaMean = StatsEngine.mean(dia)
            val diaSd = StatsEngine.standardDeviation(dia)
            sb.appendLine(
                CoachPrompts.Records.bpStats7Line(
                    n = recent7.size,
                    sysMean = sysMean,
                    sysSd = sysSd,
                    diaMean = diaMean,
                    diaSd = diaSd,
                ),
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
                    CoachPrompts.Records.morningSurgeLine(
                        surge = surge,
                        nMorning = morningSys.size,
                        nEvening = eveningSys.size,
                    ),
                )
            }
            sb.appendLine(CoachPrompts.Records.thirtyDayCountLine(recent30.size))
        }
        sb.appendLine()
    }

    /**
     * Blood-glucose 7-day summary (count, fasting mean, post-meal mean, low
     * events) — kept to ~30 tokens per roadmap §4-5. Owner-scoped: the chat /
     * coach context summarises the device owner's records only, like the BP
     * sections above. The whole block is skipped when there are no readings so
     * a BP-only user's prompt is unchanged.
     */
    private suspend fun appendGlucose(sb: StringBuilder, now: Instant) {
        val sevenDaysAgo = now.minusSeconds(7 * 24 * 3600L)
        val ownerId = runCatching { ServiceLocator.memberRepository.ownerId() }.getOrNull() ?: return
        val recent: List<GlucoseReading> = runCatching {
            glucose.observeRange(ownerId, sevenDaysAgo, now).first()
        }.getOrNull().orEmpty()
        // Don't emit an empty "no record" line — glucose is opt-in (free 10/member),
        // so most users have none; an extra header would only waste prompt budget.
        if (recent.isEmpty()) return

        val zh = Locale.getDefault().language.equals("zh", ignoreCase = true)
        sb.appendLine(if (zh) "## 血糖 (7 日)" else "## Glucose (7 days)")

        val fasting = recent.filter {
            it.measureContext == MeasureContext.Fasting || it.measureContext == MeasureContext.BeforeMeal
        }.map { it.valueMgdl }
        val postMeal = recent.filter { it.measureContext == MeasureContext.AfterMeal }
            .map { it.valueMgdl }
        val lowEvents = recent.count { GLUCOSE_CLASSIFIER.classify(it.valueMgdl, it.measureContext).isHypoglycemic }

        val fastingMean = if (fasting.isNotEmpty()) StatsEngine.mean(fasting).toInt() else null
        val postMealMean = if (postMeal.isNotEmpty()) StatsEngine.mean(postMeal).toInt() else null

        if (zh) {
            sb.appendLine("- 7 日筆數: ${recent.size}")
            if (fastingMean != null) sb.appendLine("- 空腹均值: $fastingMean mg/dL (n=${fasting.size})")
            if (postMealMean != null) sb.appendLine("- 餐後均值: $postMealMean mg/dL (n=${postMeal.size})")
            if (lowEvents > 0) sb.appendLine("- 低血糖事件: $lowEvents 次")
        } else {
            sb.appendLine("- 7-day count: ${recent.size}")
            if (fastingMean != null) sb.appendLine("- Fasting mean: $fastingMean mg/dL (n=${fasting.size})")
            if (postMealMean != null) sb.appendLine("- After-meal mean: $postMealMean mg/dL (n=${postMeal.size})")
            if (lowEvents > 0) sb.appendLine("- Low events: $lowEvents")
        }
        sb.appendLine()
    }

    private suspend fun appendExercise(sb: StringBuilder, now: Instant, zone: ZoneId) {
        val sevenDaysAgo = now.minusSeconds(7 * 24 * 3600L)
        val recent: List<ExerciseSession> = runCatching {
            exercise.observeRange(sevenDaysAgo, now).first()
        }.getOrNull().orEmpty()

        sb.appendLine(CoachPrompts.Records.sectionExercise)
        val achievementStats = achievements.state.value.stats
        sb.appendLine(
            CoachPrompts.Records.todayStepsLine(
                today = achievementStats.todaySteps,
                goal = achievementStats.dailyStepGoal,
            ),
        )
        sb.appendLine(CoachPrompts.Records.streakLine(achievementStats.currentStreakDays))
        if (recent.isEmpty()) {
            sb.appendLine("- ${CoachPrompts.Records.noExercise7Days}")
        } else {
            sb.appendLine(CoachPrompts.Records.exercise7CountLine(recent.size))
            // Cap at 7 to keep the prompt within Gemma E2B's ~4K context budget.
            val sessions = recent.sortedByDescending { it.startedAt }.take(7)
            sb.appendLine(CoachPrompts.Records.exercise7ListHeader)
            for (s in sessions) {
                sb.appendLine("  • ${formatSession(s, zone)}")
            }
        }
        sb.appendLine()
    }

    private fun formatSession(s: ExerciseSession, zone: ZoneId): String {
        val ts = TS_FMT.withZone(zone).format(s.startedAt)
        val durMin = s.activeDurationMillis / 60_000
        val km = "%.2f".format(s.distanceMeters / 1000.0)
        val pace = s.averagePaceSecPerKm?.let { CoachPrompts.Records.paceLine(it) }
        return CoachPrompts.Records.sessionLine(
            timestamp = ts,
            kindRaw = s.kind.raw,
            km = km,
            durMin = durMin,
            paceText = pace,
            steps = s.stepCount,
        )
    }

    private fun appendAchievements(sb: StringBuilder) {
        val recent = achievements.state.value.recent.take(3)
        if (recent.isEmpty()) return
        sb.appendLine(CoachPrompts.Records.sectionAchievements)
        for (m in recent) {
            val ts = TS_FMT.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(m.unlockedAtMillis))
            sb.appendLine(CoachPrompts.Records.achievementLine(m.kind.kindRaw, ts))
        }
        sb.appendLine()
    }

    private suspend fun appendSleep(sb: StringBuilder, now: Instant, zone: ZoneId) {
        val sevenDaysAgoMillis = now.minusSeconds(7 * 24 * 3600L).toEpochMilli()
        val rows = runCatching {
            coachRepo.sleepRange(sevenDaysAgoMillis, now.toEpochMilli())
        }.getOrNull().orEmpty()
        sb.appendLine(CoachPrompts.Records.sectionSleep)
        if (rows.isEmpty()) {
            sb.appendLine("- ${CoachPrompts.Records.noRecord7Days}")
        } else {
            val meanMin = rows.map { it.durationMin }.average()
            val meanH = "%.1f".format(meanMin / 60.0)
            sb.appendLine(CoachPrompts.Records.sleepAvgLine(meanH, rows.size))
        }
        sb.appendLine()
    }

    private suspend fun appendDiet(sb: StringBuilder, now: Instant, zone: ZoneId) {
        val sevenDaysAgoMillis = now.minusSeconds(7 * 24 * 3600L).toEpochMilli()
        val rows = runCatching {
            coachRepo.dietRange(sevenDaysAgoMillis, now.toEpochMilli())
        }.getOrNull().orEmpty()
        sb.appendLine(CoachPrompts.Records.sectionDiet)
        if (rows.isEmpty()) {
            sb.appendLine("- ${CoachPrompts.Records.noRecord7Days}")
        } else {
            val high = rows.count { it.sodiumLevelRaw == "high" }
            val mid = rows.count { it.sodiumLevelRaw == "mid" }
            val low = rows.count { it.sodiumLevelRaw == "low" }
            val avgVeg = rows.map { it.vegServings }.average()
            sb.appendLine(CoachPrompts.Records.dietSodiumLine(low, mid, high))
            sb.appendLine(CoachPrompts.Records.dietVegLine("%.1f".format(avgVeg)))
        }
        sb.appendLine()
    }

    private suspend fun appendMedicationAdherence(sb: StringBuilder, now: Instant) {
        val sevenDaysAgoMillis = now.minusSeconds(7 * 24 * 3600L).toEpochMilli()
        val ratio = runCatching {
            coachRepo.medicationAdherence(sevenDaysAgoMillis, now.toEpochMilli())
        }.getOrNull() ?: 0f
        sb.appendLine(CoachPrompts.Records.sectionMedication)
        sb.appendLine(CoachPrompts.Records.medicationRateLine((ratio * 100).toInt()))
        sb.appendLine()
    }

    private suspend fun appendCurrentPlan(sb: StringBuilder, now: Instant) {
        val plan = runCatching { coachRepo.currentPlan(now.toEpochMilli()) }.getOrNull() ?: return
        sb.appendLine(CoachPrompts.Records.sectionWeeklyPlan)
        sb.appendLine(CoachPrompts.Records.planPhaseLine(plan.phase.raw, plan.ruleVersion))
        val byModule = plan.tasks.groupBy { it.module }
        for (module in LifestyleModule.entries) {
            val tasks = byModule[module].orEmpty()
            if (tasks.isEmpty()) continue
            val done = tasks.count { it.completedAtMillis != null }
            sb.appendLine(CoachPrompts.Records.planModuleLine(module.raw, done, tasks.size))
        }
        sb.appendLine()
    }

    companion object {
        // Pattern is locale-neutral (digits + separators only); Locale.getDefault()
        // keeps the formatter aligned with system + per-app language settings.
        private val TS_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

        // Stateless context-aware glucose classifier (low-event detection).
        private val GLUCOSE_CLASSIFIER = GlucoseClassifier()
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
 *
 * Locale-aware via [CoachPrompts.chatPersona].
 */
val CHAT_SYSTEM_PERSONA: String
    get() = CoachPrompts.chatPersona
