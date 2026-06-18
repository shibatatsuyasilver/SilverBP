package com.silverbp.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.security.DbCipherMigration
import com.silverbp.android.ui.lock.LockScreen
import com.silverbp.android.ui.lock.canDeviceAuthenticate
import com.silverbp.android.ui.nav.AppNavHost
import com.silverbp.android.ui.paywall.PaywallHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SilverBpApp() {
    val lockManager = ServiceLocator.lockManager
    val settings by ServiceLocator.userSettings.flow.collectAsStateWithLifecycle(initialValue = null)
    val locked by lockManager.locked.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val s = settings
    val canUseAppLock = s?.appLockEnabled != true || canDeviceAuthenticate(context)

    // Keep LockManager driven by settings (cold-start gating happens on the
    // first non-null emission inside LockManager.bind).
    LaunchedEffect(s?.appLockEnabled, s?.appLockTimeoutSeconds) {
        if (s != null) {
            if (s.appLockEnabled && !canDeviceAuthenticate(context)) {
                lockManager.bind(enabled = false, timeoutSeconds = s.appLockTimeoutSeconds)
                val decrypted = withContext(Dispatchers.IO) {
                    DbCipherMigration.decrypt(context, ServiceLocator.dbKeyStore)
                } is DbCipherMigration.Outcome.Success
                if (decrypted) {
                    ServiceLocator.userSettings.migrateSensitiveFields()
                    ServiceLocator.userSettings.setAppLockEnabled(false)
                }
            } else {
                lockManager.bind(s.appLockEnabled, s.appLockTimeoutSeconds)
            }
        }
    }

    // FLAG_SECURE whenever app-lock is on — blocks the recents thumbnail and
    // screenshots for the whole app, not just the lock screen.
    DisposableEffect(canUseAppLock, s?.appLockEnabled) {
        val window = context.findActivity()?.window
        if (window != null) {
            if (canUseAppLock && s?.appLockEnabled == true) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {}
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Until settings resolve we render nothing — avoids a one-frame flash
        // of real content before we know whether the app should be locked.
        if (s != null) {
            Box(Modifier.fillMaxSize()) {
                // PaywallHost wraps the whole nav graph so any gated screen can
                // surface the subscription sheet via LocalPaywallController. The
                // lock screen is a later sibling in this Box so it always draws
                // OVER the nav host (and the paywall sheet) when the app is locked.
                PaywallHost {
                    AppNavHost()
                }
                if (s.appLockEnabled && canUseAppLock && locked) {
                    LockScreen(onUnlocked = { lockManager.onUnlocked() })
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
