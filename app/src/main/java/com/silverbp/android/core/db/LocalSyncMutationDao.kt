package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Sync metadata writes that must be transactionally paired with local Room
 * mutations. Repositories use this through the sync-layer coordinator so a
 * local hard delete cannot commit without its tombstone.
 */
@Dao
interface LocalSyncMutationDao {
    @Upsert
    suspend fun upsertTombstone(tombstone: TombstoneEntity)

    @Query("DELETE FROM bp_reading WHERE id = :id")
    suspend fun deleteBpReading(id: String)

    @Query("DELETE FROM exercise_session WHERE id = :id")
    suspend fun deleteExerciseSession(id: String)

    @Query("DELETE FROM food_log WHERE id = :id")
    suspend fun deleteFoodLog(id: String)

    @Query("DELETE FROM glucose_reading WHERE id = :id")
    suspend fun deleteGlucoseReading(id: String)

    @Query("DELETE FROM weight_log WHERE id = :id")
    suspend fun deleteWeightLog(id: String)

    @Transaction
    suspend fun deleteBpReadingWithTombstone(id: String, entityType: String, hlc: String, deletedAt: Long) {
        deleteBpReading(id)
        upsertTombstone(TombstoneEntity(entityType = entityType, pk = id, hlc = hlc, deletedAt = deletedAt))
    }

    @Transaction
    suspend fun deleteExerciseSessionWithTombstone(id: String, entityType: String, hlc: String, deletedAt: Long) {
        deleteExerciseSession(id)
        upsertTombstone(TombstoneEntity(entityType = entityType, pk = id, hlc = hlc, deletedAt = deletedAt))
    }

    @Transaction
    suspend fun deleteFoodLogWithTombstone(id: String, entityType: String, hlc: String, deletedAt: Long) {
        deleteFoodLog(id)
        upsertTombstone(TombstoneEntity(entityType = entityType, pk = id, hlc = hlc, deletedAt = deletedAt))
    }

    @Transaction
    suspend fun deleteGlucoseReadingWithTombstone(id: String, entityType: String, hlc: String, deletedAt: Long) {
        deleteGlucoseReading(id)
        upsertTombstone(TombstoneEntity(entityType = entityType, pk = id, hlc = hlc, deletedAt = deletedAt))
    }

    @Transaction
    suspend fun deleteWeightLogWithTombstone(id: String, entityType: String, hlc: String, deletedAt: Long) {
        deleteWeightLog(id)
        upsertTombstone(TombstoneEntity(entityType = entityType, pk = id, hlc = hlc, deletedAt = deletedAt))
    }

    @Query("UPDATE bp_reading SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampBpReadingHlc(id: String, hlc: String)

    @Query("UPDATE member SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampMemberHlc(id: String, hlc: String)

    @Query("UPDATE exercise_session SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampExerciseSessionHlc(id: String, hlc: String)

    @Query("UPDATE route_point SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampRoutePointHlc(id: String, hlc: String)

    @Query("UPDATE medication SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampMedicationHlc(id: String, hlc: String)

    @Query("UPDATE medication_schedule SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampMedicationScheduleHlc(id: String, hlc: String)

    @Query("UPDATE medication_dose SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampMedicationDoseHlc(id: String, hlc: String)

    @Query("UPDATE daily_step_log SET hlcUpdatedAt = :hlc WHERE dayStart = :dayStart")
    suspend fun stampDailyStepLogHlc(dayStart: Long, hlc: String)

    @Query("UPDATE achievement SET hlcUpdatedAt = :hlc WHERE kindRaw = :kindRaw")
    suspend fun stampAchievementHlc(kindRaw: String, hlc: String)

    @Query("UPDATE coach_plan SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampCoachPlanHlc(id: String, hlc: String)

    @Query("UPDATE coach_task SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampCoachTaskHlc(id: String, hlc: String)

    @Query("UPDATE sleep_log SET hlcUpdatedAt = :hlc WHERE dayStart = :dayStart")
    suspend fun stampSleepLogHlc(dayStart: Long, hlc: String)

    @Query("UPDATE diet_check SET hlcUpdatedAt = :hlc WHERE dayStart = :dayStart")
    suspend fun stampDietCheckHlc(dayStart: Long, hlc: String)

    @Query("UPDATE exercise_catalog_item SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampExerciseCatalogItemHlc(id: String, hlc: String)

    @Query("UPDATE strength_workout_session SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampStrengthWorkoutSessionHlc(id: String, hlc: String)

    @Query("UPDATE set_log SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampSetLogHlc(id: String, hlc: String)

    @Query("UPDATE bp_workout_association SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampBpWorkoutAssociationHlc(id: String, hlc: String)

    @Query("UPDATE food_log SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampFoodLogHlc(id: String, hlc: String)

    @Query("UPDATE glucose_reading SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampGlucoseReadingHlc(id: String, hlc: String)

    @Query("UPDATE weight_log SET hlcUpdatedAt = :hlc WHERE id = :id")
    suspend fun stampWeightLogHlc(id: String, hlc: String)
}
