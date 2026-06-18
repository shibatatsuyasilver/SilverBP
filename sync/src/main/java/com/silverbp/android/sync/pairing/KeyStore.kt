package com.silverbp.android.sync.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.silverbp.android.sync.transport.NoiseXk
import java.security.SecureRandom
import java.util.Base64

/**
 * Persistent store for paired-device root keys + this device's stable
 * identity (nodeId + long-term X25519 static key). Mirrors iOS
 * `KeychainPairingKeyStore`.
 *
 * Concrete impl uses [EncryptedSharedPreferences] backed by a Keystore-
 * wrapped master key (StrongBox where available). Each value (32-byte root
 * key, 8-byte nodeId, 32-byte static key) is base64-encoded for storage.
 */
interface PairingKeyStore {
    fun storeRootKey(key: ByteArray, deviceId: String)
    fun rootKey(deviceId: String): ByteArray?
    fun forget(deviceId: String)

    /**
     * Generate or load this device's stable nodeId (64 random bits) used in
     * every HLC we issue. Persisted alongside paired-device keys.
     */
    fun loadOrCreateNodeId(): Long

    /**
     * Long-term X25519 static keypair raw bytes for this device, used as
     * `localStatic` in every Noise XK handshake. Created on first use.
     *
     * Returns `(privRaw32, pubRaw32)`. JCA can't derive an X25519 pubkey
     * from the private scalar alone at runtime, so we persist both bytes
     * captured at generation time and reused as raw key material at use sites.
     */
    fun loadOrCreateStaticKey(): Pair<ByteArray, ByteArray>

    companion object {
        const val PREFS_NAME = "silverbp.sync.rootkeys"
        const val ACCOUNT_NODE_ID = "__device.nodeId"
        const val ACCOUNT_STATIC_PRIV = "__device.staticPriv"
        const val ACCOUNT_STATIC_PUB = "__device.staticPub"
        const val MIN_ROOT_KEY_BYTES = 16
    }
}

/**
 * Default impl. Backed by [EncryptedSharedPreferences]; safe to construct
 * once per app process and reuse.
 */
class EncryptedPairingKeyStore(
    private val prefs: SharedPreferences,
    private val random: SecureRandom = SecureRandom(),
) : PairingKeyStore {

    override fun storeRootKey(key: ByteArray, deviceId: String) {
        require(key.size >= PairingKeyStore.MIN_ROOT_KEY_BYTES) {
            "root key must be at least ${PairingKeyStore.MIN_ROOT_KEY_BYTES} bytes"
        }
        prefs.edit().putString(deviceId, Base64.getEncoder().encodeToString(key)).apply()
    }

    override fun rootKey(deviceId: String): ByteArray? {
        val s = prefs.getString(deviceId, null) ?: return null
        return runCatching { Base64.getDecoder().decode(s) }.getOrNull()
    }

    override fun forget(deviceId: String) {
        prefs.edit().remove(deviceId).apply()
    }

    override fun loadOrCreateNodeId(): Long {
        val existing = rootKey(PairingKeyStore.ACCOUNT_NODE_ID)
        if (existing != null && existing.size == 8) {
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (existing[i].toLong() and 0xFF)
            }
            return v
        }
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        prefs.edit().putString(
            PairingKeyStore.ACCOUNT_NODE_ID,
            Base64.getEncoder().encodeToString(bytes),
        ).apply()
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return v
    }

    override fun loadOrCreateStaticKey(): Pair<ByteArray, ByteArray> {
        val priv = rootKey(PairingKeyStore.ACCOUNT_STATIC_PRIV)
        val pub = rootKey(PairingKeyStore.ACCOUNT_STATIC_PUB)
        if (priv != null && priv.size == 32 && pub != null && pub.size == 32) {
            return priv to pub
        }
        // First use: generate raw X25519 bytes without relying on Android's
        // optional JCA provider support for X25519.
        val kp = NoiseXk.generateKeyPair()
        val privRaw = kp.privateKey
        val pubRaw = kp.publicKey
        prefs.edit().apply {
            putString(PairingKeyStore.ACCOUNT_STATIC_PRIV, Base64.getEncoder().encodeToString(privRaw))
            putString(PairingKeyStore.ACCOUNT_STATIC_PUB, Base64.getEncoder().encodeToString(pubRaw))
            apply()
        }
        return privRaw to pubRaw
    }

    companion object {
        /**
         * Production constructor. Creates an [EncryptedSharedPreferences]
         * instance using a Keystore-wrapped AES_256_GCM master key. The
         * resulting prefs file is read-only to this app + protected
         * by the device's hardware-backed Keystore where available.
         */
        fun create(context: Context): EncryptedPairingKeyStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PairingKeyStore.PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedPairingKeyStore(prefs)
        }
    }
}
