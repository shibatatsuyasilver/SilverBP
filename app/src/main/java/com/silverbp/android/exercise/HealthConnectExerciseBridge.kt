package com.silverbp.android.exercise

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Best-effort write of an [ExerciseSession] (+ its [RoutePoint] list) to
 * Health Connect. Mirrors the iOS HealthKit integration in BPExercise:
 * failure to write does NOT abort the local Room save.
 *
 * Caller is expected to have already requested
 * [Permission.WRITE_EXERCISE_SESSION] (and route) via the in-app permission
 * gate; if not granted, [write] catches and logs.
 */
class HealthConnectExerciseBridge(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )

    /** Read-side permission set, used by the achievements feature for step backfill. */
    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    private fun client(): HealthConnectClient? = runCatching {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        HealthConnectClient.getOrCreate(context)
    }.getOrNull()

    suspend fun hasPermissions(): Boolean {
        val c = client() ?: return false
        val granted = runCatching {
            c.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        return granted.containsAll(permissions)
    }

    suspend fun hasReadStepsPermission(): Boolean {
        val c = client() ?: return false
        val granted = runCatching {
            c.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        return granted.containsAll(readPermissions)
    }

    /**
     * Daily-bucket step counts from Health Connect for [fromInclusive]..[toInclusive]
     * (local-zone calendar days). Returns null if the SDK isn't installed or the
     * read permission isn't granted.
     *
     * Buckets are returned in ascending day order, missing days collapsed to 0.
     */
    suspend fun queryDailySteps(
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailyStepCount>? {
        val c = client() ?: return null
        if (!hasReadStepsPermission()) return null
        return runCatching {
            val req = AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    fromInclusive.atStartOfDay(),
                    toInclusive.plusDays(1).atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            )
            val results: List<AggregationResultGroupedByPeriod> = c.aggregateGroupByPeriod(req)
            results.map { r ->
                val dayStart = r.startTime.atZone(zone).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                val steps = (r.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                DailyStepCount(dayStartMillis = dayStart, steps = steps)
            }
        }.onFailure { t ->
            Log.w(TAG, "[HealthConnect] step query failed", t)
        }.getOrNull()
    }

    /**
     * Insert one [ExerciseSessionRecord] with attached [ExerciseRoute].
     * Returns the record id on success, null on any failure (missing
     * permissions, no Health Connect installed, malformed data, ...).
     */
    suspend fun write(session: ExerciseSession, points: List<RoutePoint>): String? {
        val c = client() ?: return null
        return runCatching {
            val zone = ZoneOffset.systemDefault().rules
            val record = ExerciseSessionRecord(
                startTime = session.startedAt,
                startZoneOffset = zone.getOffset(session.startedAt),
                endTime = session.endedAt,
                endZoneOffset = zone.getOffset(session.endedAt),
                exerciseType = when (session.kind) {
                    ActivityKind.Walking -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    ActivityKind.Running -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                    // No "moderately paced walking" HC type; brisk walking is plain walking.
                    ActivityKind.BriskWalking -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    ActivityKind.Cycling -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                },
                title = session.note.takeIf { it.isNotBlank() },
                exerciseRoute = if (points.size >= 2) {
                    ExerciseRoute(
                        points.map { p ->
                            ExerciseRoute.Location(
                                time = p.timestamp,
                                latitude = p.lat,
                                longitude = p.lon,
                                horizontalAccuracy = Length.meters(p.horizontalAccuracy.toDouble()),
                                altitude = p.altitude?.let { Length.meters(it) },
                            )
                        }
                    )
                } else {
                    null
                },
                // 1.1.0 stable made the raw Metadata constructor internal:
                // recording method is now chosen via a factory. clientRecordId
                // = our session id makes Health Connect upsert (not duplicate)
                // when the same session is edited and re-saved.
                metadata = Metadata.activelyRecorded(
                    device = Device(type = Device.TYPE_PHONE),
                    clientRecordId = session.id.toString(),
                ),
            )
            c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }.onFailure { t ->
            Log.w(TAG, "[HealthConnect] write failed", t)
        }.getOrNull()
    }

    private companion object { const val TAG = "HCExerciseBridge" }
}

/** Single per-day bucket; zero days are emitted by [HealthConnectExerciseBridge.queryDailySteps]. */
data class DailyStepCount(
    val dayStartMillis: Long,
    val steps: Int,
)
