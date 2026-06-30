package com.silverbp.android.backup

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.CborWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

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
        val decodedPayload = BackupCodec.decodePayload(bytes)
        val decodedManifest = decodedPayload.manifest
        val decodedRecords = decodedPayload.records

        assertEquals(manifest.manifestVersion, decodedManifest.manifestVersion)
        assertEquals(records.size, decodedRecords.size)
        assertEquals(records.size, decodedPayload.rawRecordBlockCount)
        assertEquals(records.size, decodedPayload.knownRecordCount)
        assertEquals(0, decodedPayload.skippedUnknownRecordCount)
        for ((orig, dec) in records.zip(decodedRecords)) {
            assertEquals(orig.type, dec.type)
            assertEquals(orig.pk, dec.pk)
            assertEquals(orig.hlc.packed, dec.hlc.packed)
            assertEquals(orig.deletedAt, dec.deletedAt)
        }
    }

    // Regression for "restored exercise sessions have no route map": a
    // route_point record must survive the backup payload CBOR round-trip with
    // all of its geometry intact (sessionId, lat/lon, timestamp, nullable
    // altitude/speed). This path was previously untested end-to-end — the
    // exercise round-trip test used a fake DAO that returned no points.
    private fun sampleRoutePoint(
        pk: String,
        sessionId: String,
        lat: Double,
        lon: Double,
        altitude: Double? = 12.5,
        speed: Double? = 1.4,
    ) = SyncRecord(
        type = SyncEntityType.ROUTE_POINT,
        pk = pk,
        hlc = sampleHlc(),
        deletedAt = null,
        payload = buildMap {
            put(1, SyncValue.Text(sessionId))
            put(2, SyncValue.Int64(1_730_000_000_000L))
            put(3, SyncValue.Double(lat))
            put(4, SyncValue.Double(lon))
            put(5, SyncValue.Double(6.0))
            put(6, altitude?.let { SyncValue.Double(it) } ?: SyncValue.Null)
            put(7, speed?.let { SyncValue.Double(it) } ?: SyncValue.Null)
        },
    )

    @Test
    fun `route_point records survive payload round-trip with full geometry`() {
        val sessionId = "sess-1"
        val session = SyncRecord(
            type = SyncEntityType.EXERCISE_SESSION,
            pk = sessionId,
            hlc = sampleHlc(),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Text("walking"), 2 to SyncValue.Int64(1_730_000_000_000L)),
        )
        val records = listOf(
            session,
            sampleRoutePoint("rp-1", sessionId, lat = 25.0339, lon = 121.5645),
            sampleRoutePoint("rp-2", sessionId, lat = 25.0341, lon = 121.5650, altitude = null, speed = null),
        )

        val decoded = BackupCodec.decodePayload(BackupCodec.encodePayload(sampleManifest(), records))

        assertEquals(0, decoded.skippedUnknownRecordCount)
        assertEquals(records.size, decoded.knownRecordCount)
        val routes = decoded.records.filter { it.type == SyncEntityType.ROUTE_POINT }
        assertEquals("both route_point rows must survive decode", 2, routes.size)
        // Order preserved so session lands before its points on import.
        assertEquals(SyncEntityType.EXERCISE_SESSION, decoded.records.first().type)
        for ((orig, dec) in records.zip(decoded.records)) {
            assertEquals(orig.type, dec.type)
            assertEquals(orig.pk, dec.pk)
            assertEquals("payload must round-trip byte-for-byte for ${orig.pk}", orig.payload, dec.payload)
        }
    }

    @Test
    fun `payload tolerates zero records (manifest-only)`() {
        val bytes = BackupCodec.encodePayload(sampleManifest(), emptyList())
        val (_, decoded) = BackupCodec.decodePayload(bytes)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `payload skips unknown future record type but preserves raw count`() {
        val known = sampleBpRecord("known-row")
        val payload = appendRawRecordBlock(
            BackupCodec.encodePayload(sampleManifest(), listOf(known)),
            unknownFutureRecordBlock(),
        )

        val decoded = BackupCodec.decodePayload(payload)

        assertEquals(2, decoded.rawRecordBlockCount)
        assertEquals(1, decoded.knownRecordCount)
        assertEquals(1, decoded.skippedUnknownRecordCount)
        assertEquals(2, decoded.knownRecordCount + decoded.skippedUnknownRecordCount)
        assertEquals("known-row", decoded.records.single().pk)
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

    @Test
    fun `header validation rejects out-of-bounds KDF params before decrypt`() {
        val header = validImportHeader(
            kdfParams = BackupCrypto.KdfParams(
                memKib = BackupCrypto.KDF_MEM_KIB_MAX + 1,
                iterations = 3,
                parallelism = 1,
            ),
            payloadSize = 0L,
        )
        val decoded = BackupCodec.decodeHeader(BackupCodec.encodeHeader(header))

        assertFailsWithMessage("KDF memory") {
            BackupCodec.validateHeaderForImport(decoded, BackupCrypto.GCM_TAG_BYTES)
        }
    }

    private fun unknownFutureRecordBlock(): ByteArray {
        val w = CborWriter()
        w.writeMapHeader(4)
        w.writeUInt(1L); w.writeUInt(9_999L)
        w.writeUInt(2L); w.writeText("future-row")
        w.writeUInt(3L); w.writeText(sampleHlc().packed)
        w.writeUInt(5L); w.writeMapHeader(0)
        return w.toByteArray()
    }

    private fun appendRawRecordBlock(payload: ByteArray, block: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(payload)
            writeBlock(block)
        }.toByteArray()

    private fun ByteArrayOutputStream.writeBlock(block: ByteArray) {
        val len = block.size
        write((len ushr 24) and 0xFF)
        write((len ushr 16) and 0xFF)
        write((len ushr 8) and 0xFF)
        write(len and 0xFF)
        write(block)
    }

    private fun validImportHeader(
        kdfParams: BackupCrypto.KdfParams = BackupCrypto.KdfParams(),
        payloadSize: Long = 0L,
    ) = BackupCodec.Header(
        formatVersion = BackupContainer.FORMAT_VERSION,
        sourcePlatform = "android",
        sourceAppVer = "1.0+1",
        schemaVersion = 12,
        contentVersion = 1,
        createdAtMs = 1_700_000_000_000L,
        payloadSize = payloadSize,
        kdfSalt = ByteArray(BackupCrypto.KDF_SALT_BYTES),
        kdfParams = kdfParams,
        aeadAlg = BackupCrypto.AEAD_ALG_AES_256_GCM,
        aeadNonce = ByteArray(BackupCrypto.GCM_NONCE_BYTES),
        keystoreWrap = null,
        recoveryWrap = BackupCodec.RecoveryWrapRef(
            BackupCrypto.KeyWrap(
                ByteArray(BackupCrypto.GCM_NONCE_BYTES),
                ByteArray(BackupCrypto.KEY_WRAP_CIPHERTEXT_BYTES),
            ),
        ),
        recordCount = 0,
        includesChat = true,
    )

    private fun assertFailsWithMessage(messagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing '$messagePart'")
        } catch (t: IllegalArgumentException) {
            assertTrue("Unexpected message: ${t.message}", t.message?.contains(messagePart) == true)
        }
    }
}
