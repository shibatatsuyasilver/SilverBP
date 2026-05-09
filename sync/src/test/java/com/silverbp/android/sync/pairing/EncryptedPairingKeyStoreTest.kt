package com.silverbp.android.sync.pairing

import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/**
 * Tests the storage logic of [EncryptedPairingKeyStore] without requiring
 * Android Context or Keystore — we drop in a fake SharedPreferences so
 * pure-JVM JUnit can exercise the codec/persistence layer. The actual
 * EncryptedSharedPreferences integration is exercised on real devices.
 */
class EncryptedPairingKeyStoreTest {

    @Test
    fun store_and_retrieve_root_key_roundtrips() {
        val store = EncryptedPairingKeyStore(FakePrefs())
        val key = ByteArray(32) { 0xAA.toByte() }
        store.storeRootKey(key, "device-a")
        assertArrayEquals(key, store.rootKey("device-a"))
    }

    @Test
    fun overwrite_same_device_upserts() {
        val store = EncryptedPairingKeyStore(FakePrefs())
        store.storeRootKey(ByteArray(32) { 0x01 }, "device-a")
        store.storeRootKey(ByteArray(32) { 0x02 }, "device-a")
        assertArrayEquals(ByteArray(32) { 0x02 }, store.rootKey("device-a"))
    }

    @Test
    fun forget_device_removes_key() {
        val store = EncryptedPairingKeyStore(FakePrefs())
        store.storeRootKey(ByteArray(32) { 0xAA.toByte() }, "device-a")
        store.forget("device-a")
        assertNull(store.rootKey("device-a"))
    }

    @Test
    fun rejects_root_key_too_small() {
        val store = EncryptedPairingKeyStore(FakePrefs())
        assertThrows(IllegalArgumentException::class.java) {
            store.storeRootKey(ByteArray(8), "x")
        }
    }

    @Test
    fun nodeId_is_stable_across_calls() {
        val prefs = FakePrefs()
        val first = EncryptedPairingKeyStore(prefs).loadOrCreateNodeId()
        val second = EncryptedPairingKeyStore(prefs).loadOrCreateNodeId()
        assertEquals(
            "nodeId must persist across instances; otherwise HLCs from " +
                "different sessions could clash",
            first,
            second,
        )
        assertNotEquals(0L, first)
    }

    @Test
    fun static_keypair_is_stable_across_calls_and_32_bytes_each() {
        val prefs = FakePrefs()
        val first = EncryptedPairingKeyStore(prefs).loadOrCreateStaticKey()
        val second = EncryptedPairingKeyStore(prefs).loadOrCreateStaticKey()
        assertEquals(32, first.first.size)
        assertEquals(32, first.second.size)
        assertArrayEquals(first.first, second.first)
        assertArrayEquals(first.second, second.second)
    }

    @Test
    fun new_install_picks_a_different_nodeId_with_overwhelming_probability() {
        val a = EncryptedPairingKeyStore(FakePrefs(), random = SecureRandom()).loadOrCreateNodeId()
        val b = EncryptedPairingKeyStore(FakePrefs(), random = SecureRandom()).loadOrCreateNodeId()
        assertNotEquals(a, b)
    }

    // ---- in-memory SharedPreferences fake ----

    private class FakePrefs : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { data[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { data[key] = values }
            override fun putInt(key: String, value: Int) = apply { data[key] = value }
            override fun putLong(key: String, value: Long) = apply { data[key] = value }
            override fun putFloat(key: String, value: Float) = apply { data[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { data[key] = value }
            override fun remove(key: String) = apply { data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }
}
