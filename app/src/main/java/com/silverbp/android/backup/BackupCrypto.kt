package com.silverbp.android.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for the `.sbpbk` encrypted backup format.
 *
 * Design (mirrors the locked plan):
 *  - Payload encryption: **AES-256-GCM**. Same JCA primitive iOS CryptoKit
 *    `AES.GCM` exposes, so a snapshot encrypted on Android decrypts on iOS
 *    bit-for-bit identically.
 *  - Random per-snapshot Data Encryption Key (DEK), 32 bytes.
 *  - DEK is **dual-wrapped** into the file header:
 *    1. `keystoreWrap` — DEK encrypted with a Keystore-bound AES key (alias
 *       [KEYSTORE_ALIAS]). Same-device fast unwrap; the key is destroyed on
 *       uninstall so this slot only helps in-place restores.
 *    2. `recoveryWrap` — DEK encrypted with an Argon2id-derived KEK from the
 *       user's 24-word BIP-39 recovery passphrase. The only path that survives
 *       a clean install or cross-device migration; therefore REQUIRED at first
 *       export setup.
 *
 * The Keystore alias is intentionally distinct from
 * [com.silverbp.android.security.KeystoreStringCipher]'s
 * `silverbp.settings.v1` so a future settings-cipher rotation can't
 * accidentally invalidate every existing backup snapshot on disk.
 */
object BackupCrypto {

    const val DEK_BYTES = 32
    const val KEK_BYTES = 32
    const val GCM_TAG_BITS = 128
    const val GCM_TAG_BYTES = 16
    const val GCM_NONCE_BYTES = 12
    const val KDF_SALT_BYTES = 16
    const val KEY_WRAP_CIPHERTEXT_BYTES = DEK_BYTES + GCM_TAG_BYTES

    const val KDF_ALG_ARGON2ID = "argon2id"
    const val AEAD_ALG_AES_256_GCM = "AES-256-GCM"

    const val KDF_MEM_KIB_MIN = 1_024
    const val KDF_MEM_KIB_MAX = 256 * 1_024
    const val KDF_ITERATIONS_MIN = 1
    const val KDF_ITERATIONS_MAX = 10
    const val KDF_PARALLELISM_MIN = 1
    const val KDF_PARALLELISM_MAX = 4
    const val MAX_PAYLOAD_BYTES: Long = 128L * 1024L * 1024L

    /** Keystore alias for the device-bound DEK wrapping key. */
    const val KEYSTORE_ALIAS = "silverbp.backup.v1"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()
    private val argon2 by lazy { Argon2Kt() }

    /** Argon2id tuning. OWASP "memory-constrained" defaults — tunable per file. */
    data class KdfParams(
        val memKib: Int = 65_536,
        val iterations: Int = 3,
        val parallelism: Int = 1,
        val alg: String = KDF_ALG_ARGON2ID,
    )

    /**
     * AES-256-GCM wrapped DEK. [iv] is 12 random bytes;
     * [ciphertextWithTag] is the 32-byte DEK ciphertext + 16-byte GCM tag.
     */
    data class KeyWrap(
        val iv: ByteArray,
        val ciphertextWithTag: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is KeyWrap && iv.contentEquals(other.iv) &&
                ciphertextWithTag.contentEquals(other.ciphertextWithTag)
        override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertextWithTag.contentHashCode()
    }

    // ---------------- random helpers ----------------

    fun newDek(): ByteArray = ByteArray(DEK_BYTES).also { random.nextBytes(it) }
    fun randomSalt(): ByteArray = ByteArray(KDF_SALT_BYTES).also { random.nextBytes(it) }
    fun randomNonce(): ByteArray = ByteArray(GCM_NONCE_BYTES).also { random.nextBytes(it) }

    // ---------------- KEK derivation ----------------

    /**
     * Argon2id KDF over the user's recovery passphrase. The 24-word BIP-39
     * mnemonic is normalised (NFC + lowercased + single-spaced) by
     * [RecoveryCode.normalize] before reaching this function so any future
     * canonicalisation rule applies on both encode and decode.
     */
    fun deriveKekArgon2id(passphrase: String, salt: ByteArray, params: KdfParams): ByteArray {
        validateKdfInputs(salt, params)
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = params.iterations,
            mCostInKibibyte = params.memKib,
            parallelism = params.parallelism,
            hashLengthInBytes = KEK_BYTES,
        )
        return result.rawHashAsByteArray()
    }

    // ---------------- DEK wrapping (recovery passphrase path) ----------------

    /** Wrap [dek] under a passphrase-derived KEK. Returns the resulting GCM wrap. */
    fun wrapDek(dek: ByteArray, kek: ByteArray): KeyWrap {
        require(dek.size == DEK_BYTES)
        require(kek.size == KEK_BYTES)
        val iv = randomNonce()
        val ct = aesGcmEncrypt(dek, SecretKeySpec(kek, "AES"), iv, aad = null)
        return KeyWrap(iv = iv, ciphertextWithTag = ct)
    }

    /** Unwrap with a passphrase-derived KEK. Returns null on auth failure. */
    fun unwrapDek(wrap: KeyWrap, kek: ByteArray): ByteArray? {
        require(kek.size == KEK_BYTES)
        return runCatching {
            aesGcmDecrypt(wrap.ciphertextWithTag, SecretKeySpec(kek, "AES"), wrap.iv, aad = null)
        }.getOrNull()
    }

    // ---------------- DEK wrapping (Keystore path) ----------------

    /**
     * Wrap [dek] under the device-bound [KEYSTORE_ALIAS] key, generating it
     * on first call. Used as the fast same-device unwrap slot.
     */
    fun wrapDekWithKeystore(dek: ByteArray): KeyWrap {
        require(dek.size == DEK_BYTES)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        val ct = cipher.doFinal(dek)
        return KeyWrap(iv = cipher.iv, ciphertextWithTag = ct)
    }

    /**
     * Unwrap with the Keystore alias. Returns null if the alias is missing
     * (e.g. fresh install) or AEAD authentication fails (different device).
     */
    fun unwrapDekWithKeystore(wrap: KeyWrap): ByteArray? {
        return runCatching {
            val key = loadKeystoreKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, wrap.iv),
            )
            cipher.doFinal(wrap.ciphertextWithTag)
        }.getOrNull()
    }

    // ---------------- payload encryption ----------------

    /**
     * Encrypt the snapshot payload. [aad] (the file header bytes) is
     * authenticated but not encrypted — tampering with the header
     * invalidates the AEAD tag and decryption fails.
     */
    fun encryptPayload(plaintext: ByteArray, dek: ByteArray, nonce: ByteArray, aad: ByteArray?): ByteArray =
        aesGcmEncrypt(plaintext, SecretKeySpec(dek, "AES"), nonce, aad)

    fun decryptPayload(ciphertextWithTag: ByteArray, dek: ByteArray, nonce: ByteArray, aad: ByteArray?): ByteArray =
        aesGcmDecrypt(ciphertextWithTag, SecretKeySpec(dek, "AES"), nonce, aad)

    fun validateKdfInputs(salt: ByteArray, params: KdfParams) {
        require(salt.size == KDF_SALT_BYTES) { "KDF salt must be $KDF_SALT_BYTES bytes" }
        require(params.alg == KDF_ALG_ARGON2ID) { "Unsupported KDF algorithm: ${params.alg}" }
        require(params.memKib in KDF_MEM_KIB_MIN..KDF_MEM_KIB_MAX) {
            "KDF memory out of range: ${params.memKib} KiB (allowed $KDF_MEM_KIB_MIN..$KDF_MEM_KIB_MAX)"
        }
        require(params.iterations in KDF_ITERATIONS_MIN..KDF_ITERATIONS_MAX) {
            "KDF iterations out of range: ${params.iterations} (allowed $KDF_ITERATIONS_MIN..$KDF_ITERATIONS_MAX)"
        }
        require(params.parallelism in KDF_PARALLELISM_MIN..KDF_PARALLELISM_MAX) {
            "KDF parallelism out of range: ${params.parallelism} (allowed $KDF_PARALLELISM_MIN..$KDF_PARALLELISM_MAX)"
        }
    }

    fun validateKeyWrap(wrap: KeyWrap, label: String) {
        require(wrap.iv.size == GCM_NONCE_BYTES) { "$label IV must be $GCM_NONCE_BYTES bytes" }
        require(wrap.ciphertextWithTag.size == KEY_WRAP_CIPHERTEXT_BYTES) {
            "$label ciphertext must be $KEY_WRAP_CIPHERTEXT_BYTES bytes"
        }
    }

    fun validatePayloadCiphertextSize(payloadSize: Long, ciphertextWithTagSize: Int) {
        require(payloadSize in 0..MAX_PAYLOAD_BYTES) {
            "Backup payload size out of range: $payloadSize bytes (max $MAX_PAYLOAD_BYTES)"
        }
        val expectedCiphertextSize = payloadSize + GCM_TAG_BYTES
        require(ciphertextWithTagSize.toLong() == expectedCiphertextSize) {
            "Backup payload ciphertext length mismatch: expected $expectedCiphertextSize bytes, got $ciphertextWithTagSize"
        }
    }

    // ---------------- internals ----------------

    private fun aesGcmEncrypt(plain: ByteArray, key: SecretKey, iv: ByteArray, aad: ByteArray?): ByteArray {
        require(iv.size == GCM_NONCE_BYTES) { "AES-GCM nonce must be $GCM_NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(ct: ByteArray, key: SecretKey, iv: ByteArray, aad: ByteArray?): ByteArray {
        require(iv.size == GCM_NONCE_BYTES) { "AES-GCM nonce must be $GCM_NONCE_BYTES bytes" }
        require(ct.size >= GCM_TAG_BYTES) { "AES-GCM ciphertext is missing its authentication tag" }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        loadKeystoreKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not biometric-bound: background work must be
                // able to re-derive after foreground reauth in the same way
                // KeystoreStringCipher does. The UI gate is separate.
                .build(),
        )
        return gen.generateKey()
    }

    private fun loadKeystoreKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }
}
