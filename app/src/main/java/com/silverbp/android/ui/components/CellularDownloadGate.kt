package com.silverbp.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.core.NetworkInfo

/** A pending confirm dialog request held while the user decides. */
private class CellularConfirmRequest(
    val sizeLabel: String,
    val onProceed: (allowMetered: Boolean) -> Unit,
    val onBlocked: () -> Unit,
)

/**
 * Gate that decides whether a multi-GB model download may proceed on the active
 * network, mirroring [rememberModelDownloadPermissionGate] in spirit.
 *
 * Call [request] from a click handler:
 *  - Unmetered network (Wi-Fi / ethernet) → runs `onProceed(false)` immediately.
 *  - Metered network + cellular allowed → shows a confirmation carrying the
 *    download size; confirm → `onProceed(true)`, cancel → `onBlocked()`.
 *  - Metered network + cellular not allowed → shows a "Wi-Fi only" notice and
 *    runs `onBlocked()` on dismiss; never downloads over mobile data.
 */
fun interface CellularDownloadGate {
    fun request(
        approxSizeLabel: String,
        onProceed: (allowMetered: Boolean) -> Unit,
        onBlocked: () -> Unit,
    )
}

/**
 * Remembers a [CellularDownloadGate] and renders its confirm / Wi-Fi-only
 * dialogs into the current composition. [allowCellular] is the user's
 * "allow download over mobile data" setting.
 */
@Composable
fun rememberCellularDownloadGate(allowCellular: Boolean): CellularDownloadGate {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<CellularConfirmRequest?>(null) }
    var wifiOnlyBlocked by remember { mutableStateOf<(() -> Unit)?>(null) }

    confirm?.let { req ->
        AlertDialog(
            onDismissRequest = { confirm = null; req.onBlocked() },
            title = { Text(stringResource(R.string.model_download_cellular_confirm_title)) },
            text = {
                Text(stringResource(R.string.model_download_cellular_confirm_body, req.sizeLabel))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    req.onProceed(true)
                }) { Text(stringResource(R.string.model_download_cellular_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirm = null
                    req.onBlocked()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    wifiOnlyBlocked?.let { onBlocked ->
        AlertDialog(
            onDismissRequest = { wifiOnlyBlocked = null; onBlocked() },
            title = { Text(stringResource(R.string.model_download_wifi_only_title)) },
            text = { Text(stringResource(R.string.model_download_wifi_only_body)) },
            confirmButton = {
                TextButton(onClick = {
                    wifiOnlyBlocked = null
                    onBlocked()
                }) { Text(stringResource(R.string.model_download_wifi_only_dismiss)) }
            },
        )
    }

    return CellularDownloadGate { sizeLabel, onProceed, onBlocked ->
        when {
            !NetworkInfo.isMetered(context) -> onProceed(false)
            allowCellular -> confirm = CellularConfirmRequest(sizeLabel, onProceed, onBlocked)
            else -> wifiOnlyBlocked = onBlocked
        }
    }
}

/** Human-readable approximate size for a download, e.g. "2.0 GB". */
fun approxSizeLabel(approxSizeGB: Double): String = "%.1f GB".format(approxSizeGB)
