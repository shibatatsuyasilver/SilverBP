package com.silverbp.android.sync.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoiseXkTest {

    @Test
    fun full_three_message_handshake_and_transport() {
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()

        val initiator = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.INITIATOR,
            localStatic = initiatorStatic,
            remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
        )
        val responder = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.RESPONDER,
            localStatic = responderStatic,
            remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
        )

        // m1: -> e, es
        val m1 = initiator.writeFirst("hello".toByteArray())
        val (m1Payload, m2) = responder.readFirstAndWriteSecond(m1, "ack".toByteArray())
        assertArrayEquals("hello".toByteArray(), m1Payload)

        // m2: <- e, ee
        val m2Payload = initiator.readSecond(m2)
        assertArrayEquals("ack".toByteArray(), m2Payload)

        // m3: -> s, se
        val m3 = initiator.writeThird("device-id-A".toByteArray())
        val m3Payload = responder.readThird(m3)
        assertArrayEquals("device-id-A".toByteArray(), m3Payload)

        // Handshake hashes must match — both observed identical transcripts.
        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)

        // Transport: 3 frames each direction with no AD.
        val (initSend, initRecv) = initiator.transportCiphers()
        val (respSend, respRecv) = responder.transportCiphers()

        for (i in 0 until 3) {
            val pt = "frame-$i".toByteArray()
            val ct = initSend.encrypt(ByteArray(0), pt)
            val decoded = respRecv.decrypt(ByteArray(0), ct)
            assertArrayEquals(pt, decoded)
        }
        for (i in 0 until 3) {
            val pt = "reply-$i".toByteArray()
            val ct = respSend.encrypt(ByteArray(0), pt)
            val decoded = initRecv.decrypt(ByteArray(0), ct)
            assertArrayEquals(pt, decoded)
        }
    }

    @Test
    fun handshake_fails_when_initiator_has_wrong_responder_pubkey() {
        val realResponder = NoiseXk.generateKeyPair()
        val attacker = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()

        val initiator = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.INITIATOR,
            localStatic = initiatorStatic,
            remoteStatic = NoiseXk.publicKeyBytes(attacker.publicKey),     // wrong
        )
        val responder = NoiseXkHandshake(
            role = NoiseXkHandshake.Role.RESPONDER,
            localStatic = realResponder,                                 // real
            remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
        )

        val m1 = initiator.writeFirst("trying".toByteArray())
        assertThrows(NoiseXk.NoiseException::class.java) {
            responder.readFirstAndWriteSecond(m1, ByteArray(0))
        }
    }

    @Test
    fun public_key_round_trip_via_raw_bytes() {
        val kp = NoiseXk.generateKeyPair()
        val raw = NoiseXk.publicKeyBytes(kp.publicKey)
        assertEquals(NoiseXk.DH_LEN, raw.size)
        val rebuilt = NoiseXk.publicKeyFromBytes(raw)
        // Compute DH(self_priv, rebuilt_pub) — should match dh(self_priv, original_pub)
        val a = NoiseXk.dh(kp.privateKey, kp.publicKey)
        val b = NoiseXk.dh(kp.privateKey, rebuilt)
        assertArrayEquals(a, b)
    }
}
