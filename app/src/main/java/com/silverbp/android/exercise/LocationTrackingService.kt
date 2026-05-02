package com.silverbp.android.exercise

import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService()
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        ExerciseNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val kindRaw = intent.getStringExtra(EXTRA_KIND) ?: ActivityKind.Walking.raw
                val kind = ActivityKind.fromRaw(kindRaw)
                ensureForeground()
                if (!trackingStarted) {
                    liveStore.start(kind, Instant.now(), stepBaseline = null)
                    startLocationUpdates()
                    startStepCounter()
                    startNotificationRefresh()
                    trackingStarted = true
                }
            }
            ACTION_PAUSE -> liveStore.pause()
            ACTION_RESUME -> liveStore.resume()
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val notification = ExerciseNotification.build(this, liveStore.flow.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ExerciseNotification.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(ExerciseNotification.NOTIF_ID, notification)
        }
    }

    private fun startLocationUpdates() {
        // 3s cadence + minDistance=0 mirrors iOS BPExercise; jitter is absorbed by
        // LiveStore's accuracy/age/speed filters and the 8 s auto-pause window.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(3_000L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked between Start tap and service start; bail out.
            stopSelf()
        }
    }

    private fun startStepCounter() {
        val sensor = stepSensor ?: return
        sensorManager?.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun startNotificationRefresh() {
        val nm = NotificationManagerCompat.from(this)
        lifecycleScope.launch {
            while (isActive) {
                delay(1_000L)
                if (!trackingStarted) break
                runCatching {
                    nm.notify(
                        ExerciseNotification.NOTIF_ID,
                        ExerciseNotification.build(this@LocationTrackingService, liveStore.flow.value),
                    )
                }
            }
        }
    }

    private fun stopTracking() {
        if (!trackingStarted) return
        runCatching { fusedClient.removeLocationUpdates(locationCallback) }
        sensorManager?.unregisterListener(stepListener)
        trackingStarted = false
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.silverbp.android.exercise.START"
        const val ACTION_PAUSE = "com.silverbp.android.exercise.PAUSE"
        const val ACTION_RESUME = "com.silverbp.android.exercise.RESUME"
        const val ACTION_STOP = "com.silverbp.android.exercise.STOP"
        const val EXTRA_KIND = "extra_kind"
    }
}
