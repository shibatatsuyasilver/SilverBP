package com.silverbp.android.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Persistent store for the SQLCipher passphrase that encrypts the Room
 * database when the user opts into app-lock + at-rest encryption.
 *
 * Design (see notes/biometric-app-lock-plan.md — "Core design principle"):
 *
 *  - The passphrase is 32 random bytes, generated once on opt-in and held in
 *    [EncryptedSharedPreferences] (Keystore-wrapped, StrongBox where available).
 *    It is **NOT** derived from any user secret and the wrapping Keystore key
 *    is **NOT** `setUserAuthenticationRequired`-bound — a failed / reset / lock-
 *    ed-out fingerprint must never destroy health data, and background work
 *    (coach, reminders, sync) must be able to open the DB while the UI is
 *    locked. Biometric is a separate UI gate, not the data key.
 *
 *  - [isDbEncrypted] is the **synchronous source of truth** for how Room must
 *    open the file. It lives here (not in DataStore) precisely because
 *    `SilverBpDatabase.get()` resolves it on the calling thread before the
 *    first DAO access, and an EncryptedSharedPreferences read is synchronous.
 *    The Settings `appLockEnabled` flag is the UI toggle; this marker is
 *    flipped only by the migration engine once a swap is verified.
 *
 * Mirrors the existing sync key pattern
 * ([com.silverbp.android.sync.pairing.EncryptedPairingKeyStore]); a separate
 * prefs file keeps the DB key isolated from the Noise sync keys.
 */
class DbKeyStore(
    private val prefs: SharedPreferences,
    private val random: SecureRandom = SecureRandom(),
) {

    /** True once the on-disk `silverbp.db` is SQLCipher-encrypted. */
    fun isDbEncrypted(): Boolean = prefs.getBoolean(KEY_ENCRYPTED, false)

    /** The stored passphrase, or null if encryption was never enabled. */
    fun passphraseOrNull(): ByteArray? {
        val s = prefs.getString(KEY_PASSPHRASE, null) ?: return null
        return runCatching { Base64.getDecoder().decode(s) }.getOrNull()
    }

    /**
     * Returns the existing passphrase, creating + persisting a fresh 32-byte
     * random one on first call. Each call returns a defensive copy because
     * SQLCipher's `SupportOpenHelperFactory` zeroes the array it is handed.
     */
    fun getOrCreatePassphrase(): ByteArray {
        passphraseOrNull()?.let { return it.copyOf() }
        val bytes = ByteArray(PASSPHRASE_BYTES).also { random.nextBytes(it) }
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.getEncoder().encodeToString(bytes))
            .apply()
        return bytes.copyOf()
    }

    /**
     * Flip the "DB is encrypted" marker. Called by the migration engine
     * **only after** a verified atomic swap (encrypt) or restore (decrypt).
     */
    fun setDbEncrypted(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENCRYPTED, value).apply()
    }

    /**
     * Opt-out cleanup: drop the passphrase + marker. Call **only after** the
     * DB has been verifiably decrypted back to plaintext, otherwise the data
     * becomes unrecoverable.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_PASSPHRASE)
            .remove(KEY_ENCRYPTED)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "silverbp.dbkey"
        const val KEY_PASSPHRASE = "__db.passphrase"
        const val KEY_ENCRYPTED = "__db.encrypted"
        const val PASSPHRASE_BYTES = 32

        /**
         * Production constructor: [EncryptedSharedPreferences] backed by a
         * Keystore-wrapped AES-256-GCM master key (StrongBox where available),
         * AES256-SIV key encryption + AES256-GCM value encryption — same
         * scheme as the sync key store.
         */
        fun create(context: Context): DbKeyStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return DbKeyStore(prefs)
        }
    }
}
