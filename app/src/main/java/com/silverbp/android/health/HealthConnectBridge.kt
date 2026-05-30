package com.silverbp.android.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
     * One [SleepEntry] per local-zone calendar day. Each whole sleep session is
     * attributed to the **wake-up day** (its end time's local date) and is NOT
     * split at midnight — an overnight 23:00→07:00 session counts entirely on
     * the day you woke. (The previous AggregateGroupByPeriod approach sliced the
     * session at midnight, so no single day showed the true nightly total.)
     * Multiple sessions ending on the same day are summed.
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
            val records = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        fromInclusive.atStartOfDay(zone).toInstant(),
                        toInclusive.plusDays(1).atStartOfDay(zone).toInstant(),
                    ),
                ),
            ).records
            val byDay = sortedMapOf<Long, Int>()
            for (r in records) {
                val asleepIntervals = r.stages
                    .filter { it.stage !in AWAKE_STAGE_TYPES }
                    .map { it.startTime to it.endTime }
                val minutes = sleepMinutesFromIntervals(r.startTime, r.endTime, asleepIntervals)
                if (minutes <= 0) continue
                val dayStart = wakeDayStartMillis(r.endTime, zone)
                byDay.merge(dayStart, minutes) { a, b -> a + b }
            }
            byDay.map { (millis, min) -> SleepEntry(dayStartMillis = millis, durationMin = min) }
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

/** HC stage types that count as awake (excluded from asleep duration). */
private val AWAKE_STAGE_TYPES = setOf(
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
)

/**
 * Asleep minutes for one sleep session: sum of the non-awake stage intervals
 * when stages are present (mirrors HC's SLEEP_DURATION_TOTAL), else the full
 * in-bed span. Pure + `internal` for unit testing.
 */
internal fun sleepMinutesFromIntervals(
    inBedStart: Instant,
    inBedEnd: Instant,
    asleepIntervals: List<Pair<Instant, Instant>>,
): Int = if (asleepIntervals.isNotEmpty()) {
    asleepIntervals.sumOf { Duration.between(it.first, it.second).toMinutes() }.toInt()
} else {
    Duration.between(inBedStart, inBedEnd).toMinutes().toInt().coerceAtLeast(0)
}

/** Local-midnight epoch millis of the day the session ENDED (the wake-up day). */
internal fun wakeDayStartMillis(endTime: Instant, zone: ZoneId): Long =
    endTime.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

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
