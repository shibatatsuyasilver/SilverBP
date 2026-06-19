package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.transport.Cbor
import com.silverbp.android.sync.transport.CborReader
import com.silverbp.android.sync.transport.CborWriter
import com.silverbp.android.sync.transport.SyncMessageType
import com.silverbp.android.sync.transport.SyncRecordCodec

/**
 * CBOR encoder/decoder for [ProtocolMessage]. Mirrors iOS
 * `ProtocolCodec.swift` byte-for-byte.
 *
 * Wire shapes (CBOR maps with integer keys):
 *
 *   HELLO        { 1: 1 (uint), 2: deviceId (text), 3: lastHlcSeen (text) }
 *   RECORDS      { 1: 5 (uint), 2: [bytes_of_each_SyncRecord] }
 *   ACK          { 1: 8 (uint), 2: hlc (text) }
 *   BYE          { 1: 9 (uint) }
 *
 * Each entry in `RECORDS.2` is the byte array produced by [SyncRecordCodec.encode]
 * — we wrap each record in a CBOR byte-string so the array length is decoupled
 * from the records' internal CBOR shapes.
 */
object ProtocolCodec {
    private const val KEY_TYPE = 1
    private const val KEY_DEVICE_ID = 2
    private const val KEY_LAST_HLC = 3
    private const val KEY_RECORDS = 2
    private const val KEY_ACK_HLC = 2

    fun encode(msg: ProtocolMessage): ByteArray {
        val w = CborWriter()
        when (msg) {
            is ProtocolMessage.Hello -> {
                w.writeMapHeader(3)
                w.writeUInt(KEY_TYPE.toLong()); w.writeUInt(SyncMessageType.HELLO.tag.toLong())
                w.writeUInt(KEY_DEVICE_ID.toLong()); w.writeText(msg.deviceId)
                w.writeUInt(KEY_LAST_HLC.toLong()); w.writeText(msg.lastHlcSeen.packed)
            }
            is ProtocolMessage.Records -> {
                w.writeMapHeader(2)
                w.writeUInt(KEY_TYPE.toLong()); w.writeUInt(SyncMessageType.RECORDS.tag.toLong())
                w.writeUInt(KEY_RECORDS.toLong())
                w.writeArrayHeader(msg.records.size)
                for (rec in msg.records) {
                    w.writeBytes(SyncRecordCodec.encode(rec))
                }
            }
            is ProtocolMessage.Ack -> {
                w.writeMapHeader(2)
                w.writeUInt(KEY_TYPE.toLong()); w.writeUInt(SyncMessageType.ACK.tag.toLong())
                w.writeUInt(KEY_ACK_HLC.toLong()); w.writeText(msg.hlc.packed)
            }
            is ProtocolMessage.Bye -> {
                w.writeMapHeader(1)
                w.writeUInt(KEY_TYPE.toLong()); w.writeUInt(SyncMessageType.BYE.tag.toLong())
            }
            is ProtocolMessage.ProtocolError -> error("ProtocolError is a decode-only message")
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): ProtocolMessage =
        runCatching { decodeStrict(bytes) }
            .getOrElse { ProtocolMessage.ProtocolError("malformed protocol message: ${it.message}") }

    private fun decodeStrict(bytes: ByteArray): ProtocolMessage {
        val r = CborReader(bytes)
        val mapEntries = r.readMapHeader()
        var typeTag: Int? = null
        var deviceId: String? = null
        var lastHlc: String? = null
        var records: List<SyncRecord>? = null
        var ackHlc: String? = null

        repeat(mapEntries) {
            val key = r.readUInt().toInt()
            // The codec is parsed greedily: we read the type first, but value
            // semantics for the same numeric key (2, 3) depend on the type.
            // We only know the type when we hit field 1 — so we decode each
            // value into the most likely slot for THAT type, post-resolving.
            when (key) {
                KEY_TYPE -> typeTag = r.readUInt().toInt()
                KEY_DEVICE_ID -> {
                    // KEY_DEVICE_ID == KEY_RECORDS == KEY_ACK_HLC == 2.
                    // Disambiguate by current major type peek.
                    when (r.peekMajorType()) {
                        Cbor.MT_TEXT -> {
                            // Hello.deviceId or Ack.hlc — both text. Stash as
                            // both candidates; resolution happens after typeTag
                            // is known.
                            val s = r.readText()
                            deviceId = s
                            ackHlc = s
                        }
                        Cbor.MT_ARRAY -> {
                            val n = r.readArrayHeader()
                            // readBytes() advances the reader for every element even
                            // when the record's type is unknown; decodeOrNull then
                            // drops it so a newer peer's record can't abort the round.
                            records = (0 until n).mapNotNull {
                                SyncRecordCodec.decodeOrNull(r.readBytes())
                            }
                        }
                        else -> {
                            r.skipValue()
                        }
                    }
                }
                KEY_LAST_HLC -> lastHlc = r.readText()
                else -> r.skipValue()
            }
        }

        val tag = typeTag
            ?: return ProtocolMessage.ProtocolError("missing type tag")
        return when (tag) {
            SyncMessageType.HELLO.tag -> ProtocolMessage.Hello(
                deviceId = deviceId
                    ?: return ProtocolMessage.ProtocolError("HELLO missing deviceId", tag),
                lastHlcSeen = Hlc(
                    lastHlc ?: return ProtocolMessage.ProtocolError("HELLO missing lastHlcSeen", tag),
                ),
            )
            SyncMessageType.RECORDS.tag -> ProtocolMessage.Records(
                records = records ?: emptyList(),
            )
            SyncMessageType.ACK.tag -> ProtocolMessage.Ack(
                hlc = Hlc(ackHlc ?: return ProtocolMessage.ProtocolError("ACK missing hlc", tag)),
            )
            SyncMessageType.BYE.tag -> ProtocolMessage.Bye
            else -> ProtocolMessage.ProtocolError("unknown protocol message type tag: $tag", tag)
        }
    }
}
