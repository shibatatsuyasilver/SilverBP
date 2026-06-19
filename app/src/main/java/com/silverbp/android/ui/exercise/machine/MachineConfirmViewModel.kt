package com.silverbp.android.ui.exercise.machine

import android.content.Context
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

/**
 * Parse a console time readout into total seconds; null if unparseable.
 * "mm:ss" and "h:mm:ss" are split on ':'; a single bare segment is read as
 * whole minutes (consoles show e.g. "32" for 32:00), so it is scaled to
 * seconds.
 */
internal fun parseClockToSeconds(text: String?): Int? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = t.split(":").map { it.trim().toIntOrNull() ?: return null }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        1 -> parts[0] * 60 // bare minutes
        else -> null
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
) {
    val isValid: Boolean
        get() = hasPositiveDuration(durationMinutes, durationSeconds) &&
            hasMeaningfulActivityMetric(distanceValue, calories, metrics)
}

internal fun hasPositiveDuration(minutes: String, seconds: String): Boolean {
    val mins = minutes.toLongOrNull() ?: 0L
    val secs = seconds.toLongOrNull() ?: 0L
    return mins * 60L + secs > 0L
}

internal fun hasMeaningfulActivityMetric(
    distanceValue: String,
    calories: String,
    metrics: List<MachineMetric>,
): Boolean {
    if (firstPositiveNumber(distanceValue) != null) return true
    if (firstPositiveNumber(calories) != null) return true
    return metrics.any { it.isMeaningfulActivityMetric() }
}

/**
 * Backs [MachineConfirmScreen]: takes the staged [RecognizedMachineWorkout],
 * pre-fills an editable form, and on save builds an [ExerciseSession]
 * (source = Ocr). Calories + heart rate are stored flagged as estimates; the
 * raw OCR JSON is kept for transparency. Mirrors NutritionConfirmViewModel.
 */
class MachineConfirmViewModel(
    private val repo: ExerciseRepository = ServiceLocator.exerciseRepository,
    private val appContext: Context = ServiceLocator.context,
) : ViewModel() {

    private val _state = MutableStateFlow(MachineConfirmUiState())
    val state: StateFlow<MachineConfirmUiState> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

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

    /** Cancel without saving: drop the orphaned console photo (no photo column). */
    fun discard(onDiscarded: () -> Unit) {
        _state.value.photoFilename?.let { MachinePhotoStore.delete(appContext, it) }
        onDiscarded()
    }

    fun save(onSaved: () -> Unit) {
        if (_saving.value) return
        val s = _state.value
        if (!s.isValid) return
        _saving.value = true
        viewModelScope.launch {
            repo.upsert(s.toSession(rawMetricsJson), points = emptyList())
            // exercise_session has no photo column — the JPEG serves no purpose post-save.
            s.photoFilename?.let { MachinePhotoStore.delete(appContext, it) }
            _saving.value = false
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

        /** Drop a trailing ".0" so 5.0 shows as "5" but 5.2 stays "5.2". */
        fun trimNumber(d: Double): String =
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }
}

private fun MachineMetric.isMeaningfulActivityMetric(): Boolean {
    val labelText = label.trim().lowercase()
    val unitText = unit.orEmpty().trim().lowercase()
    if (labelText.isBlank() && value.isBlank()) return false
    if (labelText.contains("heart") || labelText.contains("pulse") ||
        labelText == "hr" || labelText.contains("bpm") || unitText == "bpm"
    ) {
        return false
    }
    if (labelText.contains("time") || labelText.contains("duration")) return false
    return firstPositiveNumber(value) != null
}

private val numberRegex = Regex("""[-+]?\d+(?:\.\d+)?""")

private fun firstPositiveNumber(raw: String?): Double? {
    val normalized = raw?.trim()?.replace(",", ".").orEmpty()
    if (normalized.isBlank()) return null
    return numberRegex.find(normalized)
        ?.value
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
}
