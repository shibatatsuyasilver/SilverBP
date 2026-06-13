package com.silverbp.android.capture

import com.silverbp.android.ui.confirm.WeightDraft
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot holder for the current in-flight weight capture draft — the
 * weight analogue of [CaptureSessionHolder]. WeightCaptureScreen writes here
 * when OCR completes (or the user picks manual entry from the camera screen);
 * ConfirmWeightScreen reads here when its route arg is "draft".
 *
 * Application-scoped; [take] consumes the value (returns and clears) so a stale
 * draft never leaks across sessions. The OCR-staged draft only survives until the
 * confirm screen first consumes it — process-death survival of the *editable*
 * fields after that is the ViewModel's SavedStateHandle job (M5).
 */
object WeightCaptureSessionHolder {
    private val ref = AtomicReference<WeightDraft?>(null)

    fun put(draft: WeightDraft) { ref.set(draft) }
    fun take(): WeightDraft? = ref.getAndSet(null)
    fun peek(): WeightDraft? = ref.get()
    fun clear() { ref.set(null) }
}
