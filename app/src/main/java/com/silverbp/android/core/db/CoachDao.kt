package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Aggregate row used by [CoachPlanDao.adherenceForRange]. `total` and `done`
 * are SQL counts so we never store an Adherence entity.
 */
data class CoachAdherenceRow(
    val moduleRaw: String,
    val total: Int,
    val done: Int,
)

@Dao
interface CoachPlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: CoachPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<CoachTaskEntity>)

    /**
     * Atomic plan-with-tasks insert. Tasks reference the plan via FK so we
     * insert the plan first; both rows fail or persist together.
     */
    @Transaction
    suspend fun insertPlanWithTasks(plan: CoachPlanEntity, tasks: List<CoachTaskEntity>) {
        insertPlan(plan)
        insertTasks(tasks)
    }

    @Query("SELECT * FROM coach_plan WHERE weekStart <= :nowMillis ORDER BY weekStart DESC LIMIT 1")
    fun observeCurrentPlan(nowMillis: Long): Flow<CoachPlanEntity?>

    @Query("SELECT * FROM coach_plan WHERE weekStart <= :nowMillis ORDER BY weekStart DESC LIMIT 1")
    suspend fun currentPlan(nowMillis: Long): CoachPlanEntity?

    @Query("SELECT * FROM coach_plan ORDER BY weekStart DESC LIMIT :limit")
    suspend fun recentPlans(limit: Int): List<CoachPlanEntity>

    @Query("SELECT * FROM coach_task WHERE planId = :planId ORDER BY dayOffset ASC")
    fun observeTasksForPlan(planId: String): Flow<List<CoachTaskEntity>>

    @Query("SELECT * FROM coach_task WHERE planId = :planId ORDER BY dayOffset ASC")
    suspend fun tasksForPlan(planId: String): List<CoachTaskEntity>

    @Query("UPDATE coach_task SET completedAt = :ts WHERE id = :id")
    suspend fun setCompleted(id: String, ts: Long?)

    @Query(
        """
        SELECT moduleRaw AS moduleRaw,
               COUNT(*) AS total,
               SUM(CASE WHEN completedAt IS NOT NULL THEN 1 ELSE 0 END) AS done
          FROM coach_task
         WHERE planId = :planId
         GROUP BY moduleRaw
        """
    )
    suspend fun adherenceForPlan(planId: String): List<CoachAdherenceRow>
}

@Dao
interface SleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SleepLogEntity)

    @Query("SELECT * FROM sleep_log WHERE dayStart = :dayStart")
    suspend fun forDay(dayStart: Long): SleepLogEntity?

    @Query("SELECT * FROM sleep_log WHERE dayStart >= :from AND dayStart < :to ORDER BY dayStart ASC")
    fun observeRange(from: Long, to: Long): Flow<List<SleepLogEntity>>

    @Query("SELECT * FROM sleep_log WHERE dayStart >= :from AND dayStart < :to ORDER BY dayStart ASC")
    suspend fun range(from: Long, to: Long): List<SleepLogEntity>
}

@Dao
interface DietDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DietCheckEntity)

    @Query("SELECT * FROM diet_check WHERE dayStart = :dayStart")
    suspend fun forDay(dayStart: Long): DietCheckEntity?

    @Query("SELECT * FROM diet_check WHERE dayStart >= :from AND dayStart < :to ORDER BY dayStart ASC")
    fun observeRange(from: Long, to: Long): Flow<List<DietCheckEntity>>

    @Query("SELECT * FROM diet_check WHERE dayStart >= :from AND dayStart < :to ORDER BY dayStart ASC")
    suspend fun range(from: Long, to: Long): List<DietCheckEntity>
}

@Dao
interface MedicationDoseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dose: MedicationDoseEntity)

    @Query("SELECT * FROM medication_dose WHERE dayStart = :dayStart ORDER BY scheduledHour ASC")
    fun observeForDay(dayStart: Long): Flow<List<MedicationDoseEntity>>

    @Query("SELECT * FROM medication_dose WHERE dayStart = :dayStart ORDER BY scheduledHour ASC")
    suspend fun forDay(dayStart: Long): List<MedicationDoseEntity>

    @Query("SELECT * FROM medication_dose WHERE dayStart >= :from AND dayStart < :to ORDER BY dayStart ASC")
    fun observeForRange(from: Long, to: Long): Flow<List<MedicationDoseEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM medication_dose
         WHERE dayStart >= :from AND dayStart < :to AND taken = 1
        """
    )
    suspend fun countTakenInRange(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM medication_dose WHERE dayStart >= :from AND dayStart < :to")
    suspend fun countScheduledInRange(from: Long, to: Long): Int
}
