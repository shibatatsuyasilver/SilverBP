package com.silverbp.android.capture

import com.silverbp.android.ui.confirm.BpReadingDraft
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot holder for the current in-flight capture draft.
 * CaptureScreen writes here when recognition completes (or the user
 * picks manual entry from the camera screen). ConfirmReadingScreen
 * reads here when its route arg is "draft".
 *
 * This is application-scoped state; [take] consumes the value
 * (returns and clears) so a stale draft never leaks across sessions.
 */
object CaptureSessionHolder {
    private val ref = AtomicReference<BpReadingDraft?>(null)

    fun put(draft: BpReadingDraft) { ref.set(draft) }
    fun take(): BpReadingDraft? = ref.getAndSet(null)
    fun peek(): BpReadingDraft? = ref.get()
    fun clear() { ref.set(null) }
}
