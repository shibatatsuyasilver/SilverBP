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

    @Query("UPDATE coach_task SET movedDayOffset = :newDay WHERE id = :id")
    suspend fun moveTask(id: String, newDay: Int?)

    @Query("UPDATE coach_task SET skipped = :skipped WHERE id = :id")
    suspend fun markSkipped(id: String, skipped: Boolean)

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

    /** Bulk reads used by cross-device sync. Plans first, then their tasks. */
    @Query("SELECT * FROM coach_plan ORDER BY weekStart ASC")
    suspend fun listAllPlans(): List<CoachPlanEntity>

    @Query("SELECT * FROM coach_task ORDER BY planId, dayOffset ASC")
    suspend fun listAllTasks(): List<CoachTaskEntity>

    @Query("SELECT * FROM coach_plan WHERE id = :id")
    suspend fun findPlanById(id: String): CoachPlanEntity?

    @Query("SELECT * FROM coach_task WHERE id = :id")
    suspend fun findTaskById(id: String): CoachTaskEntity?
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

    @Query("SELECT * FROM sleep_log ORDER BY dayStart ASC")
    suspend fun listAll(): List<SleepLogEntity>
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

    @Query("SELECT * FROM diet_check ORDER BY dayStart ASC")
    suspend fun listAll(): List<DietCheckEntity>
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

    /**
     * Bulk read used by the cross-device sync source. Ordered by dayStart so
     * paginated peers see deterministic ordering.
     */
    @Query("SELECT * FROM medication_dose ORDER BY dayStart ASC")
    suspend fun all(): List<MedicationDoseEntity>

    @Query("SELECT COUNT(*) FROM medication_dose")
    suspend fun count(): Int

    /** Content-key dedup lookup for cross-device sync apply. */
    @Query(
        "SELECT * FROM medication_dose " +
            "WHERE dayStart = :dayStart AND medicationId = :medicationId AND scheduledHour = :scheduledHour " +
            "LIMIT 1"
    )
    suspend fun findByContent(
        dayStart: Long,
        medicationId: String,
        scheduledHour: Int,
    ): MedicationDoseEntity?
}
