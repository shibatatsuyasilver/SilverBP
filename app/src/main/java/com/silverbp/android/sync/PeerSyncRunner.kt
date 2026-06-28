package com.silverbp.android.sync

import android.content.Context
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.transport.NsdDiscovery
import com.silverbp.android.sync.transport.StreamFrameChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Runs one incremental sync round with an ALREADY-PAIRED peer, with no QR / SAS.
 *
 * Re-sync reuses the peer's long-term static key pinned at pairing time
 * ([SyncDeviceEntity.pubKey]) directly in the Noise XK handshake, so the round is
 * fully mutually authenticated — the SAS was only ever needed to bootstrap first
 * trust. Everything below the transport (LWW gate, orphan deferral, watermark
 * persistence) is the same tested machinery the pairing round uses, so a
 * background round can only converge data, never silently lose it.
 *
 * Rendezvous role is deterministic per pair to avoid both sides initiating or
 * both waiting: the lexicographically smaller [localDeviceId] browses + connects
 * (initiator); the larger advertises + accepts (responder).
 *
 * Best-effort: if the peer isn't reachable within [windowMs] this is a clean
 * no-op and the scheduler retries later.
 *
 * NOTE: the LAN rendezvous (NSD/Bonjour multicast) can only be validated on two
 * physical devices on the same Wi-Fi — emulators are on isolated virtual
 * networks and cannot discover or reach each other.
 */
class PeerSyncRunner(
    private val context: Context,
    private val localDeviceId: String,
) {
    suspend fun syncWithPeer(device: SyncDeviceEntity, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        val coordinator = ServiceLocator.syncCoordinator
        val db = ServiceLocator.database
        val syncDao = db.syncDao()
        val source = LanSyncAdapters.buildSource(db, coordinator.clock)
        val sink = LanSyncAdapters.buildSink(db)
        val getWatermark: suspend () -> Hlc = {
            syncDao.device(device.deviceId)?.lastHlcSeen?.let(::Hlc) ?: Hlc.ZERO
        }
        val setWatermark: suspend (Hlc) -> Unit = { hlc ->
            syncDao.touchDevice(device.deviceId, System.currentTimeMillis(), hlc.packed)
        }
        val nsd = NsdDiscovery(context)
        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(windowMs) {
                    if (localDeviceId < device.deviceId) {
                        // Initiator: find THIS peer on the LAN, connect, sync.
                        nsd.startBrowsing()
                        var peer = nsd.waitForPeer()
                        while (peer.deviceId != device.deviceId) peer = nsd.waitForPeer()
                        val socket = nsd.connect(peer)
                        val channel = StreamFrameChannel(socket.getInputStream(), socket.getOutputStream())
                        coordinator.runSessionAsInitiator(
                            device.pubKey, channel, source, sink, getWatermark, setWatermark,
                        )
                    } else {
                        // Responder: advertise + accept the initiator's connection.
                        nsd.startAdvertising(localDeviceId, fingerprint(coordinator.staticPublicKey))
                        val socket = nsd.acceptIncoming()
                        val channel = StreamFrameChannel(socket.getInputStream(), socket.getOutputStream())
                        coordinator.runSessionAsResponder(
                            device.pubKey, channel, source, sink, getWatermark, setWatermark,
                        )
                    }
                    true
                } ?: false
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Peer offline / handshake failed / socket reset — retry next round.
            false
        } finally {
            runCatching { nsd.stop() }
        }
    }

    private fun fingerprint(pub: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pub)
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val DEFAULT_WINDOW_MS = 25_000L
    }
}
