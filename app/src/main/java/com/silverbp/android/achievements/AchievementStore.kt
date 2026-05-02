package com.silverbp.android.achievements

import android.content.Context
import android.util.Log
import com.silverbp.android.core.db.AchievementDao
import com.silverbp.android.core.db.AchievementEntity
import com.silverbp.android.core.db.DailyStepLogEntity
import com.silverbp.android.exercise.HealthConnectExerciseBridge
import com.silverbp.android.exercise.StepCounterReader
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * App-scoped orchestrator for the achievements feature. Owns the StateFlow
 * the UI binds to and the SharedFlow that drives the unlock banner.
 *
 * `refresh` is the single entry point and is fully idempotent + re-entrant
 * (mutex-guarded). Triggers:
 *   - app foreground (lifecycle observer in [com.silverbp.android.SilverBpApplication])
 *   - exercise tab onResume (LaunchedEffect)
 *   - after [com.silverbp.android.exercise.ExerciseRepository.upsert]
 *   - settings change to dailyStepGoal
 */
class AchievementStore(
    private val context: Context,
    private val achievementDao: AchievementDao,
    private val bridge: HealthConnectExerciseBridge,
    private val settings: UserSettingsRepository,
    private val sensor: StepCounterReader,
    private val baseline: StepBaselineStore,
) {

    /** Snapshot of one persisted unlock for UI display. */
    data class UnlockedMedal(val kind: MedalKind, val unlockedAtMillis: Long)

    data class UiState(
        val recent: List<UnlockedMedal> = emptyList(),
        val unlockedSet: Set<MedalKind> = emptySet(),
        val stats: AchievementStats = DEFAULT_STATS,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<List<MedalKind>>(extraBufferCapacity = 8)
    /** One-shot stream of newly-unlocked medals. UI collects to show the banner. */
    val unlockEvents: SharedFlow<List<MedalKind>> = _events.asSharedFlow()

    /** Fire-and-forget refresh — for callers that don't want to suspend. */
    fun launchRefresh() {
        scope.launch { refresh() }
    }

    /**
     * Resample step sources, recompute stats, persist any new unlocks, and
     * emit them on [unlockEvents]. Mutex-guarded so concurrent calls (e.g.
     * foreground + post-session) collapse safely.
     */
    suspend fun refresh() = mutex.withLock {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val userSettings = runCatching { settings.flow.first() }.getOrNull()
        val dailyGoal = userSettings?.dailyStepGoal ?: 8000

        // 1. HC backfill: last 365 days. Result is null when SDK absent or perm denied.
        val hcDays = runCatching {
            bridge.queryDailySteps(today.minusDays(364), today, zone)
        }.getOrNull()
        if (hcDays != null) {
            val nowMs = System.currentTimeMillis()
            achievementDao.upsertStepLogs(hcDays.map { d ->
                DailyStepLogEntity(
                    dayStart = d.dayStartMillis,
                    steps = d.steps,
                    sourceRaw = SOURCE_HEALTH_CONNECT,
                    updatedAt = nowMs,
                )
            })
        }

        // 2. Today: prefer HC, fall back to sensor baseline.
        val todayDayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayFromHc = hcDays?.firstOrNull { it.dayStartMillis == todayDayStart }?.steps
        val todaySteps = todayFromHc
            ?: readSensorTodaySteps(todayDayStart)
            ?: 0
        if (todayFromHc == null) {
            achievementDao.upsertStepLog(
                DailyStepLogEntity(
                    dayStart = todayDayStart,
                    steps = todaySteps,
                    sourceRaw = SOURCE_SENSOR,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

        // 3. Build stats.
        val sessionCount = achievementDao.sessionCount()
        val lifetime = achievementDao.totalLoggedSteps()
        val streak = computeCurrentStreak(zone, dailyGoal)
        val stats = AchievementStats(
            todaySteps = todaySteps,
            lifetimeSteps = lifetime,
            currentStreakDays = streak,
            sessionCount = sessionCount,
            dailyStepGoal = dailyGoal,
        )

        // 4. Evaluate.
        val existingRows = achievementDao.listAll()
        val existing = existingRows.mapNotNull { MedalKind.fromRaw(it.kindRaw) }.toSet()
        val newUnlocks = AchievementEvaluator.evaluate(stats, existing)

        if (newUnlocks.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val rows = newUnlocks.map { m ->
                AchievementEntity(
                    kindRaw = m.kindRaw,
                    unlockedAt = now,
                    notifiedAt = null,
                    unlockedBackfilled = false,
                    valueAtUnlock = currentValue(m, stats),
                )
            }
            achievementDao.insertAll(rows)

            val notify = userSettings?.notifyOnMedalUnlock != false &&
                MedalNotifier.hasPostPermission(context)
            if (notify) {
                if (newUnlocks.size == 1) {
                    MedalNotifier.postMedalUnlocked(context, newUnlocks[0])
                } else {
                    MedalNotifier.postMultipleMedalsUnlocked(context, newUnlocks.size)
                }
                rows.forEach { achievementDao.markNotified(it.kindRaw, now) }
            }
            _events.tryEmit(newUnlocks)
        }

        // 5. Refresh UI state.
        val recentRows = achievementDao.recent(20)
        val recent = recentRows.mapNotNull { row ->
            MedalKind.fromRaw(row.kindRaw)?.let { UnlockedMedal(it, row.unlockedAt) }
        }
        val unlockedSet = recentRows.mapNotNull { MedalKind.fromRaw(it.kindRaw) }.toSet()
            .plus(achievementDao.listAll().mapNotNull { MedalKind.fromRaw(it.kindRaw) })
        _state.update { UiState(recent = recent, unlockedSet = unlockedSet, stats = stats) }
    }

    private fun currentValue(medal: MedalKind, s: AchievementStats): Long = when (medal.category) {
        MedalCategory.DailySteps -> s.todaySteps.toLong()
        MedalCategory.Cumulative -> s.lifetimeSteps
        MedalCategory.Streak -> s.currentStreakDays.toLong()
        MedalCategory.Session -> s.sessionCount.toLong()
    }

    /**
     * Today's steps from `TYPE_STEP_COUNTER`, using a per-day baseline so the
     * cumulative-since-reboot raw value translates to within-today delta.
     * Returns null when the sensor is missing or didn't fire within the
     * timeout (stationary device).
     *
     * On first read of a new day we set the baseline = current raw and report
     * 0 for today; subsequent reads on the same day return `raw - baseline`.
     * If the device rebooted mid-day (raw < baseline), we clamp to raw rather
     * than producing a negative count.
     */
    private suspend fun readSensorTodaySteps(todayDayStart: Long): Int? {
        if (!sensor.isAvailable) return null
        val raw = withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine<Long?> { cont ->
                sensor.snapshot { v -> if (cont.isActive) cont.resume(v) }
            }
        } ?: return null

        val (savedRaw, savedDay) = baseline.read()
        return if (savedDay == todayDayStart && savedRaw != null) {
            if (raw >= savedRaw) {
                (raw - savedRaw).toInt().coerceAtLeast(0)
            } else {
                // Reboot mid-day: raw counter restarted. Best-effort: keep going.
                Log.i(TAG, "[Achievements] Sensor counter reset detected; using raw=$raw as today's count.")
                baseline.write(0L, todayDayStart)
                raw.toInt().coerceAtLeast(0)
            }
        } else {
            baseline.write(raw, todayDayStart)
            0
        }
    }

    private suspend fun computeCurrentStreak(zone: ZoneId, goal: Int): Int {
        if (goal <= 0) return 0
        val to = LocalDate.now(zone)
        val from = to.minusDays(LOOKBACK_DAYS)
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.atStartOfDay(zone).toInstant().toEpochMilli()
        val logs = achievementDao.stepLogsBetween(fromMs, toMs).associateBy { it.dayStart }

        // Today not meeting goal yet doesn't break a streak earned through yesterday.
        var cursor = to
        val todayMs = cursor.atStartOfDay(zone).toInstant().toEpochMilli()
        if ((logs[todayMs]?.steps ?: 0) < goal) {
            cursor = cursor.minusDays(1)
        }
        var streak = 0
        while (true) {
            val ms = cursor.atStartOfDay(zone).toInstant().toEpochMilli()
            val s = logs[ms]?.steps ?: 0
            if (s >= goal) {
                streak++
                cursor = cursor.minusDays(1)
            } else break
        }
        return streak
    }

    companion object {
        private const val TAG = "AchievementStore"
        private const val SOURCE_HEALTH_CONNECT = "healthconnect"
        private const val SOURCE_SENSOR = "sensor"
        private const val SENSOR_TIMEOUT_MS = 3_000L
        private const val LOOKBACK_DAYS = 365L

        private val DEFAULT_STATS = AchievementStats(
            todaySteps = 0,
            lifetimeSteps = 0L,
            currentStreakDays = 0,
            sessionCount = 0,
            dailyStepGoal = 8000,
        )
    }
}
