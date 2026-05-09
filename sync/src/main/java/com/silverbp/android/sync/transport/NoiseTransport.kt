package com.silverbp.android.sync.transport

/**
 * Post-handshake encrypted transport over a [FrameChannel]. Wraps the
 * `(send, receive)` cipher-state pair returned by [NoiseXkHandshake.transportCiphers]
 * so the rest of the protocol layer reads/writes plaintext frames and the
 * AEAD layer stays encapsulated.
 *
 * Each plaintext frame becomes one length-prefixed encrypted frame on the
 * wire. Per-direction nonces advance monotonically; replay drops are
 * surfaced as [NoiseXk.NoiseException.DecryptionFailed].
 *
 * Associated data (AAD) is unused at this layer — the Noise transcript hash
 * already authenticated the channel during handshake, and per-frame AAD
 * would require app-protocol coordination that we don't need yet.
 */
class NoiseTransport(
    private val channel: FrameChannel,
    private val send: NoiseCipherState,
    private val receive: NoiseCipherState,
) {
    suspend fun sendFrame(plaintext: ByteArray) {
        val ct = send.encrypt(EMPTY_AD, plaintext)
        channel.send(ct)
    }

    /** Returns the decrypted plaintext, or null on clean EOF from the channel. */
    suspend fun receiveFrame(): ByteArray? {
        val ct = channel.receive() ?: return null
        return receive.decrypt(EMPTY_AD, ct)
    }

    suspend fun close() {
        channel.close()
    }

    companion object {
        private val EMPTY_AD = ByteArray(0)
    }
}
