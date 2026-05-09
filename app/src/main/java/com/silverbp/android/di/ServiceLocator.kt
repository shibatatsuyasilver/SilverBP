package com.silverbp.android.di

import android.content.Context
import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.achievements.StepBaselineStore
import com.silverbp.android.chat.ChatRepository
import com.silverbp.android.coach.BpAnomalyWatcher
import com.silverbp.android.coach.CoachEngine
import com.silverbp.android.coach.CoachNarrator
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.coach.ExerciseRepoSummaryProvider
import com.silverbp.android.coach.TodayExerciseTaskGenerator
import com.silverbp.android.recognition.chat.ChatRecognizerFactory
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.db.SilverBpDatabase
import com.silverbp.android.exercise.ExerciseController
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSessionLiveStore
import com.silverbp.android.exercise.HealthConnectExerciseBridge
import com.silverbp.android.exercise.StepCounterReader
import com.silverbp.android.health.HealthConnectBridge
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.sync.AchievementSyncMapper
import com.silverbp.android.sync.BpReadingSyncMapper
import com.silverbp.android.sync.CoachPlanSyncMapper
import com.silverbp.android.sync.CoachTaskSyncMapper
import com.silverbp.android.sync.DailyStepLogSyncMapper
import com.silverbp.android.sync.DietCheckSyncMapper
import com.silverbp.android.sync.SleepLogSyncMapper
import com.silverbp.android.sync.ExerciseSessionSyncMapper
import com.silverbp.android.sync.MedicationDoseSyncMapper
import com.silverbp.android.sync.MedicationScheduleSyncMapper
import com.silverbp.android.sync.MedicationSyncMapper
import com.silverbp.android.sync.RoutePointSyncMapper
import com.silverbp.android.sync.SyncCoordinator
import com.silverbp.android.sync.pairing.EncryptedPairingKeyStore
import com.silverbp.android.sync.pairing.PairingKeyStore

/**
 * Hand-rolled DI. All services are application-scoped and lazily initialised
 * the first time they're requested. Replace with Hilt once the Hilt Gradle
 * plugin catches up to AGP 9.
 */
object ServiceLocator {
    @Volatile private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    val context: Context
        get() = appCtx ?: error("ServiceLocator not initialised — did SilverBpApplication.onCreate run?")

    val database: SilverBpDatabase by lazy { SilverBpDatabase.get(context) }

    val bpRepository: BpRepository by lazy { BpRepository(database.bpDao()) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }

    val userSettings: UserSettingsRepository by lazy { UserSettingsRepository(context) }

    val modelLoadStatus: ModelLoadStatus by lazy { ModelLoadStatus() }

    val healthConnectExerciseBridge: HealthConnectExerciseBridge by lazy {
        HealthConnectExerciseBridge(context)
    }

    val healthConnectBridge: HealthConnectBridge by lazy { HealthConnectBridge(context) }

    val exerciseLiveStore: ExerciseSessionLiveStore by lazy { ExerciseSessionLiveStore() }

    val exerciseRepository: ExerciseRepository by lazy {
        ExerciseRepository(database.exerciseDao(), healthConnectExerciseBridge) {
            achievementStore.launchRefresh()
        }
    }

    val exerciseController: ExerciseController by lazy {
        ExerciseController(context, exerciseLiveStore)
    }

    private val stepCounterReader: StepCounterReader by lazy { StepCounterReader(context) }

    private val stepBaselineStore: StepBaselineStore by lazy { StepBaselineStore(context) }

    val achievementStore: AchievementStore by lazy {
        AchievementStore(
            context = context,
            achievementDao = database.achievementDao(),
            bridge = healthConnectExerciseBridge,
            settings = userSettings,
            sensor = stepCounterReader,
            baseline = stepBaselineStore,
        )
    }

    val coachRepository: CoachRepository by lazy {
        CoachRepository(
            plans = database.coachPlanDao(),
            sleeps = database.sleepDao(),
            diets = database.dietDao(),
            doses = database.medicationDoseDao(),
            medicationSchedules = database.medicationScheduleDao(),
            medications = database.medicationDao(),
        )
    }

    val coachEngine: CoachEngine by lazy {
        CoachEngine(
            bp = bpRepository,
            exercise = exerciseRepository,
            coachRepo = coachRepository,
            settings = userSettings,
        )
    }

    val coachNarrator: CoachNarrator by lazy { CoachNarrator() }

    val todayExerciseTaskGenerator: TodayExerciseTaskGenerator by lazy {
        TodayExerciseTaskGenerator(
            summaryProvider = ExerciseRepoSummaryProvider(exerciseRepository),
            chatFactory = { ChatRecognizerFactory.current() },
        )
    }

    val bpAnomalyWatcher: BpAnomalyWatcher by lazy {
        BpAnomalyWatcher(context, bpRepository, coachEngine, userSettings)
    }

    /**
     * Persistent identity for cross-device sync. Lazily creates the device's
     * stable nodeId (random 64 bits) and long-term X25519 static keypair on
     * first use; both live in EncryptedSharedPreferences so they survive app
     * upgrades but never leave the device.
     */
    val pairingKeyStore: PairingKeyStore by lazy {
        EncryptedPairingKeyStore.create(context)
    }

    /**
     * Bridges Room's `bp_reading` table to the wire-format SyncRecord used
     * over Noise. The mapper is the only sync component that knows about
     * Room entities; everything below it is platform-neutral.
     */
    val bpReadingSyncMapper: BpReadingSyncMapper by lazy {
        BpReadingSyncMapper(database.bpDao(), database.syncDao())
    }

    /** Phase 2 mappers — exercise + medication. Same lifetime/scope rules as
     *  [bpReadingSyncMapper]; collectively wired into [CombinedRoomSyncSource]
     *  / [CombinedRoomSyncSink] by [ui.sync.PairingViewModel]. */
    val exerciseSessionSyncMapper: ExerciseSessionSyncMapper by lazy {
        ExerciseSessionSyncMapper(database.exerciseDao(), database.syncDao())
    }

    val routePointSyncMapper: RoutePointSyncMapper by lazy {
        RoutePointSyncMapper(database.exerciseDao())
    }

    val medicationSyncMapper: MedicationSyncMapper by lazy {
        MedicationSyncMapper(database.medicationDao(), database.syncDao())
    }

    val medicationScheduleSyncMapper: MedicationScheduleSyncMapper by lazy {
        MedicationScheduleSyncMapper(database.medicationScheduleDao(), database.syncDao())
    }

    val medicationDoseSyncMapper: MedicationDoseSyncMapper by lazy {
        MedicationDoseSyncMapper(database.medicationDoseDao())
    }

    val dailyStepLogSyncMapper: DailyStepLogSyncMapper by lazy {
        DailyStepLogSyncMapper(database.achievementDao())
    }

    val achievementSyncMapper: AchievementSyncMapper by lazy {
        AchievementSyncMapper(database.achievementDao())
    }

    val coachPlanSyncMapper: CoachPlanSyncMapper by lazy {
        CoachPlanSyncMapper(database.coachPlanDao())
    }

    val coachTaskSyncMapper: CoachTaskSyncMapper by lazy {
        CoachTaskSyncMapper(database.coachPlanDao())
    }

    val sleepLogSyncMapper: SleepLogSyncMapper by lazy {
        SleepLogSyncMapper(database.sleepDao())
    }

    val dietCheckSyncMapper: DietCheckSyncMapper by lazy {
        DietCheckSyncMapper(database.dietDao())
    }

    /**
     * Stable per-device id used when introducing ourselves to a peer in the
     * HELLO frame. Sourced from the manufacturer model + a random 8-byte
     * suffix saved alongside the static key, so we don't dox the user via
     * device serial. Persisted in [PairingKeyStore].
     */
    val syncDeviceId: String by lazy {
        // 16 hex chars of nodeId — stable across app installs of the same
        // keystore-backed device but new on factory reset / re-install.
        "android-${"%016x".format(pairingKeyStore.loadOrCreateNodeId())}"
    }

    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(deviceId = syncDeviceId, keyStore = pairingKeyStore)
    }
}
