package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import com.silverbp.android.core.GlucoseHealthConnectBridge
import com.silverbp.android.core.GlucoseReading
import com.silverbp.android.core.MeasureContext
import java.time.ZoneId

/**
 * Best-effort write of a [GlucoseReading] to Health Connect as a
 * [BloodGlucoseRecord], so blood-glucose readings flow into the Google Health
 * app (and any other Health-Connect-aware app the user has installed).
 *
 * Mirrors [HealthConnectBpBridge] exactly: resolves the same singleton
 * [HealthConnectClient], degrades to null when the SDK is missing or the
 * permission isn't granted, and never throws into the caller — a failed mirror
 * must not abort the local Room save.
 *
 * Owner-only by design (roadmap §3-5 / §4-4): the guard that a family member's
 * glucose must never be written into the device owner's Google Health lives in
 * [com.silverbp.android.core.GlucoseRepository] (and [com.silverbp.android.core.
 * db.GlucoseDao.findUnmirrored] for the retry set). This bridge keeps its single
 * responsibility — it just writes whatever reading it is handed.
 *
 * Write-only by design: the app's own DB stays the source of truth for glucose;
 * Health Connect is a one-way mirror (matching the BP bridge). Reading back
 * externally-measured glucose is a possible future addition (would need
 * READ_BLOOD_GLUCOSE + write-back de-dup).
 *
 * Implements [GlucoseHealthConnectBridge] (declared in the `core` data layer so
 * it compiles standalone before this bridge lands); [com.silverbp.android.di.
 * ServiceLocator] swaps this in for the previously-null bridge.
 */
class HealthConnectGlucoseBridge(private val context: Context) : GlucoseHealthConnectBridge {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(BloodGlucoseRecord::class),
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
     * Write one [BloodGlucoseRecord]. `clientRecordId` is set to the reading's
     * own id so Health Connect upserts (rather than duplicates) when the same
     * reading is edited and re-saved.
     *
     * The canonical value is mg/dL; HC's [BloodGlucose] carries both scales, so
     * we hand it the mg/dL number directly (no lossy mmol/L round-trip).
     *
     * Returns the record id on success, null on any failure (missing
     * permission, no Health Connect installed, malformed data, ...).
     */
    override suspend fun write(reading: GlucoseReading): String? {
        val c = client() ?: return null
        if (!hasWritePermission()) return null
        return runCatching {
            val offset = ZoneId.systemDefault().rules.getOffset(reading.timestamp)
            val record = BloodGlucoseRecord(
                time = reading.timestamp,
                zoneOffset = offset,
                level = BloodGlucose.milligramsPerDeciliter(reading.valueMgdl),
                specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD,
                mealType = mealType(reading.measureContext),
                relationToMeal = relationToMeal(reading.measureContext),
                metadata = Metadata.manualEntry(clientRecordId = reading.id.toString()),
            )
            c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }.onFailure { Log.w(TAG, "[HC] glucose write failed", it) }.getOrNull()
    }

    // Home meters measure capillary (whole-blood) samples; the timing context
    // maps to HC's relation-to-meal taxonomy. Bedtime/random have no dedicated
    // HC value, so they fall through to GENERAL (the "no specific relation"
    // bucket), matching how the classifier treats them as the post-prandial scale.
    private fun relationToMeal(context: MeasureContext): Int = when (context) {
        MeasureContext.Fasting -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
        MeasureContext.BeforeMeal -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
        MeasureContext.AfterMeal -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
        MeasureContext.Bedtime,
        MeasureContext.Random -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
    }

    // We don't track which meal the reading relates to, only the timing relative
    // to one, so meal type stays UNKNOWN — the relationToMeal field carries the
    // clinically-relevant context.
    private fun mealType(context: MeasureContext): Int = MealType.MEAL_TYPE_UNKNOWN

    private companion object { const val TAG = "HCGlucoseBridge" }
}
