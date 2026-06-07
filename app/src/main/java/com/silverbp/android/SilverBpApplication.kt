package com.silverbp.android

import android.app.Application
import com.silverbp.android.achievements.MedalNotifier
import com.silverbp.android.achievements.StepSyncScheduler
import com.silverbp.android.coach.CoachNotifier
import com.silverbp.android.coach.CoachReminderScheduler
import com.silverbp.android.coach.MedicationReminderScheduler
import com.silverbp.android.coach.NutritionBackfillWorker
import com.silverbp.android.coach.SleepBackfillWorker
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseNotification
import com.silverbp.android.health.BpSyncWorker
import com.silverbp.android.recognition.DeviceCapabilities
import com.silverbp.android.recognition.ModelBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class SilverBpApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Begin observing process foreground/background for the app-lock gate.
        ServiceLocator.lockManager.attach()
        ExerciseNotification.createChannel(this)
        MedalNotifier.createChannel(this)
        CoachNotifier.createChannels(this)
        DeviceCapabilities.logFingerprint()
        // If the Gemma 3n .task file already exists in filesDir/models/,
        // kick a background preload so capture isn't blocked. No-op
        // otherwise — the user triggers a download from Settings.
        ModelBootstrap.start(this)
        // Re-warm the local engine whenever the app returns to the foreground.
        // Attaching a chat/capture photo launches the system camera/picker,
        // which can get our multi-GB-model process evicted; without this the
        // engine stays unloaded while the UI still thinks it's Ready, and the
        // next chat send dead-ends on "模型尚未就緒".
        ModelBootstrap.attachForegroundRewarm(this)

        appScope.launch { sweepOldChatImages() }
        appScope.launch { reconcileStepSync() }
        appScope.launch { reconcileCoach() }
        appScope.launch { reconcileBpSync() }

        // Watcher fires anomaly notifications in real time as the user logs
        // new readings; lives for the app process's lifetime.
        ServiceLocator.bpAnomalyWatcher.start(appScope)
    }

    /**
     * Mirror [reconcileStepSync] for the Coach feature: align the WorkManager
     * schedule with the user's `enableCoach` toggle on every cold start so a
     * setting change made while the app was killed takes effect. Also kicks
     * off any HC backfill that's been opted into.
     */
    private suspend fun reconcileCoach() {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull() ?: return
        if (s.enableCoach) {
            CoachReminderScheduler.scheduleAll(this)
            MedicationReminderScheduler.scheduleAll(this)
        } else {
            CoachReminderScheduler.cancelAll(this)
            MedicationReminderScheduler.cancelAllSuspend(this)
        }
        // Best-effort backfill on every cold start when opted-in. Workers
        // self-no-op when permissions are revoked, so an unconditional kick
        // is fine and saves us from racing with permission grant timing.
        if (s.enableCoach && s.sleepTrackingEnabled) {
            SleepBackfillWorker.enqueue(this)
        }
        if (s.enableCoach && s.dietTrackingEnabled) {
            NutritionBackfillWorker.enqueue(this)
        }
    }

    /**
     * Kick the Health Connect blood-pressure mirror retry on every cold start
     * when the integration is on. [BpSyncWorker] self-no-ops if the BP write
     * permission is missing, so this unconditional enqueue is safe and catches
     * any readings whose inline mirror failed while HC was unavailable.
     */
    private suspend fun reconcileBpSync() {
        val enabled = runCatching { ServiceLocator.userSettings.flow.first().enableHealthConnect }
            .getOrDefault(false)
        if (enabled) BpSyncWorker.enqueue(this)
    }

    /**
     * Re-align the WorkManager periodic step-sync schedule with the current
     * setting + Health Connect read permission. The user may have toggled the
     * flag while the app was killed, or revoked the permission in the system
     * Health Connect app — either way we want the schedule to reflect today's
     * truth.
     */
    private suspend fun reconcileStepSync() {
        val enabled = runCatching { ServiceLocator.userSettings.flow.first().enableHealthConnect }
            .getOrDefault(false)
        val granted = runCatching {
            ServiceLocator.healthConnectExerciseBridge.hasReadStepsPermission()
        }.getOrDefault(false)
        if (enabled && granted) {
            StepSyncScheduler.schedule(this)
        } else {
            StepSyncScheduler.cancel(this)
            // If the user had the toggle on (carried over from a previous build,
            // or granted then revoked perms in the system Health Connect app),
            // flip the flag off so the Settings switch stays honest.
            if (enabled && !granted) {
                runCatching { ServiceLocator.userSettings.setHealthConnectEnabled(false) }
            }
        }
    }

    /**
     * Delete chat-image cache files older than 30 days. The bitmaps that the
     * chat screen attaches to messages live at `cacheDir/chat-images/<uuid>.jpg`;
     * Room only stores the path so deleting expired files reclaims disk
     * without breaking persisted history (image just won't render in old turns).
     */
    private fun sweepOldChatImages() {
        val dir = File(cacheDir, "chat-images")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff && f.delete()) removed++
        }
        if (removed > 0) {
            android.util.Log.i("SilverBpApplication", "[ChatCache] swept $removed old chat-image files")
        }
    }
}
