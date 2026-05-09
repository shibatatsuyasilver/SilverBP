package com.silverbp.android.sync.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-platform Noise transport-frame interop. Mirror of iOS
 * `NoiseInteropTests.testTransportFrameMatchesCanonicalHex()`. Same key +
 * nonce + AD + plaintext on both sides must produce byte-identical
 * ciphertext.
 *
 * Handshake bytes are non-deterministic (fresh ephemerals each session) so
 * we lock the post-handshake AEAD layer here. The full handshake is
 * exercised cross-instance per platform in `NoiseXkTest`.
 */
class NoiseInteropTest {

    @Test
    fun transport_frame_matches_canonical_hex() {
        val key = ByteArray(32) { 0x42 }
        val ad = ByteArray(32) { 0xAB.toByte() }
        val plaintext = "interop".toByteArray()

        val cs = NoiseCipherState(key)
        val ciphertext = cs.encrypt(ad, plaintext)
        val hex = ciphertext.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        // Frozen canonical fixture — iOS asserts the same hex in
        // `NoiseInteropTests.testTransportFrameMatchesCanonicalHex()`.
        // Drift detection on either side fails the build.
        val expected = "3008904e6fdb4333e19ee4ad29242a8ff17133645ec5d2"
        assertEquals("Noise transport wire format diverged from iOS", expected, hex)

        // Reverse direction: decode the frozen hex and verify plaintext.
        val cs2 = NoiseCipherState(key)
        val bytes = expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val recovered = cs2.decrypt(ad, bytes)
        assertArrayEquals(plaintext, recovered)
    }
}
