package com.silverbp.android.ui.exercise.machine

import android.content.Context
import com.silverbp.android.recognition.ExtractedMachineWorkout
import java.io.File

/**
 * A freshly OCR'd gym-machine console readout: the saved photo, the extracted
 * numbers, and which backend read them. Handed to [MachineConfirmScreen] which
 * lets the user confirm/edit the values before saving an ExerciseSession.
 *
 * On a failed / not-ready analysis the capture step still stages one of these
 * with an empty [extracted] so the confirm screen opens as a blank manual form
 * (keeping the photo). Mirrors the nutrition [RecognizedMeal] hand-off.
 */
data class RecognizedMachineWorkout(
    val photoFilename: String?,
    val extracted: ExtractedMachineWorkout,
    val backendTag: String,
)

/**
 * In-memory hand-off from the capture step to [MachineConfirmScreen] — avoids
 * serialising the draft through nav arguments. Mirrors [com.silverbp.android.ui
 * .nutrition.NutritionDraftHolder].
 */
object MachineWorkoutDraftHolder {
    @Volatile private var pending: RecognizedMachineWorkout? = null

    fun put(w: RecognizedMachineWorkout) { pending = w }

    /** Consume the pending readout (cleared after read). */
    fun take(): RecognizedMachineWorkout? = pending.also { pending = null }
}

/**
 * The console-photo JPEGs written under filesDir/photos during analysis. Since
 * exercise_session has no photo column, these are only ever transient: deleted
 * when the draft is discarded (Cancel/Retry) or after a successful save.
 */
object MachinePhotoStore {
    fun delete(context: Context, filename: String) {
        runCatching { File(File(context.filesDir, "photos"), filename).delete() }
    }
}
