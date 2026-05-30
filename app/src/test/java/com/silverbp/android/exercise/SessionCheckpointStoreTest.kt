package com.silverbp.android.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class SessionCheckpointStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sessionId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private fun sampleLive() = SessionLive(
        id = sessionId,
        kind = ActivityKind.Running,
        startedAt = Instant.ofEpochMilli(1_000),
        runState = RunState.Running,
        routePoints = listOf(
            RoutePoint(
                id = UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
                sessionId = sessionId,
                timestamp = Instant.ofEpochMilli(2_000),
                lat = 25.0330,
                lon = 121.5654,
                horizontalAccuracy = 4.5f,
                altitude = 12.0,
                speedMps = 2.7f,
            ),
        ),
        accumulatedDistanceMeters = 123.4,
        activeDurationMillis = 60_000,
        paceSecPerKm = 300.0,
        stepCount = 80,
        stepBaseline = 1_000L,
        lastMovementAtMillis = 2_000,
        lastSampleAtMillis = 2_000,
        lastResumeAtMillis = 1_000,
        pausedSinceMillis = null,
    )

    @Test
    fun `round-trips a session exactly`() {
        val store = SessionCheckpointStore(tmp.newFile("cp.json"))
        val original = sampleLive()
        store.save(original)
        assertEquals(original, store.load())
    }

    @Test
    fun `load returns null when no checkpoint exists`() {
        assertNull(SessionCheckpointStore(File(tmp.root, "absent.json")).load())
    }

    @Test
    fun `clear removes the checkpoint`() {
        val f = tmp.newFile("cp.json")
        val store = SessionCheckpointStore(f)
        store.save(sampleLive())
        assertTrue(f.exists())
        store.clear()
        assertFalse(f.exists())
        assertNull(store.load())
    }

    @Test
    fun `corrupt file loads as null rather than throwing`() {
        val f = tmp.newFile("cp.json")
        f.writeText("{ this is not valid json")
        assertNull(SessionCheckpointStore(f).load())
    }
}
