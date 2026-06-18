package com.silverbp.android.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.silverbp.android.R

fun hasModelDownloadNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberModelDownloadPermissionGate(
    onPermissionDenied: (() -> Unit)? = null,
): ((startDownload: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val defaultPermissionDenied = {
        Toast.makeText(
            context,
            context.getString(R.string.model_download_notification_permission_denied),
            Toast.LENGTH_LONG,
        ).show()
    }
    val currentPermissionDenied by rememberUpdatedState(onPermissionDenied ?: defaultPermissionDenied)
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) currentPermissionDenied()
        pendingStart?.invoke()
        pendingStart = null
    }

    return remember(context, permissionLauncher) {
        { startDownload ->
            if (hasModelDownloadNotificationPermission(context)) {
                startDownload()
            } else {
                pendingStart = startDownload
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
