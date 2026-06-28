package com.silverbp.android.sync

import com.silverbp.android.core.db.SilverBpDatabase
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.sync.engine.HlcClock

/**
 * Builds the LAN-sync record [CombinedRoomSyncSource] / [CombinedRoomSyncSink]
 * from a [SilverBpDatabase], wiring every domain mapper exactly once. Shared by
 * the initial pairing round ([com.silverbp.android.ui.sync.PairingViewModel])
 * and background re-sync ([PeerSyncRunner] / [LanSyncWorker]) so the two wirings
 * can never drift apart.
 *
 * Chat + settings_kv mappers are intentionally omitted — they are backup-only
 * and must not travel over LAN sync. The B6 LWW gate is enabled by passing
 * `syncDao`; the source's pre-sync HLC repair uses `localSyncDao`.
 */
object LanSyncAdapters {

    fun buildSource(db: SilverBpDatabase, clock: HlcClock): CombinedRoomSyncSource =
        CombinedRoomSyncSource(
            bpDao = db.bpDao(),
            exerciseDao = db.exerciseDao(),
            medicationDao = db.medicationDao(),
            medicationScheduleDao = db.medicationScheduleDao(),
            medicationDoseDao = db.medicationDoseDao(),
            achievementDao = db.achievementDao(),
            coachPlanDao = db.coachPlanDao(),
            sleepDao = db.sleepDao(),
            dietDao = db.dietDao(),
            bpMapper = ServiceLocator.bpReadingSyncMapper,
            exerciseSessionMapper = ServiceLocator.exerciseSessionSyncMapper,
            routePointMapper = ServiceLocator.routePointSyncMapper,
            medicationMapper = ServiceLocator.medicationSyncMapper,
            medicationScheduleMapper = ServiceLocator.medicationScheduleSyncMapper,
            medicationDoseMapper = ServiceLocator.medicationDoseSyncMapper,
            dailyStepLogMapper = ServiceLocator.dailyStepLogSyncMapper,
            achievementMapper = ServiceLocator.achievementSyncMapper,
            coachPlanMapper = ServiceLocator.coachPlanSyncMapper,
            coachTaskMapper = ServiceLocator.coachTaskSyncMapper,
            sleepLogMapper = ServiceLocator.sleepLogSyncMapper,
            dietCheckMapper = ServiceLocator.dietCheckSyncMapper,
            clock = clock,
            exerciseLibraryDao = db.exerciseLibraryDao(),
            strengthWorkoutDao = db.strengthWorkoutDao(),
            exerciseCatalogItemMapper = ServiceLocator.exerciseCatalogItemSyncMapper,
            strengthWorkoutSessionMapper = ServiceLocator.strengthWorkoutSessionSyncMapper,
            setLogMapper = ServiceLocator.setLogSyncMapper,
            bpWorkoutAssociationDao = db.bpWorkoutAssociationDao(),
            bpWorkoutAssociationMapper = ServiceLocator.bpWorkoutAssociationSyncMapper,
            foodLogDao = db.foodLogDao(),
            foodLogMapper = ServiceLocator.foodLogSyncMapper,
            memberDao = db.memberDao(),
            memberMapper = ServiceLocator.memberSyncMapper,
            glucoseDao = db.glucoseDao(),
            glucoseMapper = ServiceLocator.glucoseReadingSyncMapper,
            weightDao = db.weightDao(),
            weightMapper = ServiceLocator.weightReadingSyncMapper,
            syncDao = db.syncDao(),
            localSyncDao = db.localSyncMutationDao(),
        )

    fun buildSink(db: SilverBpDatabase): CombinedRoomSyncSink =
        CombinedRoomSyncSink(
            bpMapper = ServiceLocator.bpReadingSyncMapper,
            exerciseSessionMapper = ServiceLocator.exerciseSessionSyncMapper,
            routePointMapper = ServiceLocator.routePointSyncMapper,
            medicationMapper = ServiceLocator.medicationSyncMapper,
            medicationScheduleMapper = ServiceLocator.medicationScheduleSyncMapper,
            medicationDoseMapper = ServiceLocator.medicationDoseSyncMapper,
            dailyStepLogMapper = ServiceLocator.dailyStepLogSyncMapper,
            achievementMapper = ServiceLocator.achievementSyncMapper,
            coachPlanMapper = ServiceLocator.coachPlanSyncMapper,
            coachTaskMapper = ServiceLocator.coachTaskSyncMapper,
            sleepLogMapper = ServiceLocator.sleepLogSyncMapper,
            dietCheckMapper = ServiceLocator.dietCheckSyncMapper,
            exerciseCatalogItemMapper = ServiceLocator.exerciseCatalogItemSyncMapper,
            strengthWorkoutSessionMapper = ServiceLocator.strengthWorkoutSessionSyncMapper,
            setLogMapper = ServiceLocator.setLogSyncMapper,
            bpWorkoutAssociationMapper = ServiceLocator.bpWorkoutAssociationSyncMapper,
            foodLogMapper = ServiceLocator.foodLogSyncMapper,
            memberMapper = ServiceLocator.memberSyncMapper,
            glucoseMapper = ServiceLocator.glucoseReadingSyncMapper,
            weightMapper = ServiceLocator.weightReadingSyncMapper,
            // B6 LWW gate over LAN sync (compares record.hlc vs local).
            syncDao = db.syncDao(),
        )
}
