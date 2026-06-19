package com.silverbp.android.sync.transport

/**
 * Wire-frame envelope used inside an established Noise session. Mirrors
 * `Packages/BPSharing/Sources/BPSharing/Transport/Framing.swift` byte-for-byte.
 *
 * Layout (encrypted by Noise, then prefixed with length on the TCP stream):
 * ```
 *   length : 4 bytes big-endian uint
 *   body   : CBOR map with numeric tags
 * ```
 */
enum class SyncMessageType(val tag: Int) {
    HELLO(1),
    DIGEST_REQUEST(2),
    DIGEST_RESPONSE(3),
    RECORDS_REQUEST(4),
    RECORDS(5),
    BLOB_REQUEST(6),
    BLOB_DATA(7),
    ACK(8),
    BYE(9);

    companion object {
        private val byTag = entries.associateBy { it.tag }
        fun fromTag(tag: Int): SyncMessageType? = byTag[tag]
    }
}

object SyncFraming {
    const val LENGTH_HEADER_BYTES = 4
    /** 16 MiB. Larger blobs chunk via BLOB_DATA rather than inline frames. */
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024
}
