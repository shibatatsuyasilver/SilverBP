package com.silverbp.android.ui.exercise

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Holds the status of every permission needed to start an exercise session
 * and exposes a single `requestAll(onReady)` that walks through them in order.
 *
 * Order: ACCESS_FINE_LOCATION → POST_NOTIFICATIONS → ACTIVITY_RECOGNITION.
 *
 * Only fine location is required; the other two degrade gracefully:
 * - notifications denied → service still tracks, no ongoing notif
 * - activity recognition denied → step counter stays null
 */
class ExercisePermissionState internal constructor(
    private val context: Context,
    private val refresh: () -> Unit,
) {
    var hasFineLocation by mutableStateOf(check(Manifest.permission.ACCESS_FINE_LOCATION))
        private set
    var canPostNotifications by mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            check(Manifest.permission.POST_NOTIFICATIONS)
        else true
    )
        private set
    var hasActivityRecognition by mutableStateOf(check(Manifest.permission.ACTIVITY_RECOGNITION))
        private set

    /** True the first time the user denies [Manifest.permission.ACCESS_FINE_LOCATION]. */
    var locationDenied by mutableStateOf(false)
        internal set

    internal fun update() {
        hasFineLocation = check(Manifest.permission.ACCESS_FINE_LOCATION)
        canPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            check(Manifest.permission.POST_NOTIFICATIONS) else true
        hasActivityRecognition = check(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    private fun check(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    internal fun forceRefresh() = refresh()
}

/**
 * Compose-side wrapper that wires up the three sequential permission launchers
 * and returns a stable holder. Call [requestAll] from a Start button click; on
 * success the [onReady] callback fires once all required permissions are in.
 */
@Composable
fun rememberExercisePermissionState(): Pair<ExercisePermissionState, (onReady: () -> Unit) -> Unit> {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val state = remember(refreshTick) {
        ExercisePermissionState(context) { refreshTick++ }
    }

    var pendingOnReady by remember { mutableStateOf<(() -> Unit)?>(null) }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        state.update()
        pendingOnReady?.invoke()
        pendingOnReady = null
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        state.update()
        if (!state.hasActivityRecognition) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            pendingOnReady?.invoke()
            pendingOnReady = null
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        state.update()
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!fineGranted) {
            state.locationDenied = true
            pendingOnReady = null
            return@rememberLauncherForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.canPostNotifications) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return@rememberLauncherForActivityResult
        }
        if (!state.hasActivityRecognition) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return@rememberLauncherForActivityResult
        }
        pendingOnReady?.invoke()
        pendingOnReady = null
    }

    val request: (onReady: () -> Unit) -> Unit = remember {
        fn@{ onReady ->
            state.update()
            if (state.hasFineLocation) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !state.canPostNotifications
                ) {
                    pendingOnReady = onReady
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@fn
                }
                if (!state.hasActivityRecognition) {
                    pendingOnReady = onReady
                    activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    return@fn
                }
                onReady()
                return@fn
            }
            pendingOnReady = onReady
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    return state to request
}
