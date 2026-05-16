package com.silverbp.android.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-process lock state for the opt-in biometric gate.
 *
 * Re-lock policy (user decision): the app re-locks only when it returns to the
 * foreground after having been backgrounded for longer than the configured
 * timeout (default 60 s) — not on every foreground, which would be punishing
 * for the elderly target who log readings frequently.
 *
 * Lifecycle is observed once via [ProcessLifecycleOwner] so it spans the whole
 * process (Activity recreation, multi-window) rather than a single Activity.
 *
 * The gate is purely UI: it does **not** hold the DB/settings keys (those are
 * Keystore-wrapped and usable while locked so background coach/reminders/sync
 * keep working). See notes/biometric-app-lock-plan.md.
 */
class LockManager {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile private var enabled = false
    @Volatile private var timeoutMs = 60_000L
    @Volatile private var backgroundedAtMs = 0L
    @Volatile private var bootstrapped = false

    /** Call once at process start to begin observing foreground/background. */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAtMs = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (!enabled || backgroundedAtMs == 0L) return
                if (SystemClock.elapsedRealtime() - backgroundedAtMs >= timeoutMs) {
                    _locked.value = true
                }
            }
        })
    }

    /**
     * Push the latest settings. The very first call bootstraps the cold-start
     * state (locked iff the feature is enabled) so a fresh launch is gated;
     * later calls (user toggling the Settings switch while the app is open)
     * only update the parameters and never lock mid-use — locking then happens
     * on the next background→foreground transition.
     */
    fun bind(enabled: Boolean, timeoutSeconds: Int) {
        this.enabled = enabled
        this.timeoutMs = timeoutSeconds.coerceAtLeast(0) * 1000L
        if (!bootstrapped) {
            bootstrapped = true
            _locked.value = enabled
        } else if (!enabled) {
            _locked.value = false
        }
    }

    /** Biometric / device-credential auth succeeded. */
    fun onUnlocked() {
        backgroundedAtMs = 0L
        _locked.value = false
    }
}
