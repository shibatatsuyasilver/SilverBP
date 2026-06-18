package com.silverbp.android.sync.transport

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue

/**
 * CBOR encoder/decoder for [SyncRecord]. Wire-format must remain byte-
 * identical with iOS `SyncRecordCodec.swift`.
 *
 * Frame shape (CBOR map, integer keys):
 * ```
 *   1: type        uint   (SyncEntityType.tag)
 *   2: pk          text
 *   3: hlc         text   (32-char hex packed HLC)
 *   4: deletedAt   uint?  (ms since epoch; absent or null when live)
 *   5: payload     map<int, SyncValue>
 * ```
 *
 * `deletedAt` is omitted entirely when null (live record) so tombstones get
 * an extra map slot rather than a wasted null byte. Decoders accept either
 * "absent" or "explicit null" for forward-compat.
 */
object SyncRecordCodec {
    private const val KEY_TYPE = 1
    private const val KEY_PK = 2
    private const val KEY_HLC = 3
    private const val KEY_DELETED_AT = 4
    private const val KEY_PAYLOAD = 5

    fun encode(record: SyncRecord): ByteArray {
        val w = CborWriter()
        val mapSize = if (record.deletedAt != null) 5 else 4
        w.writeMapHeader(mapSize)

        w.writeUInt(KEY_TYPE.toLong())
        w.writeUInt(record.type.tag.toLong())

        w.writeUInt(KEY_PK.toLong())
        w.writeText(record.pk)

        w.writeUInt(KEY_HLC.toLong())
        w.writeText(record.hlc.packed)

        record.deletedAt?.let { ms ->
            w.writeUInt(KEY_DELETED_AT.toLong())
            w.writeUInt(ms)
        }

        w.writeUInt(KEY_PAYLOAD.toLong())
        writeValueMap(w, record.payload)

        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): SyncRecord =
        decodeOrNull(bytes) ?: error("Unknown SyncRecord type tag")

    /**
     * Decode a frame, or return null when its entity-type tag is unknown to this
     * build. A newer peer or backup may carry record types we don't understand
     * yet; a forward-compatible reader skips them rather than aborting the whole
     * sync/restore. Malformed frames (missing required fields) still throw.
     */
    fun decodeOrNull(bytes: ByteArray): SyncRecord? {
        val r = CborReader(bytes)
        val mapEntries = r.readMapHeader()

        var typeTag: Int? = null
        var pk: String? = null
        var hlc: String? = null
        var deletedAt: Long? = null
        var payload: Map<Int, SyncValue> = emptyMap()

        repeat(mapEntries) {
            val key = r.readUInt().toInt()
            when (key) {
                KEY_TYPE -> typeTag = r.readUInt().toInt()
                KEY_PK -> pk = r.readText()
                KEY_HLC -> hlc = r.readText()
                KEY_DELETED_AT -> {
                    if (r.isNextNull()) {
                        r.consumeNull()
                    } else {
                        deletedAt = r.readUInt()
                    }
                }
                KEY_PAYLOAD -> payload = readValueMap(r)
                else -> skipValue(r)
            }
        }

        val type = SyncEntityType.fromTag(
            requireNotNull(typeTag) { "SyncRecord missing type" },
        ) ?: return null
        return SyncRecord(
            type = type,
            pk = requireNotNull(pk) { "SyncRecord missing pk" },
            hlc = Hlc(requireNotNull(hlc) { "SyncRecord missing hlc" }),
            deletedAt = deletedAt,
            payload = payload,
        )
    }

    // ----- payload encoding -----

    private fun writeValueMap(w: CborWriter, payload: Map<Int, SyncValue>) {
        w.writeMapHeader(payload.size)
        // Sort keys for deterministic wire output (helps test fixtures + digest hashing).
        for ((k, v) in payload.entries.sortedBy { it.key }) {
            w.writeUInt(k.toLong())
            writeValue(w, v)
        }
    }

    private fun writeValue(w: CborWriter, v: SyncValue) {
        when (v) {
            SyncValue.Null -> w.writeNull()
            is SyncValue.Bool -> w.writeBool(v.value)
            is SyncValue.Int64 -> w.writeInt(v.value)
            is SyncValue.Double -> w.writeDouble(v.value)
            is SyncValue.Text -> w.writeText(v.value)
            is SyncValue.Bytes -> w.writeBytes(v.value)
        }
    }

    private fun readValueMap(r: CborReader): Map<Int, SyncValue> {
        val entries = r.readMapHeader()
        val out = LinkedHashMap<Int, SyncValue>(entries)
        repeat(entries) {
            val k = r.readUInt().toInt()
            out[k] = readValue(r)
        }
        return out
    }

    private fun readValue(r: CborReader): SyncValue {
        if (r.isNextNull()) {
            r.consumeNull()
            return SyncValue.Null
        }
        return when (val mt = r.peekMajorType()) {
            Cbor.MT_UINT, Cbor.MT_NINT -> SyncValue.Int64(r.readInt())
            Cbor.MT_TEXT -> SyncValue.Text(r.readText())
            Cbor.MT_BYTES -> SyncValue.Bytes(r.readBytes())
            Cbor.MT_SIMPLE -> {
                val info = r.peekFirstByte() and 0x1F
                if (info == Cbor.SIMPLE_FLOAT64) SyncValue.Double(r.readDouble())
                else SyncValue.Bool(r.readBool())
            }
            else -> error("CBOR: unsupported payload value major type $mt")
        }
    }

    /** Skips a single CBOR value (used to ignore unknown map keys for forward-compat). */
    private fun skipValue(r: CborReader) {
        if (r.isNextNull()) { r.consumeNull(); return }
        when (val mt = r.peekMajorType()) {
            Cbor.MT_UINT, Cbor.MT_NINT -> r.readInt()
            Cbor.MT_TEXT -> r.readText()
            Cbor.MT_BYTES -> r.readBytes()
            Cbor.MT_ARRAY -> {
                val n = r.readArrayHeader()
                repeat(n) { skipValue(r) }
            }
            Cbor.MT_MAP -> {
                val n = r.readMapHeader()
                repeat(n) { skipValue(r); skipValue(r) }
            }
            Cbor.MT_SIMPLE -> {
                val info = r.peekFirstByte() and 0x1F
                if (info == Cbor.SIMPLE_FLOAT64) r.readDouble() else r.readBool()
            }
            else -> error("CBOR: cannot skip major type $mt")
        }
    }
}
