package com.silverbp.android.coach

import android.content.Context
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
 * Cooldown: at most one notification per [UserSettings.coachAnomalyCooldownMin]
 * minutes (default 30). State stored in process memory — losing it across
 * cold starts is acceptable; users won't get spammed and the user-flagged
 * suppress window for the same reading won't fire twice in a 24-hour window
 * anyway because the rule engine only counts unique readings.
 */
class BpAnomalyWatcher(
    private val context: Context,
    private val bp: BpRepository,
    private val engine: CoachEngine,
    private val settings: UserSettingsRepository,
) {
    @Volatile private var lastFiredAtMillis: Long = 0L

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            // .drop(1) skips the initial cached emission so we don't re-trip on
            // app cold start. .debounce coalesces bursts when the user types
            // several historical readings in quick succession (manual entry).
            bp.observeAll()
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
        if (now - lastFiredAtMillis < COOLDOWN_MILLIS) return

        val event = engine.detectAnomaly() ?: return
        CoachNotifier.postAnomaly(context, event.severity)
        lastFiredAtMillis = now
        Log.i(TAG, "[Anomaly] posted ${event.severity.raw} at $now")
    }

    private companion object {
        const val TAG = "BpAnomalyWatcher"
        // 30-minute baseline; if you wire it to UserSettings.coachAnomalyCooldownMin
        // in a follow-up PR, read it inside detectAndMaybePost() instead of a const.
        const val COOLDOWN_MILLIS = 30 * 60 * 1_000L
    }
}
