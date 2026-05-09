package com.silverbp.android.sync.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SasTest {

    @Test
    fun sas_is_six_digits_zero_padded() {
        val rootKey = ByteArray(32) { 0 }
        val pkA = ByteArray(32) { 0x01 }
        val pkB = ByteArray(32) { 0x02 }
        val sas = Sas.derive(rootKey, pkA, pkB)
        assertEquals(6, sas.length)
        assertEquals(sas, sas.toLong().toString().padStart(6, '0'))
    }

    @Test
    fun sas_is_symmetric_with_respect_to_initiator_joiner_order() {
        val rootKey = ByteArray(32) { it.toByte() }
        val pkA = ByteArray(32) { (it + 100).toByte() }
        val pkB = ByteArray(32) { (it + 200).toByte() }
        val left = Sas.derive(rootKey, pkA, pkB)
        val right = Sas.derive(rootKey, pkB, pkA)
        assertEquals("SAS must be order-independent (sorts pubkeys)", left, right)
    }

    @Test
    fun sas_diverges_when_root_key_differs() {
        val pkA = ByteArray(32) { 0x10 }
        val pkB = ByteArray(32) { 0x20 }
        val s1 = Sas.derive(ByteArray(32) { 0xAA.toByte() }, pkA, pkB)
        val s2 = Sas.derive(ByteArray(32) { 0xBB.toByte() }, pkA, pkB)
        assertNotEquals("MITM with different root key must produce different SAS", s1, s2)
    }

    @Test
    fun fromHandshakeHash_is_six_digits() {
        val sas = Sas.fromHandshakeHash(ByteArray(32) { it.toByte() })
        assertEquals(6, sas.length)
    }

    @Test
    fun fromHandshakeHash_canonical_fixture() {
        // Frozen: iOS `SASTests.testFromHandshakeHashCanonicalFixture` must
        // produce the same code for the same input.
        val hash = ByteArray(32) { it.toByte() }  // 0x00, 0x01, ..., 0x1f
        // First 4 bytes = 00 01 02 03 = 0x00010203 = 66051; mod 1_000_000 = 66051
        assertEquals("066051", Sas.fromHandshakeHash(hash))
    }
}
