package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import com.silverbp.android.core.WeightHealthConnectBridge
import com.silverbp.android.core.WeightReading
import java.time.ZoneId

/**
 * Best-effort write of a [WeightReading] to Health Connect as a [WeightRecord],
 * so body-weight readings flow into the Google Health app (and any other
 * Health-Connect-aware app the user has installed).
 *
 * Mirrors [HealthConnectGlucoseBridge] exactly: resolves the same singleton
 * [HealthConnectClient], degrades to null when the SDK is missing or the
 * permission isn't granted, and never throws into the caller — a failed mirror
 * must not abort the local Room save.
 *
 * Owner-only by design (roadmap §3-5 / §4-4): the guard that a family member's
 * weight must never be written into the device owner's Google Health lives in
 * [com.silverbp.android.core.WeightRepository] (and [com.silverbp.android.core.
 * db.WeightDao.findUnmirrored] for the retry set). This bridge keeps its single
 * responsibility — it just writes whatever reading it is handed.
 *
 * Write-only by design: the app's own DB stays the source of truth for weight;
 * Health Connect is a one-way mirror (matching the BP / glucose bridges).
 *
 * Implements [WeightHealthConnectBridge] (declared in the `core` data layer so
 * the v20 data layer compiled standalone before this bridge landed);
 * [com.silverbp.android.di.ServiceLocator] swaps this in for the previously-null
 * bridge.
 */
class HealthConnectWeightBridge(private val context: Context) : WeightHealthConnectBridge {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    private fun client(): HealthConnectClient? = runCatching {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        HealthConnectClient.getOrCreate(context)
    }.getOrNull()

    suspend fun hasWritePermission(): Boolean {
        val c = client() ?: return false
        val granted = runCatching { c.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        return granted.containsAll(permissions)
    }

    /**
     * Write one [WeightRecord]. `clientRecordId` is set to the reading's own id so
     * Health Connect upserts (rather than duplicates) when the same reading is
     * edited and re-saved.
     *
     * The canonical value is kg; HC's [Mass] takes kilograms directly (no lossy
     * lb round-trip — the display unit is a UI concern only).
     *
     * Returns the record id on success, null on any failure (missing permission,
     * no Health Connect installed, malformed data, ...).
     */
    override suspend fun write(reading: WeightReading): String? {
        val c = client() ?: return null
        if (!hasWritePermission()) return null
        return runCatching {
            val offset = ZoneId.systemDefault().rules.getOffset(reading.timestamp)
            val record = WeightRecord(
                time = reading.timestamp,
                zoneOffset = offset,
                weight = Mass.kilograms(reading.weightKg),
                metadata = Metadata.manualEntry(clientRecordId = reading.id.toString()),
            )
            c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }.onFailure { Log.w(TAG, "[HC] weight write failed", it) }.getOrNull()
    }

    private companion object { const val TAG = "HCWeightBridge" }
}
