package com.silverbp.android.sync

import com.silverbp.android.core.db.AchievementDao
import com.silverbp.android.core.db.AchievementEntity
import com.silverbp.android.core.db.CoachPlanDao
import com.silverbp.android.core.db.CoachPlanEntity
import com.silverbp.android.core.db.CoachTaskEntity
import com.silverbp.android.core.db.DailyStepLogEntity
import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.core.db.DietDao
import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.ExerciseSessionEntity
import com.silverbp.android.core.db.MedicationDao
import com.silverbp.android.core.db.MedicationDoseDao
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationScheduleDao
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.core.db.RoutePointEntity
import com.silverbp.android.core.db.SleepDao
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * Phase 2 mappers — exercise + medication. Field-tag layout MUST stay
 * byte-identical with iOS [Phase2Mappers.swift].
 *
 * ### exercise_session payload (CBOR int→value)
 *   1: activityKind          (string)
 *   2: startedAtMs           (int)
 *   3: endedAtMs             (int)
 *   4: distanceMeters        (double)
 *   5: stepCount             (int?)
 *   6: averagePaceSecPerKm   (double?)
 *   7: source                (string)
 *   8: note                  (string)
 *   9: createdAtMs           (int)
 *  10: updatedAtMs           (int)
 *  11: caloriesKcal          (double?)
 *  12: elevationGainMeters   (double?, iOS-only data — null on Android)
 *  13: activeDurationMillis  (int, appended v21; absent => wall-clock fallback)
 *  14: heartRateBpm          (int?)
 *  15: caloriesIsEstimate    (bool)
 *  16: heartRateIsEstimate   (bool)
 *  17: distanceUnitRaw       (string?)
 *  18: floors                (int?)
 *  19: rawMetricsJson        (string?)
 *  // hcRecordId / hkWorkoutUUID intentionally NOT synced
 *
 * ### route_point payload
 *   1: sessionId             (string UUID)
 *   2: timestampMs           (int)
 *   3: latitude              (double)
 *   4: longitude             (double)
 *   5: horizontalAccuracy    (double, widened from Float on Android)
 *   6: altitudeMeters        (double?)
 *   7: speedMetersPerSec     (double?)
 *
 * ### medication payload
 *   1: name                  (string)
 *   2: dose                  (string)
 *   3: kindRaw               (string, "medication"|"supplement")
 *   4: memberId              (string, v18 owning member; absent on pre-v18 peers)
 *
 * ### medication_schedule payload
 *   1: medicationId          (string UUID)
 *   2: daysOfWeekMask        (int, 7-bit ISO)
 *   3: hour                  (int)
 *   4: minute                (int)
 *   5: enabled               (bool)
 *
 * ### medication_dose payload
 *   1: dayStartMs            (int, local-midnight epoch ms)
 *   2: medicationId          (string UUID)
 *   3: scheduledHour         (int)
 *   4: taken                 (bool)
 *   5: updatedAtMs           (int)
 *   6: scheduledMinute       (int, appended v21; absent => 0)
 *   7: scheduleId            (string?, appended v21)
 *
 * ### daily_step_log payload
 *   1: steps                 (int)
 *   2: sourceRaw             (string)
 *   3: updatedAtMs           (int)
 *   // pk = dayStartMs (local-midnight epoch ms) — encoded as the SyncRecord
 *   // pk string for parity with iOS (`String(dayStart)`)
 *
 * ### achievement payload
 *   1: unlockedAtMs          (int)
 *   2: notifiedAtMs          (int?, null = unread)
 *   3: unlockedBackfilled    (bool)
 *   4: valueAtUnlock         (int)
 *   // pk = kindRaw (medal id, e.g. "daily.10000")
 */

class ExerciseSessionSyncMapper(
    private val dao: ExerciseDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<ExerciseSessionEntity> {

    override fun encode(entity: ExerciseSessionEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.activityKind),
            2 to SyncValue.Int64(entity.startedAt),
            3 to SyncValue.Int64(entity.endedAt),
            4 to SyncValue.Double(entity.distanceMeters),
            5 to (entity.stepCount?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
            6 to (entity.averagePaceSecPerKm?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            7 to SyncValue.Text(entity.source),
            8 to SyncValue.Text(entity.note),
            9 to SyncValue.Int64(entity.createdAt),
            10 to SyncValue.Int64(entity.updatedAt),
            // caloriesKcal — now populated on Android by gym-machine OCR sessions.
            11 to (entity.caloriesKcal?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            12 to SyncValue.Null, // elevationGainMeters — Android-side absent
            13 to SyncValue.Int64(entity.activeDurationMillis),
            14 to (entity.heartRateBpm?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
            15 to SyncValue.Bool(entity.caloriesIsEstimate),
            16 to SyncValue.Bool(entity.heartRateIsEstimate),
            17 to (entity.distanceUnitRaw?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            18 to (entity.floors?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
            19 to (entity.rawMetricsJson?.let { SyncValue.Text(it) } ?: SyncValue.Null),
        )
        return SyncRecord(
            type = SyncEntityType.EXERCISE_SESSION,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.EXERCISE_SESSION)
        if (record.isTombstone) {
            dao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.EXERCISE_SESSION.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }
        val p = record.payload
        val existing = dao.findById(record.pk)
        val startedAtMs = (p[2] as? SyncValue.Int64)?.value ?: 0L
        val endedAtMs = (p[3] as? SyncValue.Int64)?.value ?: 0L
        val entity = ExerciseSessionEntity(
            id = record.pk,
            activityKind = (p[1] as? SyncValue.Text)?.value ?: "walking",
            startedAt = startedAtMs,
            endedAt = endedAtMs,
            activeDurationMillis = (p[13] as? SyncValue.Int64)?.value
                ?: existing?.activeDurationMillis
                ?: (endedAtMs - startedAtMs).coerceAtLeast(0L),
            distanceMeters = (p[4] as? SyncValue.Double)?.value ?: 0.0,
            stepCount = (p[5] as? SyncValue.Int64)?.value?.toInt(),
            averagePaceSecPerKm = (p[6] as? SyncValue.Double)?.value,
            source = (p[7] as? SyncValue.Text)?.value ?: "gps",
            note = (p[8] as? SyncValue.Text)?.value ?: "",
            // hcRecordId stays platform-local — keep existing if any.
            hcRecordId = existing?.hcRecordId,
            createdAt = (p[9] as? SyncValue.Int64)?.value ?: 0L,
            updatedAt = (p[10] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
            caloriesKcal = (p[11] as? SyncValue.Double)?.value ?: existing?.caloriesKcal,
            heartRateBpm = (p[14] as? SyncValue.Int64)?.value?.toInt() ?: existing?.heartRateBpm,
            caloriesIsEstimate = (p[15] as? SyncValue.Bool)?.value ?: existing?.caloriesIsEstimate ?: true,
            heartRateIsEstimate = (p[16] as? SyncValue.Bool)?.value ?: existing?.heartRateIsEstimate ?: true,
            distanceUnitRaw = (p[17] as? SyncValue.Text)?.value ?: existing?.distanceUnitRaw,
            floors = (p[18] as? SyncValue.Int64)?.value?.toInt() ?: existing?.floors,
            rawMetricsJson = (p[19] as? SyncValue.Text)?.value ?: existing?.rawMetricsJson,
        )
        if (existing == null) dao.insertSession(entity) else dao.updateSession(entity)
    }
}

class RoutePointSyncMapper(
    private val dao: ExerciseDao,
) : SyncRecordMapper<RoutePointEntity> {

    override fun encode(entity: RoutePointEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.sessionId),
            2 to SyncValue.Int64(entity.timestamp),
            3 to SyncValue.Double(entity.lat),
            4 to SyncValue.Double(entity.lon),
            5 to SyncValue.Double(entity.horizontalAccuracy.toDouble()),
            6 to (entity.altitude?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            7 to (entity.speedMps?.toDouble()?.let { SyncValue.Double(it) } ?: SyncValue.Null),
        )
        return SyncRecord(
            type = SyncEntityType.ROUTE_POINT,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.ROUTE_POINT)
        // route_point doesn't have a native delete-by-id (uses CASCADE from
        // session). Tombstones for route_point alone are rare — skip for now.
        if (record.isTombstone) return
        val p = record.payload
        val sessionId = (p[1] as? SyncValue.Text)?.value ?: return
        // Skip if the parent session isn't present locally (FK would fail);
        // ExerciseSession sync should run first.
        if (dao.findById(sessionId) == null) return
        val entity = RoutePointEntity(
            id = record.pk,
            sessionId = sessionId,
            timestamp = (p[2] as? SyncValue.Int64)?.value ?: 0L,
            lat = (p[3] as? SyncValue.Double)?.value ?: 0.0,
            lon = (p[4] as? SyncValue.Double)?.value ?: 0.0,
            horizontalAccuracy = ((p[5] as? SyncValue.Double)?.value ?: 0.0).toFloat(),
            altitude = (p[6] as? SyncValue.Double)?.value,
            speedMps = (p[7] as? SyncValue.Double)?.value?.toFloat(),
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.insertPoints(listOf(entity))
    }
}

class MedicationSyncMapper(
    private val dao: MedicationDao,
    private val syncDao: SyncDao,
    /**
     * Resolves the owner member id for inbound medication records that arrive
     * without a memberId (old peers / pre-v18 backups). Defaults to "" so
     * existing callers/tests compile; production wires
     * [com.silverbp.android.core.member.MemberRepository.ownerId].
     */
    private val ownerIdProvider: suspend () -> String = { "" },
) : SyncRecordMapper<MedicationEntity> {

    override fun encode(entity: MedicationEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.name),
            2 to SyncValue.Text(entity.dose),
            3 to SyncValue.Text(entity.kind),
            4 to SyncValue.Text(entity.memberId),
        )
        return SyncRecord(
            type = SyncEntityType.MEDICATION,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.MEDICATION)
        if (record.isTombstone) {
            dao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.MEDICATION.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }
        val p = record.payload
        val entity = MedicationEntity(
            id = record.pk,
            name = (p[1] as? SyncValue.Text)?.value ?: "",
            dose = (p[2] as? SyncValue.Text)?.value ?: "",
            kind = (p[3] as? SyncValue.Text)?.value ?: "medication",
            hlcUpdatedAt = record.hlc.packed,
            // Tag 4 (v18). Absent / blank on a pre-v18 peer or backup → owner.
            memberId = (p[4] as? SyncValue.Text)?.value?.takeIf { it.isNotBlank() }
                ?: ownerIdProvider(),
        )
        dao.upsert(entity)
    }
}

class MedicationScheduleSyncMapper(
    private val dao: MedicationScheduleDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<MedicationScheduleEntity> {

    override fun encode(entity: MedicationScheduleEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.medicationId),
            2 to SyncValue.Int64(entity.daysOfWeekMask.toLong()),
            3 to SyncValue.Int64(entity.hour.toLong()),
            4 to SyncValue.Int64(entity.minute.toLong()),
            5 to SyncValue.Bool(entity.enabled),
        )
        return SyncRecord(
            type = SyncEntityType.MEDICATION_SCHEDULE,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.MEDICATION_SCHEDULE)
        if (record.isTombstone) {
            dao.deleteById(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.MEDICATION_SCHEDULE.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }
        val p = record.payload
        val entity = MedicationScheduleEntity(
            id = record.pk,
            medicationId = (p[1] as? SyncValue.Text)?.value ?: return,
            daysOfWeekMask = ((p[2] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            hour = ((p[3] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            minute = ((p[4] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            enabled = (p[5] as? SyncValue.Bool)?.value ?: false,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsert(entity)
    }
}

class AchievementSyncMapper(
    private val dao: AchievementDao,
) : SyncRecordMapper<AchievementEntity> {

    override fun encode(entity: AchievementEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Int64(entity.unlockedAt),
            2 to (entity.notifiedAt?.let { SyncValue.Int64(it) } ?: SyncValue.Null),
            3 to SyncValue.Bool(entity.unlockedBackfilled),
            4 to SyncValue.Int64(entity.valueAtUnlock),
        )
        return SyncRecord(
            type = SyncEntityType.ACHIEVEMENT,
            pk = entity.kindRaw,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.ACHIEVEMENT)
        // Achievements are append-only by design; no tombstones.
        if (record.isTombstone) return
        val p = record.payload
        val entity = AchievementEntity(
            kindRaw = record.pk,
            unlockedAt = (p[1] as? SyncValue.Int64)?.value ?: 0L,
            notifiedAt = (p[2] as? SyncValue.Int64)?.value,
            unlockedBackfilled = (p[3] as? SyncValue.Bool)?.value ?: false,
            valueAtUnlock = (p[4] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        // insertAll(IGNORE) preserves the existing row if the medal is already
        // unlocked locally — matches the evaluator's idempotency guarantee.
        dao.insertAll(listOf(entity))
    }
}

class DailyStepLogSyncMapper(
    private val dao: AchievementDao,
) : SyncRecordMapper<DailyStepLogEntity> {

    override fun encode(entity: DailyStepLogEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Int64(entity.steps.toLong()),
            2 to SyncValue.Text(entity.sourceRaw),
            3 to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.DAILY_STEP_LOG,
            pk = entity.dayStart.toString(),
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.DAILY_STEP_LOG)
        // Step logs are deduplicated by dayStart and never deleted today.
        if (record.isTombstone) return
        val dayStart = record.pk.toLongOrNull() ?: return
        val p = record.payload
        val entity = DailyStepLogEntity(
            dayStart = dayStart,
            steps = ((p[1] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            sourceRaw = (p[2] as? SyncValue.Text)?.value ?: "health_connect",
            updatedAt = (p[3] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsertStepLog(entity)
    }
}

class MedicationDoseSyncMapper(
    private val dao: MedicationDoseDao,
) : SyncRecordMapper<MedicationDoseEntity> {

    override fun encode(entity: MedicationDoseEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Int64(entity.dayStart),
            2 to SyncValue.Text(entity.medicationId),
            3 to SyncValue.Int64(entity.scheduledHour.toLong()),
            4 to SyncValue.Bool(entity.taken),
            5 to SyncValue.Int64(entity.updatedAt),
            6 to SyncValue.Int64(entity.scheduledMinute.toLong()),
            7 to (entity.scheduleId?.let { SyncValue.Text(it) } ?: SyncValue.Null),
        )
        return SyncRecord(
            type = SyncEntityType.MEDICATION_DOSE,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.MEDICATION_DOSE)
        // dose has no native delete-by-id today; tombstones unsupported.
        if (record.isTombstone) return
        val p = record.payload
        val dayStart = (p[1] as? SyncValue.Int64)?.value ?: 0L
        val medicationId = (p[2] as? SyncValue.Text)?.value ?: return
        val scheduledHour = ((p[3] as? SyncValue.Int64)?.value ?: 0L).toInt()
        val scheduledMinute = ((p[6] as? SyncValue.Int64)?.value ?: 0L).toInt()
        val scheduleId = (p[7] as? SyncValue.Text)?.value?.takeIf { it.isNotBlank() }
        // pk is platform-defined (Android: "med-{dayMs}-{scheduleId}", iOS:
        // UUID string). Prefer scheduleId when present so Android's
        // deterministic id survives; fall back to hour+minute for older peers.
        val existing = if (scheduleId != null) {
            dao.findById(record.pk)
                ?: dao.findBySchedule(dayStart, medicationId, scheduleId)
                ?: dao.findById(androidDoseId(dayStart, scheduleId))
        } else {
            dao.findByContent(dayStart, medicationId, scheduledHour, scheduledMinute)
        }
        // B6 LWW gate (in-mapper because dose is content-keyed, not pk-keyed, so
        // the sink-level pk lookup can't find the local row). Drop stale/equal
        // records: a pre-sync local row ("0") always loses to a real inbound HLC.
        val localHlc = existing?.hlcUpdatedAt
        if (localHlc != null && localHlc != "0" && record.hlc.packed <= localHlc) return
        val entity = MedicationDoseEntity(
            id = existing?.id ?: record.pk,
            dayStart = dayStart,
            medicationId = medicationId,
            scheduledHour = scheduledHour,
            scheduledMinute = scheduledMinute,
            scheduleId = scheduleId,
            taken = (p[4] as? SyncValue.Bool)?.value ?: false,
            updatedAt = (p[5] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsert(entity)
    }

    private fun androidDoseId(dayStart: Long, scheduleId: String): String =
        "med-$dayStart-$scheduleId"
}

/**
 * Coach module sync mappers (V11/V10 schema bump).
 *
 * ### coach_plan payload (CBOR int→value)
 *   1: weekStartMs           (int, local-midnight epoch ms)
 *   2: generatedAtMs         (int)
 *   3: ruleVersion           (int)
 *   4: phaseRaw              (string)
 *   5: goalsJson             (string)
 *
 * ### coach_task payload
 *   1: planId                (string UUID)
 *   2: dayOffset             (int)
 *   3: moduleRaw             (string)
 *   4: title                 (string)
 *   5: targetValue           (double?)
 *   6: targetUnit            (string?)
 *   7: intensityRaw          (string)
 *   8: safetyHold            (bool)
 *   9: completedAtMs         (int?)
 *  10: skipped               (bool)
 *  11: movedDayOffset        (int?)
 *
 * ### sleep_log payload
 *   1: durationMin           (int)
 *   2: sourceRaw             (string)
 *   3: updatedAtMs           (int)
 *   // pk = dayStartMs as String
 *
 * ### diet_check payload
 *   1: sodiumLevelRaw        (string, "low"|"mid"|"high")
 *   2: vegServings           (int)
 *   3: sourceRaw             (string)
 *   4: updatedAtMs           (int)
 *   // pk = dayStartMs as String
 */

class CoachPlanSyncMapper(
    private val dao: CoachPlanDao,
) : SyncRecordMapper<CoachPlanEntity> {

    override fun encode(entity: CoachPlanEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Int64(entity.weekStart),
            2 to SyncValue.Int64(entity.generatedAt),
            3 to SyncValue.Int64(entity.ruleVersion.toLong()),
            4 to SyncValue.Text(entity.phaseRaw),
            5 to SyncValue.Text(entity.goalsJson),
        )
        return SyncRecord(
            type = SyncEntityType.COACH_PLAN,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.COACH_PLAN)
        if (record.isTombstone) return
        val p = record.payload
        val entity = CoachPlanEntity(
            id = record.pk,
            weekStart = (p[1] as? SyncValue.Int64)?.value ?: 0L,
            generatedAt = (p[2] as? SyncValue.Int64)?.value ?: 0L,
            ruleVersion = ((p[3] as? SyncValue.Int64)?.value ?: 1L).toInt(),
            phaseRaw = (p[4] as? SyncValue.Text)?.value ?: "initiation",
            goalsJson = (p[5] as? SyncValue.Text)?.value ?: "{}",
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.insertPlan(entity)
    }
}

class CoachTaskSyncMapper(
    private val dao: CoachPlanDao,
) : SyncRecordMapper<CoachTaskEntity> {

    override fun encode(entity: CoachTaskEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.planId),
            2 to SyncValue.Int64(entity.dayOffset.toLong()),
            3 to SyncValue.Text(entity.moduleRaw),
            4 to SyncValue.Text(entity.title),
            5 to (entity.targetValue?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            6 to (entity.targetUnit?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            7 to SyncValue.Text(entity.intensityRaw),
            8 to SyncValue.Bool(entity.safetyHold),
            9 to (entity.completedAt?.let { SyncValue.Int64(it) } ?: SyncValue.Null),
            10 to SyncValue.Bool(entity.skipped),
            11 to (entity.movedDayOffset?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
        )
        return SyncRecord(
            type = SyncEntityType.COACH_TASK,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.COACH_TASK)
        if (record.isTombstone) return
        val p = record.payload
        val planId = (p[1] as? SyncValue.Text)?.value ?: return
        // FK to coach_plan — drop orphan tasks (parent will arrive in a
        // subsequent round and the peer can re-emit then).
        if (dao.findPlanById(planId) == null) return
        val entity = CoachTaskEntity(
            id = record.pk,
            planId = planId,
            dayOffset = ((p[2] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            moduleRaw = (p[3] as? SyncValue.Text)?.value ?: "exercise",
            title = (p[4] as? SyncValue.Text)?.value ?: "",
            targetValue = (p[5] as? SyncValue.Double)?.value,
            targetUnit = (p[6] as? SyncValue.Text)?.value,
            intensityRaw = (p[7] as? SyncValue.Text)?.value ?: "light",
            safetyHold = (p[8] as? SyncValue.Bool)?.value ?: false,
            completedAt = (p[9] as? SyncValue.Int64)?.value,
            // Tags 10/11 added later — peers that predate them fall back to the
            // entity defaults (not skipped, no move) for backward compatibility.
            skipped = (p[10] as? SyncValue.Bool)?.value ?: false,
            movedDayOffset = (p[11] as? SyncValue.Int64)?.value?.toInt(),
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.insertTasks(listOf(entity))
    }
}

class SleepLogSyncMapper(
    private val dao: SleepDao,
) : SyncRecordMapper<SleepLogEntity> {

    override fun encode(entity: SleepLogEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Int64(entity.durationMin.toLong()),
            2 to SyncValue.Text(entity.sourceRaw),
            3 to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.SLEEP_LOG,
            pk = entity.dayStart.toString(),
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.SLEEP_LOG)
        if (record.isTombstone) return
        val dayStart = record.pk.toLongOrNull() ?: return
        val p = record.payload
        val entity = SleepLogEntity(
            dayStart = dayStart,
            durationMin = ((p[1] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            sourceRaw = (p[2] as? SyncValue.Text)?.value ?: "manual",
            updatedAt = (p[3] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsert(entity)
    }
}

class DietCheckSyncMapper(
    private val dao: DietDao,
) : SyncRecordMapper<DietCheckEntity> {

    override fun encode(entity: DietCheckEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.sodiumLevelRaw),
            2 to SyncValue.Int64(entity.vegServings.toLong()),
            3 to SyncValue.Text(entity.sourceRaw),
            4 to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.DIET_CHECK,
            pk = entity.dayStart.toString(),
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.DIET_CHECK)
        if (record.isTombstone) return
        val dayStart = record.pk.toLongOrNull() ?: return
        val p = record.payload
        val entity = DietCheckEntity(
            dayStart = dayStart,
            sodiumLevelRaw = (p[1] as? SyncValue.Text)?.value ?: "mid",
            vegServings = ((p[2] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            sourceRaw = (p[3] as? SyncValue.Text)?.value ?: "manual",
            updatedAt = (p[4] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsert(entity)
    }
}
