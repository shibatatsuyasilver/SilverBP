package com.silverbp.android.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.capture.WeightCaptureSessionHolder
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.BpExtractionError
import com.silverbp.android.recognition.ExtractedWeight
import com.silverbp.android.recognition.WeightRecognizerFactory
import com.silverbp.android.recognition.decodeUriWithExif
import com.silverbp.android.recognition.recognitionReadinessFlow
import com.silverbp.android.ui.confirm.WeightDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "WeightCapture"

/** Capture/analysis phase for the camera/gallery → confirm hand-off. */
sealed interface WeightCapturePhase {
    data object Idle : WeightCapturePhase
    data object Analyzing : WeightCapturePhase
    data class Error(val message: String) : WeightCapturePhase
}

/**
 * Drives the scale-display photo → OCR → confirm hand-off. Mirrors
 * [GlucoseCaptureViewModel]: save the photo first, run the on-device/cloud
 * recognizer, stage a [WeightDraft], then navigate. On any failure (or when the
 * model is not loaded, e.g. the emulator) it still stages a manual draft carrying
 * the photo so the confirm screen opens as a blank manual form — manual entry is
 * the always-available primary path.
 */
class WeightCaptureViewModel(
    private val appContext: Context = ServiceLocator.context,
) : ViewModel() {

    private val _capturePhase = MutableStateFlow<WeightCapturePhase>(WeightCapturePhase.Idle)
    val capturePhase: StateFlow<WeightCapturePhase> = _capturePhase.asStateFlow()

    /** Gate for the shutter / photo-pick: false when the Local backend has no model loaded. */
    val readiness = recognitionReadinessFlow(viewModelScope)

    fun resetCapture() { _capturePhase.value = WeightCapturePhase.Idle }

    /**
     * Drop any staged draft (and its orphaned photo) on the discard paths —
     * Retry, Cancel/Back, or a failed shutter — so a stale draft from an earlier
     * analysis is never consumed by ConfirmWeightViewModel and the JPEG under
     * filesDir/photos is removed.
     */
    fun discardPendingDraft() {
        WeightCaptureSessionHolder.take()?.photoFilename
            ?.let { deletePhoto(it) }
    }

    /** Analyse a camera preview bitmap, stage a draft, then [onReady] to navigate. */
    fun analyzeBitmap(bitmap: Bitmap, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = WeightCapturePhase.Analyzing
            val photoName = withContext(Dispatchers.IO) { runCatching { writePhoto(bitmap) }.getOrNull() }
            try {
                val recognizer = WeightRecognizerFactory.current()
                if (recognizer.isReady()) {
                    val extracted = recognizer.analyze(bitmap)
                    WeightCaptureSessionHolder.put(draftFrom(extracted, photoName))
                    _capturePhase.value = WeightCapturePhase.Idle
                    onReady()
                } else {
                    // Model not loaded / no API key (emulator) — open a blank manual
                    // form, keep the photo.
                    WeightCaptureSessionHolder.put(manualDraft(photoName))
                    _capturePhase.value = WeightCapturePhase.Idle
                    onReady()
                }
            } catch (e: BpExtractionError.NetworkError) {
                Log.w(TAG, "[Capture] weight network error")
                stageManualOnError(photoName, appContext.getString(R.string.capture_err_no_network))
            } catch (e: BpExtractionError.ApiError) {
                Log.w(TAG, "[Capture] weight api error ${e.code}")
                val msg = when (e.code) {
                    429 -> appContext.getString(R.string.capture_err_quota)
                    401, 403 -> appContext.getString(R.string.capture_err_invalid_key)
                    else -> appContext.getString(R.string.capture_err_cloud_http, e.code)
                }
                stageManualOnError(photoName, msg)
            } catch (e: BpExtractionError.LowConfidence) {
                Log.i(TAG, "[Capture] weight lowConfidence ${e.confidence}")
                stageManualOnError(
                    photoName,
                    appContext.getString(R.string.capture_err_low_confidence, (e.confidence * 100).toInt()),
                )
            } catch (e: BpExtractionError) {
                // InvalidJson / InvalidReading (out of range) / MissingFields /
                // ModelNotLoaded — the read was unusable; keep the photo and drop to
                // manual entry.
                Log.w(TAG, "[Capture] weight read unusable: ${e.message}")
                stageManualOnError(photoName, appContext.getString(R.string.weight_analyze_failed))
            } catch (t: Throwable) {
                Log.w(TAG, "[Capture] weight analysis failed: ${t.message}", t)
                stageManualOnError(
                    photoName,
                    appContext.getString(
                        R.string.capture_err_generic,
                        t.message ?: appContext.getString(R.string.err_unknown),
                    ),
                )
            }
        }
    }

    /**
     * Analysis failed — still stage a photo-carrying manual draft so the confirm
     * screen opens with the photo, and surface [message] (mapped to a specific
     * R.string.capture_err_*) on the capture overlay.
     */
    private fun stageManualOnError(photoName: String?, message: String) {
        WeightCaptureSessionHolder.put(manualDraft(photoName))
        _capturePhase.value = WeightCapturePhase.Error(message)
    }

    /** Decode a gallery Uri then run [analyzeBitmap]. */
    fun analyzeUri(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = WeightCapturePhase.Analyzing
            val bmp = withContext(Dispatchers.IO) { decodeUriWithExif(appContext, uri) }
            if (bmp == null) {
                _capturePhase.value =
                    WeightCapturePhase.Error(appContext.getString(R.string.capture_err_load_photo))
                return@launch
            }
            analyzeBitmap(bmp, onReady)
        }
    }

    // ---- draft mapping ----

    /**
     * A blank camera draft (manual values) that just carries the photo. The user
     * types the value by hand here, so confidence is a full 1.0 (human-verified,
     * not an OCR guess) and no low-confidence badge is shown downstream.
     */
    private fun manualDraft(photoName: String?): WeightDraft = WeightDraft(
        source = WeightSource.Camera,
        photoFilename = photoName,
        confidence = 1.0,
    )

    /**
     * Build an editable draft from the OCR result. The recognizer/parser already
     * inferred the unit and calibrated confidence; the field maps straight to the
     * editable draft, held in the unit the scale reported so the user sees exactly
     * what was on the display.
     */
    private fun draftFrom(e: ExtractedWeight, photoName: String?): WeightDraft {
        val unit = WeightUnit.fromRaw(e.unit ?: WeightUnit.Kg.raw)
        val value = e.value
        return WeightDraft(
            valueText = value?.let { WeightDraft.formatValue(it, unit) } ?: "",
            displayUnit = unit,
            source = WeightSource.Camera,
            // Carry the parser's ACTUAL confidence so a shaky read surfaces a
            // low-confidence badge on confirm; never claim a fake 1.0. A model that
            // omits the field falls back to 0.8 (mirrors the BP capture path).
            confidence = e.confidence ?: 0.8,
            photoFilename = photoName,
        )
    }

    private fun writePhoto(bitmap: Bitmap): String {
        val dir = File(appContext.filesDir, "photos").apply { mkdirs() }
        val name = "${UUID.randomUUID()}.jpg"
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    private fun deletePhoto(filename: String) {
        runCatching { File(File(appContext.filesDir, "photos"), filename).delete() }
    }
}
