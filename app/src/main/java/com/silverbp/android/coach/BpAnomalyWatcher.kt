package com.silverbp.android.coach

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.silverbp.android.core.BpRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application-scoped Flow listener that fires the BP anomaly notification
 * when the rule engine detects ≥3 elevated readings in 24 h.
 *
 * Why not a Worker: WorkManager's minimum periodic interval is 15 minutes,
 * which is far too sluggish for "user just measured 3rd high reading → ping
 * within 1 second". We instead piggyback on the BP repo's existing Flow.
 *
 * Dedup: [detectAnomaly] rescans the trailing 24 h window on every new reading
 * (including normal ones), so the same elevated-readings episode would re-trip
 * the alert all day. We persist the triggering-window timestamp of the last
 * alerted episode and skip when the new anomaly's window is not strictly newer.
 * The store survives process death (SharedPreferences), so a cold start can't
 * re-fire an episode we already alerted.
 *
 * Cooldown: kept as a secondary guard — at most one notification per
 * [COOLDOWN_MILLIS] (30 min). Backstops the dedup against clock skew / rapid
 * distinct episodes.
 */
class BpAnomalyWatcher(
    private val context: Context,
    private val bp: BpRepository,
    private val engine: CoachEngine,
    private val settings: UserSettingsRepository,
) {
    @Volatile private var lastFiredAtMillis: Long = 0L
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            // Anomaly alerts are owner-only by design (roadmap §3); observe the
            // owner's BP stream as the trigger. detectAnomaly() also reads the
            // owner, so the trigger and the scan agree.
            val ownerId = ServiceLocator.memberRepository.ownerId()
            // .drop(1) skips the initial cached emission so we don't re-trip on
            // app cold start. .debounce coalesces bursts when the user types
            // several historical readings in quick succession (manual entry).
            bp.observeAll(ownerId)
                .drop(1)
                .debounce(500)
                .collect {
                    runCatching { detectAndMaybePost() }
                        .onFailure { Log.w(TAG, "[Anomaly] watcher tick failed", it) }
                }
        }
    }

    private suspend fun detectAndMaybePost() {
        val s = settings.flow.first()
        if (!s.enableCoach) return
        val now = System.currentTimeMillis()

        val event = engine.detectAnomaly() ?: return
        val lastAlertedAt = prefs.getLong(KEY_LAST_TRIGGERED, 0L)
        if (!shouldAlert(lastAlertedAt, event.triggeredAtMillis, now, lastFiredAtMillis, COOLDOWN_MILLIS)) return

        CoachNotifier.postAnomaly(context, event.severity)
        lastFiredAtMillis = now
        prefs.edit().putLong(KEY_LAST_TRIGGERED, event.triggeredAtMillis).apply()
        Log.i(TAG, "[Anomaly] posted ${event.severity.raw} at $now (window=${event.triggeredAtMillis})")
    }

    companion object {
        private const val TAG = "BpAnomalyWatcher"
        private const val PREFS = "silverbp.coach.anomaly_watcher"
        private const val KEY_LAST_TRIGGERED = "last_triggered_at"
        // 30-minute baseline; if you wire it to UserSettings.coachAnomalyCooldownMin
        // in a follow-up PR, read it inside detectAndMaybePost() instead of a const.
        const val COOLDOWN_MILLIS = 30 * 60 * 1_000L

        /**
         * Pure dedup decision. Alert only when the anomaly's triggering window is
         * strictly NEWER than the last alerted episode ([anomalyTriggeredAt] >
         * [lastAlertedAt]) — this stops the same episode re-firing as later normal
         * readings keep the elevated window in range — AND the secondary cooldown
         * has elapsed since the last fire ([now] - [lastFiredAt] ≥ [cooldownMillis]);
         * a watcher that has never fired ([lastFiredAt] == 0) has no cooldown.
         */
        fun shouldAlert(
            lastAlertedAt: Long,
            anomalyTriggeredAt: Long,
            now: Long,
            lastFiredAt: Long,
            cooldownMillis: Long,
        ): Boolean =
            anomalyTriggeredAt > lastAlertedAt &&
                (lastFiredAt == 0L || now - lastFiredAt >= cooldownMillis)
    }
}
