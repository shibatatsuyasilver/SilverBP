package com.silverbp.android.sync

import android.util.Log
import com.silverbp.android.core.db.AchievementDao
import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.CoachPlanDao
import com.silverbp.android.core.db.DietDao
import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.MedicationDao
import com.silverbp.android.core.db.MedicationDoseDao
import com.silverbp.android.core.db.MedicationScheduleDao
import com.silverbp.android.core.db.SleepDao
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.protocol.SyncRecordSink
import com.silverbp.android.sync.protocol.SyncRecordSource
import kotlinx.coroutines.flow.first

/**
 * Bridges the Room `bp_reading` table into the wire-format `SyncRecord`s
 * the [SyncSession] pushes to the peer.
 *
 * MVP semantics: emit every row each round (don't filter by hlcUpdatedAt).
 * Rows that pre-date sync have hlcUpdatedAt='0'; we stamp a fresh HLC at
 * encode time so the receiver's LWW logic accepts them. Bandwidth-optimised
 * incremental sync (filter by peer's lastHlcSeen) lands in Phase 2.
 */
class BpRoomSyncSource(
    private val bpDao: BpDao,
    private val mapper: BpReadingSyncMapper,
    private val clock: HlcClock,
) : SyncRecordSource {
    override suspend fun recordsSince(
        peerLastHlcSeen: com.silverbp.android.sync.engine.Hlc,
        limit: Int,
    ): List<SyncRecord> {
        val all = bpDao.observeAll().first()
        return all.take(limit).map { entity ->
            // Use the row's existing HLC if it has one; otherwise mint a
            // fresh one so the receiver's LWW gate accepts.
            val hlc = if (entity.hlcUpdatedAt == "0".repeat(32) || entity.hlcUpdatedAt == "0") {
                clock.next()
            } else {
                com.silverbp.android.sync.engine.Hlc(entity.hlcUpdatedAt)
            }
            mapper.encode(entity, hlc)
        }
    }
}

/**
 * Receives `SyncRecord`s from the peer and writes them through the mapper
 * (which upserts via BpDao).
 */
class BpRoomSyncSink(
    private val mapper: BpReadingSyncMapper,
) : SyncRecordSink {
    override suspend fun apply(record: SyncRecord) {
        if (record.type != SyncEntityType.BP_READING) return
        mapper.apply(record)
    }
}

/**
 * Phase 2 multi-entity sync source. Concatenates rows from BP, exercise,
 * route, medication, schedule, and dose tables into a single record stream
 * so the pairing flow can flush every domain over the same Noise channel.
 *
 * Per-row HLC handling mirrors [BpRoomSyncSource]: pre-Phase-2 rows have
 * `hlcUpdatedAt = "0"` and get a fresh HLC minted at encode time so the
 * receiver's LWW gate accepts them; rows that already carry an HLC are
 * re-encoded with the stored timestamp.
 *
 * `limit` is applied across the *combined* stream — emit order puts the
 * small per-domain tables FIRST and `route_point` LAST so a quota-bounded
 * round (default 1000 in `SyncSession`) doesn't get drowned by a single
 * GPS-heavy session that happens to have thousands of points. Apply order
 * on the receiver still lets exercise_session land before its route_points
 * arrive (different rounds is fine — orphaned points are silently dropped
 * by `RoutePointSyncMapper.apply`).
 */
class CombinedRoomSyncSource(
    private val bpDao: BpDao,
    private val exerciseDao: ExerciseDao,
    private val medicationDao: MedicationDao,
    private val medicationScheduleDao: MedicationScheduleDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val achievementDao: AchievementDao,
    private val coachPlanDao: CoachPlanDao,
    private val sleepDao: SleepDao,
    private val dietDao: DietDao,
    private val bpMapper: BpReadingSyncMapper,
    private val exerciseSessionMapper: ExerciseSessionSyncMapper,
    private val routePointMapper: RoutePointSyncMapper,
    private val medicationMapper: MedicationSyncMapper,
    private val medicationScheduleMapper: MedicationScheduleSyncMapper,
    private val medicationDoseMapper: MedicationDoseSyncMapper,
    private val dailyStepLogMapper: DailyStepLogSyncMapper,
    private val achievementMapper: AchievementSyncMapper,
    private val coachPlanMapper: CoachPlanSyncMapper,
    private val coachTaskMapper: CoachTaskSyncMapper,
    private val sleepLogMapper: SleepLogSyncMapper,
    private val dietCheckMapper: DietCheckSyncMapper,
    private val clock: HlcClock,
) : SyncRecordSource {

    override suspend fun recordsSince(
        peerLastHlcSeen: Hlc,
        limit: Int,
    ): List<SyncRecord> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<SyncRecord>(limit.coerceAtMost(256))

        // 1. bp_reading
        val bpRows = bpDao.observeAll().first()
        for (row in bpRows) {
            if (out.size >= limit) return out
            out += bpMapper.encode(row, hlcFor(row.hlcUpdatedAt))
        }

        // 2. exercise_session
        val sessions = exerciseDao.observeAll().first()
        for (session in sessions) {
            if (out.size >= limit) return out
            out += exerciseSessionMapper.encode(session, hlcFor(session.hlcUpdatedAt))
        }

        // 3. medication
        val meds = medicationDao.observeAll().first()
        for (med in meds) {
            if (out.size >= limit) return out
            out += medicationMapper.encode(med, hlcFor(med.hlcUpdatedAt))
        }

        // 4. medication_schedule
        val schedules = medicationScheduleDao.all()
        for (sched in schedules) {
            if (out.size >= limit) return out
            out += medicationScheduleMapper.encode(sched, hlcFor(sched.hlcUpdatedAt))
        }

        // 5. medication_dose
        val doses = medicationDoseDao.all()
        for (dose in doses) {
            if (out.size >= limit) return out
            out += medicationDoseMapper.encode(dose, hlcFor(dose.hlcUpdatedAt))
        }

        // 6. daily_step_log
        val stepLogs = achievementDao.listAllStepLogs()
        for (log in stepLogs) {
            if (out.size >= limit) return out
            out += dailyStepLogMapper.encode(log, hlcFor(log.hlcUpdatedAt))
        }

        // 7. achievement
        val achievements = achievementDao.listAll()
        for (medal in achievements) {
            if (out.size >= limit) return out
            out += achievementMapper.encode(medal, hlcFor(medal.hlcUpdatedAt))
        }

        // 8a. coach_plan — emit before coach_task so the FK on tasks resolves.
        val coachPlans = coachPlanDao.listAllPlans()
        for (plan in coachPlans) {
            if (out.size >= limit) return out
            out += coachPlanMapper.encode(plan, hlcFor(plan.hlcUpdatedAt))
        }

        // 8b. coach_task
        val coachTasks = coachPlanDao.listAllTasks()
        for (task in coachTasks) {
            if (out.size >= limit) return out
            out += coachTaskMapper.encode(task, hlcFor(task.hlcUpdatedAt))
        }

        // 9. sleep_log
        val sleeps = sleepDao.listAll()
        for (s in sleeps) {
            if (out.size >= limit) return out
            out += sleepLogMapper.encode(s, hlcFor(s.hlcUpdatedAt))
        }

        // 10. diet_check
        val diets = dietDao.listAll()
        for (d in diets) {
            if (out.size >= limit) return out
            out += dietCheckMapper.encode(d, hlcFor(d.hlcUpdatedAt))
        }

        val countAfterScalar = out.size

        // 8. route_point — emitted LAST so a single GPS-heavy session can't
        // starve the small per-domain tables above. Orphan points (parent
        // session not yet on receiver) are silently dropped by the apply
        // path; subsequent rounds catch them once the parent has landed.
        val points = exerciseDao.allPoints()
        for (point in points) {
            if (out.size >= limit) return out
            out += routePointMapper.encode(point, hlcFor(point.hlcUpdatedAt))
        }

        Log.i(
            "CombinedSyncSrc",
            "emit bp=${bpRows.size} ex=${sessions.size} med=${meds.size} " +
                "sched=${schedules.size} dose=${doses.size} step=${stepLogs.size} " +
                "ach=${achievements.size} plan=${coachPlans.size} " +
                "task=${coachTasks.size} sleep=${sleeps.size} diet=${diets.size} " +
                "route=${out.size - countAfterScalar} " +
                "(total=${out.size}/limit=$limit)",
        )
        return out
    }

    /** Mints a fresh HLC for legacy rows and reuses the stored one otherwise. */
    private fun hlcFor(stored: String): Hlc =
        if (stored == "0".repeat(32) || stored == "0") clock.next() else Hlc(stored)
}

/**
 * Phase 2 multi-entity sink. Dispatches an inbound [SyncRecord] to the
 * mapper that knows how to apply it. Unknown record types are ignored so
 * a future schema bump on one device doesn't crash the older peer.
 */
class CombinedRoomSyncSink(
    private val bpMapper: BpReadingSyncMapper,
    private val exerciseSessionMapper: ExerciseSessionSyncMapper,
    private val routePointMapper: RoutePointSyncMapper,
    private val medicationMapper: MedicationSyncMapper,
    private val medicationScheduleMapper: MedicationScheduleSyncMapper,
    private val medicationDoseMapper: MedicationDoseSyncMapper,
    private val dailyStepLogMapper: DailyStepLogSyncMapper,
    private val achievementMapper: AchievementSyncMapper,
    private val coachPlanMapper: CoachPlanSyncMapper,
    private val coachTaskMapper: CoachTaskSyncMapper,
    private val sleepLogMapper: SleepLogSyncMapper,
    private val dietCheckMapper: DietCheckSyncMapper,
) : SyncRecordSink {
    private val perTypeCount = java.util.concurrent.ConcurrentHashMap<SyncEntityType, Int>()
    override suspend fun apply(record: SyncRecord) {
        perTypeCount.merge(record.type, 1, Int::plus)
        try {
            when (record.type) {
                SyncEntityType.BP_READING -> bpMapper.apply(record)
                SyncEntityType.EXERCISE_SESSION -> exerciseSessionMapper.apply(record)
                SyncEntityType.ROUTE_POINT -> routePointMapper.apply(record)
                SyncEntityType.MEDICATION -> medicationMapper.apply(record)
                SyncEntityType.MEDICATION_SCHEDULE -> medicationScheduleMapper.apply(record)
                SyncEntityType.MEDICATION_DOSE -> medicationDoseMapper.apply(record)
                SyncEntityType.DAILY_STEP_LOG -> dailyStepLogMapper.apply(record)
                SyncEntityType.ACHIEVEMENT -> achievementMapper.apply(record)
                SyncEntityType.COACH_PLAN -> coachPlanMapper.apply(record)
                SyncEntityType.COACH_TASK -> coachTaskMapper.apply(record)
                SyncEntityType.SLEEP_LOG -> sleepLogMapper.apply(record)
                SyncEntityType.DIET_CHECK -> dietCheckMapper.apply(record)
                else -> {
                    // Forward-compat: silently drop record types this build
                    // doesn't yet understand (e.g. CHAT_*).
                }
            }
        } catch (t: Throwable) {
            Log.e(
                "CombinedSyncSink",
                "apply ${record.type} pk=${record.pk} hlc=${record.hlc.packed} failed",
                t,
            )
            throw t
        }
    }
    /** Returns and clears the per-record-type count snapshot. */
    fun drainStats(): Map<SyncEntityType, Int> {
        val snap = perTypeCount.toMap()
        perTypeCount.clear()
        return snap
    }
}
