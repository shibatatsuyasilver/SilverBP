package com.silverbp.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silverbp.android.BuildConfig
import com.silverbp.android.di.ServiceLocator

/**
 * Periodic background worker that attempts one incremental sync round with each
 * paired device via [PeerSyncRunner]. No paired devices → immediate success
 * (nothing to do). Each peer is attempted independently so one offline peer
 * never blocks another. Always returns success: a missed rendezvous is normal
 * and handled by the next periodic run, not a failure worth retrying/notifying.
 *
 * See [PeerSyncRunner] for why the actual LAN rendezvous needs two physical
 * devices to validate.
 */
class LanSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val devices = ServiceLocator.database.syncDao().allDevices()
        if (devices.isEmpty()) return Result.success()

        val runner = PeerSyncRunner(applicationContext, ServiceLocator.syncDeviceId)
        for (device in devices) {
            runCatching { runner.syncWithPeer(device) }
                .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "sync attempt failed", it) }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "silverbp.lan_sync"
        private const val TAG = "LanSyncWorker"
    }
}
