package com.silverbp.android.sync.pairing

import java.security.MessageDigest

/**
 * Short Authentication String — 6-digit decimal code derived from the
 * pairing transcript so the user can visually confirm both devices agree on
 * the same shared secret. Defends against active MITM during pairing.
 * Mirrors `Packages/BPSharing/Sources/BPSharing/Pairing/SAS.swift`.
 *
 * Derivation:
 * ```
 *   transcript = sortedConcat(initiatorPK, joinerPK)
 *   sas        = first 4 bytes of SHA256(rootKey || transcript) as big-endian
 *                uint32, modulo 1_000_000, zero-padded to 6 digits
 * ```
 *
 * Both devices compute and display the same 6-digit code; user taps
 * "matches" on each side. Mismatch ⇒ abort pairing.
 */
object Sas {
    const val DIGIT_COUNT = 6
    const val MODULUS = 1_000_000L

    fun derive(
        rootKey: ByteArray,
        initiatorPublicKey: ByteArray,
        joinerPublicKey: ByteArray,
    ): String {
        val transcript = sortedConcat(initiatorPublicKey, joinerPublicKey)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(rootKey)
        md.update(transcript)
        return code(md.digest())
    }

    /**
     * Compute SAS directly from a completed Noise handshake hash. Both peers
     * end the XK handshake with byte-identical [handshakeHash] iff they
     * observed the same transcript — i.e. no MITM. So the 6-digit code
     * derived from it differs on the two sides if anything was tampered with.
     *
     * Use this variant for QR-pairing: the QR carries the responder's
     * static pubkey, the joiner runs Noise XK over LAN, and after m3 both
     * compute SAS against `handshakeHash` and the user visually confirms.
     */
    fun fromHandshakeHash(handshakeHash: ByteArray): String {
        require(handshakeHash.size == 32) {
            "Noise SHA-256 handshake hash must be 32 bytes, got ${handshakeHash.size}"
        }
        return code(handshakeHash)
    }

    private fun code(digest: ByteArray): String {
        val be = ((digest[0].toLong() and 0xFF) shl 24) or
            ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or
            (digest[3].toLong() and 0xFF)
        val n = be % MODULUS
        return n.toString().padStart(DIGIT_COUNT, '0')
    }

    private fun sortedConcat(a: ByteArray, b: ByteArray): ByteArray =
        if (lex(a, b) <= 0) a + b else b + a

    private fun lex(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }
}
