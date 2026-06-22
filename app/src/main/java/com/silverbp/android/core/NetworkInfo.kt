package com.silverbp.android.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Lightweight connectivity probe — used to gate multi-GB model downloads to Wi-Fi. */
object NetworkInfo {

    /** True when the active network is Wi-Fi (so a large download won't burn mobile data). */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * True when the active network is metered (mobile data, or a metered Wi-Fi
     * hotspot) — the precise complement of the WorkManager
     * [androidx.work.NetworkType.UNMETERED] download constraint. When this is
     * true, a download enqueued WiFi-only will sit and wait, so the UI gates a
     * cellular download behind a user opt-in + confirmation. Defaults to true
     * (conservative: assume metered) when connectivity can't be read.
     */
    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return cm.isActiveNetworkMetered
    }
}
