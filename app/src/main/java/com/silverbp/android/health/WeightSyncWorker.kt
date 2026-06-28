package com.silverbp.android.health

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/** Internal cursor store for the weight Health Connect import — not user-tunable. */
private val Context.weightSyncDataStore by preferencesDataStore(name = "weight_sync_internal")

/**
 * Retry/compensation + import worker for the **two-way** Health Connect
 * body-weight mirror.
 *
 * Two independently permission-gated responsibilities, so a user who grants only
 * one of the read/write permissions still gets that half:
 *
 *  1. **Write retry** — mirrors [GlucoseSyncWorker] / [BpSyncWorker] 1:1. The
 *     happy path mirrors each owner reading inline in
 *     [com.silverbp.android.core.WeightRepository.upsert]; when that write fails
 *     (Health Connect temporarily unavailable, permission not yet granted,
 *     transient error) the row is left with `hcRecordId == null`. This worker
 *     re-attempts every such row, stamping the returned record id back on success
 *     so it isn't retried again. Idempotent end-to-end: the bridge writes with
 *     `clientRecordId = reading.id`, so re-mirroring an already-present reading
 *     upserts rather than duplicates. Owner-only (roadmap §3-5 / §4-4):
 *     [com.silverbp.android.core.WeightRepository.findUnmirrored] is already
 *     owner-filtered, so the retry set never picks up a family member's rows
 *     (which stay `hcRecordId == null` by design).
 *
 *  2. **Import** — NEW vs the write-only BP/glucose mirrors: smart scales and
 *     other apps write their own [androidx.health.connect.client.records.WeightRecord]s,
 *     so we read them back in via [HealthConnectWeightBridge.importSince] and
 *     [com.silverbp.android.core.WeightRepository.upsert] them. Imported readings
 *     carry `memberId == ""`, which the repository resolves to the owner, so the
 *     import is owner-attributed. The bridge skips records this app wrote (our own
 *     mirror) and records whose HC id we already hold — this worker wires the
 *     DAO-backed `knownHcRecordIds` lookup the bridge KDoc calls for. We persist a
 *     `lastImport` cursor in a private DataStore (mirroring
 *     [com.silverbp.android.achievements.StepBaselineStore]) so each run only
 *     scans the new window.
 *
 * Self-no-op when the integration is off. Enqueued on cold start alongside
 * [BpSyncWorker] / [GlucoseSyncWorker] (see
 * [com.silverbp.android.SilverBpApplication]); WorkManager handles the backoff
 * between retries.
 */
class WeightSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = runCatching { ServiceLocator.userSettings.flow.first() }.getOrNull()
        if (settings?.enableHealthConnect != true) return Result.success()

        val repo = ServiceLocator.weightRepository
        val dao = ServiceLocator.database.weightDao()
        // Build the bridge here (mirrors how NutritionBackfillWorker constructs
        // its HealthConnectBridge inline) wired with the DAO-backed known-ids
        // lookup the bridge KDoc delegates to the import worker, so the import
        // path never re-imports a foreign record we already hold.
        val bridge = HealthConnectWeightBridge(
            context = applicationContext,
            knownHcRecordIds = {
                dao.getAll().mapNotNull { it.hcRecordId }.filter { it.isNotBlank() }.toSet()
            },
        )

        var anyFailure = false

        // 1. Write retry — gated ONLY on the WRITE permission, deliberately NOT
        // on the Android 15+ background-read grant: mirroring locally-created
        // rows UP to Health Connect must keep working for a user who granted
        // WRITE but not the background-read permission.
        if (bridge.hasWritePermission()) {
            // findUnmirrored() is owner-scoped in the repository (only the owner's
            // weight is ever mirrored); non-owner rows are hcRecordId-null by
            // design and must not be re-attempted here.
            val pending = runCatching { repo.findUnmirrored() }.getOrElse {
                Log.w(TAG, "[WeightSync] unmirrored query failed; will retry", it)
                return Result.retry()
            }
            var mirrored = 0
            for (reading in pending) {
                val hcId = bridge.write(reading)
                if (hcId != null) {
                    runCatching { dao.updateHcRecordId(reading.id.toString(), hcId) }
                    mirrored++
                } else {
                    anyFailure = true
                }
            }
            Log.i(TAG, "[WeightSync] mirrored $mirrored/${pending.size}")
        } else {
            Log.i(TAG, "[WeightSync] weight write permission not granted; skipping write retry")
        }

        // 2. Import foreign WeightRecords — only when the read permission is
        // granted AND, on Android 15+, the background-read permission is too: a
        // WorkManager job silently reads NOTHING without it, which would wrongly
        // advance the cursor past unseen records. This background-read gate is
        // the ONLY one that applies to the import half (never to the write retry
        // above). Capture the window start BEFORE the read and persist it only
        // on success, so a transient failure (or a not-yet-granted permission)
        // never advances the cursor past unseen records.
        if (bridge.hasReadPermission() && canRunBackgroundHealthConnectReads()) {
            val since = readLastImport()
            val windowStart = Instant.now()
            val imported = runCatching {
                val readings = bridge.importSince(since)
                for (reading in readings) repo.upsert(reading)
                readings.size
            }.getOrElse {
                Log.w(TAG, "[WeightSync] import failed; will retry", it)
                anyFailure = true
                null
            }
            if (imported != null) {
                writeLastImport(windowStart)
                Log.i(TAG, "[WeightSync] imported $imported reading(s)")
            }
        } else {
            Log.i(TAG, "[WeightSync] weight read or background-read permission not granted; skipping import")
        }

        // Leftover write failures (and a failed import) retry with WorkManager
        // backoff; already-mirrored rows are stamped so they won't be re-attempted.
        return if (anyFailure) Result.retry() else Result.success()
    }

    /**
     * Mirror of `canRunBackgroundHealthConnectReads` in the Settings Health
     * Connect reconcile: Android 15+ (API 35) requires the background-read
     * permission for a WorkManager job to read Health Connect at all — without
     * it the read silently returns an empty set, which would wrongly advance the
     * import cursor past unseen records. Below API 35 no extra grant is needed.
     * Gates ONLY the import half; the write retry never depends on it.
     */
    private suspend fun canRunBackgroundHealthConnectReads(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_15_API) return true
        val granted = runCatching {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                return false
            }
            HealthConnectClient.getOrCreate(applicationContext)
                .permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        return granted.containsAll(ServiceLocator.healthConnectBridge.backgroundReadPermissions)
    }

    /**
     * Last import cursor. On the first run (no stored value) we look back a
     * bounded window so a fresh install still picks up recent scale data,
     * mirroring [com.silverbp.android.coach.NutritionBackfillWorker]'s backfill.
     */
    private suspend fun readLastImport(): Instant {
        val prefs = applicationContext.weightSyncDataStore.data.first()
        return prefs[Keys.LAST_IMPORT_MS]
            ?.let { Instant.ofEpochMilli(it) }
            ?: Instant.now().minus(Duration.ofDays(INITIAL_BACKFILL_DAYS))
    }

    private suspend fun writeLastImport(at: Instant) {
        applicationContext.weightSyncDataStore.edit {
            it[Keys.LAST_IMPORT_MS] = at.toEpochMilli()
        }
    }

    private object Keys {
        val LAST_IMPORT_MS = longPreferencesKey("weight_last_import_ms")
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.health.weight-sync"
        private const val TAG = "WeightSyncWorker"

        /** Android 15 (API 35); at/above this a background HC read needs the extra grant. */
        private const val ANDROID_15_API = 35

        /** First-run look-back so a fresh install picks up recent scale data. */
        private const val INITIAL_BACKFILL_DAYS = 14L

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<WeightSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
