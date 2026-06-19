package com.silverbp.android.sync.transport

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import java.io.ByteArrayOutputStream

/**
 * Minimal CBOR (RFC 8949) encoder/decoder for our wire format. We need only
 * a small subset: uint / nint / bytes / text / array / map / null / bool /
 * float64. Uses the smallest length encoding so frames stay compact.
 *
 * iOS counterpart at `Packages/BPSharing/Sources/BPSharing/Transport/CBORCodec.swift`
 * mirrors this byte-for-byte. The cross-platform fixture test in `:sync`
 * unit tests + `BPSharingTests` exercises both decoders against the same
 * binary blob to guarantee interop.
 */
object Cbor {
    const val MT_UINT = 0
    const val MT_NINT = 1
    const val MT_BYTES = 2
    const val MT_TEXT = 3
    const val MT_ARRAY = 4
    const val MT_MAP = 5
    const val MT_TAG = 6
    const val MT_SIMPLE = 7

    const val SIMPLE_FALSE = 20
    const val SIMPLE_TRUE = 21
    const val SIMPLE_NULL = 22
    const val SIMPLE_FLOAT64 = 27
}

/** Streaming CBOR writer. Output retrievable via [toByteArray]. */
class CborWriter {
    private val buf = ByteArrayOutputStream()

    fun writeUInt(value: Long) {
        require(value >= 0) { "writeUInt: value must be >= 0, got $value" }
        writeHeader(Cbor.MT_UINT, value)
    }

    /** Writes a signed integer using uint/nint major types per RFC 8949. */
    fun writeInt(value: Long) {
        if (value >= 0) {
            writeHeader(Cbor.MT_UINT, value)
        } else {
            // CBOR negative encoding: -1 - n  →  n
            writeHeader(Cbor.MT_NINT, -1L - value)
        }
    }

    fun writeText(s: String) {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        writeHeader(Cbor.MT_TEXT, utf8.size.toLong())
        buf.write(utf8)
    }

    fun writeBytes(b: ByteArray) {
        writeHeader(Cbor.MT_BYTES, b.size.toLong())
        buf.write(b)
    }

    fun writeArrayHeader(size: Int) {
        require(size >= 0)
        writeHeader(Cbor.MT_ARRAY, size.toLong())
    }

    fun writeMapHeader(size: Int) {
        require(size >= 0)
        writeHeader(Cbor.MT_MAP, size.toLong())
    }

    fun writeBool(value: Boolean) {
        val s = if (value) Cbor.SIMPLE_TRUE else Cbor.SIMPLE_FALSE
        buf.write((Cbor.MT_SIMPLE shl 5) or s)
    }

    fun writeNull() {
        buf.write((Cbor.MT_SIMPLE shl 5) or Cbor.SIMPLE_NULL)
    }

    fun writeDouble(value: Double) {
        buf.write((Cbor.MT_SIMPLE shl 5) or Cbor.SIMPLE_FLOAT64)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (i in 7 downTo 0) buf.write(((bits shr (i * 8)) and 0xFF).toInt())
    }

    fun toByteArray(): ByteArray = buf.toByteArray()

    private fun writeHeader(majorType: Int, length: Long) {
        require(length >= 0)
        val mt = majorType shl 5
        when {
            length <= 23L -> buf.write(mt or length.toInt())
            length <= 0xFFL -> {
                buf.write(mt or 24)
                buf.write(length.toInt() and 0xFF)
            }
            length <= 0xFFFFL -> {
                buf.write(mt or 25)
                buf.write(((length shr 8) and 0xFF).toInt())
                buf.write((length and 0xFF).toInt())
            }
            length <= 0xFFFF_FFFFL -> {
                buf.write(mt or 26)
                for (i in 3 downTo 0) buf.write(((length shr (i * 8)) and 0xFF).toInt())
            }
            else -> {
                buf.write(mt or 27)
                for (i in 7 downTo 0) buf.write(((length shr (i * 8)) and 0xFF).toInt())
            }
        }
    }
}

/** Streaming CBOR reader. Throws [IllegalStateException] on unexpected types. */
class CborReader(private val bytes: ByteArray) {
    private var pos = 0

    val remaining: Int get() = bytes.size - pos

    fun peekMajorType(): Int {
        require(pos < bytes.size) { "CBOR: unexpected EOF (peek)" }
        return (bytes[pos].toInt() and 0xFF) ushr 5
    }

    /** First (unconsumed) byte at the cursor. Useful for distinguishing
     *  bool / null / float64 within major-type-7 simple values. */
    fun peekFirstByte(): Int {
        require(pos < bytes.size) { "CBOR: unexpected EOF (peek)" }
        return bytes[pos].toInt() and 0xFF
    }

    fun isNextNull(): Boolean {
        if (pos >= bytes.size) return false
        val b = bytes[pos].toInt() and 0xFF
        return b == ((Cbor.MT_SIMPLE shl 5) or Cbor.SIMPLE_NULL)
    }

    fun consumeNull() {
        check(isNextNull()) { "CBOR: expected null, got byte 0x${"%02x".format(bytes[pos].toInt() and 0xFF)}" }
        pos++
    }

    fun readBool(): Boolean {
        val b = bytes[pos].toInt() and 0xFF
        pos++
        return when (b) {
            (Cbor.MT_SIMPLE shl 5) or Cbor.SIMPLE_TRUE -> true
            (Cbor.MT_SIMPLE shl 5) or Cbor.SIMPLE_FALSE -> false
            else -> error("CBOR: expected bool, got 0x${"%02x".format(b)}")
        }
    }

    fun readDouble(): Double {
        val b = bytes[pos].toInt() and 0xFF
        check(b == ((Cbor.MT_SIMPLE shl 5) or Cbor.SIMPLE_FLOAT64)) {
            "CBOR: expected float64, got 0x${"%02x".format(b)}"
        }
        pos++
        var bits = 0L
        for (i in 0 until 8) {
            bits = (bits shl 8) or (bytes[pos + i].toLong() and 0xFF)
        }
        pos += 8
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun readUInt(): Long {
        val (mt, len) = readHeader()
        check(mt == Cbor.MT_UINT) { "CBOR: expected uint, got mt=$mt" }
        return len
    }

    fun readInt(): Long {
        val (mt, len) = readHeader()
        return when (mt) {
            Cbor.MT_UINT -> len
            Cbor.MT_NINT -> -1L - len
            else -> error("CBOR: expected int, got mt=$mt")
        }
    }

    fun readText(): String {
        val (mt, len) = readHeader()
        check(mt == Cbor.MT_TEXT) { "CBOR: expected text, got mt=$mt" }
        val s = String(bytes, pos, len.toInt(), Charsets.UTF_8)
        pos += len.toInt()
        return s
    }

    fun readBytes(): ByteArray {
        val (mt, len) = readHeader()
        check(mt == Cbor.MT_BYTES) { "CBOR: expected bytes, got mt=$mt" }
        val out = bytes.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    fun readArrayHeader(): Int {
        val (mt, len) = readHeader()
        check(mt == Cbor.MT_ARRAY) { "CBOR: expected array, got mt=$mt" }
        return len.toInt()
    }

    fun readMapHeader(): Int {
        val (mt, len) = readHeader()
        check(mt == Cbor.MT_MAP) { "CBOR: expected map, got mt=$mt" }
        return len.toInt()
    }

    /** Skips one complete CBOR value, including nested arrays/maps. */
    fun skipValue() {
        if (isNextNull()) {
            consumeNull()
            return
        }
        when (val mt = peekMajorType()) {
            Cbor.MT_UINT, Cbor.MT_NINT -> readInt()
            Cbor.MT_TEXT -> readText()
            Cbor.MT_BYTES -> readBytes()
            Cbor.MT_ARRAY -> {
                val n = readArrayHeader()
                repeat(n) { skipValue() }
            }
            Cbor.MT_MAP -> {
                val n = readMapHeader()
                repeat(n) {
                    skipValue()
                    skipValue()
                }
            }
            Cbor.MT_TAG -> {
                readHeader()
                skipValue()
            }
            Cbor.MT_SIMPLE -> {
                val info = peekFirstByte() and 0x1F
                if (info == Cbor.SIMPLE_FLOAT64) readDouble() else readBool()
            }
            else -> error("CBOR: cannot skip major type $mt")
        }
    }

    private fun readHeader(): Pair<Int, Long> {
        require(pos < bytes.size) { "CBOR: unexpected EOF" }
        val first = bytes[pos].toInt() and 0xFF
        pos++
        val mt = first ushr 5
        val info = first and 0x1F
        val length = when (info) {
            in 0..23 -> info.toLong()
            24 -> { val v = bytes[pos].toLong() and 0xFF; pos += 1; v }
            25 -> {
                var v = 0L
                for (i in 0 until 2) { v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF) }
                pos += 2
                v
            }
            26 -> {
                var v = 0L
                for (i in 0 until 4) { v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF) }
                pos += 4
                v
            }
            27 -> {
                var v = 0L
                for (i in 0 until 8) { v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF) }
                pos += 8
                v
            }
            else -> error("CBOR: unsupported length info $info (indefinite-length not supported)")
        }
        return mt to length
    }
}
