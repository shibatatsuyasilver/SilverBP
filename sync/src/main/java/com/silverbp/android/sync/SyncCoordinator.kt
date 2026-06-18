package com.silverbp.android.sync

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.pairing.PairingKeyStore
import com.silverbp.android.sync.protocol.SyncRecordSink
import com.silverbp.android.sync.protocol.SyncRecordSource
import com.silverbp.android.sync.protocol.SyncSession
import com.silverbp.android.sync.transport.FrameChannel
import com.silverbp.android.sync.transport.NoiseTransport
import com.silverbp.android.sync.transport.NoiseXk
import com.silverbp.android.sync.transport.NoiseXkHandshake
import com.silverbp.android.sync.transport.X25519KeyPair

/**
 * High-level façade for cross-device sync. Composes:
 *  - persistent identity ([HlcClock] backed by `keyStore.loadOrCreateNodeId`)
 *  - long-term X25519 static keypair (`keyStore.loadOrCreateStaticKey`)
 *  - paired-device root keys lookup (`keyStore.rootKey(deviceId)`)
 *  - [NoiseXkHandshake] over a caller-supplied [FrameChannel]
 *  - [SyncSession] orchestration
 *
 * Discovery + socket plumbing (NSD/Bonjour) are NOT here — the coordinator
 * is transport-agnostic so unit tests can drive it over an in-memory pipe
 * (see `SyncSessionTest`) and production wires it to `NsdDiscovery`.
 *
 * Mirrors iOS `SyncCoordinator`. Both sides expose the same public method
 * surface so the app layer can switch behaviour between platforms via
 * platform-conditional injection rather than diverging code.
 */
class SyncCoordinator(
    private val deviceId: String,
    private val keyStore: PairingKeyStore,
) {
    val nodeId: Long = keyStore.loadOrCreateNodeId()
    val clock: HlcClock = HlcClock(nodeId)
    private val staticKey: X25519KeyPair = run {
        val (priv, pub) = keyStore.loadOrCreateStaticKey()
        X25519KeyPair(privateKey = priv, publicKey = pub)
    }

    /**
     * Public form of our long-term static key — the value to embed in the
     * QR code we display when adding a new peer.
     */
    val staticPublicKey: ByteArray
        get() = NoiseXk.publicKeyBytes(staticKey.publicKey)

    /**
     * Run one sync round as the *initiator*. Caller already established the
     * channel (e.g. via NSD discovery + TCP connect) and knows the peer's
     * long-term static pubkey from QR pairing.
     *
     * Returns the highest HLC observed from the peer, which the caller
     * should persist as that peer's `lastHlcSeen`.
     */
    suspend fun runSessionAsInitiator(
        peerStaticPub: ByteArray,
        channel: FrameChannel,
        source: SyncRecordSource,
        sink: SyncRecordSink,
        getLastHlcSeen: suspend () -> Hlc,
        updateLastHlcSeen: suspend (Hlc) -> Unit,
    ): Hlc {
        val handshake = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.INITIATOR,
            localStatic = staticKey,
            remoteStatic = peerStaticPub,
        )
        // m1 -> e, es
        channel.send(handshake.writeFirst())
        // m2 <- e, ee
        val m2 = channel.receive() ?: error("EOF before m2")
        handshake.readSecond(m2)
        // m3 -> s, se
        channel.send(handshake.writeThird())

        val (s, r) = handshake.transportCiphers()
        val transport = NoiseTransport(channel = channel, send = s, receive = r)
        return SyncSession(
            transport = transport,
            localDeviceId = deviceId,
            clock = clock,
            source = source,
            sink = sink,
            getLocalLastHlcSeen = getLastHlcSeen,
            updateLocalLastHlcSeen = updateLastHlcSeen,
        ).run()
    }

    /** Run one sync round as the *responder*. */
    suspend fun runSessionAsResponder(
        peerStaticPub: ByteArray,
        channel: FrameChannel,
        source: SyncRecordSource,
        sink: SyncRecordSink,
        getLastHlcSeen: suspend () -> Hlc,
        updateLastHlcSeen: suspend (Hlc) -> Unit,
    ): Hlc {
        val handshake = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.RESPONDER,
            localStatic = staticKey,
            remoteStatic = peerStaticPub,
        )
        val m1 = channel.receive() ?: error("EOF before m1")
        val (_, m2) = handshake.readFirstAndWriteSecond(m1)
        channel.send(m2)
        val m3 = channel.receive() ?: error("EOF before m3")
        handshake.readThird(m3)

        val (s, r) = handshake.transportCiphers()
        val transport = NoiseTransport(channel = channel, send = s, receive = r)
        return SyncSession(
            transport = transport,
            localDeviceId = deviceId,
            clock = clock,
            source = source,
            sink = sink,
            getLocalLastHlcSeen = getLastHlcSeen,
            updateLocalLastHlcSeen = updateLastHlcSeen,
        ).run()
    }

}
