package com.silverbp.android.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient

/**
 * Health Connect availability helpers.
 *
 * Every permission-request launch site must go through [launchHealthConnectOrInstall]:
 * `PermissionController.createRequestPermissionResultContract()` builds an intent that
 * targets the Health Connect provider. On a device where HC is absent or out of date
 * (common on Android 13 / minSdk 33 without the standalone HC app), calling `launch()`
 * directly throws `ActivityNotFoundException` and crashes the app. Guarding on
 * [HealthConnectClient.getSdkStatus] — and wrapping `launch` in `runCatching` as a
 * belt-and-suspenders — turns that crash into a graceful "install Health Connect" detour.
 */
object HealthConnectAvailability {

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Opens the Play listing for the Health Connect provider so the user can install/update it. */
    fun openProviderInstall(context: Context) {
        val pkg = "com.google.android.apps.healthdata"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

/**
 * Launch the Health Connect permission sheet only when the provider is available;
 * otherwise send the user to install/update it instead of crashing. Use this in place
 * of a bare `launcher.launch(perms)` at every HC permission-request site.
 */
fun ManagedActivityResultLauncher<Set<String>, Set<String>>.launchHealthConnectOrInstall(
    context: Context,
    perms: Set<String>,
) {
    if (HealthConnectAvailability.isAvailable(context)) {
        runCatching { launch(perms) }.onFailure { HealthConnectAvailability.openProviderInstall(context) }
    } else {
        HealthConnectAvailability.openProviderInstall(context)
    }
}
