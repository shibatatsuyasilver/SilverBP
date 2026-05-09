package com.silverbp.android.coach

import com.silverbp.android.core.db.CoachPlanDao
import com.silverbp.android.core.db.CoachPlanEntity
import com.silverbp.android.core.db.CoachTaskEntity
import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.core.db.DietDao
import com.silverbp.android.core.db.MedicationDao
import com.silverbp.android.core.db.MedicationDoseDao
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.core.db.MedicationScheduleDao
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.core.db.SleepDao
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toPlanEntity
import com.silverbp.android.core.db.toTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate
import java.time.ZoneId

/**
 * Façade over the four Coach DAOs. Lives in the coach package so callers
 * never reach directly into Room types — keeps domain code free of `*Entity`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachRepository(
    private val plans: CoachPlanDao,
    private val sleeps: SleepDao,
    private val diets: DietDao,
    private val doses: MedicationDoseDao,
    private val medicationSchedules: MedicationScheduleDao,
    private val medications: MedicationDao,
) {

    suspend fun savePlan(plan: CoachPlan) {
        val planEntity: CoachPlanEntity = plan.toPlanEntity()
        val taskEntities: List<CoachTaskEntity> = plan.tasks.map { it.toTaskEntity() }
        plans.insertPlanWithTasks(planEntity, taskEntities)
    }

    suspend fun currentPlan(nowMillis: Long): CoachPlan? {
        val plan = plans.currentPlan(nowMillis) ?: return null
        val tasks = plans.tasksForPlan(plan.id)
        return plan.toDomain(tasks)
    }

    suspend fun recentPlans(limit: Int): List<CoachPlan> {
        val planEntities = plans.recentPlans(limit)
        return planEntities.map { entity ->
            entity.toDomain(plans.tasksForPlan(entity.id))
        }
    }

    /**
     * Streams the current plan plus its tasks. Emits null until a plan exists.
     * The combined flow is the canonical observable used by [CoachViewModel].
     */
    fun observeCurrentPlan(nowMillis: Long): Flow<CoachPlan?> {
        return plans.observeCurrentPlan(nowMillis).flatMapLatest { plan ->
            if (plan == null) flowOf(null)
            else plans.observeTasksForPlan(plan.id).map { tasks ->
                plan.toDomain(tasks)
            }
        }
    }

    suspend fun setTaskCompleted(taskId: String, completedAtMillis: Long?) {
        plans.setCompleted(taskId, completedAtMillis)
    }

    suspend fun adherenceForCurrentPlan(nowMillis: Long): List<Adherence> {
        val plan = plans.currentPlan(nowMillis) ?: return emptyList()
        return adherenceForPlan(plan.id)
    }

    suspend fun adherenceForPlan(planId: String): List<Adherence> {
        return plans.adherenceForPlan(planId).map { row ->
            Adherence(
                module = LifestyleModule.fromRaw(row.moduleRaw),
                completed = row.done,
                scheduled = row.total,
            )
        }
    }

    // ----- Sleep -----
    suspend fun upsertSleep(entry: SleepLogEntity) = sleeps.upsert(entry)
    suspend fun sleepForDay(dayStart: Long): SleepLogEntity? = sleeps.forDay(dayStart)
    suspend fun sleepRange(from: Long, to: Long): List<SleepLogEntity> = sleeps.range(from, to)
    fun observeSleepRange(from: Long, to: Long): Flow<List<SleepLogEntity>> = sleeps.observeRange(from, to)

    // ----- Diet -----
    suspend fun upsertDiet(entry: DietCheckEntity) = diets.upsert(entry)
    suspend fun dietForDay(dayStart: Long): DietCheckEntity? = diets.forDay(dayStart)
    suspend fun dietRange(from: Long, to: Long): List<DietCheckEntity> = diets.range(from, to)
    fun observeDietRange(from: Long, to: Long): Flow<List<DietCheckEntity>> = diets.observeRange(from, to)

    // ----- Medication doses -----
    suspend fun upsertDose(dose: MedicationDoseEntity) = doses.upsert(dose)
    suspend fun dosesForDay(dayStart: Long): List<MedicationDoseEntity> = doses.forDay(dayStart)
    fun observeDosesForDay(dayStart: Long): Flow<List<MedicationDoseEntity>> = doses.observeForDay(dayStart)

    /** Adherence ratio across [from, to). 0f when no doses scheduled. */
    suspend fun medicationAdherence(from: Long, to: Long): Float {
        val scheduled = doses.countScheduledInRange(from, to)
        if (scheduled == 0) return 0f
        val taken = doses.countTakenInRange(from, to)
        return (taken.toFloat() / scheduled).coerceIn(0f, 1f)
    }

    /**
     * Per-medication weekly progress for the Coach module list. Each result
     * row carries `(medicationName, taken, scheduled)` so the Coach screen
     * can render one ModuleCard per active medication instead of one
     * aggregated "Medication X/Y" card.
     *
     * Numerator = dose rows in the week with `taken = true`, grouped by
     * medicationId. Denominator = enabled schedules whose [daysOfWeekMask]
     * intersects each day of the week (Mon..Sun) — i.e. the medication's
     * own weekly target, independent of which day it is today.
     *
     * Medications with `scheduled = 0` (no firings this week) are filtered
     * out — listing them would only produce 0/0 noise. Users can still see
     * them in the manage screen.
     *
     * Reactive: observes [MedicationDao.observeAll] +
     * [MedicationScheduleDao.observeAll] + [MedicationDoseDao.observeForRange]
     * so the Coach screen refreshes the moment the user toggles a Switch or
     * taps the notification "Mark as taken" action.
     */
    fun observeMedicationWeeklyProgressPerMed(
        weekStartDate: LocalDate,
        zone: ZoneId,
    ): Flow<List<MedicationPerMedProgress>> {
        val weekStartMillis = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndMillis = weekStartDate.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(
            medications.observeAll(),
            medicationSchedules.observeAll(),
            doses.observeForRange(weekStartMillis, weekEndMillis),
        ) { meds, scheduleRows, doseRows ->
            val schedulesByMed = scheduleRows.groupBy { it.medicationId }
            val takenByMed = doseRows.filter { it.taken }
                .groupingBy { it.medicationId }.eachCount()
            meds.map { med ->
                val medSchedules = schedulesByMed[med.id].orEmpty()
                MedicationPerMedProgress(
                    medicationId = med.id,
                    medicationName = med.name,
                    taken = takenByMed[med.id] ?: 0,
                    scheduled = countScheduledInWeek(medSchedules, weekStartDate),
                )
            }.filter { it.scheduled > 0 }
        }
    }

    companion object {
        /**
         * Count this medication's weekly target — for each day of the week
         * (Mon..Sun, derived from [weekStartDate]), sum the enabled schedules
         * whose mask covers that day.
         *
         * The denominator is the medication's own pattern: daily → 7,
         * Mon/Wed/Fri → 3, weekend-only → 2, and so on. It does NOT depend
         * on what day of the week it is today.
         */
        fun countScheduledInWeek(
            schedules: List<MedicationScheduleEntity>,
            weekStartDate: LocalDate,
        ): Int {
            var count = 0
            for (offset in 0..6) {
                val dow = weekStartDate.plusDays(offset.toLong()).dayOfWeek
                count += schedules.count {
                    it.enabled && DayOfWeekMask.contains(it.daysOfWeekMask, dow)
                }
            }
            return count
        }
    }
}

data class MedicationPerMedProgress(
    val medicationId: String,
    val medicationName: String,
    val taken: Int,
    val scheduled: Int,
)
