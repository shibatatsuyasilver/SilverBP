package com.silverbp.android

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import com.google.android.gms.maps.MapsInitializer
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
import com.silverbp.android.sync.engine.Hlc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

private const val ANDROID_15_API = 35

class SilverBpApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Begin observing process foreground/background for the app-lock gate.
        ServiceLocator.lockManager.attach()

        // Pin the LEGACY Google Maps renderer. Play services silently migrates
        // apps to the new ("LATEST") renderer, which renders a BLACK map on some
        // GPUs/ROMs (observed on vivo V2562 / Android 16 / Adreno: no auth
        // failure, Maps SDK + GPU init fine, but the map's TextureView presents
        // an empty buffer → black). LEGACY renders correctly there. Must run
        // before the first MapView is created, so it lives here in onCreate.
        // The callback logs which renderer Play services actually granted.
        MapsInitializer.initialize(this, MapsInitializer.Renderer.LEGACY) { renderer ->
            Log.i("SilverBpApplication", "[Maps] renderer in use: $renderer")
        }
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
        appScope.launch { reconcileSync() }

        // Debug-only: dump per-session route-point coverage so a "restored
        // session has no route map" report can be triaged from logcat (the
        // health DB is SQLCipher ciphertext and can't be inspected externally).
        if (BuildConfig.DEBUG) appScope.launch { diagnoseExerciseRoutes() }

        // Watcher fires anomaly notifications in real time as the user logs
        // new readings; lives for the app process's lifetime.
        ServiceLocator.bpAnomalyWatcher.start(appScope)
    }

    /**
     * Debug triage for missing exercise route maps. For every GPS-trackable
     * session, log its distance/steps and how many route_point rows it has.
     * Read it as: `distance>0` but `points<2` ⇒ a real point loss to chase;
     * `distance==0` ⇒ the source/environment never produced a fix (e.g. an
     * emulator with no GPS, or an indoor recording). Filter logcat by the
     * `[RouteDiag]` tag.
     */
    private suspend fun diagnoseExerciseRoutes() {
        runCatching {
            val dao = ServiceLocator.database.exerciseDao()
            val sessions = dao.observeAll().first()
            var suspect = 0
            for (s in sessions) {
                val kind = com.silverbp.android.exercise.ActivityKind.fromRaw(s.activityKind)
                if (!kind.isGpsTrackable) continue
                val points = dao.pointsFor(s.id).size
                val flag = if (s.distanceMeters > 0.0 && points < 2) " <-- LOST?" else ""
                if (flag.isNotEmpty()) suspect++
                android.util.Log.i(
                    "RouteDiag",
                    "session=${s.id} kind=${s.activityKind} dist=${s.distanceMeters} " +
                        "steps=${s.stepCount} points=$points$flag",
                )
            }
            android.util.Log.i("RouteDiag", "done: ${sessions.size} sessions, $suspect suspected route losses")
        }.onFailure { android.util.Log.w("RouteDiag", "diagnostic failed: ${it.message}") }
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
     *
     * Medication reminders are decoupled from `enableCoach` — they are a core BP
     * feature — so they are reconciled unconditionally (and before the settings
     * read, so a transient settings-load failure can't drop them). The scheduler
     * itself no-ops when no enabled schedule exists, so this is safe for users
     * with no medications.
     */
    private suspend fun reconcileCoach() {
        MedicationReminderScheduler.scheduleAll(this)
        val s = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull() ?: return
        if (s.enableCoach) {
            CoachReminderScheduler.scheduleAll(this)
        } else {
            CoachReminderScheduler.cancelAll(this)
        }
        // Best-effort backfill on every cold start when opted-in. The per-type
        // read permission is re-checked inside each worker, but on Android 15+ a
        // WorkManager job ALSO needs the background-read grant to read Health
        // Connect at all — so gate the cold-start enqueue on it (mirroring how
        // reconcileHealthConnect gates its background read workers) rather than
        // kicking a worker that would silently read nothing before the gate is
        // satisfied. The grant arrives via the Settings HC flow, which re-runs
        // its own reconcile/enqueue when the user accepts it.
        if (canRunBackgroundHealthConnectReads()) {
            if (s.enableCoach && s.sleepTrackingEnabled) {
                SleepBackfillWorker.enqueue(this)
            }
            if (s.enableCoach && s.dietTrackingEnabled) {
                NutritionBackfillWorker.enqueue(this)
            }
        }
    }

    /**
     * Mirror of `canRunBackgroundHealthConnectReads` in the Settings Health
     * Connect reconcile: Android 15+ (API 35) requires the background-read
     * permission before a WorkManager job may read Health Connect at all. Below
     * API 35 no extra grant is needed.
     */
    private suspend fun canRunBackgroundHealthConnectReads(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_15_API) return true
        val granted = runCatching {
            if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
                return false
            }
            HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        return granted.containsAll(ServiceLocator.healthConnectBridge.backgroundReadPermissions)
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
     * Schedule (or cancel) background LAN sync on cold start. Only registered when
     * at least one peer is paired — pairing is currently DEBUG-only, so this is a
     * no-op in release. Uses KEEP via [com.silverbp.android.sync.SyncScheduler] so
     * a healthy schedule keeps its next-run anchor. The two-device rendezvous
     * itself can only be validated on physical devices on one Wi-Fi.
     */
    private suspend fun reconcileSync() {
        // Harden the HLC clock against a prefs-losing restore: a .sbpbk or system
        // restore (or clear-data) can leave tombstones whose HLCs exceed the
        // persisted clock seed. Fold the DB high-water into the clock here on the
        // IO thread — before the first post-restore local write — so it can't
        // issue a backwards HLC and silently flip the LWW gate (QA P0-4).
        runCatching {
            ServiceLocator.database.syncDao().maxTombstoneHlc()
                ?.let { ServiceLocator.syncCoordinator.clock.observe(Hlc(it)) }
        }
        val hasPeers = runCatching {
            ServiceLocator.database.syncDao().allDevices().isNotEmpty()
        }.getOrDefault(false)
        val scheduler = com.silverbp.android.sync.SyncScheduler(this)
        if (hasPeers) scheduler.reconcile() else scheduler.cancel()
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
