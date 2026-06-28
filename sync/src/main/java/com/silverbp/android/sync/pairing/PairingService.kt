package com.silverbp.android.sync.pairing

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.protocol.SyncRecordSink
import com.silverbp.android.sync.protocol.SyncRecordSource
import com.silverbp.android.sync.protocol.SyncSession
import com.silverbp.android.sync.transport.FrameChannel
import com.silverbp.android.sync.transport.NoiseTransport
import com.silverbp.android.sync.transport.NoiseXk
import com.silverbp.android.sync.transport.NoiseXkHandshake
import com.silverbp.android.sync.transport.X25519KeyPair

/**
 * Two-step pairing flow over a [FrameChannel]:
 *
 *   Step 1 — `runHandshake(...)`:
 *     Runs Noise XK between the two peers. The initiator (joiner) already
 *     learned the responder's static pubkey from the QR payload; the
 *     responder learns the initiator's static pubkey from m3. Returns the
 *     [HandshakeOutcome] including the SAS and the peer's static pubkey.
 *
 *   Step 2 — `confirmAndPersist(...)`:
 *     Caller surfaces the SAS to the user and waits for human confirmation
 *     on both devices. Once both tap "matches", caller invokes this to
 *     write the peer record into the keystore. (Persistence is split out
 *     so the UI can abort if SAS doesn't match without leaving stale state.)
 *
 * The service is intentionally transport-agnostic — production wires it to
 * a real `Socket`/`NWConnection`, tests use an in-memory pipe.
 */
class PairingService(
    private val keyStore: PairingKeyStore,
    private val localStaticPair: () -> Pair<ByteArray, ByteArray> = { keyStore.loadOrCreateStaticKey() },
) {

    /** Holds the post-handshake transport so a sync round can be run on the
     *  same TCP connection without re-doing Bonjour + handshake. Set inside
     *  `runHandshakeAsX`, consumed by `runInitialSyncAsX`. */
    @Volatile
    private var pendingTransport: NoiseTransport? = null

    @Volatile
    private var pendingChannel: FrameChannel? = null

    @Volatile
    private var pendingPeerStaticPub: ByteArray? = null

    data class HandshakeOutcome(
        /** 6-digit SAS the user confirms by visual comparison. */
        val sas: String,
        /** Peer's long-term X25519 static pubkey (32 bytes). */
        val peerStaticPub: ByteArray,
        /** Peer's deviceId from QR (joiner side) or m3 (responder side). */
        val peerDeviceId: String,
        /** Full Noise transcript hash — useful for debug logs / advanced verification. */
        val handshakeHash: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HandshakeOutcome) return false
            return sas == other.sas &&
                peerStaticPub.contentEquals(other.peerStaticPub) &&
                peerDeviceId == other.peerDeviceId &&
                handshakeHash.contentEquals(other.handshakeHash)
        }
        override fun hashCode(): Int {
            var r = sas.hashCode()
            r = 31 * r + peerStaticPub.contentHashCode()
            r = 31 * r + peerDeviceId.hashCode()
            r = 31 * r + handshakeHash.contentHashCode()
            return r
        }
    }

    /**
     * Joiner (initiator) side: scanned QR has the responder's static pub +
     * deviceId. We send our own [localDeviceId] in m1's payload so the
     * responder can record us by name.
     */
    suspend fun runHandshakeAsJoiner(
        channel: FrameChannel,
        localDeviceId: String,
        scannedQR: QrPairingPayload,
    ): HandshakeOutcome {
        val (priv, pub) = localStaticPair()
        val staticKey = rebuildKeyPair(priv, pub)
        val handshake = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.INITIATOR,
            localStatic = staticKey,
            remoteStatic = scannedQR.publicKey,
        )
        channel.send(handshake.writeFirst(localDeviceId.toByteArray(Charsets.UTF_8)))
        val m2 = channel.receive() ?: error("EOF before m2")
        handshake.readSecond(m2)
        channel.send(handshake.writeThird())
        // Reuse the post-handshake cipher pair for an immediate sync round.
        val (s, r) = handshake.transportCiphers()
        pendingChannel = channel
        pendingTransport = NoiseTransport(channel = channel, send = s, receive = r)
        pendingPeerStaticPub = scannedQR.publicKey
        return HandshakeOutcome(
            sas = Sas.fromHandshakeHash(handshake.handshakeHash),
            peerStaticPub = scannedQR.publicKey,
            peerDeviceId = scannedQR.deviceId,
            handshakeHash = handshake.handshakeHash,
        )
    }

    /**
     * Initiator (responder of Noise XK) side: we displayed the QR, they
     * connected to our Bonjour service. We learn their static pub from
     * m3's encrypted "s" token, and their deviceId from m1's payload.
     */
    suspend fun runHandshakeAsQrShower(
        channel: FrameChannel,
    ): HandshakeOutcome {
        val (priv, pub) = localStaticPair()
        val staticKey = rebuildKeyPair(priv, pub)
        val handshake = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.RESPONDER,
            localStatic = staticKey,
            remoteStatic = pub,
        )
        val m1 = channel.receive() ?: error("EOF before m1")
        val (m1Payload, m2) = handshake.readFirstAndWriteSecond(m1)
        channel.send(m2)
        val peerDeviceId = m1Payload.toString(Charsets.UTF_8)
        val m3 = channel.receive() ?: error("EOF before m3")
        val peerStaticPub = readM3WithUnpinnedStatic(handshake, m3)
        // Reuse the post-handshake cipher pair for an immediate sync round.
        val (s, r) = handshake.transportCiphers()
        pendingChannel = channel
        pendingTransport = NoiseTransport(channel = channel, send = s, receive = r)
        pendingPeerStaticPub = peerStaticPub
        return HandshakeOutcome(
            sas = Sas.fromHandshakeHash(handshake.handshakeHash),
            peerStaticPub = peerStaticPub,
            peerDeviceId = peerDeviceId,
            handshakeHash = handshake.handshakeHash,
        )
    }

    /**
     * Persist a paired peer after the user confirmed SAS on both devices.
     * Idempotent — calling twice with the same outcome leaves the keystore
     * in the same state.
     */
    fun confirmAndPersist(outcome: HandshakeOutcome) {
        keyStore.storeRootKey(outcome.peerStaticPub, outcome.peerDeviceId)
    }

    /**
     * Run one bidirectional sync round over the still-open Noise channel
     * established during the most recent `runHandshakeAsX` call. Both peers
     * must call this concurrently — the underlying [SyncSession] deadlocks
     * if only one side runs.
     *
     * Returns the highest HLC observed from the peer.
     */
    suspend fun runInitialSyncRound(
        localDeviceId: String,
        clock: HlcClock,
        source: SyncRecordSource,
        sink: SyncRecordSink,
        getLocalLastHlcSeen: suspend () -> Hlc,
        updateLocalLastHlcSeen: suspend (Hlc) -> Unit,
    ): Hlc {
        val transport = pendingTransport
            ?: error("runInitialSyncRound: no pending transport — call runHandshakeAsX first")
        try {
            return SyncSession(
                transport = transport,
                localDeviceId = localDeviceId,
                clock = clock,
                source = source,
                sink = sink,
                getLocalLastHlcSeen = getLocalLastHlcSeen,
                updateLocalLastHlcSeen = updateLocalLastHlcSeen,
            ).run()
        } finally {
            // Tear down so a follow-up pairing starts fresh.
            try { pendingChannel?.close() } catch (_: Throwable) {}
            pendingTransport = null
            pendingChannel = null
            pendingPeerStaticPub = null
        }
    }

    private fun readM3WithUnpinnedStatic(
        handshake: NoiseXkHandshake,
        m3: ByteArray,
    ): ByteArray {
        // The standard `handshake.readThird(m3)` rejects when the decrypted
        // static doesn't match `remoteStatic` (which during pairing we
        // can't pre-pin). We replicate readThird here without the equality
        // check by hand-driving the symmetric state via internal API.
        //
        // Why this is safe: pairing's authentication comes from the user
        // confirming the SAS, not from a pre-known pubkey. Skipping the
        // pre-pinned-static check during pairing is correct.
        return handshake.unsafeReadThirdReturningStaticKey(m3)
    }

    private fun rebuildKeyPair(privRaw: ByteArray, pubRaw: ByteArray): X25519KeyPair =
        X25519KeyPair(privateKey = privRaw, publicKey = pubRaw)
}
