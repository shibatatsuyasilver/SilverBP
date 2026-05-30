package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.silverbp.android.core.Arm
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.Posture
import java.time.ZoneId

/**
 * Best-effort write of a [BpReading] to Health Connect as a
 * [BloodPressureRecord], so readings flow into the Google Health app (and any
 * other Health-Connect-aware app the user has installed).
 *
 * Mirrors [com.silverbp.android.exercise.HealthConnectExerciseBridge]: resolves
 * the same singleton [HealthConnectClient], degrades to null when the SDK is
 * missing or the permission isn't granted, and never throws into the caller —
 * a failed mirror must not abort the local Room save.
 *
 * Write-only by design: the app's own DB stays the source of truth for blood
 * pressure (matching iOS, which likewise keeps BP out of Apple Health); Health
 * Connect is a one-way mirror. Reading externally-measured BP back in is a
 * possible future addition (would need READ_BLOOD_PRESSURE + write-back de-dup).
 */
class HealthConnectBpBridge(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
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
     * Write one [BloodPressureRecord]. `clientRecordId` is set to the reading's
     * own id so Health Connect upserts (rather than duplicates) when the same
     * reading is edited and re-saved.
     *
     * Returns the record id on success, null on any failure (missing
     * permission, no Health Connect installed, malformed data, ...).
     */
    suspend fun write(reading: BpReading): String? {
        val c = client() ?: return null
        if (!hasWritePermission()) return null
        return runCatching {
            val offset = ZoneId.systemDefault().rules.getOffset(reading.timestamp)
            val record = BloodPressureRecord(
                time = reading.timestamp,
                zoneOffset = offset,
                systolic = Pressure.millimetersOfMercury(reading.systolic.toDouble()),
                diastolic = Pressure.millimetersOfMercury(reading.diastolic.toDouble()),
                bodyPosition = bodyPosition(reading.posture),
                measurementLocation = measurementLocation(reading.arm),
                metadata = Metadata.manualEntry(clientRecordId = reading.id.toString()),
            )
            c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }.onFailure { Log.w(TAG, "[HC] BP write failed", it) }.getOrNull()
    }

    private fun bodyPosition(p: Posture): Int = when (p) {
        Posture.Sitting -> BloodPressureRecord.BODY_POSITION_SITTING_DOWN
        Posture.Supine -> BloodPressureRecord.BODY_POSITION_LYING_DOWN
        Posture.Standing -> BloodPressureRecord.BODY_POSITION_STANDING_UP
    }

    // Home BP cuffs sit on the upper arm; map L/R accordingly.
    private fun measurementLocation(arm: Arm): Int = when (arm) {
        Arm.Left -> BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM
        Arm.Right -> BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM
    }

    private companion object { const val TAG = "HCBpBridge" }
}
