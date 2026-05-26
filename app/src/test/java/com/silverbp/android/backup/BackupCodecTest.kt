package com.silverbp.android.backup

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun sampleHlc(): Hlc =
        Hlc.of(physicalMs = 1_700_000_000_000L, logical = 0, nodeId = 0x1ABCDEF012345678L)

    private fun sampleManifest() = BackupCodec.Manifest(
        manifestVersion = 1,
        sourcePlatform = "android",
        schemaVersion = 12,
        hlcNodeIdHex = "deadbeefcafebabe",
        includesChat = true,
    )

    private fun sampleBpRecord(pk: String = "row-1") = SyncRecord(
        type = SyncEntityType.BP_READING,
        pk = pk,
        hlc = sampleHlc(),
        deletedAt = null,
        payload = mapOf(
            1 to SyncValue.Int64(120L),
            2 to SyncValue.Int64(80L),
            4 to SyncValue.Int64(1_700_000_000_000L),
            5 to SyncValue.Text("left"),
            10 to SyncValue.Double(0.92),
        ),
    )

    private fun sampleTombstone(pk: String = "row-deleted") = SyncRecord(
        type = SyncEntityType.BP_READING,
        pk = pk,
        hlc = sampleHlc(),
        deletedAt = 1_700_000_500_000L,
        payload = emptyMap(),
    )

    @Test
    fun `manifest round-trips`() {
        val m = sampleManifest()
        val bytes = BackupCodec.encodeManifest(m)
        val decoded = BackupCodec.decodeManifest(bytes)
        assertEquals(m.manifestVersion, decoded.manifestVersion)
        assertEquals(m.sourcePlatform, decoded.sourcePlatform)
        assertEquals(m.schemaVersion, decoded.schemaVersion)
        assertEquals(m.hlcNodeIdHex, decoded.hlcNodeIdHex)
        assertEquals(m.includesChat, decoded.includesChat)
    }

    @Test
    fun `payload round-trips with manifest + records + tombstone`() {
        val manifest = sampleManifest()
        val records = listOf(
            sampleBpRecord("row-1"),
            sampleBpRecord("row-2"),
            sampleTombstone("row-deleted"),
        )

        val bytes = BackupCodec.encodePayload(manifest, records)
        val (decodedManifest, decodedRecords) = BackupCodec.decodePayload(bytes)

        assertEquals(manifest.manifestVersion, decodedManifest.manifestVersion)
        assertEquals(records.size, decodedRecords.size)
        for ((orig, dec) in records.zip(decodedRecords)) {
            assertEquals(orig.type, dec.type)
            assertEquals(orig.pk, dec.pk)
            assertEquals(orig.hlc.packed, dec.hlc.packed)
            assertEquals(orig.deletedAt, dec.deletedAt)
        }
    }

    @Test
    fun `payload tolerates zero records (manifest-only)`() {
        val bytes = BackupCodec.encodePayload(sampleManifest(), emptyList())
        val (_, decoded) = BackupCodec.decodePayload(bytes)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `header encode-decode with keystore wrap`() {
        val header = BackupCodec.Header(
            formatVersion = 1,
            sourcePlatform = "android",
            sourceAppVer = "1.0+1",
            schemaVersion = 12,
            contentVersion = 1,
            createdAtMs = 1_700_000_000_000L,
            payloadSize = 4096L,
            kdfSalt = ByteArray(16) { it.toByte() },
            kdfParams = BackupCrypto.KdfParams(memKib = 65536, iterations = 3, parallelism = 1),
            aeadAlg = "AES-256-GCM",
            aeadNonce = ByteArray(12) { (it + 1).toByte() },
            keystoreWrap = BackupCodec.KeystoreWrapRef(
                alias = "silverbp.backup.v1",
                wrap = BackupCrypto.KeyWrap(
                    iv = ByteArray(12) { (it * 2).toByte() },
                    ciphertextWithTag = ByteArray(48) { (it * 3).toByte() },
                ),
            ),
            recoveryWrap = BackupCodec.RecoveryWrapRef(
                BackupCrypto.KeyWrap(
                    iv = ByteArray(12) { (it * 4).toByte() },
                    ciphertextWithTag = ByteArray(48) { (it * 5).toByte() },
                ),
            ),
            recordCount = 42,
            includesChat = false,
        )
        val bytes = BackupCodec.encodeHeader(header)
        val decoded = BackupCodec.decodeHeader(bytes)

        assertEquals(header.formatVersion, decoded.formatVersion)
        assertEquals(header.sourcePlatform, decoded.sourcePlatform)
        assertEquals(header.sourceAppVer, decoded.sourceAppVer)
        assertEquals(header.schemaVersion, decoded.schemaVersion)
        assertEquals(header.payloadSize, decoded.payloadSize)
        assertArrayEquals(header.kdfSalt, decoded.kdfSalt)
        assertEquals(header.kdfParams.memKib, decoded.kdfParams.memKib)
        assertEquals(header.kdfParams.iterations, decoded.kdfParams.iterations)
        assertEquals(header.aeadAlg, decoded.aeadAlg)
        assertArrayEquals(header.aeadNonce, decoded.aeadNonce)
        assertNotNull(decoded.keystoreWrap)
        assertEquals(header.keystoreWrap!!.alias, decoded.keystoreWrap!!.alias)
        assertArrayEquals(header.keystoreWrap!!.wrap.iv, decoded.keystoreWrap!!.wrap.iv)
        assertArrayEquals(
            header.keystoreWrap!!.wrap.ciphertextWithTag,
            decoded.keystoreWrap!!.wrap.ciphertextWithTag,
        )
        assertArrayEquals(header.recoveryWrap.wrap.iv, decoded.recoveryWrap.wrap.iv)
        assertEquals(header.recordCount, decoded.recordCount)
        assertEquals(header.includesChat, decoded.includesChat)
    }

    @Test
    fun `header encode-decode without keystore wrap`() {
        val header = BackupCodec.Header(
            formatVersion = 1,
            sourcePlatform = "ios",
            sourceAppVer = "1.0+1",
            schemaVersion = 12,
            contentVersion = 1,
            createdAtMs = 0L,
            payloadSize = 0L,
            kdfSalt = ByteArray(16),
            kdfParams = BackupCrypto.KdfParams(),
            aeadAlg = "AES-256-GCM",
            aeadNonce = ByteArray(12),
            keystoreWrap = null,
            recoveryWrap = BackupCodec.RecoveryWrapRef(
                BackupCrypto.KeyWrap(ByteArray(12), ByteArray(48)),
            ),
            recordCount = 0,
            includesChat = true,
        )
        val bytes = BackupCodec.encodeHeader(header)
        val decoded = BackupCodec.decodeHeader(bytes)

        assertNull(decoded.keystoreWrap)
        assertEquals(header.sourcePlatform, decoded.sourcePlatform)
    }
}
