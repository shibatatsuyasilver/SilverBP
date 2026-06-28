package com.silverbp.android.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.R
import com.silverbp.android.capture.GlucoseCaptureSessionHolder
import com.silverbp.android.core.GlucoseSource
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.BpExtractionError
import com.silverbp.android.recognition.ExtractedGlucose
import com.silverbp.android.recognition.GlucoseRecognizerFactory
import com.silverbp.android.recognition.decodeUriWithExif
import com.silverbp.android.recognition.recognitionReadinessFlow
import com.silverbp.android.ui.confirm.GlucoseDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

private const val TAG = "GlucoseCapture"

/**
 * Confidence stamped on a draft whose unit the parser had to INFER (#16). Kept
 * strictly below ConfirmGlucoseViewModel's unit-confirmation threshold (0.7) so the
 * confirm screen always gates an inferred unit behind an explicit confirmation.
 */
private const val UNIT_INFERRED_CONFIDENCE = 0.65

/** Capture/analysis phase for the camera/gallery → confirm hand-off. */
sealed interface GlucoseCapturePhase {
    data object Idle : GlucoseCapturePhase
    data object Analyzing : GlucoseCapturePhase
    data class Error(val message: String) : GlucoseCapturePhase
}

/**
 * Drives the glucose-meter photo → OCR → confirm hand-off. Mirrors
 * [com.silverbp.android.ui.exercise.machine.MachineCaptureViewModel]: save the
 * photo first, run the on-device/cloud recognizer, stage a [GlucoseDraft], then
 * navigate. On any failure (or when the model is not loaded, e.g. the emulator)
 * it still stages a manual draft carrying the photo so the confirm screen opens
 * as a blank manual form — manual entry is the always-available primary path.
 */
class GlucoseCaptureViewModel(
    private val appContext: Context = ServiceLocator.context,
) : ViewModel() {

    private val _capturePhase = MutableStateFlow<GlucoseCapturePhase>(GlucoseCapturePhase.Idle)
    val capturePhase: StateFlow<GlucoseCapturePhase> = _capturePhase.asStateFlow()

    /** Gate for the shutter / photo-pick: false when the Local backend has no model loaded. */
    val readiness = recognitionReadinessFlow(viewModelScope)

    fun resetCapture() { _capturePhase.value = GlucoseCapturePhase.Idle }

    /**
     * Surface a specific error message while still staging a manual draft (carrying
     * the photo) so manual entry stays the always-available primary path. Shared by
     * every catch arm in [analyzeBitmap] (#22).
     */
    private fun failToManual(photoName: String?, message: String) {
        GlucoseCaptureSessionHolder.put(manualDraft(photoName))
        _capturePhase.value = GlucoseCapturePhase.Error(message)
    }

    /**
     * Drop any staged draft (and its orphaned photo) on the discard paths —
     * Retry, Cancel/Back, or a failed shutter — so a stale draft from an earlier
     * analysis is never consumed by ConfirmGlucoseViewModel and the JPEG under
     * filesDir/photos is removed.
     */
    fun discardPendingDraft() {
        GlucoseCaptureSessionHolder.take()?.photoFilename
            ?.let { deletePhoto(it) }
    }

    /** Analyse a camera preview bitmap, stage a draft, then [onReady] to navigate. */
    fun analyzeBitmap(bitmap: Bitmap, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = GlucoseCapturePhase.Analyzing
            val photoName = withContext(Dispatchers.IO) { runCatching { writePhoto(bitmap) }.getOrNull() }
            try {
                val recognizer = GlucoseRecognizerFactory.current()
                if (recognizer.isReady()) {
                    val extracted = recognizer.extract(bitmap)
                    GlucoseCaptureSessionHolder.put(draftFrom(extracted, photoName))
                    _capturePhase.value = GlucoseCapturePhase.Idle
                    onReady()
                } else {
                    // Model not loaded / no API key (emulator) — open a blank manual
                    // form, keep the photo.
                    GlucoseCaptureSessionHolder.put(manualDraft(photoName))
                    _capturePhase.value = GlucoseCapturePhase.Idle
                    onReady()
                }
            } catch (e: BpExtractionError.InvalidReading) {
                // #22: out-of-range / implausible value — mirror BP's specific copy.
                Log.i(TAG, "[Capture] glucose invalidReading ${e.reason}")
                failToManual(photoName, appContext.getString(R.string.capture_err_invalid_reading))
            } catch (e: BpExtractionError.LowConfidence) {
                Log.i(TAG, "[Capture] glucose lowConfidence ${e.confidence}")
                failToManual(
                    photoName,
                    appContext.getString(R.string.capture_err_low_confidence, (e.confidence * 100).toInt()),
                )
            } catch (e: BpExtractionError.NetworkError) {
                Log.w(TAG, "[Capture] glucose network error")
                failToManual(photoName, appContext.getString(R.string.capture_err_no_network))
            } catch (e: BpExtractionError.ApiError) {
                Log.w(TAG, "[Capture] glucose api error ${e.code}")
                val msg = when (e.code) {
                    429 -> appContext.getString(R.string.capture_err_quota)
                    401, 403 -> appContext.getString(R.string.capture_err_invalid_key)
                    else -> appContext.getString(R.string.capture_err_cloud_http, e.code)
                }
                failToManual(photoName, msg)
            } catch (t: Throwable) {
                // InvalidJson / MissingFields / anything else → generic, like BP's VM.
                Log.w(TAG, "[Capture] glucose analysis failed: ${t.message}", t)
                failToManual(
                    photoName,
                    appContext.getString(
                        R.string.capture_err_generic,
                        t.message ?: appContext.getString(R.string.err_unknown),
                    ),
                )
            }
        }
    }

    /** Decode a gallery Uri then run [analyzeBitmap]. */
    fun analyzeUri(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            _capturePhase.value = GlucoseCapturePhase.Analyzing
            val bmp = withContext(Dispatchers.IO) { decodeUriWithExif(appContext, uri) }
            if (bmp == null) {
                // #10: this message is now rendered, so localize it like BP's loadFromUri.
                _capturePhase.value =
                    GlucoseCapturePhase.Error(appContext.getString(R.string.capture_err_load_photo))
                return@launch
            }
            analyzeBitmap(bmp, onReady)
        }
    }

    // ---- draft mapping ----

    /** A blank camera draft (manual values) that just carries the photo. */
    private fun manualDraft(photoName: String?): GlucoseDraft = GlucoseDraft(
        source = GlucoseSource.Camera,
        photoFilename = photoName,
        confidence = 1.0,
    )

    /**
     * Build an editable draft from the OCR result. The recognizer/parser already
     * inferred the unit and calibrated confidence (unit/range cross-check); the
     * field names match the domain enums' raw strings so mapping is direct.
     *
     * #16: [GlucoseDraft] only carries [GlucoseDraft.confidence] (it can't hold a
     * separate flag), so when the parser had to INFER the unit we fold that into a
     * sub-threshold confidence — the confirm screen's single confidence gate then
     * makes the user explicitly confirm mg/dL vs mmol/L before saving.
     */
    private fun draftFrom(e: ExtractedGlucose, photoName: String?): GlucoseDraft {
        val unit = GlucoseUnit.fromRaw(e.unit ?: GlucoseUnit.Mgdl.raw)
        val value = e.value
        val confidence = (e.confidence ?: 1.0)
            .let { if (e.unitInferred) minOf(it, UNIT_INFERRED_CONFIDENCE) else it }
        return GlucoseDraft(
            valueText = value?.let { GlucoseDraft.formatValue(it, unit) } ?: "",
            displayUnit = unit,
            // Meter rarely tags a context; null → user picks (default Fasting).
            measureContext = e.measureContext?.let { MeasureContext.fromRaw(it) }
                ?: MeasureContext.Fasting,
            timestamp = parseDeviceTimestamp(e.timestampOnDevice) ?: Instant.now(),
            source = GlucoseSource.Camera,
            confidence = confidence,
            photoFilename = photoName,
        )
    }

    /** Parse the meter's "YYYY-MM-DDTHH:mm" stamp to an Instant (local zone); null on any failure. */
    private fun parseDeviceTimestamp(raw: String?): Instant? {
        val t = raw?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            java.time.LocalDateTime.parse(t)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
        }.recoverCatching {
            if (it is DateTimeParseException) null else throw it
        }.getOrNull()
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
