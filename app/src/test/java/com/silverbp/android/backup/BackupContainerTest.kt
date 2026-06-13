package com.silverbp.android.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class BackupContainerTest {

    @Test
    fun `write then read round-trips`() {
        val header = ByteArray(123) { it.toByte() }
        val payload = ByteArray(2048) { (it xor 0xA5).toByte() }

        val sink = ByteArrayOutputStream()
        BackupContainer.write(sink, header, payload)
        val bytes = sink.toByteArray()

        val parsed = BackupContainer.read(ByteArrayInputStream(bytes))
        assertEquals(BackupContainer.FORMAT_VERSION, parsed.version)
        assertArrayEquals(header, parsed.headerCbor)
        assertArrayEquals(payload, parsed.payloadCiphertext)
    }

    @Test
    fun `read rejects bad magic bytes`() {
        val bytes = ByteArray(20) { 0xFF.toByte() }
        assertThrows(IOException::class.java) {
            BackupContainer.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `read rejects unsupported version`() {
        val sink = ByteArrayOutputStream()
        sink.write(BackupContainer.MAGIC)
        sink.write(0x00); sink.write(0x07)  // version 7 — unsupported
        sink.write(0x00); sink.write(0x01)  // header_len = 1
        sink.write(0x00)  // header byte
        sink.write(ByteArray(16))  // payload
        assertThrows(IOException::class.java) {
            BackupContainer.read(ByteArrayInputStream(sink.toByteArray()))
        }
    }

    @Test
    fun `current write version is v2 (v18 multi-member)`() {
        assertEquals(2, BackupContainer.FORMAT_VERSION)
    }

    @Test
    fun `read accepts older v1 container (backward compat)`() {
        // A pre-v18 (.sbpbk v1) backup must still import — the container framing
        // is identical across versions; only the encrypted payload differs.
        val sink = ByteArrayOutputStream()
        sink.write(BackupContainer.MAGIC)
        sink.write(0x00); sink.write(BackupContainer.MIN_SUPPORTED_VERSION)  // version 1
        sink.write(0x00); sink.write(0x01)  // header_len = 1
        sink.write(0x42)  // header byte
        sink.write(ByteArray(16) { it.toByte() })  // payload
        val parsed = BackupContainer.read(ByteArrayInputStream(sink.toByteArray()))
        assertEquals(BackupContainer.MIN_SUPPORTED_VERSION, parsed.version)
    }

    @Test
    fun `read rejects truncated file`() {
        // 只寫 magic 不寫 version
        val bytes = BackupContainer.MAGIC
        assertThrows(IOException::class.java) {
            BackupContainer.read(ByteArrayInputStream(bytes))
        }
    }
}
