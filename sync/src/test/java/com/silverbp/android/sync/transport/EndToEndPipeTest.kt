package com.silverbp.android.sync.transport

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The marquee end-to-end test for Phase 1.2's transport stack.
 *
 * Pairs two Noise XK handshakes back-to-back over an in-memory [FrameChannel]
 * pipe, then exchanges encrypted CBOR-encoded [SyncRecord]s in both
 * directions. Validates everything between two devices except the actual
 * TCP socket:
 *
 *   handshake (3 messages, real X25519 + ChaChaPoly + HKDF)
 *     ↓
 *   transport AEAD with per-direction monotonic nonce
 *     ↓
 *   length-prefixed framing
 *     ↓
 *   in-memory channel pipe (replaceable with Socket I/O in production)
 *     ↓
 *   CBOR-encoded SyncRecord payloads
 *     ↓
 *   bit-identical decoded SyncRecord on the other side
 *
 * If this test passes, the whole post-pairing pipeline is correct end to
 * end. iOS has the symmetric test in `EndToEndPipeTests.swift`.
 */
class EndToEndPipeTest {

    @Test
    fun two_noise_xk_sessions_exchange_encrypted_sync_records_bidirectionally() = runTest {
        // 1. Both sides have long-term static keypairs (would normally come
        //    from QR pairing's persisted Keystore/Keychain entry).
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()

        // 2. In-memory pipe simulating the LAN socket between the two devices.
        val (initiatorChannel, responderChannel) = MemoryPipe.create()

        // 3. Run both handshakes concurrently — initiator and responder
        //    each pump frames on the wire as they're available.
        val transports = coroutineScope {
            val initiatorJob = async {
                runInitiatorHandshake(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
                    ),
                    channel = initiatorChannel,
                )
            }
            val responderJob = async {
                runResponderHandshake(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
                    ),
                    channel = responderChannel,
                )
            }
            initiatorJob.await() to responderJob.await()
        }
        val (initiatorTransport, responderTransport) = transports

        // 4. Exchange 5 CBOR-encoded SyncRecords each direction. These are
        //    representative BP-reading wire shapes — fully encrypted and
        //    framed, then decoded back on the receiver.
        val records = (0 until 5).map { i ->
            SyncRecord(
                type = SyncEntityType.BP_READING,
                pk = "reading-$i",
                hlc = Hlc.of(physicalMs = 1_730_000_000_000L + i, logical = 0, nodeId = 0xCAFEBABEL),
                deletedAt = null,
                payload = mapOf(
                    1 to SyncValue.Int64((118 + i).toLong()),
                    2 to SyncValue.Int64((72 + i).toLong()),
                    4 to SyncValue.Int64(1_730_000_000_000L + i),
                    5 to SyncValue.Text("left"),
                    11 to SyncValue.Text("manual"),
                    12 to SyncValue.Text("frame-$i 多位元組測試"),
                ),
            )
        }

        // Initiator → responder
        for (rec in records) {
            initiatorTransport.sendFrame(SyncRecordCodec.encode(rec))
        }
        for (rec in records) {
            val bytes = responderTransport.receiveFrame() ?: error("EOF mid-exchange")
            val decoded = SyncRecordCodec.decode(bytes)
            assertEquals(rec, decoded)
        }

        // Responder → initiator
        for (rec in records) {
            responderTransport.sendFrame(SyncRecordCodec.encode(rec))
        }
        for (rec in records) {
            val bytes = initiatorTransport.receiveFrame() ?: error("EOF mid-exchange")
            val decoded = SyncRecordCodec.decode(bytes)
            assertEquals(rec, decoded)
        }

        initiatorTransport.close()
        // Closing one side EOFs the other.
        assertNull(responderTransport.receiveFrame())
    }

    // ---- helpers ----

    /** Drive the initiator side of the XK handshake over [channel] and
     *  return the post-handshake [NoiseTransport]. */
    private suspend fun runInitiatorHandshake(
        handshake: NoiseXkHandshake,
        channel: FrameChannel,
    ): NoiseTransport {
        // m1 -> e, es
        channel.send(handshake.writeFirst())
        // m2 <- e, ee
        val m2 = channel.receive() ?: error("handshake EOF before m2")
        handshake.readSecond(m2)
        // m3 -> s, se
        channel.send(handshake.writeThird())
        val (s, r) = handshake.transportCiphers()
        return NoiseTransport(channel = channel, send = s, receive = r)
    }

    /** Drive the responder side of the XK handshake over [channel] and
     *  return the post-handshake [NoiseTransport]. */
    private suspend fun runResponderHandshake(
        handshake: NoiseXkHandshake,
        channel: FrameChannel,
    ): NoiseTransport {
        val m1 = channel.receive() ?: error("handshake EOF before m1")
        val (_, m2) = handshake.readFirstAndWriteSecond(m1)
        channel.send(m2)
        val m3 = channel.receive() ?: error("handshake EOF before m3")
        handshake.readThird(m3)
        val (s, r) = handshake.transportCiphers()
        return NoiseTransport(channel = channel, send = s, receive = r)
    }

}
