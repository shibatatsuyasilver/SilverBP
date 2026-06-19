package com.silverbp.android.exercise

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Foreground service that keeps GPS + step counter active while the app is
 * backgrounded or screen is off. Writes into the singleton
 * [ExerciseSessionLiveStore]; the UI subscribes to its flow.
 *
 * Manifest entry must include `android:foregroundServiceType="location"`,
 * otherwise [startForeground] throws SecurityException on Android 14+.
 */
class LocationTrackingService : LifecycleService() {

    private val liveStore by lazy { ServiceLocator.exerciseLiveStore }
    private lateinit var fusedClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            liveStore.appendPoint(loc)
        }
    }

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val raw = event.values.firstOrNull()?.toLong() ?: return
            liveStore.updateRawStepCounter(raw)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var trackingStarted = false

    /**
     * One-shot guard so we don't keep re-posting the heads-up reminder on
     * every 1 s tick once the user has crossed the idle threshold. Cleared
     * when runState transitions out of Paused/AutoPaused (the loop's else
     * branch) or when the user explicitly taps "Keep going"
     * ([ACTION_IDLE_CONTINUE]).
     */
    @Volatile private var idleReminderShown = false

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService()
        ExerciseNotification.createChannel(this)
        ExerciseNotification.createIdleReminderChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val kindRaw = intent.getStringExtra(EXTRA_KIND) ?: ActivityKind.Walking.raw
                val kind = ActivityKind.fromRaw(kindRaw)
                if (!trackingStarted) {
                    liveStore.start(kind, Instant.now(), stepBaseline = null)
                    beginTracking()
                }
            }
            ACTION_RESTORE -> {
                // liveStore already holds the checkpoint-recovered (Paused)
                // session; re-attach GPS/steps without creating a new session.
                // 若位置權限在背景被撤銷,fail safe:直接停止,保留檢查點讓使用者
                // 授權後重試,而非讓 startForeground 在 Android 14+ 拋出例外崩潰。
                // Finished 檢查點(只等摘要頁儲存)不會由 Controller 啟動本服務;
                // 萬一仍收到,無事可追蹤(也不得要求位置權限),直接停止。
                val live = liveStore.flow.value
                if (!trackingStarted && live != null) {
                    if (live.runState != RunState.Finished && hasFineLocation()) {
                        beginTracking()
                        liveStore.persist()
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_PAUSE -> { liveStore.pause(); liveStore.persist() }
            ACTION_RESUME -> { liveStore.resume(); liveStore.persist() }
            ACTION_IDLE_CONTINUE -> {
                liveStore.acknowledgeIdleReminder()
                runCatching {
                    NotificationManagerCompat.from(this)
                        .cancel(ExerciseNotification.IDLE_REMINDER_NOTIF_ID)
                }
                idleReminderShown = false
            }
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /** Bring up the foreground notification + GPS/steps. Returns false (and stops) on permission loss. */
    private fun beginTracking(): Boolean {
        if (!ensureForeground()) {
            // startForeground 在缺少位置權限時於 Android 14+ 拋出 SecurityException;
            // 視同權限遺失,保留檢查點並停止服務而非崩潰。
            liveStore.setError(LiveError.LocationPermissionRevoked)
            stopSelf()
            return false
        }
        return if (startLocationUpdates()) {
            startStepCounter()
            startNotificationRefresh()
            trackingStarted = true
            true
        } else {
            // Permission failure already surfaced via liveStore.error; tear down
            // the foreground we just started.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            false
        }
    }

    /** Returns true if the foreground notification was raised; false if it was rejected (e.g. revoked location permission on Android 14+). */
    private fun ensureForeground(): Boolean {
        val notification = ExerciseNotification.build(this, liveStore.flow.value)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ExerciseNotification.NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(ExerciseNotification.NOTIF_ID, notification)
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** True when ACCESS_FINE_LOCATION is granted; coarse-only counts as missing (the 50 m accuracy gate filters every coarse fix). */
    private fun hasFineLocation(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Returns true if updates were requested; false (and surfaces an error) on permission loss. */
    private fun startLocationUpdates(): Boolean {
        // 3s cadence + minDistance=0 mirrors iOS BPExercise; jitter is absorbed by
        // LiveStore's accuracy/age/speed filters and the 8 s auto-pause window.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(3_000L)
            .setWaitForAccurateLocation(false)
            .build()
        return try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            // Permission revoked between Start tap and service start. Surface it
            // to the UI (which shows why it can't track instead of silently
            // bouncing); setError() also drops the half-started live session.
            liveStore.setError(LiveError.LocationPermissionRevoked)
            false
        }
    }

    private fun startStepCounter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stepSensor = null
            return
        }
        val mgr = sensorManager ?: return
        val sensor = stepSensor
            ?: runCatching { mgr.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
                .getOrNull()
                ?.also { stepSensor = it }
            ?: return
        try {
            mgr.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: SecurityException) {
            stepSensor = null
        }
    }

    private fun startNotificationRefresh() {
        val nm = NotificationManagerCompat.from(this)
        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000L)
                if (!trackingStarted) break
                val live = liveStore.flow.value
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching {
                        nm.notify(
                            ExerciseNotification.NOTIF_ID,
                            ExerciseNotification.build(this@LocationTrackingService, live),
                        )
                    }
                }
                maybeUpdateIdleReminder(nm, live)
                // Checkpoint roughly every 10 s so a process kill loses at most a
                // few route points, not the whole session.
                if (++tick % 10 == 0) liveStore.persist()
            }
        }
    }

    /**
     * Surfaces the heads-up idle-reminder notification once continuous
     * Paused/AutoPaused time crosses [ExerciseSessionLiveStore.IDLE_REMINDER_THRESHOLD_MS],
     * and cancels it as soon as runState transitions back out of paused
     * (manual Resume, auto-resume on movement, or Stop).
     */
    private fun maybeUpdateIdleReminder(nm: NotificationManagerCompat, live: SessionLive?) {
        val paused = live != null &&
            (live.runState == RunState.Paused || live.runState == RunState.AutoPaused)
        val pausedSince = live?.pausedSinceMillis
        // Don't even attempt the notify() when POST_NOTIFICATIONS is denied —
        // mirrors MedalNotifier's check-before-notify pattern and keeps
        // idleReminderShown honest (we never mark it shown if it can't show).
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        when {
            canNotify && paused && pausedSince != null &&
                (System.currentTimeMillis() - pausedSince) >= ExerciseSessionLiveStore.IDLE_REMINDER_THRESHOLD_MS &&
                !idleReminderShown -> {
                runCatching {
                    nm.notify(
                        ExerciseNotification.IDLE_REMINDER_NOTIF_ID,
                        ExerciseNotification.buildIdleReminder(this, live!!),
                    )
                }
                idleReminderShown = true
            }
            !paused && idleReminderShown -> {
                runCatching { nm.cancel(ExerciseNotification.IDLE_REMINDER_NOTIF_ID) }
                idleReminderShown = false
            }
        }
    }

    private fun stopTracking() {
        if (!trackingStarted) return
        runCatching { fusedClient.removeLocationUpdates(locationCallback) }
        sensorManager?.unregisterListener(stepListener)
        trackingStarted = false
        runCatching {
            NotificationManagerCompat.from(this)
                .cancel(ExerciseNotification.IDLE_REMINDER_NOTIF_ID)
        }
        idleReminderShown = false
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.silverbp.android.exercise.START"
        /** Re-attach tracking to a session already restored into the LiveStore. */
        const val ACTION_RESTORE = "com.silverbp.android.exercise.RESTORE"
        const val ACTION_PAUSE = "com.silverbp.android.exercise.PAUSE"
        const val ACTION_RESUME = "com.silverbp.android.exercise.RESUME"
        const val ACTION_STOP = "com.silverbp.android.exercise.STOP"
        /**
         * User tapped "Keep going" on the idle-reminder heads-up notification:
         * restart the 10 min countdown without changing runState. Session
         * stays Paused/AutoPaused; the next reminder won't fire for another
         * [ExerciseSessionLiveStore.IDLE_REMINDER_THRESHOLD_MS].
         */
        const val ACTION_IDLE_CONTINUE = "com.silverbp.android.exercise.IDLE_CONTINUE"
        const val EXTRA_KIND = "extra_kind"
    }
}
