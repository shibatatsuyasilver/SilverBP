package com.silverbp.android.backup

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * `.sbpbk` 檔案外層 framing — pure bytes, 不認識加密內容.
 *
 * 佈局:
 * ```
 *   [8]  magic       "SBPBK\0\0\0"
 *   [2]  version     u16 big-endian (從 1 開始)
 *   [2]  header_len  u16 big-endian (CBOR header 長度)
 *   [N]  header      CBOR map(整數鍵; 內容由 [BackupCodec] 定義)
 *   [*]  payload     AES-256-GCM ciphertext || 16-byte tag (由 [BackupCrypto] 產生)
 * ```
 *
 * magic + version + header_len 刻意放在加密區外: 匯入時不用先解密就能驗證
 * 檔案類型/版本、決定走哪條解碼路徑.
 *
 * iOS BPCoach 必須產出 byte-identical 的容器格式 — 不要在這層做任何 platform-
 * specific 的事(沒有 UTF-8 BOM、沒有換行、沒有 padding).
 */
object BackupContainer {

    /** 8 byte magic: "SBPBK\0\0\0". */
    val MAGIC: ByteArray = byteArrayOf(
        0x53, 0x42, 0x50, 0x42, 0x4B, 0x00, 0x00, 0x00,
    )

    /** 容器格式版號. Header CBOR 的 `format_version` 鍵與此值需保持一致. */
    const val FORMAT_VERSION: Int = 1

    /** Header CBOR 最大長度 — u16 上限,留 32 KB 緩衝給未來欄位擴充. */
    const val MAX_HEADER_LEN: Int = 32 * 1024

    /** 解析後的容器(header + 加密 payload bytes). */
    data class Parsed(
        val version: Int,
        val headerCbor: ByteArray,
        val payloadCiphertext: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Parsed &&
                version == other.version &&
                headerCbor.contentEquals(other.headerCbor) &&
                payloadCiphertext.contentEquals(other.payloadCiphertext)

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + headerCbor.contentHashCode()
            result = 31 * result + payloadCiphertext.contentHashCode()
            return result
        }
    }

    /**
     * 寫入完整容器到 [out]. 不關閉 stream — caller 負責.
     *
     * @throws IllegalArgumentException [headerCbor] 過大(超過 [MAX_HEADER_LEN]).
     */
    fun write(out: OutputStream, headerCbor: ByteArray, payloadCiphertext: ByteArray) {
        require(headerCbor.size <= MAX_HEADER_LEN) {
            "header too large: ${headerCbor.size} bytes (max $MAX_HEADER_LEN)"
        }
        out.write(MAGIC)
        out.writeU16BE(FORMAT_VERSION)
        out.writeU16BE(headerCbor.size)
        out.write(headerCbor)
        out.write(payloadCiphertext)
        out.flush()
    }

    /**
     * 從 [input] 讀取完整容器. 不關閉 stream — caller 負責.
     * Payload 讀到 stream EOF 為止.
     *
     * @throws IOException magic 不符 / version 不支援 / header 超長 / 截斷
     */
    fun read(input: InputStream): Parsed {
        val magic = ByteArray(MAGIC.size)
        input.readFullyOrThrow(magic, "magic")
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("Not an .sbpbk file (magic mismatch: ${magic.toHex()})")
        }
        val version = input.readU16BE("version")
        // 目前只支援版本 1; 之後升版時這裡擴充 + format_version 鍵在 header 內也要更新.
        if (version != FORMAT_VERSION) {
            throw IOException("Unsupported .sbpbk version: $version (expected $FORMAT_VERSION)")
        }
        val headerLen = input.readU16BE("header_len")
        if (headerLen > MAX_HEADER_LEN) {
            throw IOException("Header too large: $headerLen bytes (max $MAX_HEADER_LEN)")
        }
        val header = ByteArray(headerLen)
        input.readFullyOrThrow(header, "header")
        val payload = input.readBytes()
        if (payload.isEmpty()) throw IOException("Payload is empty")
        return Parsed(version, header, payload)
    }

    // ---- internals ----

    private fun OutputStream.writeU16BE(value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun InputStream.readU16BE(field: String): Int {
        val hi = read(); val lo = read()
        if (hi < 0 || lo < 0) throw EOFException("unexpected EOF reading $field")
        return ((hi and 0xFF) shl 8) or (lo and 0xFF)
    }

    private fun InputStream.readFullyOrThrow(dst: ByteArray, field: String) {
        var read = 0
        while (read < dst.size) {
            val n = read(dst, read, dst.size - read)
            if (n < 0) throw EOFException("unexpected EOF reading $field (got $read/${dst.size})")
            read += n
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
