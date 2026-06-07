package com.silverbp.android.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed output of the gym-machine console-display OCR model — the numbers shown
 * on a treadmill / indoor-bike / elliptical / rower / stair-climber console.
 *
 * The model reads what is on screen; it does NOT judge reliability. Calories and
 * heart rate are captured as-shown but the app treats them as estimates (machine
 * calories are systematically high; contact HR is unreliable / often blank), so
 * [heartRate] is nullable and must be left null — never zero — when the console
 * shows blank / dashes. Distance is meaningless without [distanceUnit]; the model
 * must read the on-screen unit label rather than assume km vs mi vs m vs floors.
 */
@Serializable
data class ExtractedMachineWorkout(
    /** "treadmill"|"indoor_bike"|"elliptical"|"rower"|"stair_climber"|"unknown". */
    @SerialName("machine_type") val machineType: String? = null,
    /** Raw elapsed-time clock as shown, e.g. "32:15" or "1:05:30". Parsed in Kotlin. */
    @SerialName("time_text") val timeText: String? = null,
    @SerialName("distance_value") val distanceValue: Double? = null,
    /** On-screen unit: "km"|"mi"|"m"|"floors"|"steps". Read the label, do not assume. */
    @SerialName("distance_unit") val distanceUnit: String? = null,
    /** Cumulative calories (kcal) — NOT the CAL/HR rate window. */
    val calories: Double? = null,
    /** BPM, or null when the console shows blank / dashes / no contact. Never 0-as-real. */
    @SerialName("heart_rate") val heartRate: Int? = null,
    /** Every other number visible on the console, for transparency / future use. */
    val metrics: List<MachineMetric> = emptyList(),
    /** Overall read confidence 0–1. */
    val confidence: Double? = null,
)

@Serializable
data class MachineMetric(
    /** On-screen label as read, e.g. "SPEED", "INCLINE", "WATTS", "SPM", "LEVEL". */
    val label: String = "",
    /** The value as shown (string so "5.20", "8:30/km", "---" all round-trip). */
    val value: String = "",
    val unit: String? = null,
    val confidence: Double? = null,
)
