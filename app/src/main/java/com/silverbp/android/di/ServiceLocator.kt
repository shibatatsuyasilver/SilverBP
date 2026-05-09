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
}
