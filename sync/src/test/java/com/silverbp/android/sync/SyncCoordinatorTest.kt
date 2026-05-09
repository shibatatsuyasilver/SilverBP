package com.silverbp.android.sync

import android.content.SharedPreferences
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.pairing.EncryptedPairingKeyStore
import com.silverbp.android.sync.protocol.SyncRecordSink
import com.silverbp.android.sync.protocol.SyncRecordSource
import com.silverbp.android.sync.transport.MemoryPipe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCoordinatorTest {

    @Test
    fun two_coordinators_complete_one_sync_round_with_persisted_keys() = runTest {
        val storeA = EncryptedPairingKeyStore(FakePrefs())
        val storeB = EncryptedPairingKeyStore(FakePrefs())
        val coordA = SyncCoordinator(deviceId = "device-a", keyStore = storeA)
        val coordB = SyncCoordinator(deviceId = "device-b", keyStore = storeB)

        // Seed each side with one record nobody else has.
        val recA = bpRecord("A-1", physMs = 1_700_000_000_000, nodeId = coordA.nodeId)
        val recB = bpRecord("B-1", physMs = 1_700_000_000_500, nodeId = coordB.nodeId)
        val sourceA = mutableListOf(recA)
        val sourceB = mutableListOf(recB)
        val sinkA = mutableListOf<SyncRecord>()
        val sinkB = mutableListOf<SyncRecord>()

        val (chA, chB) = MemoryPipe.create()

        // Run both sides concurrently — A initiator, B responder.
        coroutineScope {
            val ja = async {
                coordA.runSessionAsInitiator(
                    peerStaticPub = coordB.staticPublicKey,
                    channel = chA,
                    source = SyncRecordSource { peerHlc, _ -> sourceA.filter { it.hlc > peerHlc } },
                    sink = SyncRecordSink { sinkA.add(it) },
                    getLastHlcSeen = { Hlc.ZERO },
                    updateLastHlcSeen = { },
                )
            }
            val jb = async {
                coordB.runSessionAsResponder(
                    peerStaticPub = coordA.staticPublicKey,
                    channel = chB,
                    source = SyncRecordSource { peerHlc, _ -> sourceB.filter { it.hlc > peerHlc } },
                    sink = SyncRecordSink { sinkB.add(it) },
                    getLastHlcSeen = { Hlc.ZERO },
                    updateLastHlcSeen = { },
                )
            }
            ja.await()
            jb.await()
        }

        // Each side received the other's record.
        assertEquals(listOf(recB), sinkA)
        assertEquals(listOf(recA), sinkB)
    }

    @Test
    fun static_public_key_is_stable_across_coordinator_restarts() {
        val prefs = FakePrefs()
        val store = EncryptedPairingKeyStore(prefs)
        val first = SyncCoordinator(deviceId = "x", keyStore = store).staticPublicKey
        val second = SyncCoordinator(deviceId = "x", keyStore = store).staticPublicKey
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun nodeId_is_stable_across_coordinator_restarts() {
        val prefs = FakePrefs()
        val store = EncryptedPairingKeyStore(prefs)
        val first = SyncCoordinator(deviceId = "x", keyStore = store).nodeId
        val second = SyncCoordinator(deviceId = "x", keyStore = store).nodeId
        assertEquals(first, second)
    }

    private fun bpRecord(pk: String, physMs: Long, nodeId: Long): SyncRecord = SyncRecord(
        type = SyncEntityType.BP_READING,
        pk = pk,
        hlc = Hlc.of(physicalMs = physMs, logical = 0, nodeId = nodeId),
        deletedAt = null,
        payload = mapOf(
            1 to SyncValue.Int64(120),
            2 to SyncValue.Int64(80),
            4 to SyncValue.Int64(physMs),
        ),
    )

    // Same fake as EncryptedPairingKeyStoreTest.
    private class FakePrefs : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
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
