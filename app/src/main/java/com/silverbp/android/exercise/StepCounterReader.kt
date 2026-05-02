package com.silverbp.android.exercise

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService

/**
 * Single-shot reader for the device step counter. Used by ExerciseHomeScreen
 * to display today's cumulative steps; the live session uses the service-side
 * sensor listener instead so background ticks keep accumulating.
 *
 * TYPE_STEP_COUNTER returns "steps since last reboot", not session delta —
 * that's a known Android quirk; consumers must subtract a baseline to get a
 * meaningful number.
 */
class StepCounterReader(context: Context) {

    private val sensorManager: SensorManager? = context.getSystemService()
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val isAvailable: Boolean get() = sensor != null

    /**
     * Register a one-shot listener; on the first sensor event the listener is
     * unregistered and [onResult] receives the raw counter value (or null if
     * the sensor is missing).
     *
     * Note: TYPE_STEP_COUNTER often only fires on each step taken, so this can
     * take a few seconds to return on a stationary device. Callers should
     * tolerate the asynchronous nature.
     */
    fun snapshot(onResult: (Long?) -> Unit) {
        val mgr = sensorManager
        val s = sensor
        if (mgr == null || s == null) {
            onResult(null)
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
                val raw = event.values.firstOrNull()?.toLong()
                mgr.unregisterListener(this)
                onResult(raw)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        mgr.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
    }
}
