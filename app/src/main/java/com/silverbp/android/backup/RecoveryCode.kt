package com.silverbp.android.backup

import java.security.SecureRandom

/**
 * Crockford Base32 編解碼 + UX-friendly grouping.
 *
 * 用途: 把 256 bits 隨機熵編成 52 個字元的恢復碼,讓使用者抄在紙上或記事本裡;
 * 之後在新裝置/重灌後輸入,經由 [BackupCrypto.deriveKekArgon2id] 推導出 KEK,
 * 解開 `.sbpbk` 備份檔的雙重包裝 DEK。
 *
 * Crockford Base32 字母表 (32 字元):
 *   `0-9, A-Z` 去掉 `I, L, O, U` — 與數字/形近字混淆的字元都被踢掉。
 *
 * 解碼時的容錯正規化(常見手寫/OCR 錯誤):
 *   - `I, i, L, l` → `1`
 *   - `O, o`       → `0`
 *   - `U, u`       → `V`(Crockford 規格,避免和 V 混淆;極少出現但要正確)
 *   - 大小寫不敏感
 *   - 忽略連字號 / 空白 / 其他不在字母表的字元
 *
 * 顯示時以 4 字一組用連字號分隔:`A4G7-K9PR-XQM2-VHN8-...`(13 組整除)。
 *
 * 沒有顯式 checksum: AES-GCM 在解密時就會偵測錯誤 KEK(2^128 的 tag 防護)。
 * Argon2id KDF 大約 1 秒 — 比顯式 checksum 慢一個量級,但實際 UX 影響小。
 *
 * 與 iOS BPCoach 對等實作: 字母表/正規化規則需逐字一致。Swift 端 ~30 行可寫完。
 */
object RecoveryCode {

    /** 隨機熵長度(byte),= 256 bits. */
    const val ENTROPY_BYTES = 32

    /** 編碼後字元數 (256 bits / 5 bits per char = 51.2,需 52 chars,最後 4 bits 為 0). */
    const val ENCODED_CHAR_COUNT = 52

    /** 顯示分組大小. */
    const val GROUP_SIZE = 4

    /** 32 字元字母表 — 順序固定,iOS 必須對齊. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 反查表 — 給定字元回傳其在字母表的索引 (0..31),或 -1 表示無效字元. */
    private val DECODE_TABLE: IntArray = IntArray(128) { -1 }.also { table ->
        for ((i, c) in ALPHABET.withIndex()) {
            table[c.code] = i
            table[c.lowercaseChar().code] = i
        }
        // Crockford 容錯: I/L → 1, O → 0, U → V.
        table['I'.code] = ALPHABET.indexOf('1'); table['i'.code] = ALPHABET.indexOf('1')
        table['L'.code] = ALPHABET.indexOf('1'); table['l'.code] = ALPHABET.indexOf('1')
        table['O'.code] = ALPHABET.indexOf('0'); table['o'.code] = ALPHABET.indexOf('0')
        table['U'.code] = ALPHABET.indexOf('V'); table['u'.code] = ALPHABET.indexOf('V')
    }

    private val random = SecureRandom()

    /**
     * 產生 256-bit 隨機熵的恢復碼(52 個 Base32 字元,未加分組連字號).
     * 用 [formatGrouped] 來取得使用者看得到的形式.
     */
    fun generate(): String {
        val entropy = ByteArray(ENTROPY_BYTES).also { random.nextBytes(it) }
        return encode(entropy)
    }

    /**
     * 把 32 byte 熵編成 52 個字元(未分組).
     * Big-endian 視 [entropy] 為 256 bits 的整數,每 5 bits 取 1 字元.
     */
    fun encode(entropy: ByteArray): String {
        require(entropy.size == ENTROPY_BYTES) {
            "entropy must be $ENTROPY_BYTES bytes, got ${entropy.size}"
        }
        val sb = StringBuilder(ENCODED_CHAR_COUNT)
        var buffer = 0L
        var bitsInBuffer = 0
        for (b in entropy) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                val index = ((buffer shr bitsInBuffer) and 0x1F).toInt()
                sb.append(ALPHABET[index])
            }
        }
        if (bitsInBuffer > 0) {
            // 剩下 1..4 bits — 左移到 5 bit 的高位.
            val index = ((buffer shl (5 - bitsInBuffer)) and 0x1F).toInt()
            sb.append(ALPHABET[index])
        }
        check(sb.length == ENCODED_CHAR_COUNT) {
            "encode produced ${sb.length} chars, expected $ENCODED_CHAR_COUNT"
        }
        return sb.toString()
    }

    /**
     * 把使用者輸入的字串解回 32 byte 熵.
     * 忽略連字號、空白、非字母表字元;套用 Crockford 容錯正規化(I→1, O→0 ...).
     * 回傳 null 表示字元數不對(編碼必為 52 個有效字元)或包含非容錯的無效字元.
     */
    fun decode(input: String): ByteArray? {
        val indices = IntArray(ENCODED_CHAR_COUNT)
        var count = 0
        for (c in input) {
            if (c.isWhitespace() || c == '-' || c == '_') continue
            if (c.code !in DECODE_TABLE.indices) return null
            val idx = DECODE_TABLE[c.code]
            if (idx < 0) return null
            if (count >= ENCODED_CHAR_COUNT) return null  // 過長
            indices[count++] = idx
        }
        if (count != ENCODED_CHAR_COUNT) return null

        val out = ByteArray(ENTROPY_BYTES)
        var buffer = 0L
        var bitsInBuffer = 0
        var outIdx = 0
        for (i in 0 until ENCODED_CHAR_COUNT) {
            buffer = (buffer shl 5) or indices[i].toLong()
            bitsInBuffer += 5
            while (bitsInBuffer >= 8 && outIdx < ENTROPY_BYTES) {
                bitsInBuffer -= 8
                out[outIdx++] = ((buffer shr bitsInBuffer) and 0xFF).toByte()
            }
        }
        check(outIdx == ENTROPY_BYTES) { "decode produced $outIdx bytes, expected $ENTROPY_BYTES" }
        return out
    }

    /**
     * 把 52 字元的編碼以 4 字一組用 `-` 分隔顯示,例: `A4G7-K9PR-XQM2-...`(共 13 組).
     */
    fun formatGrouped(encoded: String): String {
        require(encoded.length == ENCODED_CHAR_COUNT)
        return encoded.chunked(GROUP_SIZE).joinToString("-")
    }

    /**
     * UX 便利函式: 把使用者輸入的字串清理為「只剩字母表字元」的純編碼形式,
     * 用於和原始 generate() 結果比對(例如「重新輸入第 N 組」的驗證).
     */
    fun canonicalize(input: String): String {
        val sb = StringBuilder(ENCODED_CHAR_COUNT)
        for (c in input) {
            if (c.isWhitespace() || c == '-' || c == '_') continue
            if (c.code !in DECODE_TABLE.indices) continue
            val idx = DECODE_TABLE[c.code]
            if (idx < 0) continue
            sb.append(ALPHABET[idx])
        }
        return sb.toString()
    }

    /**
     * 取出第 [groupIndex] 組 (0-based) 的 4 個字元 — 給「請重新輸入第 3、7、12 組」用.
     * groupIndex 範圍 0..12(13 組).
     */
    fun groupAt(encoded: String, groupIndex: Int): String {
        require(encoded.length == ENCODED_CHAR_COUNT)
        require(groupIndex in 0 until (ENCODED_CHAR_COUNT / GROUP_SIZE))
        val start = groupIndex * GROUP_SIZE
        return encoded.substring(start, start + GROUP_SIZE)
    }
}
