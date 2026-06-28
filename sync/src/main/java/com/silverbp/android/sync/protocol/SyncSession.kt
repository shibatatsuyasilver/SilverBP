package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.OrphanRecordException
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.transport.NoiseTransport

/**
 * Read source of records to ship to the peer. The session asks for every
 * record with `hlc > peerLastHlcSeen` so the peer doesn't see what it
 * already has.
 */
fun interface SyncRecordSource {
    suspend fun recordsSince(peerLastHlcSeen: Hlc, limit: Int): List<SyncRecord>
}

/**
 * Apply incoming records to the local store. The mapper handles LWW gating;
 * the session is just the message pump.
 */
fun interface SyncRecordSink {
    suspend fun apply(record: SyncRecord)
}

class SyncProtocolException(message: String) : IllegalStateException(message)

/**
 * Thrown by a sink when an inbound record carries an entity *type* this build
 * doesn't know how to apply yet — a newer peer may sync record types added on
 * the other side (e.g. a future `blob_meta`).
 *
 * The sync session treats this as RETRY-LATER, not as applied: it must NOT
 * observe the record's HLC or let the peer watermark advance past it. Otherwise
 * the watermark would skip the record and the peer would never re-send it, so a
 * future app version that understands the type could never receive it. The
 * record is re-shipped every round until a build that maps the type lands, and
 * the LWW gate dedupes the rows that did apply.
 *
 * Mirrors [com.silverbp.android.sync.engine.OrphanRecordException] but for a
 * different cause: orphan is a *known* type whose FK parent hasn't arrived yet,
 * whereas this is a type the sink can't dispatch at all. A malformed *known*
 * record raises neither and still aborts the session. Deliberately extends
 * [Exception] (not `CancellationException`) so it never interferes with
 * coroutine cancellation.
 */
class UnknownRecordTypeException(message: String) : Exception(message)

/**
 * Drives one round of bidirectional BP-reading sync over a [NoiseTransport].
 *
 * Wire trace (six frames per round, each direction interleaves):
 * ```
 *   A → B  HELLO   {deviceId=A, lastHlcSeen=H_A}
 *   B → A  HELLO   {deviceId=B, lastHlcSeen=H_B}
 *   A → B  RECORDS [records hlc > H_B]
 *   B → A  RECORDS [records hlc > H_A]
 *   A → B  ACK     {hlc=max received from B}
 *   B → A  ACK     {hlc=max received from A}
 *   A → B  BYE
 *   B → A  BYE
 * ```
 *
 * Both peers must call [run] concurrently — the protocol doesn't survive
 * a serial run because each `transport.receiveFrame()` would block forever
 * waiting on a peer that's also blocked on receive.
 */
class SyncSession(
    private val transport: NoiseTransport,
    private val localDeviceId: String,
    private val clock: HlcClock,
    private val source: SyncRecordSource,
    private val sink: SyncRecordSink,
    private val getLocalLastHlcSeen: suspend () -> Hlc,
    private val updateLocalLastHlcSeen: suspend (Hlc) -> Unit,
    // 50k chosen so a single-pair workload (BP+exercise+route_point+meds+
    // step_log+achievements across years) fits in one round on LAN. Per
    // record averages ~80 bytes after CBOR; 50k × 80 ≈ 4 MB single frame,
    // well under Noise's 64 KiB per-message ceiling once we chunk in the
    // future. For now the framing layer handles it as one message.
    private val recordsBatchLimit: Int = 50_000,
) {
    /**
     * One full sync round-trip. Returns the committed watermark — the highest
     * HLC for which every record up to it was durably accounted (applied or
     * LWW-stale). Held at the pre-round value if any child was deferred as an
     * orphan or any record carried a type this build can't apply yet, so callers
     * can persist the return value without skipping records.
     */
    suspend fun run(): Hlc {
        // 1. HELLO exchange
        val ourHello = ProtocolMessage.Hello(localDeviceId, getLocalLastHlcSeen())
        transport.sendFrame(ProtocolCodec.encode(ourHello))
        val peerHelloBytes = transport.receiveFrame() ?: error("EOF before peer HELLO")
        val peerHello = decodeExpected<ProtocolMessage.Hello>(peerHelloBytes, "HELLO")

        // 2. RECORDS exchange — ship everything since peer's last seen
        val outgoing = source.recordsSince(peerHello.lastHlcSeen, recordsBatchLimit)
        transport.sendFrame(ProtocolCodec.encode(ProtocolMessage.Records(outgoing)))
        val peerRecordsBytes = transport.receiveFrame() ?: error("EOF before peer RECORDS")
        val peerRecords = decodeExpected<ProtocolMessage.Records>(peerRecordsBytes, "RECORDS")

        // 3. Apply incoming, advance HLC clock + watermark
        var maxIncoming = ourHello.lastHlcSeen
        var anyDeferred = false
        var anyUnknownType = false
        for (rec in peerRecords.records) {
            try {
                sink.apply(rec)
            } catch (deferred: OrphanRecordException) {
                // A child arrived before its FK parent (e.g. route_point before
                // its exercise_session). Don't observe its HLC or let the
                // watermark pass it — we hold the watermark so the peer re-ships
                // it next round once the parent has landed (QA #5 / P1-18).
                anyDeferred = true
                continue
            } catch (unknown: UnknownRecordTypeException) {
                // The record's entity type isn't understood by this build (a
                // newer peer added it). Don't observe its HLC or let the
                // watermark pass it — hold the watermark so the record stays
                // eligible for a future version that can apply it, instead of
                // being skipped forever.
                anyUnknownType = true
                continue
            }
            clock.observe(rec.hlc)
            if (rec.hlc > maxIncoming) maxIncoming = rec.hlc
        }
        // Commit the watermark only as far as we durably accounted for every
        // record up to it. If any child was deferred as an orphan, or any record
        // carried a type this build can't apply yet, hold the pre-round
        // watermark: already-applied rows are simply re-sent next round and
        // deduped by the LWW gate, so nothing is lost.
        val committedWatermark =
            if (anyDeferred || anyUnknownType) ourHello.lastHlcSeen else maxIncoming
        updateLocalLastHlcSeen(committedWatermark)

        // 4. ACK exchange
        transport.sendFrame(ProtocolCodec.encode(ProtocolMessage.Ack(committedWatermark)))
        val peerAckBytes = transport.receiveFrame() ?: error("EOF before peer ACK")
        decodeExpected<ProtocolMessage.Ack>(peerAckBytes, "ACK")

        // 5. BYE — best-effort; the peer may have closed already.
        try {
            transport.sendFrame(ProtocolCodec.encode(ProtocolMessage.Bye))
        } catch (_: Throwable) { /* peer already gone */ }

        return committedWatermark
    }

    private inline fun <reified T : ProtocolMessage> decodeExpected(
        bytes: ByteArray,
        expected: String,
    ): T {
        return when (val msg = ProtocolCodec.decode(bytes)) {
            is ProtocolMessage.ProtocolError -> throw SyncProtocolException(msg.reason)
            is T -> msg
            else -> throw SyncProtocolException("expected $expected, got ${messageName(msg)}")
        }
    }

    private fun messageName(msg: ProtocolMessage): String = when (msg) {
        is ProtocolMessage.Hello -> "HELLO"
        is ProtocolMessage.Records -> "RECORDS"
        is ProtocolMessage.Ack -> "ACK"
        ProtocolMessage.Bye -> "BYE"
        is ProtocolMessage.ProtocolError -> "PROTOCOL_ERROR"
    }
}
