package com.silverbp.android.di

import android.content.Context
import androidx.room.withTransaction
import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.achievements.StepBaselineStore
import com.silverbp.android.chat.ChatRepository
import com.silverbp.android.coach.BpAnomalyWatcher
import com.silverbp.android.coach.BpWorkoutAssociationRepository
import com.silverbp.android.coach.CoachEngine
import com.silverbp.android.coach.CoachNarrator
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.coach.ExerciseRepoSummaryProvider
import com.silverbp.android.coach.MedicationRepository
import com.silverbp.android.coach.TodayExerciseTaskGenerator
import com.silverbp.android.recognition.chat.ChatRecognizerFactory
import com.silverbp.android.core.BpRepository
import com.silverbp.android.core.GlucoseRepository
import com.silverbp.android.core.WeightRepository
import com.silverbp.android.core.db.SilverBpDatabase
import com.silverbp.android.core.member.CurrentMemberStore
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.exercise.ExerciseController
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSessionLiveStore
import com.silverbp.android.exercise.SessionCheckpointStore
import com.silverbp.android.exercise.HealthConnectExerciseBridge
import com.silverbp.android.exercise.StepCounterReader
import com.silverbp.android.health.HealthConnectBpBridge
import com.silverbp.android.health.HealthConnectGlucoseBridge
import com.silverbp.android.health.HealthConnectWeightBridge
import com.silverbp.android.health.HealthConnectBridge
import com.silverbp.android.health.HealthConnectNutritionBridge
import com.silverbp.android.nutrition.NutritionRepository
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.security.DbKeyStore
import com.silverbp.android.security.LockManager
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.strength.ExerciseLibraryRepository
import com.silverbp.android.strength.StrengthSeed
import com.silverbp.android.strength.StrengthWorkoutLiveStore
import com.silverbp.android.strength.StrengthWorkoutRepository
import com.silverbp.android.BuildConfig
import com.silverbp.android.backup.BackupManager
import com.silverbp.android.backup.auto.AutoBackupScheduler
import com.silverbp.android.backup.auto.GoogleAuthClient
import com.silverbp.android.backup.auto.GoogleDriveBackupClient
import com.silverbp.android.billing.BillingClientWrapper
import com.silverbp.android.billing.EntitlementManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import com.silverbp.android.sync.AchievementSyncMapper
import com.silverbp.android.sync.BpReadingSyncMapper
import com.silverbp.android.sync.GlucoseReadingSyncMapper
import com.silverbp.android.sync.WeightReadingSyncMapper
import com.silverbp.android.sync.BpWorkoutAssociationSyncMapper
import com.silverbp.android.sync.ChatMessageSyncMapper
import com.silverbp.android.sync.ChatSessionSyncMapper
import com.silverbp.android.sync.CoachPlanSyncMapper
import com.silverbp.android.sync.CoachTaskSyncMapper
import com.silverbp.android.sync.CombinedRoomSyncSink
import com.silverbp.android.sync.CombinedRoomSyncSource
import com.silverbp.android.sync.DailyStepLogSyncMapper
import com.silverbp.android.sync.DietCheckSyncMapper
import com.silverbp.android.sync.ExerciseCatalogItemSyncMapper
import com.silverbp.android.sync.FoodLogSyncMapper
import com.silverbp.android.sync.SetLogSyncMapper
import com.silverbp.android.sync.StrengthWorkoutSessionSyncMapper
import com.silverbp.android.sync.SettingsKvSyncMapper
import com.silverbp.android.sync.SleepLogSyncMapper
import com.silverbp.android.sync.ExerciseSessionSyncMapper
import com.silverbp.android.sync.MedicationDoseSyncMapper
import com.silverbp.android.sync.MedicationScheduleSyncMapper
import com.silverbp.android.sync.MedicationSyncMapper
import com.silverbp.android.sync.MemberSyncMapper
import com.silverbp.android.sync.RoutePointSyncMapper
import com.silverbp.android.sync.RoomLocalSyncWriter
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

    /**
     * Keystore-wrapped SQLCipher passphrase + "is the DB encrypted" marker.
     * Constructed before [database] is first touched; the marker decides
     * whether Room opens the file with a cipher key. Also used by the
     * opt-in/opt-out migration and the Settings toggle.
     */
    val dbKeyStore: DbKeyStore by lazy { DbKeyStore.create(context) }

    /** Process-wide app-lock UI state for the opt-in biometric gate. */
    val lockManager: LockManager by lazy { LockManager() }

    /**
     * Process-lifetime scope for「必須跑完」的背景工作(例如備份還原).
     * 跟 viewModelScope 不同,使用者離開畫面不會取消正在進行的工作.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val database: SilverBpDatabase by lazy { SilverBpDatabase.get(context) }

    /** Member (family) profiles + the single-owner invariant; anchor for the HC mirror guard. */
    val memberRepository: MemberRepository by lazy { MemberRepository(database.memberDao(), localSyncWriter) }

    /**
     * Currently-selected member, persisted device-locally (NOT in settings sync).
     * Falls back to the owner when unset; survives process death.
     */
    val currentMemberStore: CurrentMemberStore by lazy {
        CurrentMemberStore(context, memberRepository)
    }

    val bpRepository: BpRepository by lazy {
        BpRepository(
            dao = database.bpDao(),
            members = memberRepository,
            healthConnect = healthConnectBpBridge,
            // Coarse gate: only mirror to Health Connect when the user has the
            // integration switched on. The bridge still independently checks
            // the BP write permission before inserting.
            healthConnectEnabled = { userSettings.flow.first().enableHealthConnect },
            localSync = localSyncWriter,
        )
    }

    /**
     * Blood-glucose readings (v19), member-scoped with the same owner-only
     * Health Connect mirror guard as [bpRepository]. The mirror writes a
     * [androidx.health.connect.client.records.BloodGlucoseRecord] via
     * [healthConnectGlucoseBridge]; the bridge independently checks the glucose
     * write permission, and the owner-only guard lives in the repository.
     */
    val glucoseRepository: GlucoseRepository by lazy {
        GlucoseRepository(
            dao = database.glucoseDao(),
            members = memberRepository,
            healthConnect = healthConnectGlucoseBridge,
            healthConnectEnabled = { userSettings.flow.first().enableHealthConnect },
            localSync = localSyncWriter,
        )
    }

    /**
     * Body-weight readings (kg canonical), member-scoped with the same owner-only
     * Health Connect mirror guard as [glucoseRepository]. The mirror writes a
     * [androidx.health.connect.client.records.WeightRecord] via
     * [healthConnectWeightBridge]; the bridge independently checks the weight
     * write permission, and the owner-only guard lives in the repository.
     */
    val weightRepository: WeightRepository by lazy {
        WeightRepository(
            dao = database.weightDao(),
            members = memberRepository,
            healthConnect = healthConnectWeightBridge,
            healthConnectEnabled = { userSettings.flow.first().enableHealthConnect },
            localSync = localSyncWriter,
        )
    }

    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }

    val nutritionRepository: NutritionRepository by lazy {
        NutritionRepository(
            dao = database.foodLogDao(),
            dietDao = database.dietDao(),
            healthConnect = healthConnectNutritionBridge,
            healthConnectEnabled = { userSettings.flow.first().enableHealthConnect },
            localSync = localSyncWriter,
        )
    }

    val userSettings: UserSettingsRepository by lazy { UserSettingsRepository(context) }

    // ============================================================
    // Play Billing (Phase 3) — subscription + entitlement resolution.
    // ============================================================

    /**
     * Wraps the Play [com.android.billingclient.api.BillingClient] for the single
     * "silverbp_premium" sub. Uses [applicationScope] so an in-flight
     * acknowledge/refresh outlives any UI scope. Degrades to empty results on the
     * emulator (no products) — never crashes.
     */
    val billingClient: BillingClientWrapper by lazy {
        BillingClientWrapper(context, applicationScope)
    }

    /**
     * Single source of truth for premium gating. Gates call [EntitlementManager.isPremium];
     * Settings reads [EntitlementManager.entitlement] for the real sub status.
     * With BuildConfig.PREMIUM_ENFORCED=false (beta) isPremium() is always true
     * unless a DEBUG override is set.
     */
    val entitlementManager: EntitlementManager by lazy {
        EntitlementManager(
            gateway = billingClient,
            settings = userSettings,
            scope = applicationScope,
        )
    }

    val modelLoadStatus: ModelLoadStatus by lazy { ModelLoadStatus() }

    val healthConnectExerciseBridge: HealthConnectExerciseBridge by lazy {
        HealthConnectExerciseBridge(context)
    }

    val healthConnectBridge: HealthConnectBridge by lazy { HealthConnectBridge(context) }

    val healthConnectBpBridge: HealthConnectBpBridge by lazy { HealthConnectBpBridge(context) }

    /** Owner-only one-way mirror of glucose readings to Health Connect (v19). */
    val healthConnectGlucoseBridge: HealthConnectGlucoseBridge by lazy {
        HealthConnectGlucoseBridge(context)
    }

    /**
     * Owner-only mirror of body-weight readings to Health Connect (v20), plus the
     * read-back import path for smart-scale / foreign WeightRecords. The import
     * de-dup uses the weight DAO's existing hcRecordIds so a re-sync never
     * duplicates rows we already hold.
     */
    val healthConnectWeightBridge: HealthConnectWeightBridge by lazy {
        HealthConnectWeightBridge(
            context,
            knownHcRecordIds = {
                database.weightDao().getAll().mapNotNull { it.hcRecordId }.toSet()
            },
        )
    }

    val healthConnectNutritionBridge: HealthConnectNutritionBridge by lazy {
        HealthConnectNutritionBridge(context)
    }

    private val sessionCheckpointStore: SessionCheckpointStore by lazy {
        SessionCheckpointStore(java.io.File(context.filesDir, "exercise/session-checkpoint.json"))
    }

    val exerciseLiveStore: ExerciseSessionLiveStore by lazy {
        ExerciseSessionLiveStore(sessionCheckpointStore)
    }

    val exerciseRepository: ExerciseRepository by lazy {
        ExerciseRepository(database.exerciseDao(), healthConnectExerciseBridge, localSyncWriter) {
            achievementStore.launchRefresh()
        }
    }

    val exerciseController: ExerciseController by lazy {
        ExerciseController(context, exerciseLiveStore)
    }

    // ============================================================
    // Strength training (v13)
    // ============================================================

    val exerciseLibraryRepository: ExerciseLibraryRepository by lazy {
        ExerciseLibraryRepository(database.exerciseLibraryDao())
    }

    val strengthWorkoutRepository: StrengthWorkoutRepository by lazy {
        StrengthWorkoutRepository(database.strengthWorkoutDao(), database.exerciseLibraryDao())
    }

    /** In-memory holder for the in-progress strength workout. */
    val strengthWorkoutLiveStore: StrengthWorkoutLiveStore by lazy { StrengthWorkoutLiveStore() }

    /**
     * One-time seed of the exercise move library. Idempotent — no-ops once the
     * table is populated. Call from the library/workout entry ViewModel before
     * first read (there is no global one-time init hook for this module yet).
     */
    suspend fun ensureSeeded() {
        StrengthSeed.seedIfEmpty(database.exerciseLibraryDao())
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
            localSync = localSyncWriter,
        )
    }

    val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(
            medications = database.medicationDao(),
            schedules = database.medicationScheduleDao(),
            currentMemberId = { currentMemberStore.current() },
            ownerMemberId = { memberRepository.ownerId() },
            localSync = localSyncWriter,
            writeTombstone = { database.localSyncMutationDao().upsertTombstone(it) },
            inTransaction = { block -> database.withTransaction { block() } },
        )
    }

    val bpWorkoutAssociationRepository: BpWorkoutAssociationRepository by lazy {
        BpWorkoutAssociationRepository(database.bpWorkoutAssociationDao())
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
            bp = bpRepository,
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
        BpReadingSyncMapper(
            database.bpDao(),
            database.syncDao(),
            ownerIdProvider = { memberRepository.ownerId() },
        )
    }

    val memberSyncMapper: MemberSyncMapper by lazy {
        MemberSyncMapper(database.memberDao(), database.syncDao())
    }

    val glucoseReadingSyncMapper: GlucoseReadingSyncMapper by lazy {
        GlucoseReadingSyncMapper(
            database.glucoseDao(),
            database.syncDao(),
            ownerIdProvider = { memberRepository.ownerId() },
        )
    }

    val weightReadingSyncMapper: WeightReadingSyncMapper by lazy {
        WeightReadingSyncMapper(
            database.weightDao(),
            database.syncDao(),
            ownerIdProvider = { memberRepository.ownerId() },
        )
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
        MedicationSyncMapper(
            database.medicationDao(),
            database.syncDao(),
            ownerIdProvider = { memberRepository.ownerId() },
        )
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

    val exerciseCatalogItemSyncMapper: ExerciseCatalogItemSyncMapper by lazy {
        ExerciseCatalogItemSyncMapper(database.exerciseLibraryDao(), database.syncDao())
    }

    val strengthWorkoutSessionSyncMapper: StrengthWorkoutSessionSyncMapper by lazy {
        StrengthWorkoutSessionSyncMapper(database.strengthWorkoutDao(), database.syncDao())
    }

    val setLogSyncMapper: SetLogSyncMapper by lazy {
        SetLogSyncMapper(database.strengthWorkoutDao())
    }

    val bpWorkoutAssociationSyncMapper: BpWorkoutAssociationSyncMapper by lazy {
        BpWorkoutAssociationSyncMapper(database.bpWorkoutAssociationDao(), database.syncDao())
    }

    val foodLogSyncMapper: FoodLogSyncMapper by lazy {
        FoodLogSyncMapper(database.foodLogDao(), database.syncDao())
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

    val localSyncWriter: RoomLocalSyncWriter by lazy {
        RoomLocalSyncWriter(database.localSyncMutationDao(), syncCoordinator.clock)
    }

    // ============================================================
    // Backup (encrypted .sbpbk snapshot)
    // ============================================================

    val chatSessionSyncMapper: ChatSessionSyncMapper by lazy {
        ChatSessionSyncMapper(database.chatDao())
    }

    val chatMessageSyncMapper: ChatMessageSyncMapper by lazy {
        ChatMessageSyncMapper(database.chatDao())
    }

    val settingsKvSyncMapper: SettingsKvSyncMapper by lazy {
        SettingsKvSyncMapper(userSettings)
    }

    /**
     * Encrypted snapshot export/import orchestrator.
     *
     * Factories(rather than direct instances) for source/sink let BackupManager
     * build fresh combined adapters per export round — they hold ref to DAOs
     * which are safe to re-resolve from [database].
     */
    // ============================================================
    // Auto-backup to Google Drive (appDataFolder)
    // ============================================================

    /**
     * Shared OkHttp client for the Drive REST upload/list/delete/download
     * calls. Has its own dispatcher pool — we don't share with any other
     * subsystem because Drive uploads can stall on slow networks and we
     * don't want them blocking unrelated HTTP work.
     */
    private val driveHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val googleAuthClient: GoogleAuthClient by lazy { GoogleAuthClient(context) }
    val googleDriveBackupClient: GoogleDriveBackupClient by lazy {
        GoogleDriveBackupClient(driveHttpClient)
    }
    val autoBackupScheduler: AutoBackupScheduler by lazy { AutoBackupScheduler(context) }

    val backupManager: BackupManager by lazy {
        BackupManager(
            database = database,
            sourceFactory = {
                CombinedRoomSyncSource(
                    bpDao = database.bpDao(),
                    exerciseDao = database.exerciseDao(),
                    medicationDao = database.medicationDao(),
                    medicationScheduleDao = database.medicationScheduleDao(),
                    medicationDoseDao = database.medicationDoseDao(),
                    achievementDao = database.achievementDao(),
                    coachPlanDao = database.coachPlanDao(),
                    sleepDao = database.sleepDao(),
                    dietDao = database.dietDao(),
                    bpMapper = bpReadingSyncMapper,
                    exerciseSessionMapper = exerciseSessionSyncMapper,
                    routePointMapper = routePointSyncMapper,
                    medicationMapper = medicationSyncMapper,
                    medicationScheduleMapper = medicationScheduleSyncMapper,
                    medicationDoseMapper = medicationDoseSyncMapper,
                    dailyStepLogMapper = dailyStepLogSyncMapper,
                    achievementMapper = achievementSyncMapper,
                    coachPlanMapper = coachPlanSyncMapper,
                    coachTaskMapper = coachTaskSyncMapper,
                    sleepLogMapper = sleepLogSyncMapper,
                    dietCheckMapper = dietCheckSyncMapper,
                    clock = syncCoordinator.clock,
                    exerciseLibraryDao = database.exerciseLibraryDao(),
                    strengthWorkoutDao = database.strengthWorkoutDao(),
                    exerciseCatalogItemMapper = exerciseCatalogItemSyncMapper,
                    strengthWorkoutSessionMapper = strengthWorkoutSessionSyncMapper,
                    setLogMapper = setLogSyncMapper,
                    chatDao = database.chatDao(),
                    chatSessionMapper = chatSessionSyncMapper,
                    chatMessageMapper = chatMessageSyncMapper,
                    syncDao = database.syncDao(),
                    bpWorkoutAssociationDao = database.bpWorkoutAssociationDao(),
                    bpWorkoutAssociationMapper = bpWorkoutAssociationSyncMapper,
                    foodLogDao = database.foodLogDao(),
                    foodLogMapper = foodLogSyncMapper,
                    memberDao = database.memberDao(),
                    memberMapper = memberSyncMapper,
                    glucoseDao = database.glucoseDao(),
                    glucoseMapper = glucoseReadingSyncMapper,
                    weightDao = database.weightDao(),
                    weightMapper = weightReadingSyncMapper,
                    localSyncDao = database.localSyncMutationDao(),
                )
            },
            sinkFactory = {
                CombinedRoomSyncSink(
                    bpMapper = bpReadingSyncMapper,
                    exerciseSessionMapper = exerciseSessionSyncMapper,
                    routePointMapper = routePointSyncMapper,
                    medicationMapper = medicationSyncMapper,
                    medicationScheduleMapper = medicationScheduleSyncMapper,
                    medicationDoseMapper = medicationDoseSyncMapper,
                    dailyStepLogMapper = dailyStepLogSyncMapper,
                    achievementMapper = achievementSyncMapper,
                    coachPlanMapper = coachPlanSyncMapper,
                    coachTaskMapper = coachTaskSyncMapper,
                    sleepLogMapper = sleepLogSyncMapper,
                    dietCheckMapper = dietCheckSyncMapper,
                    exerciseCatalogItemMapper = exerciseCatalogItemSyncMapper,
                    strengthWorkoutSessionMapper = strengthWorkoutSessionSyncMapper,
                    setLogMapper = setLogSyncMapper,
                    chatSessionMapper = chatSessionSyncMapper,
                    chatMessageMapper = chatMessageSyncMapper,
                    settingsKvMapper = settingsKvSyncMapper,
                    bpWorkoutAssociationMapper = bpWorkoutAssociationSyncMapper,
                    foodLogMapper = foodLogSyncMapper,
                    memberMapper = memberSyncMapper,
                    glucoseMapper = glucoseReadingSyncMapper,
                    weightMapper = weightReadingSyncMapper,
                    // B6 LWW gate: import compares record.hlc vs the local row's
                    // hlcUpdatedAt + tombstone hlc before applying.
                    syncDao = database.syncDao(),
                )
            },
            settingsKvMapper = settingsKvSyncMapper,
            hlcClock = syncCoordinator.clock,
            localNodeIdHex = "%016x".format(pairingKeyStore.loadOrCreateNodeId()),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            // Room schema version — keep in lock-step with SilverBpDatabase.
            // 升 schema 時改這裡(備份檔頭會記下,匯入時做相容處理).
            schemaVersion = 21,
            // 向後相容:匯入 pre-v18 (無 member 表) 備份時合成 owner,
            // 讓無 memberId 的讀數歸 owner。
            ensureOwnerId = { memberRepository.ownerId() },
            // Replace 模式清空 member 表後,丟掉 MemberRepository 記憶體中的
            // owner id 快取,否則冷啟動暖好的舊 id 會繼續被回傳(findings 1 & 4)。
            invalidateOwnerCache = { memberRepository.invalidateOwnerCache() },
        )
    }
}
