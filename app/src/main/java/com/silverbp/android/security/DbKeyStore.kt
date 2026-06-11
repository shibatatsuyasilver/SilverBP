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

    /**
     * The stored passphrase, or null if encryption was never enabled.
     *
     * Canonical form is the **Base64 string** of 32 random bytes — not the raw
     * bytes — so the exact same characters feed SQLCipher's KDF in both places
     * it matters: Room's `SupportOpenHelperFactory(byte[])` and the migration's
     * `ATTACH DATABASE ... KEY '<passphrase>'`. Base64's alphabet is SQL-safe
     * (no quotes), and an ASCII string round-trips identically through both.
     */
    fun passphraseOrNull(): String? = prefs.getString(KEY_PASSPHRASE, null)

    /**
     * Returns the existing passphrase, creating + persisting a fresh one
     * (Base64 of 32 CSPRNG bytes) on first call.
     *
     * Persisted with synchronous `commit()`: this string is the only way to
     * open an encrypted DB, so it must be on disk before the migration
     * encrypts anything — an `apply()` lost to process death would strand the
     * freshly-encrypted data forever.
     */
    fun getOrCreatePassphrase(): String {
        passphraseOrNull()?.let { return it }
        val bytes = ByteArray(PASSPHRASE_BYTES).also { random.nextBytes(it) }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        prefs.edit().putString(KEY_PASSPHRASE, b64).commit()
        return b64
    }

    /**
     * Flip the "DB is encrypted" marker. Called by the migration engine
     * **only after** a verified atomic swap (encrypt) or restore (decrypt),
     * and by [DbCipherMigration.reconcileSwapOnStartup] when repairing an
     * interrupted swap.
     *
     * Synchronous `commit()`: this marker gates how Room opens the file, so
     * it must never lag the on-disk state across process death — an `apply()`
     * lost mid-flush left plain SQLite opening ciphertext → crash loop.
     */
    fun setDbEncrypted(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENCRYPTED, value).commit()
    }

    /**
     * Opt-out cleanup: drop the passphrase + marker. Call **only after** the
     * DB has been verifiably decrypted back to plaintext, otherwise the data
     * becomes unrecoverable. Synchronous `commit()` for the same reason as
     * [setDbEncrypted] — the marker gates DB-open behaviour.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_PASSPHRASE)
            .remove(KEY_ENCRYPTED)
            .commit()
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
