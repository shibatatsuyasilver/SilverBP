package com.silverbp.android.sync.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrPairingPayloadTest {

    @Test
    fun encode_decode_roundtrip_preserves_all_fields() {
        val original = QrPairingPayload(
            publicKey = ByteArray(32) { it.toByte() },
            bonjourServiceName = "device-a (Pixel)",
            deviceId = "android-cafebabe12345678",
        )
        val parsed = QrPairingPayload.parse(original.toUrl())
        assertEquals(original.version, parsed.version)
        assertArrayEquals(original.publicKey, parsed.publicKey)
        assertEquals(original.bonjourServiceName, parsed.bonjourServiceName)
        assertEquals(original.deviceId, parsed.deviceId)
    }

    @Test
    fun rejects_invalid_pubkey_length() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPairingPayload(
                publicKey = ByteArray(16),
                bonjourServiceName = "svc",
                deviceId = "did",
            )
        }
    }

    @Test
    fun rejects_blank_device_id() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPairingPayload(
                publicKey = ByteArray(32),
                bonjourServiceName = "svc",
                deviceId = "",
            )
        }
    }

    @Test
    fun parse_rejects_wrong_scheme() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            QrPairingPayload.parse("https://pair?v=1&pk=AA&svc=x&did=y")
        }
        assert(ex.message!!.contains("scheme"))
    }

    @Test
    fun parse_rejects_unsupported_version() {
        val good = QrPairingPayload(
            publicKey = ByteArray(32),
            bonjourServiceName = "svc",
            deviceId = "did",
        ).toUrl()
        val bumped = good.replace("v=1", "v=99")
        assertThrows(IllegalArgumentException::class.java) {
            QrPairingPayload.parse(bumped)
        }
    }

    /**
     * Cross-platform fixture: iOS `QRPairingPayloadTests.testParseAndroidFixture`
     * decodes the same URL and asserts identical fields. Drift on either
     * side breaks pairing.
     */
    @Test
    fun parses_canonical_fixture() {
        val payload = QrPairingPayload(
            publicKey = byteArrayOf(
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
            ),
            bonjourServiceName = "ios-aabbccdd11223344",
            deviceId = "ios-aabbccdd11223344",
        )
        val expected =
            "silverbp://pair?v=1" +
                "&pk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA" +
                "&svc=ios-aabbccdd11223344" +
                "&did=ios-aabbccdd11223344"
        assertEquals(expected, payload.toUrl())
        val parsed = QrPairingPayload.parse(expected)
        assertArrayEquals(payload.publicKey, parsed.publicKey)
        assertEquals(payload.bonjourServiceName, parsed.bonjourServiceName)
        assertEquals(payload.deviceId, parsed.deviceId)
    }
}
