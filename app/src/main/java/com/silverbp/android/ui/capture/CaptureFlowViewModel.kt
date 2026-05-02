package com.silverbp.android.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.capture.CaptureSessionHolder
import com.silverbp.android.core.Source
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.BpExtractionError
import com.silverbp.android.recognition.GemmaBpService
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.RecognizerFactory
import com.silverbp.android.ui.confirm.BpReadingDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "CaptureFlow"

sealed class CapturePhase {
    data object Idle : CapturePhase()
    data object Capturing : CapturePhase()
    data object Recognizing : CapturePhase()
    data class Error(val message: String) : CapturePhase()
}

class CaptureFlowViewModel(
    private val context: Context = ServiceLocator.context,
) : ViewModel() {

    private val _phase = MutableStateFlow<CapturePhase>(CapturePhase.Idle)
    val phase: StateFlow<CapturePhase> = _phase.asStateFlow()

    fun setCapturing() { _phase.value = CapturePhase.Capturing }

    /**
     * Process a captured image (camera output OR photo-picker selection).
     * On success: draft is placed in [CaptureSessionHolder] and [onSuccess] invoked.
     * On failure: phase is set to [CapturePhase.Error]; caller should
     * still allow user to switch to manual entry.
     */
    fun processCapturedImage(bitmap: Bitmap, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _phase.value = CapturePhase.Recognizing
            Log.i(
                TAG,
                "[Capture] recognize() entered, image ${bitmap.width}x${bitmap.height}",
            )
            try {
                val downsized = withContext(Dispatchers.Default) { downsample(bitmap, 1024) }
                Log.i(
                    TAG,
                    "[Capture] display=${downsized.width}x${downsized.height} " +
                        "model=${downsized.width}x${downsized.height}",
                )
                val recognizer = RecognizerFactory.current()
                Log.i(TAG, "[Capture] calling ${recognizer::class.simpleName}.extract")
                val draft = if (recognizer.isReady()) {
                    val r = recognizer.extract(downsized)
                    Log.i(TAG, "[Capture] extract returned: $r")
                    BpReadingDraft(
                        systolic = r.systolic ?: 0,
                        diastolic = r.diastolic ?: 0,
                        pulse = r.pulse,
                        timestamp = Instant.now(),
                        confidence = r.confidence ?: 0.8,
                        irregularHeartbeat = r.irregularHeartbeat ?: false,
                        photo = downsized,
                        source = Source.CameraGemma,
                    )
                } else {
                    // Recognizer not ready (no local model loaded / no API key set) —
                    // fall through to manual entry with the photo attached.
                    BpReadingDraft(
                        timestamp = Instant.now(),
                        photo = downsized,
                        source = Source.Manual,
                        confidence = 1.0,
                    )
                }
                CaptureSessionHolder.put(draft)
                _phase.value = CapturePhase.Idle
                Log.i(TAG, "[Capture] phase -> confirming")
                onSuccess()
            } catch (e: BpExtractionError.LowConfidence) {
                Log.i(TAG, "[Capture] lowConfidence ${e.confidence}")
                _phase.value = CapturePhase.Error("辨識信心度過低 (${(e.confidence * 100).toInt()}%),請重拍或手動輸入")
                // still allow downstream manual entry with photo attached
                CaptureSessionHolder.put(BpReadingDraft(timestamp = Instant.now(), photo = bitmap, source = Source.Manual))
            } catch (e: Exception) {
                Log.w(TAG, "[Capture] extract threw: $e")
                _phase.value = CapturePhase.Error("辨識失敗:${e.message ?: "未知錯誤"}")
                CaptureSessionHolder.put(BpReadingDraft(timestamp = Instant.now(), photo = bitmap, source = Source.Manual))
            }
        }
    }

    fun loadFromUri(uri: Uri, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                com.silverbp.android.recognition.decodeUriWithExif(context, uri)
            } ?: run {
                _phase.value = CapturePhase.Error("無法載入照片")
                return@launch
            }
            processCapturedImage(bmp, onSuccess)
        }
    }

    private fun downsample(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val scale = maxDim.toFloat() / maxSide
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }
}
