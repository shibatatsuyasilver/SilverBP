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
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
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
 *
 * (v1.0) Plans/tasks/sleep/diet are owner-only by design per roadmap section
 * 3-2 — they operate on the device owner's own sensor / Health Connect /
 * coaching data and are intentionally NOT member-scoped (no memberId). Do not
 * member-scope without a product decision. Medication (doses/schedules) IS
 * member-scoped and is handled separately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachRepository(
    private val plans: CoachPlanDao,
    private val sleeps: SleepDao,
    private val diets: DietDao,
    private val doses: MedicationDoseDao,
    private val medicationSchedules: MedicationScheduleDao,
    private val medications: MedicationDao,
    private val localSync: LocalSyncWriter? = null,
) {

    suspend fun savePlan(plan: CoachPlan) {
        val hlc = localSync?.nextHlc()
        val planEntity: CoachPlanEntity = plan.toPlanEntity().let { entity ->
            if (hlc == null) entity else entity.copy(hlcUpdatedAt = hlc)
        }
        val taskEntities: List<CoachTaskEntity> = plan.tasks.map { task ->
            task.toTaskEntity().let { entity ->
                if (hlc == null) entity else entity.copy(hlcUpdatedAt = hlc)
            }
        }
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
        // Bump the HLC so the completion propagates over incremental sync (QA #3).
        localSync?.stamp(SyncEntityType.COACH_TASK, taskId)
    }

    /** Move a task to a different day-of-week. Pass null to clear the override. */
    suspend fun moveTask(taskId: String, newDayOffset: Int?) {
        plans.moveTask(taskId, newDayOffset)
        localSync?.stamp(SyncEntityType.COACH_TASK, taskId)
    }

    /** Mark a task skipped/un-skipped. Skipped tasks stay in the adherence denominator. */
    suspend fun markSkipped(taskId: String, skipped: Boolean) {
        plans.markSkipped(taskId, skipped)
        localSync?.stamp(SyncEntityType.COACH_TASK, taskId)
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
    suspend fun upsertSleep(entry: SleepLogEntity) =
        sleeps.upsert(entry.copy(hlcUpdatedAt = localSync?.nextHlc() ?: entry.hlcUpdatedAt))
    suspend fun sleepForDay(dayStart: Long): SleepLogEntity? = sleeps.forDay(dayStart)
    suspend fun sleepRange(from: Long, to: Long): List<SleepLogEntity> = sleeps.range(from, to)
    fun observeSleepRange(from: Long, to: Long): Flow<List<SleepLogEntity>> = sleeps.observeRange(from, to)

    // ----- Diet -----
    suspend fun upsertDiet(entry: DietCheckEntity) =
        diets.upsert(entry.copy(hlcUpdatedAt = localSync?.nextHlc() ?: entry.hlcUpdatedAt))
    suspend fun dietForDay(dayStart: Long): DietCheckEntity? = diets.forDay(dayStart)
    suspend fun dietRange(from: Long, to: Long): List<DietCheckEntity> = diets.range(from, to)
    fun observeDietRange(from: Long, to: Long): Flow<List<DietCheckEntity>> = diets.observeRange(from, to)

    // ----- Medication doses -----
    suspend fun upsertDose(dose: MedicationDoseEntity) =
        doses.upsert(dose.copy(hlcUpdatedAt = localSync?.nextHlc() ?: dose.hlcUpdatedAt))
    suspend fun dosesForDay(dayStart: Long): List<MedicationDoseEntity> = doses.forDay(dayStart)
    fun observeDosesForDay(dayStart: Long): Flow<List<MedicationDoseEntity>> = doses.observeForDay(dayStart)

    /**
     * Adherence ratio across the trailing 7-day window [from, to).
     *
     * Numerator = dose rows marked taken; denominator = the schedule-derived
     * weekly target ([countScheduledInWeek]), NOT a `COUNT(*)` of dose rows.
     * Dose rows only exist once the user interacts with them, so a row-count
     * denominator reported 100% for 3-of-7 doses taken (the 4 untouched days
     * had no rows) and 0% for a user who never opened the dose sheet at all.
     *
     * Any 7-day window covers each weekday exactly once, so the schedule
     * count is independent of which weekday the window starts on (see the
     * [countScheduledInWeek] KDoc) — a fixed reference Monday suffices and
     * keeps this zone-free. 0f when no enabled schedules exist (no data).
     */
    suspend fun medicationAdherence(from: Long, to: Long): Float {
        val scheduled = countScheduledInWeek(medicationSchedules.all(), REFERENCE_MONDAY)
        if (scheduled == 0) return 0f
        return medicationAdherenceRatio(doses.countTakenInRange(from, to), scheduled)
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
     * Scoped to [memberId] — the Coach home summary shows only the currently
     * selected member's medications, matching the medication manage/log screens
     * ([MedicationDao.observeForMember]). Using the household-wide
     * [MedicationDao.observeAll] here would surface another member's (or an
     * imported foreign-member) medication as an extra tile that the member-scoped
     * manage screen can neither show nor delete.
     *
     * Reactive: observes [MedicationDao.observeForMember] +
     * [MedicationScheduleDao.observeForMember] + [MedicationDoseDao.observeForRange]
     * so the Coach screen refreshes the moment the user toggles a Switch or
     * taps the notification "Mark as taken" action.
     */
    fun observeMedicationWeeklyProgressPerMed(
        weekStartDate: LocalDate,
        zone: ZoneId,
        memberId: String,
    ): Flow<List<MedicationPerMedProgress>> {
        val weekStartMillis = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndMillis = weekStartDate.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(
            medications.observeForMember(memberId),
            medicationSchedules.observeForMember(memberId),
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

    /**
     * Live weekly logging rows for the three non-Exercise module rings, in one
     * flow so the Coach screen's `combine` stays at five arms. Diet/Sleep
     * completion is *derived from the logged rows themselves* (like Exercise is
     * derived from sessions and Medication from doses) — the `coach_task`
     * `completedAtMillis` column is never set for Diet/Sleep, so deriving rings
     * from it left them permanently stuck at 0.
     *
     * Reactive: re-emits the instant the user logs diet/sleep or marks a dose,
     * because [observeMedicationWeeklyProgressPerMed], [DietDao.observeRange]
     * and [SleepDao.observeRange] are all Room-observed.
     */
    fun observeWeeklyLifestyleLogs(
        weekStartDate: LocalDate,
        zone: ZoneId,
        memberId: String,
    ): Flow<WeeklyLifestyleLogs> {
        val weekStartMillis = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndMillis = weekStartDate.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(
            observeMedicationWeeklyProgressPerMed(weekStartDate, zone, memberId),
            diets.observeRange(weekStartMillis, weekEndMillis),
            sleeps.observeRange(weekStartMillis, weekEndMillis),
        ) { perMed, dietRows, sleepRows ->
            WeeklyLifestyleLogs(perMed = perMed, dietDays = dietRows, sleepDays = sleepRows)
        }
    }

    companion object {
        /** Any Monday — see [medicationAdherence] for why the actual date is irrelevant. */
        private val REFERENCE_MONDAY: LocalDate = LocalDate.of(2024, 1, 1)

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

        /**
         * Pure ratio core of [medicationAdherence]: taken doses over the
         * schedule-derived weekly target, clamped to [0, 1]. 0f when nothing
         * is scheduled. Companion-level so it is unit-testable next to
         * [countScheduledInWeek].
         */
        fun medicationAdherenceRatio(taken: Int, scheduled: Int): Float =
            if (scheduled == 0) 0f else (taken.toFloat() / scheduled).coerceIn(0f, 1f)
    }
}

data class MedicationPerMedProgress(
    val medicationId: String,
    val medicationName: String,
    val taken: Int,
    val scheduled: Int,
)

/**
 * Raw weekly logging rows for the non-Exercise module rings. Completion is
 * computed by the caller from these rows (Diet: a day counts when sodium isn't
 * "high"; Sleep: a day counts when duration ≥ the plan's sleep target).
 */
data class WeeklyLifestyleLogs(
    val perMed: List<MedicationPerMedProgress>,
    val dietDays: List<DietCheckEntity>,
    val sleepDays: List<SleepLogEntity>,
)
