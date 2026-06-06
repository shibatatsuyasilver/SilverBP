package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.silverbp.android.nutrition.FoodLog
import java.time.ZoneId

/**
 * Best-effort one-way mirror of a logged [FoodLog] into Health Connect as a
 * [NutritionRecord], so meals flow into Google Health / other HC-aware apps.
 *
 * Mirrors [HealthConnectBpBridge]: resolves the same singleton client, returns
 * null (never throws) when the SDK is missing or WRITE_NUTRITION isn't granted,
 * and uses `clientRecordId` = the log id so edits upsert instead of duplicate.
 *
 * Sodium note: a photo-estimated sodium value is rough; we still mirror it so
 * HC has *a* number, but the app keeps the [com.silverbp.android.nutrition.
 * SodiumSource] distinction (label vs estimate) only in its own DB.
 */
class HealthConnectNutritionBridge(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
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

    /** Returns the HC record id on success, null on any failure. */
    suspend fun write(log: FoodLog): String? {
        val c = client() ?: return null
        if (!hasWritePermission()) return null
        return runCatching {
            val zone = ZoneId.systemDefault()
            val start = log.timestamp
            // NutritionRecord is an interval record; give it a 1-second span so
            // end is strictly after start.
            val end = start.plusSeconds(1)
            val offset = zone.rules.getOffset(start)
            val record = NutritionRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = offset,
                energy = log.calories?.let { Energy.kilocalories(it) },
                protein = log.proteinG?.let { Mass.grams(it) },
                totalCarbohydrate = log.carbsG?.let { Mass.grams(it) },
                totalFat = log.fatG?.let { Mass.grams(it) },
                sugar = log.sugarG?.let { Mass.grams(it) },
                dietaryFiber = log.fiberG?.let { Mass.grams(it) },
                sodium = log.sodiumMg?.let { Mass.grams(it / 1000.0) },
                name = log.description.ifBlank { log.productName ?: "" }.ifBlank { null },
                metadata = Metadata.manualEntry(clientRecordId = log.id.toString()),
            )
            c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }.onFailure { Log.w(TAG, "[HC] nutrition write failed", it) }.getOrNull()
    }

    private companion object { const val TAG = "HCNutritionBridge" }
}
