package com.silverbp.android.sync.transport

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecordCodecTest {

    @Test
    fun roundtrip_live_record_with_mixed_payload() {
        val original = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "362c65d9-d66f-48bf-bafd-1c98e1d9bd81",
            hlc = Hlc.of(physicalMs = 0x0001_8be8_2f00L, logical = 0x0455, nodeId = 0x280e_00a8_cbac_0001L),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Int64(118),         // systolic
                2 to SyncValue.Int64(72),          // diastolic
                3 to SyncValue.Null,                // pulse — unknown
                4 to SyncValue.Int64(1_730_000_000_000L), // timestampMs
                5 to SyncValue.Text("left"),       // armRaw
                6 to SyncValue.Text("sitting"),    // postureRaw
                7 to SyncValue.Text("morning"),    // partOfDayRaw
                8 to SyncValue.Bool(true),         // beforeMedication
                9 to SyncValue.Null,                // photoFilename
                10 to SyncValue.Double(0.92),      // confidence
                11 to SyncValue.Text("manual"),    // sourceRaw
                12 to SyncValue.Text("快走 30 分鐘"), // note (UTF-8 multibyte)
                13 to SyncValue.Bool(false),       // irregularHeartbeat
                14 to SyncValue.Null,                // medicationId
                15 to SyncValue.Int64(1_730_000_000_500L), // createdAtMs
                16 to SyncValue.Int64(1_730_000_001_000L), // updatedAtMs
            ),
        )
        val bytes = SyncRecordCodec.encode(original)
        val decoded = SyncRecordCodec.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun roundtrip_tombstone() {
        val original = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "deadbeef-cafe-1234-5678-feedfacef00d",
            hlc = Hlc.of(0x0001_8be8_2f80L, 0, 0x1L),
            deletedAt = 1_730_000_002_000L,
            payload = emptyMap(),
        )
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
        assertTrue(decoded.isTombstone)
    }

    @Test
    fun encoding_is_deterministic_for_same_input() {
        val record = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "abc",
            hlc = Hlc.of(1L, 0, 0L),
            deletedAt = null,
            payload = mapOf(
                3 to SyncValue.Text("c"),
                1 to SyncValue.Text("a"),
                2 to SyncValue.Text("b"),
            ),
        )
        val a = SyncRecordCodec.encode(record)
        val b = SyncRecordCodec.encode(record)
        assertArrayEquals(a, b)
        // Verify keys came out sorted ascending: payload's first inner key
        // must be 1, then 2, then 3.
        val decoded = SyncRecordCodec.decode(a)
        val keys = decoded.payload.keys.toList()
        assertEquals(listOf(1, 2, 3), keys)
    }

    @Test
    fun decode_tolerates_unknown_map_keys() {
        // Manually craft a SyncRecord-shaped frame with an extra key 99
        // that the decoder should skip without error.
        val w = CborWriter()
        w.writeMapHeader(5)
        w.writeUInt(1L); w.writeUInt(SyncEntityType.BP_READING.tag.toLong())
        w.writeUInt(2L); w.writeText("pk-1")
        w.writeUInt(3L); w.writeText(Hlc.of(1L, 0, 0L).packed)
        w.writeUInt(5L); w.writeMapHeader(0)
        w.writeUInt(99L); w.writeText("future-field")
        val bytes = w.toByteArray()
        val decoded = SyncRecordCodec.decode(bytes)
        assertEquals("pk-1", decoded.pk)
    }

    /**
     * Cross-platform interop fixture. The same hex string is asserted by
     * `SyncRecordCodecTests.testInteropFixture()` in iOS BPSharingTests.
     * If you change payload field tags or HLC layout, update both sides
     * simultaneously — drift breaks Android↔iOS sync.
     */
    @Test
    fun interop_fixture_matches_canonical_hex_and_round_trips() {
        val record = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "abc",
            hlc = Hlc.of(physicalMs = 0x0000_18be_82f0L, logical = 0, nodeId = 1L),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Int64(118),
                2 to SyncValue.Int64(72),
            ),
        )
        val expected = "a4" +
            "0101" +
            "0263616263" +
            "0378203030303031386265383266303030303030303030303030303030303030303031" +
            "05a2011876021848"
        val encoded = SyncRecordCodec.encode(record)
        val hex = encoded.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals("Wire format drifted; sync interop with iOS will break", expected, hex)
        // Round-trip from the frozen fixture
        val fixtureBytes = expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = SyncRecordCodec.decode(fixtureBytes)
        assertEquals(record, decoded)
    }

    @Test
    fun cbor_uint_uses_smallest_length_encoding() {
        val w = CborWriter()
        w.writeUInt(0L)
        w.writeUInt(23L)
        w.writeUInt(24L)
        w.writeUInt(255L)
        w.writeUInt(256L)
        val bytes = w.toByteArray()
        // 0 → 0x00, 23 → 0x17, 24 → 0x18 0x18, 255 → 0x18 0xff,
        // 256 → 0x19 0x01 0x00
        val expected = byteArrayOf(
            0x00, 0x17,
            0x18, 0x18.toByte(),
            0x18, 0xFF.toByte(),
            0x19, 0x01, 0x00,
        )
        assertArrayEquals(expected, bytes)
    }
}
