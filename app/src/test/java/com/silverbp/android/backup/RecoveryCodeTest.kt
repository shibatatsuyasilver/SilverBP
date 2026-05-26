package com.silverbp.android.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class RecoveryCodeTest {

    @Test
    fun `encode then decode round-trips entropy bytes`() {
        val random = SecureRandom()
        repeat(50) {
            val entropy = ByteArray(RecoveryCode.ENTROPY_BYTES).also { random.nextBytes(it) }
            val encoded = RecoveryCode.encode(entropy)
            assertEquals(RecoveryCode.ENCODED_CHAR_COUNT, encoded.length)
            val decoded = RecoveryCode.decode(encoded)
            assertNotNull(decoded)
            assertArrayEquals(entropy, decoded)
        }
    }

    @Test
    fun `decode tolerates hyphens spaces and lowercase`() {
        val entropy = ByteArray(RecoveryCode.ENTROPY_BYTES) { it.toByte() }
        val encoded = RecoveryCode.encode(entropy)
        val grouped = RecoveryCode.formatGrouped(encoded)

        // 加空白與連字號的多種變形都應該解出原值
        val variations = listOf(
            grouped,
            grouped.lowercase(),
            grouped.replace("-", " "),
            grouped.replace("-", " - "),
            grouped.replace("-", ""),
        )
        for (v in variations) {
            val decoded = RecoveryCode.decode(v)
            assertNotNull("decode($v) returned null", decoded)
            assertArrayEquals(entropy, decoded)
        }
    }

    @Test
    fun `decode normalises confusable characters I L O U`() {
        val entropy = ByteArray(RecoveryCode.ENTROPY_BYTES) { (it * 7).toByte() }
        val encoded = RecoveryCode.encode(entropy)

        // 把字串內所有 1 換成 I/L (使用者手寫常見錯誤)
        val withI = encoded.replace('1', 'I')
        val withL = encoded.replace('1', 'L')
        val with0 = encoded.replace('0', 'O')
        val withV = encoded.replace('V', 'U')

        assertArrayEquals(entropy, RecoveryCode.decode(withI))
        assertArrayEquals(entropy, RecoveryCode.decode(withL))
        assertArrayEquals(entropy, RecoveryCode.decode(with0))
        assertArrayEquals(entropy, RecoveryCode.decode(withV))
    }

    @Test
    fun `decode returns null for too short or too long input`() {
        assertNull(RecoveryCode.decode(""))
        assertNull(RecoveryCode.decode("A"))
        assertNull(RecoveryCode.decode("A".repeat(RecoveryCode.ENCODED_CHAR_COUNT - 1)))
        assertNull(RecoveryCode.decode("A".repeat(RecoveryCode.ENCODED_CHAR_COUNT + 1)))
    }

    @Test
    fun `groupAt returns correct 4-char slice`() {
        val encoded = "A".repeat(8) + "B".repeat(8) + "C".repeat(8) + "D".repeat(8) + "E".repeat(20)
        assertEquals(52, encoded.length)
        assertEquals("AAAA", RecoveryCode.groupAt(encoded, 0))
        assertEquals("AAAA", RecoveryCode.groupAt(encoded, 1))
        assertEquals("BBBB", RecoveryCode.groupAt(encoded, 2))
        assertEquals("BBBB", RecoveryCode.groupAt(encoded, 3))
    }

    @Test
    fun `formatGrouped produces 13 groups of 4 chars`() {
        val encoded = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" + "ABCDEFGHJKMNPQRSTVWX"
        assertEquals(RecoveryCode.ENCODED_CHAR_COUNT, encoded.length)
        val grouped = RecoveryCode.formatGrouped(encoded)
        val parts = grouped.split("-")
        assertEquals(13, parts.size)
        parts.forEach { assertEquals(4, it.length) }
    }

    @Test
    fun `generate produces decodable codes`() {
        repeat(20) {
            val code = RecoveryCode.generate()
            assertEquals(RecoveryCode.ENCODED_CHAR_COUNT, code.length)
            val decoded = RecoveryCode.decode(code)
            assertNotNull(decoded)
            assertEquals(RecoveryCode.ENTROPY_BYTES, decoded!!.size)
        }
    }

    @Test
    fun `canonicalize strips non-alphabet characters`() {
        val input = "A4G7  K9PR - XQM2_VHN8"
        val canonical = RecoveryCode.canonicalize(input)
        // I→1, O→0, U→V, lowercase→upper
        assertEquals("A4G7K9PRXQM2VHN8", canonical)
    }
}
