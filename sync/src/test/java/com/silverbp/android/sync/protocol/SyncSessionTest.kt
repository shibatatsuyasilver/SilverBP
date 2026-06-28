package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.OrphanRecordException
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.MemoryPipe
import com.silverbp.android.sync.transport.NoiseTransport
import com.silverbp.android.sync.transport.NoiseXk
import com.silverbp.android.sync.transport.NoiseXkHandshake
import com.silverbp.android.sync.transport.FrameChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.2's marquee orchestration test. Two SyncSessions run concurrently
 * over an in-memory Noise pipe; each device starts with its own BP readings
 * and ends with both sets after one round.
 *
 * If this passes, "BP reading written on Pixel appears on iPhone after one
 * sync round" is structurally correct end-to-end (modulo the actual TCP
 * socket and Bonjour discovery, which are equivalent transports).
 */
class SyncSessionTest {

    @Test
    fun two_devices_converge_after_one_round() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()

        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (transportA, transportB) = transports

        // Each side starts with 3 records nobody else has.
        val recordsFromA = (0 until 3).map { i ->
            bpRecord(pk = "A-$i", physicalMs = 1_700_000_000_000L + i, nodeId = 0xAAAAL)
        }
        val recordsFromB = (0 until 3).map { i ->
            bpRecord(pk = "B-$i", physicalMs = 1_700_000_000_500L + i, nodeId = 0xBBBBL)
        }

        val storeA = mutableListOf<SyncRecord>().apply { addAll(recordsFromA) }
        val storeB = mutableListOf<SyncRecord>().apply { addAll(recordsFromB) }

        val sessionA = SyncSession(
            transport = transportA,
            localDeviceId = "device-a",
            clock = HlcClock(nodeId = 0xAAAAL),
            source = SyncRecordSource { peerHlc, _ -> storeA.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { rec -> storeA.add(rec) },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { /* no-op for test */ },
        )
        val sessionB = SyncSession(
            transport = transportB,
            localDeviceId = "device-b",
            clock = HlcClock(nodeId = 0xBBBBL),
            source = SyncRecordSource { peerHlc, _ -> storeB.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { rec -> storeB.add(rec) },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { /* no-op for test */ },
        )

        coroutineScope {
            val ja = async { sessionA.run() }
            val jb = async { sessionB.run() }
            ja.await()
            jb.await()
        }

        // Both stores now contain both record sets.
        val pksA = storeA.map { it.pk }.toSet()
        val pksB = storeB.map { it.pk }.toSet()
        val expected = (recordsFromA + recordsFromB).map { it.pk }.toSet()
        assertEquals(expected, pksA)
        assertEquals(expected, pksB)
        assertTrue(storeA.size == 6 && storeB.size == 6)
    }

    @Test
    fun lastHlcSeen_filters_already_acknowledged_records() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()
        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (tA, tB) = transports

        // A has 5 records, B already saw the first 3 (high watermark = R2's hlc).
        val rs = (0 until 5).map { i ->
            bpRecord(pk = "x-$i", physicalMs = 1_700_000_000_000L + i, nodeId = 1L)
        }
        val storeA = rs.toMutableList()
        val storeB = mutableListOf<SyncRecord>()
        val bWatermark = rs[2].hlc // B claims to have already seen R0..R2

        var sourceCalls = 0
        val sessionA = SyncSession(
            transport = tA,
            localDeviceId = "a",
            clock = HlcClock(nodeId = 1L),
            source = SyncRecordSource { peerHlc, _ ->
                sourceCalls++
                storeA.filter { it.hlc > peerHlc }
            },
            sink = SyncRecordSink { rec -> storeA.add(rec) },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { },
        )
        val sessionB = SyncSession(
            transport = tB,
            localDeviceId = "b",
            clock = HlcClock(nodeId = 2L),
            source = SyncRecordSource { _, _ -> emptyList() },
            sink = SyncRecordSink { rec -> storeB.add(rec) },
            getLocalLastHlcSeen = { bWatermark },
            updateLocalLastHlcSeen = { },
        )

        coroutineScope {
            val ja = async { sessionA.run() }
            val jb = async { sessionB.run() }
            ja.await()
            jb.await()
        }

        // A's source was asked for records since B's watermark — should
        // ship R3 and R4 only.
        assertEquals(1, sourceCalls)
        assertEquals(2, storeB.size)
        assertEquals(setOf("x-3", "x-4"), storeB.map { it.pk }.toSet())
    }

    @Test
    fun deferred_orphan_holds_the_watermark_so_it_is_resent() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()
        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (tA, tB) = transports

        // A ships a parent and an orphan child; B applies the parent but its sink
        // rejects the orphan (parent-not-present-yet) by throwing.
        val parent = bpRecord(pk = "parent", physicalMs = 1_700_000_000_000L, nodeId = 1L)
        val orphan = bpRecord(pk = "orphan", physicalMs = 1_700_000_000_900L, nodeId = 1L)
        val storeA = mutableListOf(parent, orphan)

        val bStore = mutableListOf<SyncRecord>()
        var bCommitted: Hlc? = null

        val sessionA = SyncSession(
            transport = tA,
            localDeviceId = "a",
            clock = HlcClock(nodeId = 1L),
            source = SyncRecordSource { peerHlc, _ -> storeA.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { },
        )
        val sessionB = SyncSession(
            transport = tB,
            localDeviceId = "b",
            clock = HlcClock(nodeId = 2L),
            source = SyncRecordSource { _, _ -> emptyList() },
            sink = SyncRecordSink { rec ->
                if (rec.pk == "orphan") throw OrphanRecordException("parent of ${rec.pk} not present yet")
                bStore.add(rec)
            },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { hlc -> bCommitted = hlc },
        )

        coroutineScope {
            val ja = async { sessionA.run() }
            val jb = async { sessionB.run() }
            ja.await()
            jb.await()
        }

        // The orphan was deferred (not stored)…
        assertEquals(setOf("parent"), bStore.map { it.pk }.toSet())
        // …and the watermark was HELD at the pre-round value, not advanced past
        // the orphan — so the peer re-ships it next round (QA #5).
        assertEquals(Hlc.ZERO, bCommitted)
    }

    @Test
    fun unknown_record_type_holds_the_watermark_so_it_is_resent() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()
        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (tA, tB) = transports

        // A ships a known record and a record whose entity *type* this build can't
        // map yet (a newer peer added it, e.g. a future `blob_meta`). B applies the
        // known one but its sink raises UnknownRecordTypeException for the unmapped
        // record, exactly like an unrecognised type would at the mapper boundary.
        val known = bpRecord(pk = "known", physicalMs = 1_700_000_000_000L, nodeId = 1L)
        val unknownType = bpRecord(pk = "future_blob_meta", physicalMs = 1_700_000_000_900L, nodeId = 1L)
        val storeA = mutableListOf(known, unknownType)

        val bStore = mutableListOf<SyncRecord>()
        var bCommitted: Hlc? = null

        val sessionA = SyncSession(
            transport = tA,
            localDeviceId = "a",
            clock = HlcClock(nodeId = 1L),
            source = SyncRecordSource { peerHlc, _ -> storeA.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { },
        )
        val sessionB = SyncSession(
            transport = tB,
            localDeviceId = "b",
            clock = HlcClock(nodeId = 2L),
            source = SyncRecordSource { _, _ -> emptyList() },
            sink = SyncRecordSink { rec ->
                if (rec.pk == "future_blob_meta") {
                    throw UnknownRecordTypeException("entity type for ${rec.pk} not mapped by this build")
                }
                bStore.add(rec)
            },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { hlc -> bCommitted = hlc },
        )

        coroutineScope {
            val ja = async { sessionA.run() }
            val jb = async { sessionB.run() }
            ja.await()
            jb.await()
        }

        // The unmapped-type record was deferred (not stored)…
        assertEquals(setOf("known"), bStore.map { it.pk }.toSet())
        // …and the watermark was HELD at the pre-round value, not advanced past the
        // unknown-type record — so the peer re-ships it every round until a build
        // that understands the type lands (Wave-1 #5), instead of skipping it forever.
        assertEquals(Hlc.ZERO, bCommitted)
    }

    @Test
    fun malformed_known_record_aborts_the_round_and_propagates() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()
        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.publicKey),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.publicKey),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (tA, tB) = transports

        // Unlike the orphan / unknown-type deferrals above, a *known* record that
        // the sink chokes on with a generic exception must NOT be swallowed: it
        // aborts the round and propagates, and the watermark is never committed.
        val known = bpRecord(pk = "known", physicalMs = 1_700_000_000_000L, nodeId = 1L)
        val malformed = bpRecord(pk = "malformed", physicalMs = 1_700_000_000_900L, nodeId = 1L)
        val storeA = mutableListOf(known, malformed)

        var bCommitted: Hlc? = null

        val sessionA = SyncSession(
            transport = tA,
            localDeviceId = "a",
            clock = HlcClock(nodeId = 1L),
            source = SyncRecordSource { peerHlc, _ -> storeA.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { },
        )
        val sessionB = SyncSession(
            transport = tB,
            localDeviceId = "b",
            clock = HlcClock(nodeId = 2L),
            source = SyncRecordSource { _, _ -> emptyList() },
            sink = SyncRecordSink { rec ->
                if (rec.pk == "malformed") {
                    throw IllegalArgumentException("malformed payload for ${rec.pk}")
                }
            },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { hlc -> bCommitted = hlc },
        )

        var thrown: Throwable? = null
        try {
            coroutineScope {
                val ja = async { sessionA.run() }
                val jb = async { sessionB.run() }
                ja.await()
                jb.await()
            }
        } catch (t: Throwable) {
            thrown = t
        }

        // The generic failure propagated out of the round (it was not deferred)…
        assertTrue(thrown != null)
        // …and the watermark was never committed because the apply aborted partway,
        // so nothing advanced past the bad record.
        assertEquals(null, bCommitted)
    }

    // ---- helpers ----

    private fun bpRecord(pk: String, physicalMs: Long, nodeId: Long): SyncRecord {
        return SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = pk,
            hlc = Hlc.of(physicalMs = physicalMs, logical = 0, nodeId = nodeId),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Int64(120),
                2 to SyncValue.Int64(80),
                4 to SyncValue.Int64(physicalMs),
            ),
        )
    }

    private suspend fun runInitiator(
        handshake: NoiseXkHandshake,
        channel: FrameChannel,
    ): NoiseTransport {
        channel.send(handshake.writeFirst())
        val m2 = channel.receive() ?: error("EOF m2")
        handshake.readSecond(m2)
        channel.send(handshake.writeThird())
        val (s, r) = handshake.transportCiphers()
        return NoiseTransport(channel = channel, send = s, receive = r)
    }

    private suspend fun runResponder(
        handshake: NoiseXkHandshake,
        channel: FrameChannel,
    ): NoiseTransport {
        val m1 = channel.receive() ?: error("EOF m1")
        val (_, m2) = handshake.readFirstAndWriteSecond(m1)
        channel.send(m2)
        val m3 = channel.receive() ?: error("EOF m3")
        handshake.readThird(m3)
        val (s, r) = handshake.transportCiphers()
        return NoiseTransport(channel = channel, send = s, receive = r)
    }
}
