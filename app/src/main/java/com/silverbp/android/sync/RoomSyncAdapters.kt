package com.silverbp.android.sync

import android.util.Log
import com.silverbp.android.core.db.AchievementDao
import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.BpWorkoutAssociationDao
import com.silverbp.android.core.db.ChatDao
import com.silverbp.android.core.db.CoachPlanDao
import com.silverbp.android.core.db.DietDao
import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.FoodLogDao
import com.silverbp.android.core.db.MedicationDao
import com.silverbp.android.core.db.MedicationDoseDao
import com.silverbp.android.core.db.MedicationScheduleDao
import com.silverbp.android.core.db.SleepDao
import com.silverbp.android.core.db.StrengthWorkoutDao
import com.silverbp.android.core.db.SyncDao
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
    // Strength-training tables (v13). Optional so older callers compile, but
    // wired by both ServiceLocator (backup) and PairingViewModel (LAN sync).
    private val exerciseLibraryDao: ExerciseLibraryDao? = null,
    private val strengthWorkoutDao: StrengthWorkoutDao? = null,
    private val exerciseCatalogItemMapper: ExerciseCatalogItemSyncMapper? = null,
    private val strengthWorkoutSessionMapper: StrengthWorkoutSessionSyncMapper? = null,
    private val setLogMapper: SetLogSyncMapper? = null,
    // Backup-only dependencies — optional so existing LAN sync callers
    // (PairingViewModel) compile without supplying these. Required by
    // [snapshotAll]; the standard [recordsSince] path doesn't touch them.
    private val chatDao: ChatDao? = null,
    private val chatSessionMapper: ChatSessionSyncMapper? = null,
    private val chatMessageMapper: ChatMessageSyncMapper? = null,
    private val syncDao: SyncDao? = null,
    // BP↔workout association (v14). Optional so older callers compile, but
    // wired by both ServiceLocator (backup) and PairingViewModel (LAN sync).
    private val bpWorkoutAssociationDao: BpWorkoutAssociationDao? = null,
    private val bpWorkoutAssociationMapper: BpWorkoutAssociationSyncMapper? = null,
    // Nutrition / food_log (v16). Optional so older callers compile.
    private val foodLogDao: FoodLogDao? = null,
    private val foodLogMapper: FoodLogSyncMapper? = null,
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

        // 11. exercise_catalog_item
        var catalogCount = 0
        if (exerciseLibraryDao != null && exerciseCatalogItemMapper != null) {
            for (item in exerciseLibraryDao.allOnce()) {
                if (out.size >= limit) return out
                out += exerciseCatalogItemMapper.encode(item, hlcFor(item.hlcUpdatedAt))
                catalogCount++
            }
        }

        // 12a. strength_workout_session — before set_log so the FK on sets resolves.
        var strengthSessionCount = 0
        var setLogCount = 0
        if (strengthWorkoutDao != null && strengthWorkoutSessionMapper != null && setLogMapper != null) {
            val strengthSessions = strengthWorkoutDao.observeAllSessions().first()
            for (s in strengthSessions) {
                if (out.size >= limit) return out
                out += strengthWorkoutSessionMapper.encode(s, hlcFor(s.hlcUpdatedAt))
                strengthSessionCount++
            }
            // 12b. set_log
            for (s in strengthSessions) {
                for (set in strengthWorkoutDao.setsForSession(s.id)) {
                    if (out.size >= limit) return out
                    out += setLogMapper.encode(set, hlcFor(set.hlcUpdatedAt))
                    setLogCount++
                }
            }
        }

        // 13. bp_workout_association
        var assocCount = 0
        if (bpWorkoutAssociationDao != null && bpWorkoutAssociationMapper != null) {
            for (assoc in bpWorkoutAssociationDao.listAll()) {
                if (out.size >= limit) return out
                out += bpWorkoutAssociationMapper.encode(assoc, hlcFor(assoc.hlcUpdatedAt))
                assocCount++
            }
        }

        // 14. food_log
        var foodLogCount = 0
        if (foodLogDao != null && foodLogMapper != null) {
            for (log in foodLogDao.all()) {
                if (out.size >= limit) return out
                out += foodLogMapper.encode(log, hlcFor(log.hlcUpdatedAt))
                foodLogCount++
            }
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
                "catalog=$catalogCount strSession=$strengthSessionCount set=$setLogCount " +
                "assoc=$assocCount food=$foodLogCount " +
                "route=${out.size - countAfterScalar} " +
                "(total=${out.size}/limit=$limit)",
        )
        return out
    }

    /** Mints a fresh HLC for legacy rows and reuses the stored one otherwise. */
    private fun hlcFor(stored: String): Hlc =
        if (stored == "0".repeat(32) || stored == "0") clock.next() else Hlc(stored)

    // ============================================================
    // Backup snapshot — full table dump for .sbpbk export
    // ============================================================
    //
    // 與 [recordsSince] 的差異:
    //  - 不受 `limit` 限制 — 發送全部列(備份必須完整)
    //  - 忽略 peer cursor — peerLastHlcSeen 概念在備份場景沒意義
    //  - 包含 [SyncEntityType.CHAT_SESSION] / [SyncEntityType.CHAT_MESSAGE]
    //    (chat tables 目前不參與 LAN sync)
    //  - 包含 tombstones — 讓還原時能正確帶上「已刪除」狀態
    //
    // 三個 chat/sync 依賴 (chatDao / chatSessionMapper / chatMessageMapper / syncDao)
    // 必須在建構時提供;LAN sync 的 PairingViewModel 路徑可以不提供.
    //
    // [includeChat] = false 時,跳過聊天表(備份匯出 UI 的「不備份聊天歷史」勾選).

    suspend fun snapshotAll(includeChat: Boolean = true): List<SyncRecord> {
        val out = ArrayList<SyncRecord>()

        // 1. bp_reading
        for (row in bpDao.observeAll().first()) {
            out += bpMapper.encode(row, hlcFor(row.hlcUpdatedAt))
        }

        // 2. exercise_session
        for (session in exerciseDao.observeAll().first()) {
            out += exerciseSessionMapper.encode(session, hlcFor(session.hlcUpdatedAt))
        }

        // 3. medication
        for (med in medicationDao.observeAll().first()) {
            out += medicationMapper.encode(med, hlcFor(med.hlcUpdatedAt))
        }

        // 4. medication_schedule
        for (sched in medicationScheduleDao.all()) {
            out += medicationScheduleMapper.encode(sched, hlcFor(sched.hlcUpdatedAt))
        }

        // 5. medication_dose
        for (dose in medicationDoseDao.all()) {
            out += medicationDoseMapper.encode(dose, hlcFor(dose.hlcUpdatedAt))
        }

        // 6. daily_step_log
        for (log in achievementDao.listAllStepLogs()) {
            out += dailyStepLogMapper.encode(log, hlcFor(log.hlcUpdatedAt))
        }

        // 7. achievement
        for (medal in achievementDao.listAll()) {
            out += achievementMapper.encode(medal, hlcFor(medal.hlcUpdatedAt))
        }

        // 8a. coach_plan — before tasks for FK order
        for (plan in coachPlanDao.listAllPlans()) {
            out += coachPlanMapper.encode(plan, hlcFor(plan.hlcUpdatedAt))
        }

        // 8b. coach_task
        for (task in coachPlanDao.listAllTasks()) {
            out += coachTaskMapper.encode(task, hlcFor(task.hlcUpdatedAt))
        }

        // 9. sleep_log
        for (s in sleepDao.listAll()) {
            out += sleepLogMapper.encode(s, hlcFor(s.hlcUpdatedAt))
        }

        // 10. diet_check
        for (d in dietDao.listAll()) {
            out += dietCheckMapper.encode(d, hlcFor(d.hlcUpdatedAt))
        }

        // 10a. exercise_catalog_item
        if (exerciseLibraryDao != null && exerciseCatalogItemMapper != null) {
            for (item in exerciseLibraryDao.allOnce()) {
                out += exerciseCatalogItemMapper.encode(item, hlcFor(item.hlcUpdatedAt))
            }
        }

        // 10b. strength_workout_session + set_log (session first for FK order)
        if (strengthWorkoutDao != null && strengthWorkoutSessionMapper != null && setLogMapper != null) {
            val strengthSessions = strengthWorkoutDao.observeAllSessions().first()
            for (s in strengthSessions) {
                out += strengthWorkoutSessionMapper.encode(s, hlcFor(s.hlcUpdatedAt))
            }
            for (s in strengthSessions) {
                for (set in strengthWorkoutDao.setsForSession(s.id)) {
                    out += setLogMapper.encode(set, hlcFor(set.hlcUpdatedAt))
                }
            }
        }

        // 10c. bp_workout_association
        if (bpWorkoutAssociationDao != null && bpWorkoutAssociationMapper != null) {
            for (assoc in bpWorkoutAssociationDao.listAll()) {
                out += bpWorkoutAssociationMapper.encode(assoc, hlcFor(assoc.hlcUpdatedAt))
            }
        }

        // 10d. food_log
        if (foodLogDao != null && foodLogMapper != null) {
            for (log in foodLogDao.all()) {
                out += foodLogMapper.encode(log, hlcFor(log.hlcUpdatedAt))
            }
        }

        // 11. chat_session + chat_message (Backup-only — 不參與 LAN sync)
        if (includeChat && chatDao != null && chatSessionMapper != null && chatMessageMapper != null) {
            // Sessions first so child messages' FK resolves on import.
            val sessions = chatDao.observeAllSessions().first()
            for (s in sessions) {
                val sessionEntity = chatDao.getSession(s.id) ?: continue
                out += chatSessionMapper.encode(sessionEntity, clock.next())
            }
            for (s in sessions) {
                for (msg in chatDao.messagesFor(s.id)) {
                    out += chatMessageMapper.encode(msg, clock.next())
                }
            }
        }

        // 12. route_point — last, can be voluminous
        for (point in exerciseDao.allPoints()) {
            out += routePointMapper.encode(point, hlcFor(point.hlcUpdatedAt))
        }

        // 13. tombstones — convert to deletedAt!=null SyncRecord so import can
        // replay the deletes via existing apply paths.
        if (syncDao != null) {
            val tombstones = syncDao.tombstonesSince("0".repeat(32))
            for (t in tombstones) {
                val type = SyncEntityType.entries.firstOrNull { it.tableName == t.entityType }
                    ?: continue // 未知 type — 跳過(forward-compat).
                out += SyncRecord(
                    type = type,
                    pk = t.pk,
                    hlc = Hlc(t.hlc),
                    deletedAt = t.deletedAt,
                    payload = emptyMap(),
                )
            }
        }

        return out
    }
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
    // Strength-training mappers (v13). Optional so older callers compile, but
    // wired by both ServiceLocator (backup) and PairingViewModel (LAN sync).
    private val exerciseCatalogItemMapper: ExerciseCatalogItemSyncMapper? = null,
    private val strengthWorkoutSessionMapper: StrengthWorkoutSessionSyncMapper? = null,
    private val setLogMapper: SetLogSyncMapper? = null,
    // Backup-only mappers. Provided by BackupManager; LAN-sync caller leaves
    // these null and the corresponding record types silently drop as
    // forward-compat.
    private val chatSessionMapper: ChatSessionSyncMapper? = null,
    private val chatMessageMapper: ChatMessageSyncMapper? = null,
    private val settingsKvMapper: SettingsKvSyncMapper? = null,
    // BP↔workout association (v14). Optional so older callers compile.
    private val bpWorkoutAssociationMapper: BpWorkoutAssociationSyncMapper? = null,
    // Nutrition / food_log (v16). Optional so older callers compile.
    private val foodLogMapper: FoodLogSyncMapper? = null,
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
                SyncEntityType.EXERCISE_CATALOG_ITEM -> exerciseCatalogItemMapper?.apply(record)
                SyncEntityType.STRENGTH_WORKOUT_SESSION -> strengthWorkoutSessionMapper?.apply(record)
                SyncEntityType.SET_LOG -> setLogMapper?.apply(record)
                SyncEntityType.BP_WORKOUT_ASSOCIATION -> bpWorkoutAssociationMapper?.apply(record)
                SyncEntityType.CHAT_SESSION -> chatSessionMapper?.apply(record)
                SyncEntityType.CHAT_MESSAGE -> chatMessageMapper?.apply(record)
                SyncEntityType.SETTINGS_KV -> settingsKvMapper?.apply(record)
                SyncEntityType.FOOD_LOG -> foodLogMapper?.apply(record)
                else -> {
                    // Forward-compat: silently drop record types this build
                    // doesn't yet understand (e.g. future BLOB_META).
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
