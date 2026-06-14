package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.silverbp.android.core.WeightHealthConnectBridge
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import java.time.Instant
import java.time.ZoneId

/**
 * Two-way Health Connect surface for body weight.
 *
 * WRITE (mirrors [HealthConnectGlucoseBridge] exactly): a best-effort one-way
 * mirror of a [WeightReading] to Health Connect as a [WeightRecord], so the
 * device owner's weight flows into Google Health (and any other
 * Health-Connect-aware app). Resolves the same singleton [HealthConnectClient],
 * degrades to null when the SDK is missing or the permission isn't granted, and
 * never throws into the caller — a failed mirror must not abort the local Room
 * save. Owner-only by design (roadmap §3-5 / §4-4): the guard that a family
 * member's weight is never written into the device owner's Google Health lives
 * in [com.silverbp.android.core.WeightRepository] (and [com.silverbp.android.
 * core.db.WeightDao.findUnmirrored] for the retry set). This bridge keeps its
 * single responsibility — it just writes whatever reading it is handed.
 *
 * READ — NEW vs the write-only BP/glucose bridges: smart scales and other apps
 * write their own [WeightRecord]s, so [importSince] reads those back in (mirrors
 * the READ pattern in [HealthConnectBridge.querySleep] / queryNutrition). Each
 * foreign record becomes a [WeightReading] with [WeightSource.HealthConnect] and
 * `memberId = ""` so [com.silverbp.android.core.WeightRepository] resolves the
 * owner on upsert. Records written by THIS app (our own mirror) and records we
 * already hold ([knownHcRecordIds]) are skipped to avoid re-importing duplicates.
 *
 * Implements [WeightHealthConnectBridge] (declared in the `core` data layer so it
 * compiles standalone before this bridge lands); [com.silverbp.android.di.
 * ServiceLocator] swaps this in for the previously-null bridge.
 */
class HealthConnectWeightBridge(
    private val context: Context,
    /**
     * HC record ids we already hold locally; foreign records carrying one of
     * these are skipped by [importSince] so a re-sync doesn't duplicate them.
     * Defaults to empty so callers that only need the write path (or that
     * construct the bridge with just a [Context]) compile unchanged; the
     * weight import worker wires in a DAO-backed lookup.
     */
    private val knownHcRecordIds: suspend () -> Set<String> = { emptySet() },
) : WeightHealthConnectBridge {

    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    private fun client(): HealthConnectClient? = runCatching {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        HealthConnectClient.getOrCreate(context)
    }.getOrNull()

    suspend fun hasWritePermission(): Boolean = hasGranted(writePermissions)
    suspend fun hasReadPermission(): Boolean = hasGranted(readPermissions)

    private suspend fun hasGranted(perms: Set<String>): Boolean {
        val c = client() ?: return false
        val granted = runCatching { c.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        return granted.containsAll(perms)
    }

    /**
     * Write one [WeightRecord]. `clientRecordId` is set to the reading's own id
     * so Health Connect upserts (rather than duplicates) when the same reading
     * is edited and re-saved.
     *
     * The canonical value is kilograms; HC's [Mass] is constructed from kg
     * directly (no lossy unit round-trip).
     *
     * Returns the record id on success, null on any failure (missing
     * permission, no Health Connect installed, malformed data, ...).
     */
    override suspend fun write(reading: WeightReading): String? {
        val c = client() ?: return null
        if (!hasWritePermission()) return null
        return runCatching {
            val offset = ZoneId.systemDefault().rules.getOffset(reading.timestamp)
            val record = WeightRecord(
                time = reading.timestamp,
                zoneOffset = offset,
                weight = Mass.kilograms(reading.valueKg),
                metadata = Metadata.manualEntry(clientRecordId = reading.id.toString()),
            )
            c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }.onFailure { Log.w(TAG, "[HC] weight write failed", it) }.getOrNull()
    }

    /**
     * Import externally-measured weight (smart scales, other apps) recorded at
     * or after [since], mapped to [WeightReading]s with
     * [WeightSource.HealthConnect]. Mirrors the READ pattern in
     * [HealthConnectBridge]: resolves the SDK, checks the read permission, and
     * swallows its own errors (returns an empty list rather than throwing).
     *
     * De-dup: records whose `dataOrigin` is THIS app (our own write-back mirror)
     * and records whose HC id is already in [knownHcRecordIds] are skipped.
     * `memberId` is left empty so [com.silverbp.android.core.WeightRepository]
     * resolves the owner on upsert.
     */
    suspend fun importSince(since: Instant): List<WeightReading> {
        val c = client() ?: return emptyList()
        if (!hasReadPermission()) return emptyList()
        return runCatching {
            val known = knownHcRecordIds()
            val ownPackage = context.packageName
            val records = c.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(since),
                ),
            ).records
            records.mapNotNull { record ->
                // Skip our own mirror (would re-import what we wrote) and any
                // record we already hold locally.
                if (record.metadata.dataOrigin.packageName == ownPackage) return@mapNotNull null
                val hcId = record.metadata.id
                if (hcId.isNotBlank() && hcId in known) return@mapNotNull null
                WeightReading(
                    memberId = "",
                    valueKg = record.weight.inKilograms,
                    timestamp = record.time,
                    source = WeightSource.HealthConnect,
                    hcRecordId = hcId,
                )
            }
        }.onFailure { Log.w(TAG, "[HC] weight import failed", it) }.getOrDefault(emptyList())
    }

    private companion object { const val TAG = "HCWeightBridge" }
}
