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
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

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
    private val staticKey: KeyPair = run {
        val (priv, pub) = keyStore.loadOrCreateStaticKey()
        rebuildKeyPair(priv, pub)
    }

    /**
     * Public form of our long-term static key — the value to embed in the
     * QR code we display when adding a new peer.
     */
    val staticPublicKey: ByteArray
        get() = NoiseXk.publicKeyBytes(staticKey.public)

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

    private fun rebuildKeyPair(privRaw: ByteArray, pubRaw: ByteArray): KeyPair {
        require(privRaw.size == 32 && pubRaw.size == 32) {
            "static key bytes must be 32 each, got priv=${privRaw.size} pub=${pubRaw.size}"
        }
        val kf = KeyFactory.getInstance("X25519")
        // Wrap raw scalar/u-coordinate in PKCS8 / X.509 envelopes so Conscrypt
        // accepts them. JDK reference impl also accepts these forms.
        val privKey: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8WrapX25519(privRaw)))
        val pubKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(x509WrapX25519(pubRaw)))
        return KeyPair(pubKey, privKey)
    }

    /**
     * Wrap a 32-byte X25519 raw scalar in a PKCS8 PrivateKeyInfo envelope
     * per RFC 8410 §7.
     */
    private fun pkcs8WrapX25519(scalar32: ByteArray): ByteArray {
        require(scalar32.size == 32)
        val prefix = byteArrayOf(
            0x30, 0x2E,                 // SEQUENCE, length 46
            0x02, 0x01, 0x00,           // INTEGER 0 (version v1)
            0x30, 0x05,                 // SEQUENCE, length 5 (algorithm)
            0x06, 0x03, 0x2B, 0x65, 0x6E, // OID 1.3.101.110 (X25519)
            0x04, 0x22,                 // OCTET STRING, length 34
            0x04, 0x20,                 // nested OCTET STRING, length 32
        )
        return prefix + scalar32
    }

    /**
     * Wrap a 32-byte X25519 raw u-coordinate (little-endian per RFC 7748)
     * in an X.509 SubjectPublicKeyInfo envelope per RFC 8410 §4.
     */
    private fun x509WrapX25519(u32: ByteArray): ByteArray {
        require(u32.size == 32)
        val prefix = byteArrayOf(
            0x30, 0x2A,                 // SEQUENCE, length 42
            0x30, 0x05,                 // SEQUENCE, length 5 (algorithm)
            0x06, 0x03, 0x2B, 0x65, 0x6E, // OID 1.3.101.110 (X25519)
            0x03, 0x21, 0x00,           // BIT STRING, length 33, 0 unused bits
        )
        return prefix + u32
    }
}
