package com.silverbp.android.ui.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.BuildConfig
import com.silverbp.android.R
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.sync.PeerSyncRunner
import com.silverbp.android.sync.CombinedRoomSyncSink
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.CombinedRoomSyncSource
import com.silverbp.android.sync.SyncCoordinator
import com.silverbp.android.sync.pairing.PairingKeyStore
import com.silverbp.android.sync.pairing.PairingService
import com.silverbp.android.sync.pairing.QrPairingPayload
import com.silverbp.android.sync.transport.NsdDiscovery
import com.silverbp.android.sync.transport.StreamFrameChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Drives the pairing flow as a state machine. UI observes [state] and
 * dispatches user intents via [onShowQrTapped] / [onScanned] / [onConfirmSas].
 *
 * Mirrors iOS `SyncPairingSheet` behaviour: pick a role → run Noise XK
 * over LAN → display 6-digit SAS → wait for visual confirmation → persist.
 */
class PairingViewModel(
    private val context: Context,
    private val coordinator: SyncCoordinator,
    private val pairingService: PairingService,
    private val pairingKeyStore: PairingKeyStore,
    private val localDeviceId: String,
) : ViewModel() {

    sealed interface State {
        data object Picker : State
        data class ShowingQr(
            val qrUrl: String,
            val statusText: String,
        ) : State
        data object Scanning : State
        data class ConfirmingSas(
            val outcome: PairingService.HandshakeOutcome,
            val asJoiner: Boolean,
        ) : State
        data class Syncing(val peerDeviceId: String) : State
        data class Done(val peerDeviceId: String, val syncedCount: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Picker)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Already-paired peers — drives the "sync now" / unpair list on the picker. */
    val pairedDevices: StateFlow<List<com.silverbp.android.core.db.SyncDeviceEntity>> =
        ServiceLocator.database.syncDao().devicesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var activeJob: Job? = null
    private var discovery: NsdDiscovery? = null

    fun onShowQrTapped() {
        dlog("onShowQrTapped invoked, deviceId=$localDeviceId")
        cancelActive()
        val payload = QrPairingPayload(
            publicKey = coordinator.staticPublicKey,
            bonjourServiceName = localDeviceId,
            deviceId = localDeviceId,
        )
        _state.value = State.ShowingQr(
            qrUrl = payload.toUrl(),
            statusText = context.getString(R.string.pairing_status_starting_lan),
        )
        activeJob = viewModelScope.launch {
            try {
                val nsd = NsdDiscovery(context)
                discovery = nsd
                val fp = sha256Fingerprint(coordinator.staticPublicKey)
                dlog("Show: startAdvertising deviceId=$localDeviceId fp=$fp")
                withContext(Dispatchers.IO) {
                    nsd.startAdvertising(localDeviceId, fp)
                }
                dlog("Show: NSD registered; awaiting incoming TCP connection")
                _state.update {
                    State.ShowingQr(payload.toUrl(), context.getString(R.string.pairing_status_waiting_scan))
                }
                val socket = withContext(Dispatchers.IO) { nsd.acceptIncoming() }
                dlog("Show: incoming connection from ${socket.inetAddress}:${socket.port}")
                _state.update { State.ShowingQr(payload.toUrl(), context.getString(R.string.pairing_status_handshake)) }
                val channel = StreamFrameChannel(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                )
                val outcome = withContext(Dispatchers.IO) {
                    pairingService.runHandshakeAsQrShower(channel)
                }
                dlog("Show: handshake done, peerDeviceId=${outcome.peerDeviceId}")
                _state.value = State.ConfirmingSas(outcome, asJoiner = false)
            } catch (t: Throwable) {
                if (isActiveCancelled(t)) {
                    dlog("Show: coroutine cancelled (expected on navigation away)")
                } else {
                    Log.e(TAG, "Show: pairing failed", t)
                    _state.value = State.Error(context.getString(R.string.pairing_err_failed, t.message ?: t.javaClass.simpleName))
                }
            } finally {
                runCatching { discovery?.stop() }
                discovery = null
            }
        }
    }

    fun onScanQrTapped() {
        cancelActive()
        _state.value = State.Scanning
    }

    /** Called by the Compose camera analyzer when it has decoded a URL. */
    fun onScanned(url: String) {
        if (_state.value !is State.Scanning) {
            Log.w(TAG, "onScanned ignored — state=${_state.value}")
            return
        }
        dlog("onScanned: QR scanned")
        cancelActive()
        activeJob = viewModelScope.launch {
            try {
                val qr = QrPairingPayload.parse(url)
                dlog("Scan: QR parsed deviceId=${qr.deviceId} svc=${qr.bonjourServiceName}")
                val nsd = NsdDiscovery(context)
                discovery = nsd
                nsd.startBrowsing()
                dlog("Scan: browsing started; awaiting peer")
                val peer = withContext(Dispatchers.IO) { nsd.waitForPeer() }
                dlog("Scan: peer found name=${peer.bonjourName} host=${peer.host} port=${peer.port}")
                val socket = withContext(Dispatchers.IO) { nsd.connect(peer) }
                dlog("Scan: TCP connected; running Noise handshake as joiner")
                val channel = StreamFrameChannel(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                )
                val outcome = withContext(Dispatchers.IO) {
                    pairingService.runHandshakeAsJoiner(
                        channel = channel,
                        localDeviceId = localDeviceId,
                        scannedQR = qr,
                    )
                }
                dlog("Scan: handshake done, peerDeviceId=${outcome.peerDeviceId}")
                _state.value = State.ConfirmingSas(outcome, asJoiner = true)
            } catch (t: Throwable) {
                if (isActiveCancelled(t)) {
                    dlog("Scan: coroutine cancelled")
                } else {
                    Log.e(TAG, "Scan: pairing failed", t)
                    _state.value = State.Error(context.getString(R.string.pairing_err_failed, t.message ?: t.javaClass.simpleName))
                }
            } finally {
                runCatching { discovery?.stop() }
                discovery = null
            }
        }
    }

    fun onConfirmSas(matched: Boolean) {
        val current = _state.value
        if (current !is State.ConfirmingSas) return
        if (!matched) {
            _state.value = State.Picker
            return
        }
        try {
            pairingService.confirmAndPersist(current.outcome)
        } catch (t: Throwable) {
            _state.value = State.Error(context.getString(R.string.pairing_err_save, t.message ?: t.javaClass.simpleName))
            return
        }
        // Immediately follow with one bidirectional sync round over the same
        // Noise channel — the user expects "paired ⇒ data appears" so we
        // pull/push BP readings, exercise sessions, route points, and the
        // full medication graph before the screen closes.
        _state.value = State.Syncing(current.outcome.peerDeviceId)
        viewModelScope.launch {
            try {
                dlog("InitialSync: starting round with ${current.outcome.peerDeviceId}")
                val db = ServiceLocator.database
                val syncDao = db.syncDao()
                val peerId = current.outcome.peerDeviceId
                // Register/refresh the peer so its incremental watermark can be
                // persisted and reused across rounds. Always upsert so a re-pair
                // of the SAME deviceId with a NEW key refreshes pubKey/name/lastSeen
                // — otherwise a rotated key would be ignored and the handshake would
                // verify against a stale pubkey. Preserve any existing watermark so
                // repeat syncs still ship only new records (not reset on re-pair). (QA #4)
                val existingDevice = syncDao.device(peerId)
                syncDao.upsertDevice(
                    SyncDeviceEntity(
                        deviceId = peerId,
                        name = peerId,
                        pubKey = current.outcome.peerStaticPub,
                        lastSeenAt = System.currentTimeMillis(),
                        lastHlcSeen = existingDevice?.lastHlcSeen ?: Hlc.ZERO.packed,
                    ),
                )
                // Shared wiring with background re-sync — see [LanSyncAdapters].
                val source = com.silverbp.android.sync.LanSyncAdapters.buildSource(db, coordinator.clock)
                val sink = com.silverbp.android.sync.LanSyncAdapters.buildSink(db)
                // Snapshot row counts across the synced tables. We surface the
                // BP delta in the Done state because the UI string still says
                // "<n> 筆血壓", but the log captures the full picture so QA
                // can see exercise/medication land too.
                val bpBefore = db.bpDao().count()
                val exerciseBefore = db.exerciseDao().count()
                val doseBefore = db.medicationDoseDao().count()
                withContext(Dispatchers.IO) {
                    pairingService.runInitialSyncRound(
                        localDeviceId = localDeviceId,
                        clock = coordinator.clock,
                        source = source,
                        sink = sink,
                        // Seed from + persist this peer's incremental watermark so
                        // repeat syncs ship only new records; orphan-deferral in
                        // SyncSession keeps advancing it safe. (QA #4 / P1-18)
                        getLocalLastHlcSeen = {
                            syncDao.device(peerId)?.lastHlcSeen?.let(::Hlc) ?: Hlc.ZERO
                        },
                        updateLocalLastHlcSeen = { hlc ->
                            syncDao.touchDevice(peerId, System.currentTimeMillis(), hlc.packed)
                        },
                    )
                }
                val bpAfter = db.bpDao().count()
                val exerciseAfter = db.exerciseDao().count()
                val doseAfter = db.medicationDoseDao().count()
                dlog(
                    "InitialSync: done; bp $bpBefore→$bpAfter, " +
                        "exercise $exerciseBefore→$exerciseAfter, " +
                        "dose $doseBefore→$doseAfter, " +
                        "received=${sink.drainStats()}",
                )
                _state.value = State.Done(
                    peerDeviceId = current.outcome.peerDeviceId,
                    syncedCount = (bpAfter - bpBefore).coerceAtLeast(0),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "InitialSync: failed", t)
                _state.value = State.Error(context.getString(R.string.pairing_err_sync_after, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /**
     * Re-sync with an already-paired peer — no QR / SAS. Both devices tap this
     * around the same time so they rendezvous (deterministic role in
     * [PeerSyncRunner]). Reuses the trusted stored key; reuses the same tested
     * SyncSession/LWW/orphan path as pairing, so it can only converge data.
     */
    fun syncNow(device: SyncDeviceEntity) {
        cancelActive()
        _state.value = State.Syncing(device.deviceId)
        activeJob = viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { PeerSyncRunner(context, localDeviceId).syncWithPeer(device) }
                    .getOrDefault(false)
            }
            _state.value = if (ok) {
                State.Done(device.deviceId, syncedCount = 0)
            } else {
                State.Error(context.getString(R.string.pairing_err_sync_after, "—"))
            }
        }
    }

    /** Remove a paired peer so it no longer syncs or appears in the list. */
    fun forgetPeer(device: SyncDeviceEntity) {
        viewModelScope.launch {
            ServiceLocator.database.syncDao().forgetDevice(device.deviceId)
            // Also drop the peer's trust material (shared root key) from the
            // keystore — leaving it behind would let a stale secret linger after
            // "forget" and silently authenticate a future re-pair. (QA)
            withContext(Dispatchers.IO) { pairingKeyStore.forget(device.deviceId) }
        }
    }

    fun onDismissError() {
        _state.value = State.Picker
    }

    override fun onCleared() {
        cancelActive()
        runCatching { discovery?.stop() }
    }

    private fun cancelActive() {
        activeJob?.cancel()
        activeJob = null
        runCatching { discovery?.stop() }
        discovery = null
    }

    private fun isActiveCancelled(t: Throwable): Boolean =
        t is kotlinx.coroutines.CancellationException

    /**
     * Debug-only pairing diagnostics. Pairing traces carry sensitive material —
     * SAS codes, the QR URL (which embeds the peer's static pubkey + device id),
     * and LAN host/port — that must never reach a release logcat (QA #7). Release
     * builds drop these entirely; failures still surface via Log.e (no secrets).
     */
    private fun dlog(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, msg)
    }

    companion object {
        private const val TAG = "PairingVM"
    }

    private fun sha256Fingerprint(pub: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pub)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private inline fun MutableStateFlow<State>.update(transform: (State) -> State) {
        value = transform(value)
    }

    /**
     * Factory that pulls dependencies from [ServiceLocator]. Compose code
     * uses `viewModel { factory }` to reach an instance scoped to the
     * pairing screen lifecycle.
     */
    class Factory(private val context: Context) :
        androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val coordinator = ServiceLocator.syncCoordinator
            val pairingService = PairingService(ServiceLocator.pairingKeyStore)
            @Suppress("UNCHECKED_CAST")
            return PairingViewModel(
                context = context.applicationContext,
                coordinator = coordinator,
                pairingService = pairingService,
                pairingKeyStore = ServiceLocator.pairingKeyStore,
                localDeviceId = ServiceLocator.syncDeviceId,
            ) as T
        }
    }
}
