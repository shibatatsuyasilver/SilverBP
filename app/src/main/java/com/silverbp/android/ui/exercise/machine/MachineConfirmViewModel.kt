package com.silverbp.android.ui.exercise.machine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseRepository
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.exercise.ExerciseSource
import com.silverbp.android.recognition.ExtractedMachineWorkout
import com.silverbp.android.recognition.MachineMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant

/** Distance units a machine console can show; drives storage + the unit picker. */
enum class DistanceUnit(val raw: String) {
    Km("km"), Mi("mi"), M("m"), Floors("floors"), Steps("steps");

    companion object {
        fun fromRaw(s: String?): DistanceUnit? = entries.firstOrNull { it.raw == s }
    }
}

/** Editable confirm-form state, pre-filled from the OCR'd console readout. */
data class MachineConfirmUiState(
    val kind: ActivityKind = ActivityKind.Treadmill,
    val durationMinutes: String = "",
    val durationSeconds: String = "",
    val distanceValue: String = "",
    val distanceUnit: DistanceUnit = DistanceUnit.Km,
    val calories: String = "",
    val heartRate: String = "",
    val note: String = "",
    val photoFilename: String? = null,
    val backendTag: String = "manual",
    val overallConfidence: Double? = null,
    val metrics: List<MachineMetric> = emptyList(),
)

/**
 * Backs [MachineConfirmScreen]: takes the staged [RecognizedMachineWorkout],
 * pre-fills an editable form, and on save builds an [ExerciseSession]
 * (source = Ocr). Calories + heart rate are stored flagged as estimates; the
 * raw OCR JSON is kept for transparency. Mirrors NutritionConfirmViewModel.
 */
class MachineConfirmViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MachineConfirmUiState())
    val state: StateFlow<MachineConfirmUiState> = _state.asStateFlow()

    /** Raw OCR JSON kept verbatim for the saved row (transparency / future use). */
    private var rawMetricsJson: String? = null
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        val w = MachineWorkoutDraftHolder.take()
        if (w == null) return
        rawMetricsJson = runCatching { json.encodeToString(ExtractedMachineWorkout.serializer(), w.extracted) }.getOrNull()
        _state.value = buildInitial(w)
    }

    fun update(transform: (MachineConfirmUiState) -> MachineConfirmUiState) {
        _state.value = transform(_state.value)
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            repo.upsert(s.toSession(rawMetricsJson), points = emptyList())
            onSaved()
        }
    }

    // ---- pre-fill ----

    private fun buildInitial(w: RecognizedMachineWorkout): MachineConfirmUiState {
        val e = w.extracted
        val kind = kindFromRaw(e.machineType)
        val totalSec = parseClockToSeconds(e.timeText)
        val unit = DistanceUnit.fromRaw(e.distanceUnit) ?: defaultUnitFor(kind)
        return MachineConfirmUiState(
            kind = kind,
            durationMinutes = if (totalSec != null) (totalSec / 60).toString() else "",
            durationSeconds = if (totalSec != null) (totalSec % 60).toString() else "",
            distanceValue = e.distanceValue?.let { trimNumber(it) } ?: "",
            distanceUnit = unit,
            calories = e.calories?.let { trimNumber(it) } ?: "",
            heartRate = e.heartRate?.toString() ?: "",
            note = "",
            photoFilename = w.photoFilename,
            backendTag = w.backendTag,
            overallConfidence = e.confidence,
            metrics = e.metrics,
        )
    }

    private fun MachineConfirmUiState.toSession(rawJson: String?): ExerciseSession {
        val mins = durationMinutes.toLongOrNull() ?: 0L
        val secs = durationSeconds.toLongOrNull() ?: 0L
        val durationMs = ((mins * 60 + secs) * 1000L).coerceAtLeast(0L)
        val value = distanceValue.replace(",", ".").toDoubleOrNull()

        var distanceMeters = 0.0
        var floorsVal: Int? = null
        var stepsVal: Int? = null
        when (distanceUnit) {
            DistanceUnit.Km -> distanceMeters = (value ?: 0.0) * 1000.0
            DistanceUnit.Mi -> distanceMeters = (value ?: 0.0) * 1609.344
            DistanceUnit.M -> distanceMeters = value ?: 0.0
            DistanceUnit.Floors -> floorsVal = value?.toInt()
            DistanceUnit.Steps -> stepsVal = value?.toInt()
        }
        val pace = if (distanceMeters > 0 && durationMs > 0) {
            (durationMs / 1000.0) / (distanceMeters / 1000.0)
        } else {
            null
        }
        val now = Instant.now()
        return ExerciseSession(
            kind = kind,
            startedAt = now.minusMillis(durationMs),
            endedAt = now,
            activeDurationMillis = durationMs,
            distanceMeters = distanceMeters,
            stepCount = stepsVal,
            averagePaceSecPerKm = pace,
            source = ExerciseSource.Ocr,
            note = note.trim(),
            caloriesKcal = calories.toDoubleOrNull(),
            heartRateBpm = heartRate.toIntOrNull(),
            caloriesIsEstimate = true,
            heartRateIsEstimate = true,
            distanceUnitRaw = distanceUnit.raw,
            floors = floorsVal,
            rawMetricsJson = rawJson,
        )
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun kindFromRaw(s: String?): ActivityKind = when (s) {
            "treadmill" -> ActivityKind.Treadmill
            "indoor_bike" -> ActivityKind.IndoorBike
            "elliptical" -> ActivityKind.Elliptical
            "rower" -> ActivityKind.Rower
            "stair_climber" -> ActivityKind.StairClimber
            else -> ActivityKind.Treadmill
        }

        fun defaultUnitFor(kind: ActivityKind): DistanceUnit = when (kind) {
            ActivityKind.Rower -> DistanceUnit.M
            ActivityKind.StairClimber -> DistanceUnit.Floors
            else -> DistanceUnit.Km
        }

        /** Parse "mm:ss" or "h:mm:ss" into total seconds; null if unparseable. */
        fun parseClockToSeconds(text: String?): Int? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parts = t.split(":").map { it.trim().toIntOrNull() ?: return null }
            return when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                1 -> parts[0] // bare minutes
                else -> null
            }
        }

        /** Drop a trailing ".0" so 5.0 shows as "5" but 5.2 stays "5.2". */
        fun trimNumber(d: Double): String =
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
}
