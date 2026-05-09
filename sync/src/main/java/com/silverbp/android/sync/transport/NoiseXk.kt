package com.silverbp.android.sync.transport

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise Protocol Framework — pattern XK over Curve25519 + ChaCha20-Poly1305 +
 * SHA-256. Wire-compatible with iOS `NoiseXK.swift` byte-for-byte.
 *
 * ```
 *   XK:
 *       <- s
 *       ...
 *       -> e, es
 *       <- e, ee
 *       -> s, se
 * ```
 *
 * The initiator already knows the responder's static public key (cached at
 * QR pairing). After three messages both peers split into two AEAD
 * `NoiseCipherState`s — one per direction.
 */
object NoiseXk {
    const val PROTOCOL_NAME = "Noise_XK_25519_ChaChaPoly_SHA256"
    const val DH_LEN = 32
    const val MAC_LEN = 16
    const val HASH_LEN = 32

    sealed class NoiseException(msg: String) : RuntimeException(msg) {
        class DecryptionFailed(msg: String) : NoiseException(msg)
        class UnexpectedLength(expected: Int, got: Int) :
            NoiseException("expected $expected, got $got")
        class NonceOverflow : NoiseException("ChaCha20 nonce counter exhausted")
    }

    /** Generate a fresh X25519 keypair. */
    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("X25519")
        return gen.generateKeyPair()
    }

    /** Extract the 32-byte raw public key (u-coordinate, little-endian).
     *
     * Three fallback paths because Android's Conscrypt-based providers ship
     * `OpenSSLX25519PublicKey` which does NOT implement
     * [java.security.interfaces.XECPublicKey] — direct cast crashes on every
     * non-AOSP-reference device (vivo / OPPO / Samsung etc.).
     */
    fun publicKeyBytes(pub: PublicKey): ByteArray {
        // Path 1: standard XECPublicKey interface (JDK reference impl).
        val asXec = pub as? java.security.interfaces.XECPublicKey
        if (asXec != null) return uToLittleEndian(asXec.u)
        // Path 2: cross-provider key-spec extraction.
        runCatching {
            val spec = KeyFactory.getInstance("X25519")
                .getKeySpec(pub, java.security.spec.XECPublicKeySpec::class.java)
            return uToLittleEndian(spec.u)
        }
        // Path 3: X.509 SubjectPublicKeyInfo trailing 32 bytes are the
        // little-endian u-coordinate per RFC 8410.
        val encoded = pub.encoded
            ?: error("X25519 public key has no encoded form on this provider")
        require(encoded.size >= DH_LEN) {
            "X.509 encoded X25519 key shorter than $DH_LEN bytes: ${encoded.size}"
        }
        return encoded.takeLast(DH_LEN).toByteArray()
    }

    private fun uToLittleEndian(u: java.math.BigInteger): ByteArray {
        val raw = ByteArray(DH_LEN)
        var v = u
        for (i in 0 until DH_LEN) {
            raw[i] = (v.toLong() and 0xFF).toByte()
            v = v.shiftRight(8)
        }
        return raw
    }

    /** Construct an X25519 PublicKey from its 32-byte raw form.
     *
     * Wraps the raw u-coordinate in an X.509 SubjectPublicKeyInfo envelope
     * (RFC 8410 §4) because Conscrypt's KeyFactory rejects [XECPublicKeySpec]
     * — even though it claims to accept it. JDK reference impl also
     * accepts X509EncodedKeySpec, so this path is universal.
     */
    fun publicKeyFromBytes(raw: ByteArray): PublicKey {
        require(raw.size == DH_LEN) { "X25519 public key must be $DH_LEN bytes, got ${raw.size}" }
        val prefix = byteArrayOf(
            0x30, 0x2A,                 // SEQUENCE, length 42
            0x30, 0x05,                 // SEQUENCE, length 5 (algorithm)
            0x06, 0x03, 0x2B, 0x65, 0x6E, // OID 1.3.101.110 (X25519)
            0x03, 0x21, 0x00,           // BIT STRING, length 33, 0 unused bits
        )
        val encoded = prefix + raw
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        return KeyFactory.getInstance("X25519").generatePublic(spec)
    }

    /** Compute X25519 DH and return the 32-byte shared secret. */
    fun dh(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privateKey)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }
}

/** AEAD cipher state. 32-byte ChaCha20-Poly1305 key + 64-bit monotonic nonce. */
class NoiseCipherState(private val key: ByteArray) {
    init {
        require(key.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
    }

    private var nonce: Long = 0

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val n = chachaNonce(nonce)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(n),
        )
        cipher.updateAAD(ad)
        val out = cipher.doFinal(plaintext)
        advance()
        return out
    }

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < NoiseXk.MAC_LEN) {
            throw NoiseXk.NoiseException.DecryptionFailed("ciphertext too short")
        }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val n = chachaNonce(nonce)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(n),
        )
        cipher.updateAAD(ad)
        val out = try {
            cipher.doFinal(ciphertext)
        } catch (t: Throwable) {
            throw NoiseXk.NoiseException.DecryptionFailed("AEAD auth failed: ${t.message}")
        }
        advance()
        return out
    }

    private fun advance() {
        if (nonce == Long.MAX_VALUE) throw NoiseXk.NoiseException.NonceOverflow()
        nonce++
    }

    private fun chachaNonce(n: Long): ByteArray {
        // Noise: 4-byte zero prefix || 8-byte little-endian counter.
        val out = ByteArray(12)
        for (i in 0 until 8) {
            out[4 + i] = ((n ushr (8 * i)) and 0xFF).toByte()
        }
        return out
    }
}

/** Symmetric handshake state — chaining key + handshake hash + lazy AEAD. */
class NoiseSymmetricState(protocolName: String) {
    var ck: ByteArray
        private set
    var h: ByteArray
        private set
    private var keyed: Boolean = false
    private var k: ByteArray = ByteArray(32)
    private var nonce: Long = 0

    init {
        val nameBytes = protocolName.toByteArray(Charsets.UTF_8)
        h = if (nameBytes.size <= NoiseXk.HASH_LEN) {
            val pad = ByteArray(NoiseXk.HASH_LEN)
            nameBytes.copyInto(pad)
            pad
        } else {
            sha256(nameBytes)
        }
        ck = h.copyOf()
    }

    fun mixHash(data: ByteArray) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(h)
        md.update(data)
        h = md.digest()
    }

    fun mixKey(input: ByteArray) {
        val (newCk, newK) = hkdfPair(salt = ck, ikm = input)
        ck = newCk
        k = newK
        keyed = true
        nonce = 0
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        return if (keyed) {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(k, "ChaCha20"),
                IvParameterSpec(chachaNonce(nonce)),
            )
            cipher.updateAAD(h)
            val ct = cipher.doFinal(plaintext)
            nonce++
            mixHash(ct)
            ct
        } else {
            mixHash(plaintext)
            plaintext
        }
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        return if (keyed) {
            if (ciphertext.size < NoiseXk.MAC_LEN) {
                throw NoiseXk.NoiseException.DecryptionFailed("ciphertext too short")
            }
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(k, "ChaCha20"),
                IvParameterSpec(chachaNonce(nonce)),
            )
            cipher.updateAAD(h)
            val pt = try {
                cipher.doFinal(ciphertext)
            } catch (t: Throwable) {
                throw NoiseXk.NoiseException.DecryptionFailed("AEAD auth failed: ${t.message}")
            }
            nonce++
            mixHash(ciphertext)
            pt
        } else {
            mixHash(ciphertext)
            ciphertext
        }
    }

    /** Final split: produce two `CipherState`s, one per direction. */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val (k1, k2) = hkdfPair(salt = ck, ikm = ByteArray(0))
        return NoiseCipherState(k1) to NoiseCipherState(k2)
    }

    private fun chachaNonce(n: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) {
            out[4 + i] = ((n ushr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * RFC 5869 HKDF with empty info, output length 64 bytes, split into two
     * 32-byte halves. With empty info this is identical to Noise's `HKDF(...,
     * num_outputs=2)`.
     */
    private fun hkdfPair(salt: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC(salt, ikm)
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // Expand: T(1) = HMAC(prk, 0x01); T(2) = HMAC(prk, T(1) || 0x02)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(byteArrayOf(0x01))
        val t1 = mac.doFinal()

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t1)
        mac.update(byteArrayOf(0x02))
        val t2 = mac.doFinal()

        return t1 to t2
    }
}

/**
 * XK handshake state machine. After three messages it produces a transport
 * pair (sendCipher, receiveCipher).
 */
class NoiseXkHandshake(
    val role: Role,
    private val localStatic: KeyPair,
    private val remoteStatic: ByteArray,
) {
    enum class Role { INITIATOR, RESPONDER }

    init {
        require(remoteStatic.size == NoiseXk.DH_LEN) {
            "remoteStatic must be ${NoiseXk.DH_LEN} bytes"
        }
    }

    private val symm = NoiseSymmetricState(NoiseXk.PROTOCOL_NAME)
    private val localStaticPublic: ByteArray = NoiseXk.publicKeyBytes(localStatic.public)
    private var localEphemeral: KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var step: Int = 0

    init {
        // Pre-message: <- s. Both sides mixHash the responder's static pub.
        val responderStaticPub = when (role) {
            Role.INITIATOR -> remoteStatic
            Role.RESPONDER -> localStaticPublic
        }
        symm.mixHash(responderStaticPub)
    }

    val handshakeHash: ByteArray get() = symm.h

    /** Initiator: write message 1 (-> e, es). */
    fun writeFirst(payload: ByteArray = ByteArray(0)): ByteArray {
        check(role == Role.INITIATOR && step == 0) { "writeFirst: wrong role/step" }
        val e = NoiseXk.generateKeyPair()
        localEphemeral = e
        val ePub = NoiseXk.publicKeyBytes(e.public)
        symm.mixHash(ePub)
        val dh = NoiseXk.dh(e.private, NoiseXk.publicKeyFromBytes(remoteStatic))
        symm.mixKey(dh)
        val ct = symm.encryptAndHash(payload)
        step = 1
        return ePub + ct
    }

    /** Responder: read message 1 and emit message 2 (<- e, ee) in one step. */
    fun readFirstAndWriteSecond(
        message: ByteArray,
        payload: ByteArray = ByteArray(0),
    ): Pair<ByteArray, ByteArray> {
        check(role == Role.RESPONDER && step == 0) { "readFirst: wrong role/step" }
        if (message.size < NoiseXk.DH_LEN) {
            throw NoiseXk.NoiseException.UnexpectedLength(NoiseXk.DH_LEN, message.size)
        }
        val re = message.copyOfRange(0, NoiseXk.DH_LEN)
        remoteEphemeral = re
        symm.mixHash(re)
        val rePub = NoiseXk.publicKeyFromBytes(re)
        val dh1 = NoiseXk.dh(localStatic.private, rePub)
        symm.mixKey(dh1)
        val firstPayload = symm.decryptAndHash(message.copyOfRange(NoiseXk.DH_LEN, message.size))

        // -> e, ee
        val e = NoiseXk.generateKeyPair()
        localEphemeral = e
        val ePub = NoiseXk.publicKeyBytes(e.public)
        symm.mixHash(ePub)
        val dh2 = NoiseXk.dh(e.private, rePub)
        symm.mixKey(dh2)
        val ct = symm.encryptAndHash(payload)
        step = 1
        return firstPayload to (ePub + ct)
    }

    /** Initiator: read message 2 (<- e, ee). */
    fun readSecond(message: ByteArray): ByteArray {
        check(role == Role.INITIATOR && step == 1) { "readSecond: wrong role/step" }
        if (message.size < NoiseXk.DH_LEN) {
            throw NoiseXk.NoiseException.UnexpectedLength(NoiseXk.DH_LEN, message.size)
        }
        val re = message.copyOfRange(0, NoiseXk.DH_LEN)
        remoteEphemeral = re
        symm.mixHash(re)
        val e = checkNotNull(localEphemeral) { "no localEphemeral" }
        val dh = NoiseXk.dh(e.private, NoiseXk.publicKeyFromBytes(re))
        symm.mixKey(dh)
        val payload = symm.decryptAndHash(message.copyOfRange(NoiseXk.DH_LEN, message.size))
        step = 2
        return payload
    }

    /** Initiator: write message 3 (-> s, se). */
    fun writeThird(payload: ByteArray = ByteArray(0)): ByteArray {
        check(role == Role.INITIATOR && step == 2) { "writeThird: wrong role/step" }
        val staticCipher = symm.encryptAndHash(localStaticPublic)
        val re = checkNotNull(remoteEphemeral) { "no remoteEphemeral" }
        val dh = NoiseXk.dh(localStatic.private, NoiseXk.publicKeyFromBytes(re))
        symm.mixKey(dh)
        val payloadCipher = symm.encryptAndHash(payload)
        step = 3
        return staticCipher + payloadCipher
    }

    /** Responder: read message 3 (-> s, se). */
    fun readThird(message: ByteArray): ByteArray {
        check(role == Role.RESPONDER && step == 1) { "readThird: wrong role/step" }
        val (_, payload) = readThirdInternal(message, pinRemoteStatic = true)
        return payload
    }

    /**
     * Pairing-only: read m3 without enforcing `decrypted_static == remoteStatic`.
     * Returns the freshly learned peer static pubkey (32 bytes). Use only
     * inside the pairing flow where we don't yet know the peer's static
     * key — authentication comes from the SAS the user confirms, not from
     * pre-pinning. Production sync sessions must use [readThird] instead.
     */
    fun unsafeReadThirdReturningStaticKey(message: ByteArray): ByteArray {
        check(role == Role.RESPONDER && step == 1) { "unsafeReadThird: wrong role/step" }
        val (rsBytes, _) = readThirdInternal(message, pinRemoteStatic = false)
        return rsBytes
    }

    private fun readThirdInternal(
        message: ByteArray,
        pinRemoteStatic: Boolean,
    ): Pair<ByteArray, ByteArray> {
        val staticCipherLen = NoiseXk.DH_LEN + NoiseXk.MAC_LEN
        if (message.size < staticCipherLen) {
            throw NoiseXk.NoiseException.UnexpectedLength(staticCipherLen, message.size)
        }
        val staticCipher = message.copyOfRange(0, staticCipherLen)
        val rsBytes = symm.decryptAndHash(staticCipher)
        if (rsBytes.size != NoiseXk.DH_LEN) {
            throw NoiseXk.NoiseException.UnexpectedLength(NoiseXk.DH_LEN, rsBytes.size)
        }
        if (pinRemoteStatic && !rsBytes.contentEquals(remoteStatic)) {
            throw NoiseXk.NoiseException.DecryptionFailed("peer static key mismatch")
        }
        // "se" token: DH between initiator.s and responder.e — responder uses
        // its own ephemeral private key.
        val e = checkNotNull(localEphemeral) { "no localEphemeral" }
        val dh = NoiseXk.dh(e.private, NoiseXk.publicKeyFromBytes(rsBytes))
        symm.mixKey(dh)
        val payload = symm.decryptAndHash(message.copyOfRange(staticCipherLen, message.size))
        step = 2
        return rsBytes to payload
    }

    /**
     * After both messages 2 & 3 are consumed, derive the transport pair.
     * Returns `(toSend, toReceive)` from the caller's perspective.
     *  - Initiator: first cipher is for sending, second for receiving.
     *  - Responder: vice-versa (Noise spec).
     */
    fun transportCiphers(): Pair<NoiseCipherState, NoiseCipherState> {
        val (a, b) = symm.split()
        return when (role) {
            Role.INITIATOR -> a to b
            Role.RESPONDER -> b to a
        }
    }
}
