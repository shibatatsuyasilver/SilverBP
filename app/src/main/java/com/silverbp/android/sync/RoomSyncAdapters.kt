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
import com.silverbp.android.core.db.LocalSyncMutationDao
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

internal data class SyncCandidate(
    val record: SyncRecord,
    val dependencyRank: Int,
)

internal fun selectRecordsSince(
    candidates: List<SyncCandidate>,
    peerLastHlcSeen: Hlc,
    limit: Int,
): List<SyncRecord> {
    if (limit <= 0) return emptyList()
    return candidates
        .asSequence()
        .filter { it.record.hlc > peerLastHlcSeen }
        .sortedWith(
            compareBy<SyncCandidate> { it.record.hlc.packed }
                .thenBy { it.dependencyRank }
                .thenBy { it.record.type.tag }
                .thenBy { it.record.pk },
        )
        .take(limit)
        .toList()
        .toDependencyOrderedRecords()
}

private fun List<SyncCandidate>.toDependencyOrderedRecords(): List<SyncRecord> =
    sortedWith(
        compareBy<SyncCandidate> { it.dependencyRank }
            .thenBy { it.record.hlc.packed }
            .thenBy { it.record.type.tag }
            .thenBy { it.record.pk },
    ).map { it.record }

private fun isZeroHlc(stored: String): Boolean =
    stored == "0" || stored == "0".repeat(Hlc.HEX_LEN)

private fun dependencyRank(type: SyncEntityType): Int = when (type) {
    SyncEntityType.MEMBER -> 0
    SyncEntityType.BP_READING -> 10
    SyncEntityType.EXERCISE_SESSION -> 20
    SyncEntityType.MEDICATION -> 30
    SyncEntityType.MEDICATION_SCHEDULE -> 40
    SyncEntityType.MEDICATION_DOSE -> 50
    SyncEntityType.DAILY_STEP_LOG -> 60
    SyncEntityType.ACHIEVEMENT -> 70
    SyncEntityType.COACH_PLAN -> 80
    SyncEntityType.COACH_TASK -> 90
    SyncEntityType.SLEEP_LOG -> 100
    SyncEntityType.DIET_CHECK -> 110
    SyncEntityType.EXERCISE_CATALOG_ITEM -> 120
    SyncEntityType.STRENGTH_WORKOUT_SESSION -> 130
    SyncEntityType.SET_LOG -> 140
    SyncEntityType.BP_WORKOUT_ASSOCIATION -> 150
    SyncEntityType.FOOD_LOG -> 160
    SyncEntityType.GLUCOSE_READING -> 170
    SyncEntityType.WEIGHT_LOG -> 180
    SyncEntityType.ROUTE_POINT -> 190
    else -> 1_000
}

/**
 * Bridges the Room `bp_reading` table into the wire-format `SyncRecord`s
 * the [SyncSession] pushes to the peer.
 *
 * Incremental semantics: emit rows with HLC newer than the peer watermark.
 * Rows that pre-date sync have hlcUpdatedAt='0'; we stamp and persist a fresh
 * HLC at encode time so the receiver's LWW logic accepts them and future
 * rounds can advance by watermark safely.
 */
class BpRoomSyncSource(
    private val bpDao: BpDao,
    private val mapper: BpReadingSyncMapper,
    private val clock: HlcClock,
    private val localSyncDao: LocalSyncMutationDao? = null,
) : SyncRecordSource {
    override suspend fun recordsSince(
        peerLastHlcSeen: com.silverbp.android.sync.engine.Hlc,
        limit: Int,
    ): List<SyncRecord> {
        val all = bpDao.observeAll().first()
        val candidates = all.map { entity ->
            val hlc = hlcFor(entity.hlcUpdatedAt) { localSyncDao?.stampBpReadingHlc(entity.id, it.packed) }
            SyncCandidate(mapper.encode(entity, hlc), dependencyRank = dependencyRank(SyncEntityType.BP_READING))
        }
        return selectRecordsSince(candidates, peerLastHlcSeen, limit)
    }

    private suspend fun hlcFor(stored: String, persist: suspend (Hlc) -> Unit): Hlc =
        if (isZeroHlc(stored)) clock.next().also { persist(it) } else Hlc(stored)
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
 * `hlcUpdatedAt = "0"` and get a fresh HLC minted and persisted at encode
 * time so the receiver's LWW gate accepts them and the peer watermark can
 * advance safely; rows that already carry an HLC are re-encoded with the stored
 * timestamp.
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
    // Family member (v18). Optional so older callers compile.
    private val memberDao: com.silverbp.android.core.db.MemberDao? = null,
    private val memberMapper: MemberSyncMapper? = null,
    // Blood glucose (v19). Optional so older callers compile.
    private val glucoseDao: com.silverbp.android.core.db.GlucoseDao? = null,
    private val glucoseMapper: GlucoseReadingSyncMapper? = null,
    // Body weight (v20). Optional so older callers compile.
    private val weightDao: com.silverbp.android.core.db.WeightDao? = null,
    private val weightMapper: WeightReadingSyncMapper? = null,
    // Local HLC repair for pre-sync rows. Optional for legacy unit tests; wired
    // in production so minted HLCs are persisted instead of being transient.
    private val localSyncDao: LocalSyncMutationDao? = null,
) : SyncRecordSource {

    override suspend fun recordsSince(
        peerLastHlcSeen: Hlc,
        limit: Int,
    ): List<SyncRecord> {
        if (limit <= 0) return emptyList()
        val candidates = ArrayList<SyncCandidate>(256)

        // 0. member — emit FIRST so a peer/import has every owning member row
        // before the BP/medication rows that reference it land.
        var memberCount = 0
        if (memberDao != null && memberMapper != null) {
            for (m in memberDao.getAll()) {
                candidates += SyncCandidate(
                    memberMapper.encode(
                        m,
                        hlcFor(m.hlcUpdatedAt) { localSyncDao?.stampMemberHlc(m.id, it.packed) },
                    ),
                    dependencyRank = 0,
                )
                memberCount++
            }
        }

        // 1. bp_reading
        val bpRows = bpDao.observeAll().first()
        for (row in bpRows) {
            candidates += SyncCandidate(
                bpMapper.encode(
                    row,
                    hlcFor(row.hlcUpdatedAt) { localSyncDao?.stampBpReadingHlc(row.id, it.packed) },
                ),
                dependencyRank = 10,
            )
        }

        // 2. exercise_session
        val sessions = exerciseDao.observeAll().first()
        val sessionHlcById = HashMap<String, Hlc>(sessions.size)
        for (session in sessions) {
            val hlc = hlcFor(session.hlcUpdatedAt) {
                localSyncDao?.stampExerciseSessionHlc(session.id, it.packed)
            }
            sessionHlcById[session.id] = hlc
            candidates += SyncCandidate(
                exerciseSessionMapper.encode(
                    session,
                    hlc,
                ),
                dependencyRank = 20,
            )
        }

        // 3. medication
        val meds = medicationDao.observeAll().first()
        for (med in meds) {
            candidates += SyncCandidate(
                medicationMapper.encode(
                    med,
                    hlcFor(med.hlcUpdatedAt) { localSyncDao?.stampMedicationHlc(med.id, it.packed) },
                ),
                dependencyRank = 30,
            )
        }

        // 4. medication_schedule
        val schedules = medicationScheduleDao.all()
        for (sched in schedules) {
            candidates += SyncCandidate(
                medicationScheduleMapper.encode(
                    sched,
                    hlcFor(sched.hlcUpdatedAt) {
                        localSyncDao?.stampMedicationScheduleHlc(sched.id, it.packed)
                    },
                ),
                dependencyRank = 40,
            )
        }

        // 5. medication_dose
        val doses = medicationDoseDao.all()
        for (dose in doses) {
            candidates += SyncCandidate(
                medicationDoseMapper.encode(
                    dose,
                    hlcFor(dose.hlcUpdatedAt) {
                        localSyncDao?.stampMedicationDoseHlc(dose.id, it.packed)
                    },
                ),
                dependencyRank = 50,
            )
        }

        // 6. daily_step_log
        val stepLogs = achievementDao.listAllStepLogs()
        for (log in stepLogs) {
            candidates += SyncCandidate(
                dailyStepLogMapper.encode(
                    log,
                    hlcFor(log.hlcUpdatedAt) {
                        localSyncDao?.stampDailyStepLogHlc(log.dayStart, it.packed)
                    },
                ),
                dependencyRank = 60,
            )
        }

        // 7. achievement
        val achievements = achievementDao.listAll()
        for (medal in achievements) {
            candidates += SyncCandidate(
                achievementMapper.encode(
                    medal,
                    hlcFor(medal.hlcUpdatedAt) {
                        localSyncDao?.stampAchievementHlc(medal.kindRaw, it.packed)
                    },
                ),
                dependencyRank = 70,
            )
        }

        // 8a. coach_plan — emit before coach_task so the FK on tasks resolves.
        val coachPlans = coachPlanDao.listAllPlans()
        for (plan in coachPlans) {
            candidates += SyncCandidate(
                coachPlanMapper.encode(
                    plan,
                    hlcFor(plan.hlcUpdatedAt) { localSyncDao?.stampCoachPlanHlc(plan.id, it.packed) },
                ),
                dependencyRank = 80,
            )
        }

        // 8b. coach_task
        val coachTasks = coachPlanDao.listAllTasks()
        for (task in coachTasks) {
            candidates += SyncCandidate(
                coachTaskMapper.encode(
                    task,
                    hlcFor(task.hlcUpdatedAt) { localSyncDao?.stampCoachTaskHlc(task.id, it.packed) },
                ),
                dependencyRank = 90,
            )
        }

        // 9. sleep_log
        val sleeps = sleepDao.listAll()
        for (s in sleeps) {
            candidates += SyncCandidate(
                sleepLogMapper.encode(
                    s,
                    hlcFor(s.hlcUpdatedAt) { localSyncDao?.stampSleepLogHlc(s.dayStart, it.packed) },
                ),
                dependencyRank = 100,
            )
        }

        // 10. diet_check
        val diets = dietDao.listAll()
        for (d in diets) {
            candidates += SyncCandidate(
                dietCheckMapper.encode(
                    d,
                    hlcFor(d.hlcUpdatedAt) { localSyncDao?.stampDietCheckHlc(d.dayStart, it.packed) },
                ),
                dependencyRank = 110,
            )
        }

        // 11. exercise_catalog_item
        var catalogCount = 0
        if (exerciseLibraryDao != null && exerciseCatalogItemMapper != null) {
            for (item in exerciseLibraryDao.allOnce()) {
                candidates += SyncCandidate(
                    exerciseCatalogItemMapper.encode(
                        item,
                        hlcFor(item.hlcUpdatedAt) {
                            localSyncDao?.stampExerciseCatalogItemHlc(item.id, it.packed)
                        },
                    ),
                    dependencyRank = 120,
                )
                catalogCount++
            }
        }

        // 12a. strength_workout_session — before set_log so the FK on sets resolves.
        var strengthSessionCount = 0
        var setLogCount = 0
        if (strengthWorkoutDao != null && strengthWorkoutSessionMapper != null && setLogMapper != null) {
            val strengthSessions = strengthWorkoutDao.observeAllSessions().first()
            for (s in strengthSessions) {
                candidates += SyncCandidate(
                    strengthWorkoutSessionMapper.encode(
                        s,
                        hlcFor(s.hlcUpdatedAt) {
                            localSyncDao?.stampStrengthWorkoutSessionHlc(s.id, it.packed)
                        },
                    ),
                    dependencyRank = 130,
                )
                strengthSessionCount++
            }
            // 12b. set_log
            for (s in strengthSessions) {
                for (set in strengthWorkoutDao.setsForSession(s.id)) {
                    candidates += SyncCandidate(
                        setLogMapper.encode(
                            set,
                            hlcFor(set.hlcUpdatedAt) { localSyncDao?.stampSetLogHlc(set.id, it.packed) },
                        ),
                        dependencyRank = 140,
                    )
                    setLogCount++
                }
            }
        }

        // 13. bp_workout_association
        var assocCount = 0
        if (bpWorkoutAssociationDao != null && bpWorkoutAssociationMapper != null) {
            for (assoc in bpWorkoutAssociationDao.listAll()) {
                candidates += SyncCandidate(
                    bpWorkoutAssociationMapper.encode(
                        assoc,
                        hlcFor(assoc.hlcUpdatedAt) {
                            localSyncDao?.stampBpWorkoutAssociationHlc(assoc.id, it.packed)
                        },
                    ),
                    dependencyRank = 150,
                )
                assocCount++
            }
        }

        // 14. food_log
        var foodLogCount = 0
        if (foodLogDao != null && foodLogMapper != null) {
            for (log in foodLogDao.all()) {
                candidates += SyncCandidate(
                    foodLogMapper.encode(
                        log,
                        hlcFor(log.hlcUpdatedAt) { localSyncDao?.stampFoodLogHlc(log.id, it.packed) },
                    ),
                    dependencyRank = 160,
                )
                foodLogCount++
            }
        }

        // 15. glucose_reading (v19) — references member, so after the member rows
        // above (emitted first); a small per-row table like bp_reading.
        var glucoseCount = 0
        if (glucoseDao != null && glucoseMapper != null) {
            for (row in glucoseDao.getAll()) {
                candidates += SyncCandidate(
                    glucoseMapper.encode(
                        row,
                        hlcFor(row.hlcUpdatedAt) {
                            localSyncDao?.stampGlucoseReadingHlc(row.id, it.packed)
                        },
                    ),
                    dependencyRank = 170,
                )
                glucoseCount++
            }
        }

        // 16. weight_log (v20) — references member, so after the member rows
        // above (emitted first); a small per-row table like bp_reading.
        var weightCount = 0
        if (weightDao != null && weightMapper != null) {
            for (row in weightDao.getAll()) {
                candidates += SyncCandidate(
                    weightMapper.encode(
                        row,
                        hlcFor(row.hlcUpdatedAt) { localSyncDao?.stampWeightLogHlc(row.id, it.packed) },
                    ),
                    dependencyRank = 180,
                )
                weightCount++
            }
        }

        // 17. route_point — route rows are children of exercise_session. Repair
        // old equal/lower point HLCs so HLC-prefix batching sends the parent
        // before its points and the peer watermark cannot strand orphan points.
        val points = exerciseDao.allPoints()
        for (point in points) {
            val pointHlc = hlcFor(point.hlcUpdatedAt) {
                localSyncDao?.stampRoutePointHlc(point.id, it.packed)
            }
            val parentHlc = sessionHlcById[point.sessionId]
            val safePointHlc = if (parentHlc != null && pointHlc <= parentHlc) {
                clock.observe(parentHlc)
                clock.next().also { localSyncDao?.stampRoutePointHlc(point.id, it.packed) }
            } else {
                pointHlc
            }
            candidates += SyncCandidate(
                routePointMapper.encode(
                    point,
                    safePointHlc,
                ),
                dependencyRank = 190,
            )
        }

        if (syncDao != null) {
            for (t in syncDao.tombstonesSince(peerLastHlcSeen.packed)) {
                val type = SyncEntityType.entries.firstOrNull { it.tableName == t.entityType } ?: continue
                candidates += SyncCandidate(
                    SyncRecord(
                        type = type,
                        pk = t.pk,
                        hlc = Hlc(t.hlc),
                        deletedAt = t.deletedAt,
                        payload = emptyMap(),
                    ),
                    dependencyRank = dependencyRank(type),
                )
            }
        }

        val out = selectRecordsSince(candidates, peerLastHlcSeen, limit)
        val routeCount = out.count { it.type == SyncEntityType.ROUTE_POINT }

        Log.i(
            "CombinedSyncSrc",
            "emit member=$memberCount bp=${bpRows.size} ex=${sessions.size} med=${meds.size} " +
                "sched=${schedules.size} dose=${doses.size} step=${stepLogs.size} " +
                "ach=${achievements.size} plan=${coachPlans.size} " +
                "task=${coachTasks.size} sleep=${sleeps.size} diet=${diets.size} " +
                "catalog=$catalogCount strSession=$strengthSessionCount set=$setLogCount " +
                "assoc=$assocCount food=$foodLogCount glucose=$glucoseCount " +
                "weight=$weightCount " +
                "route=$routeCount " +
                "(total=${out.size}/limit=$limit)",
        )
        return out
    }

    /** Mints a fresh HLC for legacy rows and reuses the stored one otherwise. */
    private suspend fun hlcFor(stored: String, persist: suspend (Hlc) -> Unit): Hlc =
        if (isZeroHlc(stored)) clock.next().also { persist(it) } else Hlc(stored)

    private suspend fun hlcFor(stored: String): Hlc = hlcFor(stored) {}

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

        // 0. member — first so import has every owning member before the
        // BP/medication rows that carry its id (and before backward-compat
        // owner-resolution kicks in for any memberless rows).
        if (memberDao != null && memberMapper != null) {
            for (m in memberDao.getAll()) {
                out += memberMapper.encode(m, hlcFor(m.hlcUpdatedAt))
            }
        }

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

        // 10e. glucose_reading (v19) — after member (emitted first) for owning-member
        // ordering; a fresh-device import re-mirrors HC so hcRecordId isn't carried.
        if (glucoseDao != null && glucoseMapper != null) {
            for (row in glucoseDao.getAll()) {
                out += glucoseMapper.encode(row, hlcFor(row.hlcUpdatedAt))
            }
        }

        // 10f. weight_log (v20) — after member (emitted first) for owning-member
        // ordering; a fresh-device import re-mirrors HC so hcRecordId isn't carried.
        if (weightDao != null && weightMapper != null) {
            for (row in weightDao.getAll()) {
                out += weightMapper.encode(row, hlcFor(row.hlcUpdatedAt))
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
 *
 * B6 LWW gate: every dispatch is wrapped in an [com.silverbp.android.sync.engine.
 * LwwMerger] so a record is applied **iff `record.hlc > local`** (live row's
 * `hlcUpdatedAt` OR any tombstone hlc, whichever is greater). This is the gate
 * the mapper doc-comments always delegated upward; without it stale peer copies
 * blindly REPLACEd newer local edits and stale tombstones resurrect-then-deleted
 * rows every round. The per-type table/pk lookup is [LwwTables]; types not in
 * that allowlist (chat_*, settings_kv, route_point, medication_dose) keep their
 * own apply semantics — see the [LwwTables] KDoc for why each is exempt.
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
    // Family member (v18). Optional so older callers compile.
    private val memberMapper: MemberSyncMapper? = null,
    // Blood glucose (v19). Optional so older callers compile.
    private val glucoseMapper: GlucoseReadingSyncMapper? = null,
    // Body weight (v20). Optional so older callers compile.
    private val weightMapper: WeightReadingSyncMapper? = null,
    // B6 LWW gate dependency. Required to read the local high-water HLC; when
    // null the gate is disabled (the record always applies) — kept optional so
    // legacy test callers that don't care about LWW still compile. Both
    // production wirings (ServiceLocator backup + PairingViewModel LAN) supply
    // it.
    private val syncDao: SyncDao? = null,
) : SyncRecordSink {
    private val perTypeCount = java.util.concurrent.ConcurrentHashMap<SyncEntityType, Int>()

    /** Per-type write dispatch — runs only after the LWW gate accepts. */
    private suspend fun dispatch(record: SyncRecord) {
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
            SyncEntityType.MEMBER -> memberMapper?.apply(record)
            SyncEntityType.GLUCOSE_READING -> glucoseMapper?.apply(record)
            SyncEntityType.WEIGHT_LOG -> weightMapper?.apply(record)
            else -> {
                // Forward-compat: silently drop record types this build
                // doesn't yet understand (e.g. future BLOB_META).
            }
        }
    }

    /**
     * Local high-water HLC for [record]'s identity: max(live row hlc, tombstone
     * hlc). Null when the type isn't pk-gated or there's no local trace, in
     * which case the gate always applies.
     */
    private suspend fun localHlc(record: SyncRecord): Hlc? {
        val dao = syncDao ?: return null
        val (table, pkCol) = LwwTables.pkColumnFor(record.type) ?: return null
        val live = dao.localRowHlc(table, pkCol, record.pk)
        val tomb = dao.tombstoneFor(record.type.tableName, record.pk)?.hlc
        return LwwTables.resolveLocalHlc(live, tomb)
    }

    private val gate = com.silverbp.android.sync.engine.LwwMerger(
        inner = { record -> dispatch(record) },
        localHlc = { record -> localHlc(record) },
    )

    override suspend fun apply(record: SyncRecord) {
        perTypeCount.merge(record.type, 1, Int::plus)
        try {
            gate.apply(record)
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
