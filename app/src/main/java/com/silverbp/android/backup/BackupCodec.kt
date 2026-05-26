package com.silverbp.android.backup

import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.transport.CborReader
import com.silverbp.android.sync.transport.CborWriter
import com.silverbp.android.sync.transport.SyncRecordCodec
import java.io.ByteArrayOutputStream

/**
 * `.sbpbk` 內容編解碼.
 *
 * 兩個關注點:
 *  1. **Header CBOR** — 容器外層 framing 之後立刻接著的 CBOR map,描述加密參數
 *     和 metadata. 內容為整數鍵以維持跨平台對等(iOS 端鍵也是整數).
 *  2. **明文載荷(在 AES-GCM 解密後)** — 採用 length-prefixed CBOR blocks:
 *     ```
 *       manifest_len_u32_be || manifest_cbor
 *       record_len_u32_be   || record_cbor   (×N)
 *     ```
 *     第一個 block 永遠是 manifest;之後每個 block 都是 SyncRecord 編碼,
 *     SyncRecord 自帶 `deletedAt` 可區分 live record vs tombstone.
 *     讀到 EOF 表示沒有更多 record. 不用 CBOR array 是因為它的 header 需要
 *     pre-known 總筆數,對 streaming export 不友善.
 *
 * 每個 record block 的 byte 即是 [SyncRecordCodec.encode] 的位元組原樣輸出 —
 * 整個專案唯一一條 record 對應路徑.
 */
object BackupCodec {

    /** Header CBOR 整數鍵 — 與 plan 對齊,iOS 要逐一鏡像. */
    private object HeaderKey {
        const val FORMAT_VERSION = 1
        const val SOURCE_PLATFORM = 2
        const val SOURCE_APP_VER = 3
        const val SCHEMA_VERSION = 4
        const val CONTENT_VERSION = 5
        const val CREATED_AT_MS = 6
        const val PAYLOAD_SIZE = 7
        const val KDF_SALT = 8
        const val KDF_PARAMS = 9
        const val AEAD_ALG = 10
        const val AEAD_NONCE = 11
        const val KEYSTORE_WRAP = 12
        const val RECOVERY_WRAP = 13
        const val RECORD_COUNT = 14
        const val INCLUDES_CHAT = 15
    }

    private object KdfParamKey {
        const val ALG = 1
        const val MEM_KIB = 2
        const val ITERS = 3
        const val PARALLELISM = 4
    }

    private object WrapKey {
        const val ALIAS = 1
        const val IV = 2
        const val CIPHERTEXT = 3
    }

    private object ManifestKey {
        const val MANIFEST_VERSION = 1
        const val SOURCE_PLATFORM = 2
        const val SCHEMA_VERSION = 3
        const val HLC_NODE_ID_HEX = 4
        const val INCLUDES_CHAT = 5
    }

    /** Header CBOR 內容 — encrypted payload 的 metadata + key wrap. */
    data class Header(
        val formatVersion: Int,
        val sourcePlatform: String,
        val sourceAppVer: String,
        val schemaVersion: Int,
        val contentVersion: Int,
        val createdAtMs: Long,
        val payloadSize: Long,
        val kdfSalt: ByteArray,
        val kdfParams: BackupCrypto.KdfParams,
        val aeadAlg: String,
        val aeadNonce: ByteArray,
        val keystoreWrap: KeystoreWrapRef?,
        val recoveryWrap: RecoveryWrapRef,
        val recordCount: Int,
        val includesChat: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Header 內 keystore wrap 的描述(實際 ciphertext 也在這). */
    data class KeystoreWrapRef(
        val alias: String,
        val wrap: BackupCrypto.KeyWrap,
    )

    /** Header 內 recovery wrap 的描述. */
    data class RecoveryWrapRef(val wrap: BackupCrypto.KeyWrap)

    /** Payload 第一個 block 的內容. */
    data class Manifest(
        val manifestVersion: Int,
        val sourcePlatform: String,
        val schemaVersion: Int,
        val hlcNodeIdHex: String,
        val includesChat: Boolean,
    )

    // ---------------- header ----------------

    fun encodeHeader(h: Header): ByteArray {
        val w = CborWriter()
        // 算 map size: 必填 14 個 + 可選 keystoreWrap.
        val mapSize = if (h.keystoreWrap != null) 15 else 14
        w.writeMapHeader(mapSize)

        w.writeUInt(HeaderKey.FORMAT_VERSION.toLong()); w.writeUInt(h.formatVersion.toLong())
        w.writeUInt(HeaderKey.SOURCE_PLATFORM.toLong()); w.writeText(h.sourcePlatform)
        w.writeUInt(HeaderKey.SOURCE_APP_VER.toLong()); w.writeText(h.sourceAppVer)
        w.writeUInt(HeaderKey.SCHEMA_VERSION.toLong()); w.writeUInt(h.schemaVersion.toLong())
        w.writeUInt(HeaderKey.CONTENT_VERSION.toLong()); w.writeUInt(h.contentVersion.toLong())
        w.writeUInt(HeaderKey.CREATED_AT_MS.toLong()); w.writeUInt(h.createdAtMs)
        w.writeUInt(HeaderKey.PAYLOAD_SIZE.toLong()); w.writeUInt(h.payloadSize)
        w.writeUInt(HeaderKey.KDF_SALT.toLong()); w.writeBytes(h.kdfSalt)

        // kdf_params 子 map.
        w.writeUInt(HeaderKey.KDF_PARAMS.toLong())
        w.writeMapHeader(4)
        w.writeUInt(KdfParamKey.ALG.toLong()); w.writeText("argon2id")
        w.writeUInt(KdfParamKey.MEM_KIB.toLong()); w.writeUInt(h.kdfParams.memKib.toLong())
        w.writeUInt(KdfParamKey.ITERS.toLong()); w.writeUInt(h.kdfParams.iterations.toLong())
        w.writeUInt(KdfParamKey.PARALLELISM.toLong()); w.writeUInt(h.kdfParams.parallelism.toLong())

        w.writeUInt(HeaderKey.AEAD_ALG.toLong()); w.writeText(h.aeadAlg)
        w.writeUInt(HeaderKey.AEAD_NONCE.toLong()); w.writeBytes(h.aeadNonce)

        // keystore_wrap (可選).
        if (h.keystoreWrap != null) {
            w.writeUInt(HeaderKey.KEYSTORE_WRAP.toLong())
            w.writeMapHeader(3)
            w.writeUInt(WrapKey.ALIAS.toLong()); w.writeText(h.keystoreWrap.alias)
            w.writeUInt(WrapKey.IV.toLong()); w.writeBytes(h.keystoreWrap.wrap.iv)
            w.writeUInt(WrapKey.CIPHERTEXT.toLong()); w.writeBytes(h.keystoreWrap.wrap.ciphertextWithTag)
        }

        // recovery_wrap (必填).
        w.writeUInt(HeaderKey.RECOVERY_WRAP.toLong())
        w.writeMapHeader(2)
        w.writeUInt(WrapKey.IV.toLong()); w.writeBytes(h.recoveryWrap.wrap.iv)
        w.writeUInt(WrapKey.CIPHERTEXT.toLong()); w.writeBytes(h.recoveryWrap.wrap.ciphertextWithTag)

        w.writeUInt(HeaderKey.RECORD_COUNT.toLong()); w.writeUInt(h.recordCount.toLong())
        w.writeUInt(HeaderKey.INCLUDES_CHAT.toLong()); w.writeBool(h.includesChat)

        return w.toByteArray()
    }

    fun decodeHeader(bytes: ByteArray): Header {
        val r = CborReader(bytes)
        val entries = r.readMapHeader()

        var formatVersion = 0
        var sourcePlatform = ""
        var sourceAppVer = ""
        var schemaVersion = 0
        var contentVersion = 0
        var createdAtMs = 0L
        var payloadSize = 0L
        var kdfSalt = ByteArray(0)
        var kdfMem = 0; var kdfIters = 0; var kdfPar = 0
        var aeadAlg = ""
        var aeadNonce = ByteArray(0)
        var keystoreWrap: KeystoreWrapRef? = null
        var recoveryWrap: RecoveryWrapRef? = null
        var recordCount = 0
        var includesChat = true

        repeat(entries) {
            when (val key = r.readUInt().toInt()) {
                HeaderKey.FORMAT_VERSION -> formatVersion = r.readUInt().toInt()
                HeaderKey.SOURCE_PLATFORM -> sourcePlatform = r.readText()
                HeaderKey.SOURCE_APP_VER -> sourceAppVer = r.readText()
                HeaderKey.SCHEMA_VERSION -> schemaVersion = r.readUInt().toInt()
                HeaderKey.CONTENT_VERSION -> contentVersion = r.readUInt().toInt()
                HeaderKey.CREATED_AT_MS -> createdAtMs = r.readUInt()
                HeaderKey.PAYLOAD_SIZE -> payloadSize = r.readUInt()
                HeaderKey.KDF_SALT -> kdfSalt = r.readBytes()
                HeaderKey.KDF_PARAMS -> {
                    val subEntries = r.readMapHeader()
                    repeat(subEntries) {
                        when (r.readUInt().toInt()) {
                            KdfParamKey.ALG -> r.readText() // ignored (we assume argon2id)
                            KdfParamKey.MEM_KIB -> kdfMem = r.readUInt().toInt()
                            KdfParamKey.ITERS -> kdfIters = r.readUInt().toInt()
                            KdfParamKey.PARALLELISM -> kdfPar = r.readUInt().toInt()
                            else -> skipValue(r)
                        }
                    }
                }
                HeaderKey.AEAD_ALG -> aeadAlg = r.readText()
                HeaderKey.AEAD_NONCE -> aeadNonce = r.readBytes()
                HeaderKey.KEYSTORE_WRAP -> {
                    if (r.isNextNull()) {
                        r.consumeNull()
                    } else {
                        val sub = r.readMapHeader()
                        var alias = ""
                        var iv = ByteArray(0)
                        var ct = ByteArray(0)
                        repeat(sub) {
                            when (r.readUInt().toInt()) {
                                WrapKey.ALIAS -> alias = r.readText()
                                WrapKey.IV -> iv = r.readBytes()
                                WrapKey.CIPHERTEXT -> ct = r.readBytes()
                                else -> skipValue(r)
                            }
                        }
                        keystoreWrap = KeystoreWrapRef(alias, BackupCrypto.KeyWrap(iv, ct))
                    }
                }
                HeaderKey.RECOVERY_WRAP -> {
                    val sub = r.readMapHeader()
                    var iv = ByteArray(0)
                    var ct = ByteArray(0)
                    repeat(sub) {
                        when (r.readUInt().toInt()) {
                            WrapKey.IV -> iv = r.readBytes()
                            WrapKey.CIPHERTEXT -> ct = r.readBytes()
                            else -> skipValue(r)
                        }
                    }
                    recoveryWrap = RecoveryWrapRef(BackupCrypto.KeyWrap(iv, ct))
                }
                HeaderKey.RECORD_COUNT -> recordCount = r.readUInt().toInt()
                HeaderKey.INCLUDES_CHAT -> includesChat = r.readBool()
                else -> skipValue(r) // forward-compat: skip unknown keys
            }
        }

        return Header(
            formatVersion = formatVersion,
            sourcePlatform = sourcePlatform,
            sourceAppVer = sourceAppVer,
            schemaVersion = schemaVersion,
            contentVersion = contentVersion,
            createdAtMs = createdAtMs,
            payloadSize = payloadSize,
            kdfSalt = kdfSalt,
            kdfParams = BackupCrypto.KdfParams(
                memKib = kdfMem,
                iterations = kdfIters,
                parallelism = kdfPar,
            ),
            aeadAlg = aeadAlg,
            aeadNonce = aeadNonce,
            keystoreWrap = keystoreWrap,
            recoveryWrap = requireNotNull(recoveryWrap) { "recovery_wrap missing" },
            recordCount = recordCount,
            includesChat = includesChat,
        )
    }

    // ---------------- payload (length-prefixed CBOR blocks) ----------------

    /** 編碼 manifest map. */
    fun encodeManifest(m: Manifest): ByteArray {
        val w = CborWriter()
        w.writeMapHeader(5)
        w.writeUInt(ManifestKey.MANIFEST_VERSION.toLong()); w.writeUInt(m.manifestVersion.toLong())
        w.writeUInt(ManifestKey.SOURCE_PLATFORM.toLong()); w.writeText(m.sourcePlatform)
        w.writeUInt(ManifestKey.SCHEMA_VERSION.toLong()); w.writeUInt(m.schemaVersion.toLong())
        w.writeUInt(ManifestKey.HLC_NODE_ID_HEX.toLong()); w.writeText(m.hlcNodeIdHex)
        w.writeUInt(ManifestKey.INCLUDES_CHAT.toLong()); w.writeBool(m.includesChat)
        return w.toByteArray()
    }

    fun decodeManifest(bytes: ByteArray): Manifest {
        val r = CborReader(bytes)
        val entries = r.readMapHeader()
        var version = 0
        var platform = ""
        var schema = 0
        var nodeId = ""
        var includesChat = true
        repeat(entries) {
            when (r.readUInt().toInt()) {
                ManifestKey.MANIFEST_VERSION -> version = r.readUInt().toInt()
                ManifestKey.SOURCE_PLATFORM -> platform = r.readText()
                ManifestKey.SCHEMA_VERSION -> schema = r.readUInt().toInt()
                ManifestKey.HLC_NODE_ID_HEX -> nodeId = r.readText()
                ManifestKey.INCLUDES_CHAT -> includesChat = r.readBool()
                else -> skipValue(r)
            }
        }
        return Manifest(version, platform, schema, nodeId, includesChat)
    }

    /**
     * 編碼整個明文載荷 — manifest + records 的 length-prefixed 串接.
     * Records 可以是 live 或 tombstone(透過 SyncRecord.deletedAt 區分).
     */
    fun encodePayload(manifest: Manifest, records: List<SyncRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        writeBlock(out, encodeManifest(manifest))
        for (rec in records) {
            writeBlock(out, SyncRecordCodec.encode(rec))
        }
        return out.toByteArray()
    }

    /** 解碼明文載荷,回傳 manifest + 解出的 record 列表(包含 tombstones). */
    fun decodePayload(bytes: ByteArray): Pair<Manifest, List<SyncRecord>> {
        var pos = 0
        // 1) manifest
        val (manifestBytes, after1) = readBlock(bytes, pos)
            ?: error("Backup payload missing manifest block")
        pos = after1
        val manifest = decodeManifest(manifestBytes)

        // 2) records...
        val records = ArrayList<SyncRecord>()
        while (pos < bytes.size) {
            val (recBytes, after) = readBlock(bytes, pos)
                ?: error("Truncated record block at offset $pos")
            records += SyncRecordCodec.decode(recBytes)
            pos = after
        }
        return manifest to records
    }

    // ---------------- block framing helpers ----------------

    private fun writeBlock(out: ByteArrayOutputStream, block: ByteArray) {
        val len = block.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(block)
    }

    /** 從 [pos] 讀取一個 length-prefixed block;回傳 (blockBytes, nextPos). */
    private fun readBlock(bytes: ByteArray, pos: Int): Pair<ByteArray, Int>? {
        if (pos + 4 > bytes.size) return null
        val len =
            ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
        if (len < 0 || pos + 4 + len > bytes.size) return null
        val block = bytes.copyOfRange(pos + 4, pos + 4 + len)
        return block to (pos + 4 + len)
    }

    // ---------------- CBOR skip (for forward-compat unknown keys) ----------------

    /**
     * 跳過一個 CBOR value(用於 forward-compat 略過未來新增的 header 欄位).
     * 重複實作但和 SyncRecordCodec 的版本對齊.
     */
    private fun skipValue(r: CborReader) {
        if (r.isNextNull()) { r.consumeNull(); return }
        when (val mt = r.peekMajorType()) {
            com.silverbp.android.sync.transport.Cbor.MT_UINT,
            com.silverbp.android.sync.transport.Cbor.MT_NINT -> r.readInt()
            com.silverbp.android.sync.transport.Cbor.MT_TEXT -> r.readText()
            com.silverbp.android.sync.transport.Cbor.MT_BYTES -> r.readBytes()
            com.silverbp.android.sync.transport.Cbor.MT_ARRAY -> {
                val n = r.readArrayHeader()
                repeat(n) { skipValue(r) }
            }
            com.silverbp.android.sync.transport.Cbor.MT_MAP -> {
                val n = r.readMapHeader()
                repeat(n) { skipValue(r); skipValue(r) }
            }
            com.silverbp.android.sync.transport.Cbor.MT_SIMPLE -> {
                val info = r.peekFirstByte() and 0x1F
                if (info == com.silverbp.android.sync.transport.Cbor.SIMPLE_FLOAT64) r.readDouble()
                else r.readBool()
            }
            else -> error("CBOR: cannot skip major type $mt")
        }
    }
}
