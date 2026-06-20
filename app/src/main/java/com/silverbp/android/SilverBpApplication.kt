package com.silverbp.android

import android.app.Application
import com.silverbp.android.achievements.MedalNotifier
import com.silverbp.android.backup.auto.BackupNotification
import com.silverbp.android.billing.EntitlementRevalidationScheduler
import com.silverbp.android.coach.CoachNotifier
import com.silverbp.android.coach.CoachReminderScheduler
import com.silverbp.android.coach.MedicationReminderScheduler
import com.silverbp.android.coach.NutritionBackfillWorker
import com.silverbp.android.coach.SleepBackfillWorker
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ExerciseNotification
import com.silverbp.android.nutrition.BulkNutritionStore
import com.silverbp.android.nutrition.NutritionDatabase
import com.silverbp.android.recognition.DeviceCapabilities
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.recognition.ModelDownloadNotification
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
        ModelDownloadNotification.createChannel(this)
        BackupNotification.createChannel(this)
        DeviceCapabilities.logFingerprint()
        // If the Gemma 3n .task file already exists in filesDir/models/,
        // kick a background preload so capture isn't blocked. No-op
        // otherwise — the user triggers a download from Settings.
        ModelBootstrap.start(this)

        appScope.launch { loadBulkNutrition() }
        appScope.launch { sweepOldChatImages() }
        appScope.launch { reconcileHealthConnect() }
        appScope.launch { reconcileCoach() }
        appScope.launch { reconcileEntitlement() }
        appScope.launch { reconcileAutoBackup() }

        // Watcher fires anomaly notifications in real time as the user logs
        // new readings; lives for the app process's lifetime.
        ServiceLocator.bpAnomalyWatcher.start(appScope)
    }

    /**
     * Background-load the bundled long-tail nutrition dataset (TFDA/USDA) and
     * wire it into [NutritionDatabase] as the fallback layer behind the 66
     * curated records. Best-effort: on any failure (missing/corrupt asset)
     * match() simply stays curated-only, so food logging still works.
     */
    private fun loadBulkNutrition() {
        runCatching { NutritionDatabase.bulk = BulkNutritionStore.fromAsset(this) }
            .onFailure {
                android.util.Log.w("SilverBpApplication", "[Nutrition] bulk load failed: ${it.message}")
            }
    }

    /**
     * Mirror [reconcileHealthConnect] for the Coach feature: align the WorkManager
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
     * Resolve the subscription entitlement on every cold start (writes the
     * last-known cache via [EntitlementManager.queryPurchasesOnStartup]) and
     * keep the 24 h revalidation worker scheduled. Both are safe when Play is
     * unavailable (emulator / unpublished product): the query degrades to empty
     * → Free (still unlocked while PREMIUM_ENFORCED=false), and the worker
     * self-retries behind its network constraint. Always scheduled — there is no
     * user toggle for billing; the master gate is the BuildConfig flag.
     */
    private suspend fun reconcileEntitlement() {
        runCatching { ServiceLocator.entitlementManager.queryPurchasesOnStartup() }
        EntitlementRevalidationScheduler.schedule(this)
    }

    /**
     * Mirror [reconcileCoach] for Google Drive auto-backup: re-register the
     * periodic WorkManager schedule on every cold start to match the persisted
     * `autoBackupFrequency`. WorkManager survives normal process death and
     * reboots on its own, but a force-stop / OEM task-killer cancels the work
     * with nothing to restore it — this is that restore path (KEEP, so a
     * healthy schedule is left untouched). Equivalent to iOS re-submitting its
     * backup BGTask in `SilverBPApp.init()`.
     */
    private suspend fun reconcileAutoBackup() {
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull() ?: return
        ServiceLocator.autoBackupScheduler.reconcile(s.autoBackupFrequency)
    }

    /**
     * Cold-start reconciliation of the Health Connect integration: delegate to
     * the shared [reconcileHealthConnect], which re-checks the full core
     * permission set (not just steps), schedules/cancels every mirror + step
     * worker to match, and flips the master toggle off if the user revoked a
     * core permission in the system Health Connect app while we were killed.
     */
    private suspend fun reconcileHealthConnect() {
        com.silverbp.android.ui.settings.reconcileHealthConnect(this)
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
