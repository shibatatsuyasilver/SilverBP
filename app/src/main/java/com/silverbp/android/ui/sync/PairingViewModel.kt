package com.silverbp.android.ui.sync

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.sync.CombinedRoomSyncSink
import com.silverbp.android.sync.CombinedRoomSyncSource
import com.silverbp.android.sync.SyncCoordinator
import com.silverbp.android.sync.pairing.PairingService
import com.silverbp.android.sync.pairing.QrPairingPayload
import com.silverbp.android.sync.transport.NsdDiscovery
import com.silverbp.android.sync.transport.StreamFrameChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var activeJob: Job? = null
    private var discovery: NsdDiscovery? = null

    fun onShowQrTapped() {
        Log.i(TAG, "onShowQrTapped invoked, deviceId=$localDeviceId")
        cancelActive()
        val payload = QrPairingPayload(
            publicKey = coordinator.staticPublicKey,
            bonjourServiceName = localDeviceId,
            deviceId = localDeviceId,
        )
        _state.value = State.ShowingQr(
            qrUrl = payload.toUrl(),
            statusText = "正在啟動 LAN 廣播…",
        )
        activeJob = viewModelScope.launch {
            try {
                val nsd = NsdDiscovery(context)
                discovery = nsd
                val fp = sha256Fingerprint(coordinator.staticPublicKey)
                Log.i(TAG, "Show: startAdvertising deviceId=$localDeviceId fp=$fp")
                withContext(Dispatchers.IO) {
                    nsd.startAdvertising(localDeviceId, fp)
                }
                Log.i(TAG, "Show: NSD registered; awaiting incoming TCP connection")
                _state.update {
                    State.ShowingQr(payload.toUrl(), "等待對方掃描配對…")
                }
                val socket = withContext(Dispatchers.IO) { nsd.acceptIncoming() }
                Log.i(TAG, "Show: incoming connection from ${socket.inetAddress}:${socket.port}")
                _state.update { State.ShowingQr(payload.toUrl(), "正在進行 Noise XK 握手…") }
                val channel = StreamFrameChannel(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                )
                val outcome = withContext(Dispatchers.IO) {
                    pairingService.runHandshakeAsQrShower(channel)
                }
                Log.i(TAG, "Show: handshake done, sas=${outcome.sas} peerDeviceId=${outcome.peerDeviceId}")
                _state.value = State.ConfirmingSas(outcome, asJoiner = false)
            } catch (t: Throwable) {
                if (isActiveCancelled(t)) {
                    Log.i(TAG, "Show: coroutine cancelled (expected on navigation away)")
                } else {
                    Log.e(TAG, "Show: pairing failed", t)
                    _state.value = State.Error("配對失敗:${t.message ?: t.javaClass.simpleName}")
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
        Log.i(TAG, "onScanned url=$url")
        cancelActive()
        activeJob = viewModelScope.launch {
            try {
                val qr = QrPairingPayload.parse(url)
                Log.i(TAG, "Scan: QR parsed deviceId=${qr.deviceId} svc=${qr.bonjourServiceName}")
                val nsd = NsdDiscovery(context)
                discovery = nsd
                nsd.startBrowsing()
                Log.i(TAG, "Scan: browsing started; awaiting peer")
                val peer = withContext(Dispatchers.IO) { nsd.waitForPeer() }
                Log.i(TAG, "Scan: peer found name=${peer.bonjourName} host=${peer.host} port=${peer.port}")
                val socket = withContext(Dispatchers.IO) { nsd.connect(peer) }
                Log.i(TAG, "Scan: TCP connected; running Noise handshake as joiner")
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
                Log.i(TAG, "Scan: handshake done, sas=${outcome.sas} peerDeviceId=${outcome.peerDeviceId}")
                _state.value = State.ConfirmingSas(outcome, asJoiner = true)
            } catch (t: Throwable) {
                if (isActiveCancelled(t)) {
                    Log.i(TAG, "Scan: coroutine cancelled")
                } else {
                    Log.e(TAG, "Scan: pairing failed", t)
                    _state.value = State.Error("配對失敗:${t.message ?: t.javaClass.simpleName}")
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
            _state.value = State.Error("無法儲存配對:${t.message ?: t.javaClass.simpleName}")
            return
        }
        // Immediately follow with one bidirectional sync round over the same
        // Noise channel — the user expects "paired ⇒ data appears" so we
        // pull/push BP readings, exercise sessions, route points, and the
        // full medication graph before the screen closes.
        _state.value = State.Syncing(current.outcome.peerDeviceId)
        viewModelScope.launch {
            try {
                Log.i(TAG, "InitialSync: starting round with ${current.outcome.peerDeviceId}")
                val db = ServiceLocator.database
                val source = CombinedRoomSyncSource(
                    bpDao = db.bpDao(),
                    exerciseDao = db.exerciseDao(),
                    medicationDao = db.medicationDao(),
                    medicationScheduleDao = db.medicationScheduleDao(),
                    medicationDoseDao = db.medicationDoseDao(),
                    achievementDao = db.achievementDao(),
                    coachPlanDao = db.coachPlanDao(),
                    sleepDao = db.sleepDao(),
                    dietDao = db.dietDao(),
                    bpMapper = ServiceLocator.bpReadingSyncMapper,
                    exerciseSessionMapper = ServiceLocator.exerciseSessionSyncMapper,
                    routePointMapper = ServiceLocator.routePointSyncMapper,
                    medicationMapper = ServiceLocator.medicationSyncMapper,
                    medicationScheduleMapper = ServiceLocator.medicationScheduleSyncMapper,
                    medicationDoseMapper = ServiceLocator.medicationDoseSyncMapper,
                    dailyStepLogMapper = ServiceLocator.dailyStepLogSyncMapper,
                    achievementMapper = ServiceLocator.achievementSyncMapper,
                    coachPlanMapper = ServiceLocator.coachPlanSyncMapper,
                    coachTaskMapper = ServiceLocator.coachTaskSyncMapper,
                    sleepLogMapper = ServiceLocator.sleepLogSyncMapper,
                    dietCheckMapper = ServiceLocator.dietCheckSyncMapper,
                    clock = coordinator.clock,
                    exerciseLibraryDao = db.exerciseLibraryDao(),
                    strengthWorkoutDao = db.strengthWorkoutDao(),
                    exerciseCatalogItemMapper = ServiceLocator.exerciseCatalogItemSyncMapper,
                    strengthWorkoutSessionMapper = ServiceLocator.strengthWorkoutSessionSyncMapper,
                    setLogMapper = ServiceLocator.setLogSyncMapper,
                    bpWorkoutAssociationDao = db.bpWorkoutAssociationDao(),
                    bpWorkoutAssociationMapper = ServiceLocator.bpWorkoutAssociationSyncMapper,
                )
                val sink = CombinedRoomSyncSink(
                    bpMapper = ServiceLocator.bpReadingSyncMapper,
                    exerciseSessionMapper = ServiceLocator.exerciseSessionSyncMapper,
                    routePointMapper = ServiceLocator.routePointSyncMapper,
                    medicationMapper = ServiceLocator.medicationSyncMapper,
                    medicationScheduleMapper = ServiceLocator.medicationScheduleSyncMapper,
                    medicationDoseMapper = ServiceLocator.medicationDoseSyncMapper,
                    dailyStepLogMapper = ServiceLocator.dailyStepLogSyncMapper,
                    achievementMapper = ServiceLocator.achievementSyncMapper,
                    coachPlanMapper = ServiceLocator.coachPlanSyncMapper,
                    coachTaskMapper = ServiceLocator.coachTaskSyncMapper,
                    sleepLogMapper = ServiceLocator.sleepLogSyncMapper,
                    dietCheckMapper = ServiceLocator.dietCheckSyncMapper,
                    exerciseCatalogItemMapper = ServiceLocator.exerciseCatalogItemSyncMapper,
                    strengthWorkoutSessionMapper = ServiceLocator.strengthWorkoutSessionSyncMapper,
                    setLogMapper = ServiceLocator.setLogSyncMapper,
                    bpWorkoutAssociationMapper = ServiceLocator.bpWorkoutAssociationSyncMapper,
                )
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
                    )
                }
                val bpAfter = db.bpDao().count()
                val exerciseAfter = db.exerciseDao().count()
                val doseAfter = db.medicationDoseDao().count()
                Log.i(
                    TAG,
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
                _state.value = State.Error("配對成功但同步失敗:${t.message ?: t.javaClass.simpleName}")
            }
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
                localDeviceId = ServiceLocator.syncDeviceId,
            ) as T
        }
    }
}
