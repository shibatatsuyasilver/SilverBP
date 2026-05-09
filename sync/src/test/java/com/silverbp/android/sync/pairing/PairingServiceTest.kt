package com.silverbp.android.sync.pairing

import android.content.SharedPreferences
import com.silverbp.android.sync.transport.MemoryPipe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingServiceTest {

    @Test
    fun full_pairing_flow_produces_matching_sas_and_persists_peer_key() = runTest {
        val storeShower = EncryptedPairingKeyStore(FakePrefs())
        val storeJoiner = EncryptedPairingKeyStore(FakePrefs())
        val pairShower = PairingService(storeShower)
        val pairJoiner = PairingService(storeJoiner)

        val showerStaticPub = storeShower.loadOrCreateStaticKey().second
        val joinerStaticPub = storeJoiner.loadOrCreateStaticKey().second

        val qr = QrPairingPayload(
            publicKey = showerStaticPub,
            bonjourServiceName = "device-shower",
            deviceId = "device-shower",
        )

        val (chShower, chJoiner) = MemoryPipe.create()
        val outcomes = coroutineScope {
            val s = async { pairShower.runHandshakeAsQrShower(chShower) }
            val j = async {
                pairJoiner.runHandshakeAsJoiner(
                    channel = chJoiner,
                    localDeviceId = "device-joiner",
                    scannedQR = qr,
                )
            }
            s.await() to j.await()
        }
        val (showerOutcome, joinerOutcome) = outcomes

        // Same SAS on both — proves transcripts matched.
        assertEquals(showerOutcome.sas, joinerOutcome.sas)
        assertArrayEquals(showerOutcome.handshakeHash, joinerOutcome.handshakeHash)

        // Each side learned the other's static pubkey.
        assertArrayEquals(joinerStaticPub, showerOutcome.peerStaticPub)
        assertArrayEquals(showerStaticPub, joinerOutcome.peerStaticPub)

        // Each side learned the other's deviceId.
        assertEquals("device-joiner", showerOutcome.peerDeviceId)
        assertEquals("device-shower", joinerOutcome.peerDeviceId)

        // Persist after user confirms SAS.
        pairShower.confirmAndPersist(showerOutcome)
        pairJoiner.confirmAndPersist(joinerOutcome)
        assertArrayEquals(joinerStaticPub, storeShower.rootKey("device-joiner"))
        assertArrayEquals(showerStaticPub, storeJoiner.rootKey("device-shower"))
    }

    @Test
    fun mitm_attack_produces_diverging_sas() = runTest {
        val realStore = EncryptedPairingKeyStore(FakePrefs())
        val attackerStore = EncryptedPairingKeyStore(FakePrefs())
        val realPair = PairingService(realStore)
        val attackerPair = PairingService(attackerStore)
        val realPub = realStore.loadOrCreateStaticKey().second
        val attackerPub = attackerStore.loadOrCreateStaticKey().second
        assertNotEquals(realPub.toList(), attackerPub.toList())

        val joinerStore = EncryptedPairingKeyStore(FakePrefs())
        val joinerA = PairingService(joinerStore)

        // Joiner scans an attacker-spoofed QR, runs handshake against attacker.
        val attackerQR = QrPairingPayload(
            publicKey = attackerPub,
            bonjourServiceName = "spoofed",
            deviceId = "spoofed",
        )
        val (chJ1, chA1) = MemoryPipe.create()
        val (spoofSas, _) = coroutineScope {
            val j = async {
                joinerA.runHandshakeAsJoiner(chJ1, "joiner", attackerQR).sas
            }
            val a = async { attackerPair.runHandshakeAsQrShower(chA1).sas }
            j.await() to a.await()
        }

        // Same joiner scanning the legitimate QR runs against real shower.
        val joinerB = PairingService(joinerStore)
        val realQR = QrPairingPayload(
            publicKey = realPub,
            bonjourServiceName = "real",
            deviceId = "real",
        )
        val (chJ2, chR) = MemoryPipe.create()
        val (realSas, _) = coroutineScope {
            val j = async {
                joinerB.runHandshakeAsJoiner(chJ2, "joiner", realQR).sas
            }
            val r = async { realPair.runHandshakeAsQrShower(chR).sas }
            j.await() to r.await()
        }

        // Different responder pubkeys ⇒ different transcripts ⇒ different
        // SAS values. User sees the mismatch and aborts.
        assertNotEquals("MITM must produce a different SAS", spoofSas, realSas)
    }

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
