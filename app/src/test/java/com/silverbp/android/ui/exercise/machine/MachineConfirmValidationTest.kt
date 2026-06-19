package com.silverbp.android.ui.exercise.machine

import com.silverbp.android.recognition.MachineMetric
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineConfirmValidationTest {

    @Test
    fun blank_duration_and_metrics_are_invalid() {
        assertFalse(MachineConfirmUiState().isValid)
    }

    @Test
    fun duration_without_activity_metric_is_invalid() {
        val state = MachineConfirmUiState(durationMinutes = "20")

        assertFalse(state.isValid)
    }

    @Test
    fun positive_duration_and_distance_are_valid() {
        val state = MachineConfirmUiState(
            durationMinutes = "20",
            distanceValue = "2.5",
        )

        assertTrue(state.isValid)
    }

    @Test
    fun positive_duration_and_calories_are_valid() {
        val state = MachineConfirmUiState(
            durationSeconds = "45",
            calories = "10",
        )

        assertTrue(state.isValid)
    }

    @Test
    fun heart_rate_alone_does_not_make_workout_valid() {
        val state = MachineConfirmUiState(
            durationMinutes = "20",
            heartRate = "120",
            metrics = listOf(MachineMetric(label = "HR", value = "120", unit = "bpm")),
        )

        assertFalse(state.isValid)
    }

    @Test
    fun positive_non_hr_machine_metric_is_valid() {
        val state = MachineConfirmUiState(
            durationMinutes = "20",
            metrics = listOf(MachineMetric(label = "WATTS", value = "85", unit = "W")),
        )

        assertTrue(state.isValid)
    }
}
