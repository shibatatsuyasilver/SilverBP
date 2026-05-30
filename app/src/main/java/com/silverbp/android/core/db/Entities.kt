package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bp_reading", indices = [Index("timestamp")])
data class BpReadingEntity(
    @PrimaryKey val id: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val timestamp: Long,
    val arm: String,
    val posture: String,
    val partOfDay: String,
    val beforeMedication: Boolean,
    val photoFilename: String?,
    val confidence: Double,
    val source: String,
    val note: String,
    val irregularHeartbeat: Boolean,
    val medicationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Packed HLC string for cross-device LWW. Lex-sortable; defaults to "0" pre-sync. */
    val hlcUpdatedAt: String = "0",
    /**
     * Health Connect record id once this reading has been mirrored, else null.
     * Null marks the row as "not yet mirrored" so [com.silverbp.android.health.
     * BpSyncWorker] can retry it. Device-local: never synced or backed up (a
     * fresh device re-mirrors and gets its own id), see [com.silverbp.android.
     * sync.BpReadingSyncMapper].
     */
    val hcRecordId: String? = null,
)

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val birthYear: Int,
    val hasDiabetes: Boolean,
    val hasCKD: Boolean,
    val hasASCVD: Boolean,
    val guideline: String,
)

@Entity(tableName = "medication")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val dose: String,
    /** [MedicationKind] string discriminator. Default keeps v5 rows classified as drugs. */
    val kind: String = MedicationKind.MEDICATION,
    /** Packed HLC for cross-device LWW; "0" pre-sync. v7→v8 migration. */
    val hlcUpdatedAt: String = "0",
)

object MedicationKind {
    const val MEDICATION = "medication"
    const val SUPPLEMENT = "supplement"
}

@Entity(
    tableName = "medication_schedule",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class MedicationScheduleEntity(
    @PrimaryKey val id: String,
    val medicationId: String,
    /** 7-bit ISO mask: bit (DayOfWeek.value - 1). bit0=Mon … bit6=Sun. */
    val daysOfWeekMask: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val hlcUpdatedAt: String = "0",
)

@Entity(tableName = "tag")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "reading_tag", primaryKeys = ["readingId", "tagId"])
data class ReadingTagCrossRef(
    val readingId: String,
    val tagId: String,
)

@Entity(
    tableName = "exercise_session",
    indices = [Index("startedAt"), Index("activityKind")],
)
data class ExerciseSessionEntity(
    @PrimaryKey val id: String,
    val activityKind: String,
    val startedAt: Long,
    val endedAt: Long,
    /**
     * Running-state-only duration. Excludes Paused/AutoPaused time so UI and
     * aggregations don't inflate exercise minutes with sit-down breaks.
     * Backfilled for v11→v12 legacy rows as `endedAt - startedAt` (wall-clock,
     * matches previous display behaviour). New rows carry the real value from
     * [com.silverbp.android.exercise.ExerciseSessionLiveStore.SessionLive.activeDurationMillis].
     */
    val activeDurationMillis: Long,
    val distanceMeters: Double,
    val stepCount: Int?,
    val averagePaceSecPerKm: Double?,
    val source: String,
    val note: String,
    /** Health Connect record id — platform-local, NOT synced to peers. */
    val hcRecordId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
)

@Entity(
    tableName = "route_point",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("timestamp")],
)
data class RoutePointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val horizontalAccuracy: Float,
    val altitude: Double?,
    val speedMps: Float?,
    val hlcUpdatedAt: String = "0",
)
