package com.silverbp.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM string encryption keyed by a hardware-backed Android Keystore
 * key. Used to protect the sensitive free-text DataStore fields (API key,
 * prompts, nickname) at rest when the user opts into app-lock + encryption.
 *
 * Same design contract as [DbKeyStore]: the Keystore key is **not**
 * `setUserAuthenticationRequired`-bound, so a failed/changed biometric never
 * invalidates it and background work can still read settings while the UI is
 * locked. Biometric is a separate UI gate, not this data key.
 *
 * Tokens are `base64( iv(12) || ciphertext||gcmTag )`. Callers prefix stored
 * values with [SENTINEL] so the read path can transparently tell an encrypted
 * value from a legacy plaintext one (tolerant during opt-in/opt-out).
 */
object KeystoreStringCipher {

    const val SENTINEL = "enc:v1:"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "silverbp.settings.v1"
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT setUserAuthenticationRequired — see kdoc.
                .build(),
        )
        return gen.generateKey()
    }

    /** Returns a [SENTINEL]-prefixed token. Empty input stays empty (cheap + avoids noise). */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return SENTINEL + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Reverse of [encrypt]. Returns "" if [token] can't be decrypted (key
     * gone / corrupt) rather than throwing — a lost free-text setting is
     * recoverable by the user; a crash loop is not.
     */
    fun decrypt(token: String): String {
        if (token.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(token.removePrefix(SENTINEL), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_LEN),
            )
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(SENTINEL)
}
