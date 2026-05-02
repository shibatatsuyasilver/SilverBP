package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Read-only Health Connect surface for the Coach feature: sleep session
 * duration and nutrition (sodium) per day.
 *
 * Co-existence note: [com.silverbp.android.exercise.HealthConnectExerciseBridge]
 * already owns the exercise write path + step read path; we deliberately do
 * NOT delegate through it to keep two well-tested paths independent. Both
 * resolve the same singleton [HealthConnectClient], so there's no extra cost.
 */
class HealthConnectBridge(private val context: Context) {

    val sleepReadPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    val nutritionReadPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(NutritionRecord::class),
    )

    private fun client(): HealthConnectClient? = runCatching {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        HealthConnectClient.getOrCreate(context)
    }.getOrNull()

    suspend fun hasSleepReadPermission(): Boolean = hasGranted(sleepReadPermissions)
    suspend fun hasNutritionReadPermission(): Boolean = hasGranted(nutritionReadPermissions)

    private suspend fun hasGranted(perms: Set<String>): Boolean {
        val c = client() ?: return false
        val granted = runCatching { c.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        return granted.containsAll(perms)
    }

    /**
     * One [SleepEntry] per local-zone calendar day, total nightly minutes.
     * Multi-segment sleep (waking up, going back) is summed.
     *
     * Returns null if the SDK isn't installed or the permission isn't granted —
     * caller treats null as "no sync this run".
     */
    suspend fun querySleep(
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SleepEntry>? {
        val c = client() ?: return null
        if (!hasSleepReadPermission()) return null
        return runCatching {
            val req = AggregateGroupByPeriodRequest(
                metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    fromInclusive.atStartOfDay(),
                    toInclusive.plusDays(1).atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            )
            val rows: List<AggregationResultGroupedByPeriod> = c.aggregateGroupByPeriod(req)
            rows.mapNotNull { r ->
                val durationMin = r.result[SleepSessionRecord.SLEEP_DURATION_TOTAL]
                    ?.toMinutes()?.toInt() ?: return@mapNotNull null
                val dayStart = r.startTime.atZone(zone).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                SleepEntry(dayStartMillis = dayStart, durationMin = durationMin)
            }
        }.onFailure { Log.w(TAG, "[HC] sleep query failed", it) }.getOrNull()
    }

    /**
     * Per-day total sodium intake in milligrams. Health Connect's
     * NutritionRecord stores grams (Double); we sum across all records that
     * fall within each calendar day.
     *
     * Returns null on missing SDK / permission.
     */
    suspend fun queryNutrition(
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<NutritionEntry>? {
        val c = client() ?: return null
        if (!hasNutritionReadPermission()) return null
        return runCatching {
            val records = c.readRecords(
                ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        fromInclusive.atStartOfDay(),
                        toInclusive.plusDays(1).atStartOfDay(),
                    ),
                ),
            ).records
            // Manual day-bucket aggregation; HC's group-by-period requires
            // each metric to support aggregation, and `sodium` does, but we
            // also keep this loop so callers without the experimental
            // aggregation API still work.
            val byDay = sortedMapOf<Long, Double>()
            for (r in records) {
                val grams = r.sodium?.inGrams ?: continue
                val dayMillis = r.startTime.atZone(zone).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                byDay.merge(dayMillis, grams) { a, b -> a + b }
            }
            byDay.map { (millis, g) ->
                NutritionEntry(dayStartMillis = millis, sodiumMg = (g * 1_000.0).toInt())
            }
        }.onFailure { Log.w(TAG, "[HC] nutrition query failed", it) }.getOrNull()
    }

    private companion object { const val TAG = "HCBridge" }
}

data class SleepEntry(val dayStartMillis: Long, val durationMin: Int)
data class NutritionEntry(val dayStartMillis: Long, val sodiumMg: Int)

/**
 * Map a milligram total to the bucket the Coach engine consumes:
 *  - Low:  < 1500 mg  (well below AHA target)
 *  - Mid:  1500–2300 mg (around target)
 *  - High: > 2300 mg (above target)
 */
fun classifySodium(mg: Int): String = when {
    mg < 1500 -> "low"
    mg <= 2300 -> "mid"
    else -> "high"
}
