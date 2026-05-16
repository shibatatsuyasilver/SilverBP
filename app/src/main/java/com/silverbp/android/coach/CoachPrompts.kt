package com.silverbp.android.coach

import java.time.Instant
import java.util.Locale

/**
 * Locale-aware LLM prompt strings for the coach + chat layers.
 *
 * The app supports `en` and `zh-TW`. Without this object every coach prompt
 * was hard-coded in Traditional Chinese, so English-locale users received
 * Chinese narrations. Every persona / system prompt / user prompt the LLM
 * sees passes through here so language follows `Locale.getDefault()`.
 *
 * Safety contract (mirrors [CoachNarrator]'s comment): no persona may suggest
 * dose, intensity, or frequency changes. The English variants must keep the
 * same "do not change the plan" clause as the Chinese ones.
 */
internal object CoachPrompts {

    private fun isZh(): Boolean =
        Locale.getDefault().language.equals("zh", ignoreCase = true)

    // ---- Coach narrator personas ----------------------------------------

    val dailyPersona: String
        get() = if (isZh()) ZH_DAILY else EN_DAILY

    val weeklyPersona: String
        get() = if (isZh()) ZH_WEEKLY else EN_WEEKLY

    val anomalyPersona: String
        get() = if (isZh()) ZH_ANOMALY else EN_ANOMALY

    val chatPersona: String
        get() = if (isZh()) ZH_CHAT else EN_CHAT

    val safetySuffix: String
        get() = if (isZh()) ZH_SAFETY else EN_SAFETY

    fun nicknameInstruction(nickname: String): String? {
        val n = nickname.trim()
        if (n.isEmpty()) return null
        return if (isZh()) {
            "使用者希望被稱為「$n」。回應時可在開頭或鼓勵句偶爾使用這個稱呼," +
                "讓語氣自然親切,但不要每一句都重複稱呼。"
        } else {
            "The user prefers to be called \"$n\". Use this name occasionally at " +
                "the start or in encouraging sentences to keep the tone warm and " +
                "personal — do not repeat it in every sentence."
        }
    }

    /** Same intent as [nicknameInstruction] but for the JSON-output exercise title generator. */
    fun nicknameInstructionForExerciseTitle(nickname: String): String? {
        val n = nickname.trim()
        if (n.isEmpty()) return null
        return if (isZh()) {
            "使用者希望被稱為「$n」,可在 title 或 subtitle 偶爾使用,不要硬塞或每句重複。"
        } else {
            "The user prefers to be called \"$n\". You may use this name " +
                "occasionally in the title or subtitle — do not force it into every line."
        }
    }

    // ---- Coach narrator user-prompt builders ----------------------------

    fun buildDailyPrompt(plan: CoachPlan, today: CoachTask): String = buildString {
        if (isZh()) {
            appendLine("# 今日任務 (請以親切口吻向使用者解釋,不超過三句)")
            appendLine("- 模組: ${today.module.raw}")
            appendLine("- 標題: ${today.title}")
            if (today.targetValue != null && today.targetUnit != null) {
                appendLine("- 目標: ${today.targetValue} ${today.targetUnit}")
            }
            appendLine("- 強度: ${today.intensity.raw}")
            if (today.safetyHold) appendLine("- ⚠ 今日為休息日,請提醒使用者聯絡醫師")
            appendLine("- 本週階段: ${plan.phase.raw}")
        } else {
            appendLine("# Today's task (explain warmly to the user in no more than three sentences)")
            appendLine("- Module: ${today.module.raw}")
            appendLine("- Title: ${today.title}")
            if (today.targetValue != null && today.targetUnit != null) {
                appendLine("- Target: ${today.targetValue} ${today.targetUnit}")
            }
            appendLine("- Intensity: ${today.intensity.raw}")
            if (today.safetyHold) appendLine("- ⚠ Today is a rest day — remind the user to contact their doctor")
            appendLine("- This-week phase: ${plan.phase.raw}")
        }
    }

    fun buildWeeklyPrompt(r: WeeklyReport): String = buildString {
        if (isZh()) {
            appendLine("# 本週報告 (請以 3 段:回顧、亮點、下週重點)")
            appendLine("- 7 日 SBP 平均: ${"%.1f".format(r.sbpMean)} mmHg, 變化: ${"%+.1f".format(r.sbpDelta)}")
            appendLine("- 有氧運動: ${r.aerobicMin} / ${r.aerobicTarget} 分鐘")
            appendLine("- 睡眠平均: ${"%.1f".format(r.sleepMeanH)} 小時")
            appendLine("- 鈉攝取超標天數: ${r.sodiumDaysOver}")
            appendLine("- 服藥完成率: ${(r.medAdherence * 100).toInt()}%")
            if (r.highlights.isNotEmpty()) {
                appendLine("- 亮點: ${r.highlights.joinToString("; ")}")
            }
            if (r.nextWeekFocus.isNotEmpty()) {
                appendLine("- 下週重點: ${r.nextWeekFocus.joinToString("; ")}")
            }
        } else {
            appendLine("# Weekly report (write in 3 paragraphs: review, highlight, next-week focus)")
            appendLine("- 7-day SBP mean: ${"%.1f".format(r.sbpMean)} mmHg, change: ${"%+.1f".format(r.sbpDelta)}")
            appendLine("- Aerobic exercise: ${r.aerobicMin} / ${r.aerobicTarget} min")
            appendLine("- Sleep mean: ${"%.1f".format(r.sleepMeanH)} hours")
            appendLine("- Days with sodium over target: ${r.sodiumDaysOver}")
            appendLine("- Medication adherence: ${(r.medAdherence * 100).toInt()}%")
            if (r.highlights.isNotEmpty()) {
                appendLine("- Highlights: ${r.highlights.joinToString("; ")}")
            }
            if (r.nextWeekFocus.isNotEmpty()) {
                appendLine("- Next-week focus: ${r.nextWeekFocus.joinToString("; ")}")
            }
        }
    }

    fun buildAnomalyPrompt(event: CoachEvent.Anomaly): String = buildString {
        if (isZh()) {
            appendLine("# 血壓警示 (請給 2 句安撫 + 1 個立刻可做的放鬆建議)")
            appendLine("- 嚴重度: ${event.severity.raw}")
            appendLine("- 最新讀數: ${event.latestSystolic}/${event.latestDiastolic} mmHg")
            appendLine("- 觸發於: ${Instant.ofEpochMilli(event.triggeredAtMillis)}")
            if (event.severity == Severity.Critical) {
                appendLine("- 嚴重等級為 Critical,請強烈建議立即聯絡醫師")
            }
        } else {
            appendLine("# Blood-pressure alert (give 2 calming sentences + 1 immediate relaxation tip)")
            appendLine("- Severity: ${event.severity.raw}")
            appendLine("- Latest reading: ${event.latestSystolic}/${event.latestDiastolic} mmHg")
            appendLine("- Triggered at: ${Instant.ofEpochMilli(event.triggeredAtMillis)}")
            if (event.severity == Severity.Critical) {
                appendLine("- Severity is Critical — strongly advise contacting a doctor immediately")
            }
        }
    }

    // ---- Today exercise task title generator ---------------------------

    fun buildExerciseTitleSystemPrompt(baseTask: CoachTask, nickname: String): String = buildString {
        if (isZh()) {
            appendLine("你是 SilverBp 健康教練。根據使用者最近 7 天的運動紀錄,")
            appendLine("為今日的運動任務寫一個短標題,讓使用者覺得親切、想立刻去做。")
            nicknameInstructionForExerciseTitle(nickname)?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("固定條件 (絕對不可更動):")
            appendLine("- 運動類型: ${baseTask.module.raw}")
            baseTask.targetValue?.let { v ->
                appendLine("- 目標時間: ${v.toInt()} 分鐘")
            }
            appendLine("- 強度: ${baseTask.intensity.raw}")
            appendLine()
            appendLine("只能改變【標題的措辭】,不可改變分鐘數、強度、類型,")
            appendLine("不可建議休息或省略運動,不可加入未提供的數字。")
            appendLine()
            appendLine("只輸出 JSON,不要 Markdown 也不要程式碼框:")
            appendLine("{\"title\": \"...\", \"subtitle\": \"...\"}")
            appendLine("- title ≤ 16 個中文字,口語、自然引用最近紀錄")
            append("- subtitle ≤ 24 個中文字,可省略 (空字串)")
        } else {
            appendLine("You are SilverBp's health coach. Based on the user's exercise records for the past 7 days,")
            appendLine("write a short title for today's exercise task that feels warm and motivating.")
            nicknameInstructionForExerciseTitle(nickname)?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("Fixed constraints (must not be changed):")
            appendLine("- Exercise type: ${baseTask.module.raw}")
            baseTask.targetValue?.let { v ->
                appendLine("- Target duration: ${v.toInt()} min")
            }
            appendLine("- Intensity: ${baseTask.intensity.raw}")
            appendLine()
            appendLine("You may only change the wording of the title — not the minutes, intensity, or type.")
            appendLine("Do not suggest skipping the workout or adding numbers that were not provided.")
            appendLine()
            appendLine("Output JSON only — no Markdown, no code fences:")
            appendLine("{\"title\": \"...\", \"subtitle\": \"...\"}")
            appendLine("- title ≤ 40 characters, conversational, naturally referring to recent records")
            append("- subtitle ≤ 60 characters, optional (empty string allowed)")
        }
    }

    fun buildExerciseTitleUserPrompt(baseTask: CoachTask, s: RecentExerciseSummary): String = buildString {
        if (isZh()) {
            appendLine("# 最近 7 天運動紀錄")
            appendLine("- 有運動的天數: ${s.daysWithExerciseLast7}")
            appendLine("- 累計分鐘: ${s.totalMinutesLast7}")
            appendLine("- 距離上次運動: ${s.lastSessionMinutesAgo?.let { "${it / 60} 小時前" } ?: "從未紀錄"}")
            appendLine("- 上次運動類型: ${s.lastKind?.raw ?: "無"}")
            appendLine("- 本週目標: ${s.weeklyTargetMin} 分鐘 (已達 ${s.weeklyAchievedMin} 分鐘)")
            appendLine()
            appendLine("# 今日任務基礎資料")
            appendLine("- 預設標題: ${baseTask.title}")
            baseTask.targetValue?.let { v ->
                appendLine("- 目標分鐘: ${v.toInt()}")
            }
            append("- 強度: ${baseTask.intensity.raw}")
        } else {
            appendLine("# Exercise records for the past 7 days")
            appendLine("- Days with exercise: ${s.daysWithExerciseLast7}")
            appendLine("- Total minutes: ${s.totalMinutesLast7}")
            appendLine("- Time since last session: ${s.lastSessionMinutesAgo?.let { "${it / 60} hours ago" } ?: "no record"}")
            appendLine("- Last exercise type: ${s.lastKind?.raw ?: "none"}")
            appendLine("- Weekly target: ${s.weeklyTargetMin} min (achieved ${s.weeklyAchievedMin} min)")
            appendLine()
            appendLine("# Base data for today's task")
            appendLine("- Default title: ${baseTask.title}")
            baseTask.targetValue?.let { v ->
                appendLine("- Target minutes: ${v.toInt()}")
            }
            append("- Intensity: ${baseTask.intensity.raw}")
        }
    }

    // ---- Records context builder labels --------------------------------

    object Records {
        private fun zh() = isZh()

        val header: String get() = if (zh()) "# 使用者目前資料 (供你回答時參考)" else "# Current user data (for reference when answering)"
        val sectionProfile: String get() = if (zh()) "## 個人化" else "## Personalization"
        val sectionLatestBp: String get() = if (zh()) "## 最新血壓" else "## Latest reading"
        val sectionBpStats: String get() = if (zh()) "## 血壓統計" else "## BP stats"
        val sectionExercise: String get() = if (zh()) "## 運動" else "## Exercise"
        val sectionAchievements: String get() = if (zh()) "## 最近徽章" else "## Recent badges"
        val sectionSleep: String get() = if (zh()) "## 睡眠 (7 日)" else "## Sleep (7 days)"
        val sectionDiet: String get() = if (zh()) "## 飲食 (7 日)" else "## Diet (7 days)"
        val sectionMedication: String get() = if (zh()) "## 服藥 (7 日)" else "## Medication (7 days)"
        val sectionWeeklyPlan: String get() = if (zh()) "## 本週計畫" else "## This week's plan"

        val noRecord: String get() = if (zh()) "尚無紀錄" else "no record yet"
        val noRecord7Days: String get() = if (zh()) "7 日內無紀錄" else "no record in the last 7 days"
        val noExercise7Days: String get() = if (zh()) "7 日內無運動紀錄" else "no exercise in the last 7 days"

        fun guidelineLine(value: String): String =
            if (zh()) "- 高血壓指引: $value" else "- Hypertension guideline: $value"
        fun stepGoalLine(value: Int): String =
            if (zh()) "- 每日步數目標: $value" else "- Daily step goal: $value"
        fun backendLine(value: String): String =
            if (zh()) "- 辨識後端: $value" else "- Recognition backend: $value"

        fun latestBpLine(timestamp: String, sys: Int, dia: Int, category: String, pulse: Int?): String {
            val pulseInfix = pulse?.let {
                if (zh()) "，脈搏 $it" else ", pulse $it"
            } ?: ""
            return "- $timestamp — $sys/$dia mmHg ($category)$pulseInfix"
        }
        fun noteLine(note: String): String = if (zh()) "- 備註: $note" else "- Note: $note"

        fun bpStats7Line(n: Int, sysMean: Double, sysSd: Double, diaMean: Double, diaSd: Double): String {
            val sysM = "%.1f".format(sysMean); val sysS = "%.1f".format(sysSd)
            val diaM = "%.1f".format(diaMean); val diaS = "%.1f".format(diaSd)
            return if (zh()) {
                "- 7 日 (n=$n): SBP $sysM±$sysS, DBP $diaM±$diaS mmHg"
            } else {
                "- 7 days (n=$n): SBP $sysM±$sysS, DBP $diaM±$diaS mmHg"
            }
        }

        fun morningSurgeLine(surge: Double, nMorning: Int, nEvening: Int): String {
            val s = "%+.1f".format(surge)
            return if (zh()) {
                "- 30 日晨起 vs 夜晚 SBP 差: $s mmHg (n 早=$nMorning, n 晚=$nEvening)"
            } else {
                "- 30-day morning vs evening SBP delta: $s mmHg (n morning=$nMorning, n evening=$nEvening)"
            }
        }
        fun thirtyDayCountLine(n: Int): String =
            if (zh()) "- 30 日紀錄筆數: $n" else "- 30-day record count: $n"

        fun todayStepsLine(today: Int, goal: Int): String =
            if (zh()) "- 今日步數: $today / 目標 $goal" else "- Steps today: $today / goal $goal"
        fun streakLine(days: Int): String =
            if (zh()) "- 連續達標天數: $days 天" else "- Current streak: $days days"
        fun exercise7CountLine(n: Int): String =
            if (zh()) "- 7 日運動次數: $n" else "- 7-day exercise count: $n"
        val exercise7ListHeader: String get() = if (zh()) "- 7 日內紀錄:" else "- Records in last 7 days:"

        fun sessionLine(timestamp: String, kindRaw: String, km: String, durMin: Long, paceText: String?, steps: Int?): String {
            val pace = paceText ?: if (zh()) "配速 —" else "pace —"
            val stepsSuffix = steps?.let {
                if (zh()) ", ${"%,d".format(it)} 步" else ", ${"%,d".format(it)} steps"
            } ?: ""
            return if (zh()) {
                "$timestamp $kindRaw ${km}km / ${durMin} 分 ($pace$stepsSuffix)"
            } else {
                "$timestamp $kindRaw ${km}km / ${durMin} min ($pace$stepsSuffix)"
            }
        }
        fun paceLine(secPerKm: Double): String {
            val mins = "%.1f".format(secPerKm / 60.0)
            return if (zh()) "$mins 分/km" else "$mins min/km"
        }

        fun achievementLine(kindRaw: String, timestamp: String): String =
            if (zh()) "- $kindRaw (解鎖於 $timestamp)" else "- $kindRaw (unlocked at $timestamp)"

        fun sleepAvgLine(meanHours: String, n: Int): String =
            if (zh()) "- 平均 $meanHours 小時 (n=$n)" else "- Average $meanHours hours (n=$n)"

        fun dietSodiumLine(low: Int, mid: Int, high: Int): String =
            if (zh()) "- 鈉攝取: 低=$low / 中=$mid / 高=$high"
            else "- Sodium intake: low=$low / mid=$mid / high=$high"
        fun dietVegLine(avg: String): String =
            if (zh()) "- 平均蔬菜份數: $avg" else "- Average vegetable servings: $avg"

        fun medicationRateLine(pct: Int): String =
            if (zh()) "- 完成率: $pct%" else "- Adherence: $pct%"

        fun planPhaseLine(phaseRaw: String, ruleVersion: Int): String =
            if (zh()) "- Phase: $phaseRaw, ruleVersion=$ruleVersion"
            else "- Phase: $phaseRaw, ruleVersion=$ruleVersion"
        fun planModuleLine(moduleRaw: String, done: Int, total: Int): String =
            if (zh()) "- $moduleRaw: $done / $total" else "- $moduleRaw: $done / $total"
    }

    // ---- Constants -----------------------------------------------------

    private const val ZH_SAFETY: String =
        "請勿建議任何劑量、強度或頻率變更;僅以你看到的數字解釋已決定的計畫。"
    private const val EN_SAFETY: String =
        "Do not suggest any change to dose, intensity, or frequency; only paraphrase " +
            "the already-decided plan based on the numbers shown."

    private const val ZH_DAILY: String =
        "你是 SilverBp 的居家健康教練,語氣親切、用語簡單,像家人提醒長輩。" +
            "目標是讓今天的任務聽起來容易執行,並提供一個小動機。"
    private const val EN_DAILY: String =
        "You are SilverBp's home health coach. Speak warmly and simply, like a " +
            "family member reminding an elder. Make today's task sound easy to do " +
            "and add one small motivator."

    private const val ZH_WEEKLY: String =
        "你是 SilverBp 的居家健康教練。請用三段格式撰寫本週報告:" +
            "(1) 回顧上週數據; (2) 一個值得鼓勵的亮點; (3) 下週重點與一個明確可做的小動作。" +
            "全程繁體中文,字數約 200–300 字,避免醫療術語。"
    private const val EN_WEEKLY: String =
        "You are SilverBp's home health coach. Write a weekly report in three " +
            "paragraphs: (1) review last week's numbers; (2) one highlight worth " +
            "encouraging; (3) next-week focus plus one concrete small action. " +
            "Write in English, around 200–300 words, avoid medical jargon."

    private const val ZH_ANOMALY: String =
        "你是 SilverBp 的居家健康教練。語氣冷靜不驚慌,先肯定使用者願意量測," +
            "再給一個立刻可做的放鬆動作 (深呼吸、坐下、補水)。"
    private const val EN_ANOMALY: String =
        "You are SilverBp's home health coach. Stay calm — first acknowledge that " +
            "the user took the measurement, then offer one immediate relaxation " +
            "action (deep breath, sit down, drink water)."

    private const val ZH_CHAT: String =
        "你是 SilverBp 健康助理。依下方各區段 (## 最新血壓 / ## 血壓統計 / ## 運動 / ## 最近徽章) " +
            "之資料以繁體中文簡短回答；僅在對應區段明確顯示無紀錄時才回「目前沒有相關紀錄」。" +
            "不開處方、不下診斷，數值異常時提醒就診。"
    private const val EN_CHAT: String =
        "You are SilverBp's health assistant. Answer briefly in English using the " +
            "data in the sections below (## Latest reading / ## BP stats / ## Exercise / " +
            "## Recent badges); only reply \"No relevant record yet\" when the matching " +
            "section explicitly shows no record. Do not prescribe, do not diagnose, " +
            "and remind the user to see a doctor when readings are abnormal."
}
