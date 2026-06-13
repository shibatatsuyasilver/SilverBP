package com.silverbp.android.capture

import com.silverbp.android.ui.confirm.GlucoseDraft
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot holder for the current in-flight glucose capture draft — the
 * glucose analogue of [CaptureSessionHolder]. GlucoseCaptureScreen writes here
 * when OCR completes (or the user picks manual entry from the camera screen);
 * ConfirmGlucoseScreen reads here when its route arg is "draft".
 *
 * Application-scoped; [take] consumes the value (returns and clears) so a stale
 * draft never leaks across sessions. The OCR-staged draft only survives until the
 * confirm screen first consumes it — process-death survival of the *editable*
 * fields after that is the ViewModel's SavedStateHandle job (M5).
 */
object GlucoseCaptureSessionHolder {
    private val ref = AtomicReference<GlucoseDraft?>(null)

    fun put(draft: GlucoseDraft) { ref.set(draft) }
    fun take(): GlucoseDraft? = ref.getAndSet(null)
    fun peek(): GlucoseDraft? = ref.get()
    fun clear() { ref.set(null) }
}
