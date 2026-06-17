package com.silverbp.android.ui.exercise.machine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ExtractedMachineWorkout
import com.silverbp.android.recognition.MachineDisplayRecognizerFactory
import com.silverbp.android.recognition.decodeUriWithExif
import com.silverbp.android.recognition.recognitionReadinessFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Capture/analysis phase for the camera/gallery → confirm hand-off. */
sealed interface MachineCapturePhase {
    data object Idle : MachineCapturePhase
    data object Analyzing : MachineCapturePhase
    data class Error(val message: String) : MachineCapturePhase
}

/**
 * Drives the gym-machine console photo → OCR → confirm hand-off. Mirrors
 * [com.silverbp.android.ui.nutrition.NutritionViewModel]'s analyze flow: save
 * the photo first, run the on-device/cloud recognizer, stage a
 * [RecognizedMachineWorkout], then navigate. On any failure it still stages an
 * empty readout so the confirm screen opens as a manual form with the photo.
 */
class MachineCaptureViewModel(
    private val appContext: Context = ServiceLocator.context,
) : ViewModel() {

    private val _capturePhase = MutableStateFlow<MachineCapturePhase>(MachineCapturePhase.Idle)
    val capturePhase: StateFlow<MachineCapturePhase> = _capturePhase.asStateFlow()

    /** Gate for the shutter / photo-pick: false when the Local backend has no model loaded. */
    val readiness = recognitionReadinessFlow(viewModelScope)

    fun resetCapture() { _capturePhase.value = MachineCapturePhase.Idle }

    /**
     * Drop any staged draft (and its orphaned console photo) on the discard
     * paths — Retry, Cancel/Back, or a failed shutter — so a stale
     * [RecognizedMachineWorkout] from an earlier analysis is never consumed by
     * [MachineConfirmViewModel], and the JPEG under filesDir/photos is removed.
     */
    fun discardPendingDraft() {
        MachineWorkoutDraftHolder.take()?.photoFilename
            ?.let { MachinePhotoStore.delete(appContext, it) }
    }

    /** Analyse a camera preview bitmap, stage a draft, then [onReady] to navigate. */
    fun analyzeBitmap(bitmap: Bitmap, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = MachineCapturePhase.Analyzing
            val photoName = withContext(Dispatchers.IO) { runCatching { writePhoto(bitmap) }.getOrNull() }
            try {
                val recognizer = MachineDisplayRecognizerFactory.current()
                if (recognizer.isReady()) {
                    val downsized = withContext(Dispatchers.Default) { downsample(bitmap, MAX_DIM) }
                    val extracted = recognizer.analyze(downsized)
                    MachineWorkoutDraftHolder.put(
                        RecognizedMachineWorkout(photoName, extracted, recognizer.backendTag)
                    )
                    _capturePhase.value = MachineCapturePhase.Idle
                    onReady()
                } else {
                    // Model not loaded / no API key — open a blank manual form, keep the photo.
                    MachineWorkoutDraftHolder.put(
                        RecognizedMachineWorkout(photoName, ExtractedMachineWorkout(), "manual")
                    )
                    _capturePhase.value = MachineCapturePhase.Idle
                    onReady()
                }
            } catch (t: Throwable) {
                // Analysis failed — still let the user log it manually with the photo.
                MachineWorkoutDraftHolder.put(
                    RecognizedMachineWorkout(photoName, ExtractedMachineWorkout(), "manual")
                )
                _capturePhase.value = MachineCapturePhase.Error(t.message ?: "analysis failed")
            }
        }
    }

    /** Decode a gallery Uri then run [analyzeBitmap]. */
    fun analyzeUri(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = MachineCapturePhase.Analyzing
            val bmp = withContext(Dispatchers.IO) { decodeUriWithExif(appContext, uri) }
            if (bmp == null) {
                _capturePhase.value = MachineCapturePhase.Error("cannot load image")
                return@launch
            }
            analyzeBitmap(bmp, onReady)
        }
    }

    private fun writePhoto(bitmap: Bitmap): String {
        val dir = File(appContext.filesDir, "photos").apply { mkdirs() }
        val name = "${UUID.randomUUID()}.jpg"
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    private fun downsample(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val scale = maxDim.toFloat() / maxSide
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    private companion object {
        const val MAX_DIM = 1024
    }
}
