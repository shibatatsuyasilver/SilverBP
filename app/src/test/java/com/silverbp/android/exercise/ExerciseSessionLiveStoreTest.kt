package com.silverbp.android.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Covers the auto-pause / auto-resume state machine in [ExerciseSessionLiveStore].
 *
 * Regression target: a runner stopping briefly at a traffic light used to flip
 * the session into Paused with no auto-resume, silently discarding every metre
 * of the rest of the run (observed: 2.34 km recorded vs 3.68 km on Fitbit).
 */
class ExerciseSessionLiveStoreTest {

    private fun newStore(): ExerciseSessionLiveStore =
        ExerciseSessionLiveStore().also {
            it.start(ActivityKind.Running, Instant.ofEpochMilli(BASE_MS), stepBaseline = null)
        }

    private fun sample(
        timeMs: Long,
        lat: Double = TAIPEI_LAT,
        lon: Double = TAIPEI_LON,
        speedMps: Float? = 3.0f,
        accuracy: Float = 5f,
    ) = GpsSample(
        timeMs = timeMs,
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        speedMps = speedMps,
        altitude = null,
    )

    @Test
    fun `first sample records point with zero distance delta`() {
        val store = newStore()
        store.appendSample(sample(timeMs = BASE_MS + 1_000), wallClockNowMs = BASE_MS + 1_000)

        val live = store.flow.value!!
        assertEquals(1, live.routePoints.size)
        assertEquals(0.0, live.accumulatedDistanceMeters, 1e-6)
        assertEquals(RunState.Running, live.runState)
    }

    @Test
    fun `two moving samples accumulate haversine distance`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON), BASE_MS + 1_000)
        store.appendSample(
            sample(BASE_MS + 4_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON),
            BASE_MS + 4_000,
        )

        val live = store.flow.value!!
        assertEquals(2, live.routePoints.size)
        // ~100 m hop; allow a couple of metres for spherical math rounding.
        assertEquals(100.0, live.accumulatedDistanceMeters, 2.0)
        assertEquals(RunState.Running, live.runState)
    }

    @Test
    fun `8s of stillness flips to AutoPaused not Paused`() {
        val store = newStore()
        // Seed: one moving sample so lastMovementAtMillis is set.
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        // Then one stationary sample arriving 9 s after the moving sample.
        store.appendSample(sample(BASE_MS + 10_000, speedMps = 0f), BASE_MS + 10_000)

        assertEquals(RunState.AutoPaused, store.flow.value!!.runState)
    }

    @Test
    fun `moving sample while AutoPaused auto-resumes and accumulates distance`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f), BASE_MS + 1_000)
        // Force AutoPaused.
        store.appendSample(sample(BASE_MS + 10_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 0f), BASE_MS + 10_000)
        assertEquals(RunState.AutoPaused, store.flow.value!!.runState)
        val distanceBeforeResume = store.flow.value!!.accumulatedDistanceMeters

        // User keeps running — a moving sample 100 m away arrives.
        store.appendSample(
            sample(BASE_MS + 13_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON, speedMps = 3.0f),
            BASE_MS + 13_000,
        )

        val live = store.flow.value!!
        assertEquals("auto-resume should flip back to Running", RunState.Running, live.runState)
        assertTrue(
            "post-resume sample should add ~100 m, got delta = ${live.accumulatedDistanceMeters - distanceBeforeResume}",
            live.accumulatedDistanceMeters - distanceBeforeResume > 95.0,
        )
    }

    @Test
    fun `manual pause is sticky and drops moving samples`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f), BASE_MS + 1_000)
        store.pause()
        assertEquals(RunState.Paused, store.flow.value!!.runState)
        val before = store.flow.value!!.accumulatedDistanceMeters
        val countBefore = store.flow.value!!.routePoints.size

        // A moving sample MUST NOT auto-resume from manual Paused.
        store.appendSample(
            sample(BASE_MS + 4_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON, speedMps = 3.0f),
            BASE_MS + 4_000,
        )

        val live = store.flow.value!!
        assertEquals(RunState.Paused, live.runState)
        assertEquals(before, live.accumulatedDistanceMeters, 1e-6)
        assertEquals(countBefore, live.routePoints.size)
    }

    @Test
    fun `resume works from both Paused and AutoPaused`() {
        // Manual pause → resume.
        val a = newStore()
        a.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        a.pause()
        a.resume()
        assertEquals(RunState.Running, a.flow.value!!.runState)

        // AutoPaused → manual resume (user notices and taps Resume themselves).
        val b = newStore()
        b.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        b.appendSample(sample(BASE_MS + 10_000, speedMps = 0f), BASE_MS + 10_000)
        assertEquals(RunState.AutoPaused, b.flow.value!!.runState)
        b.resume()
        assertEquals(RunState.Running, b.flow.value!!.runState)
    }

    @Test
    fun `manual pause overrides AutoPaused`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        store.appendSample(sample(BASE_MS + 10_000, speedMps = 0f), BASE_MS + 10_000)
        assertEquals(RunState.AutoPaused, store.flow.value!!.runState)

        store.pause()
        assertEquals(RunState.Paused, store.flow.value!!.runState)

        // Movement after manual override stays Paused.
        store.appendSample(
            sample(BASE_MS + 13_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON, speedMps = 3.0f),
            BASE_MS + 13_000,
        )
        assertEquals(RunState.Paused, store.flow.value!!.runState)
    }

    @Test
    fun `samples failing accuracy or age filters are dropped`() {
        val store = newStore()
        // Bad accuracy.
        store.appendSample(sample(BASE_MS + 1_000, accuracy = 100f), BASE_MS + 1_000)
        assertEquals(0, store.flow.value!!.routePoints.size)

        // Stale (> 5 s).
        store.appendSample(sample(BASE_MS + 1_000), wallClockNowMs = BASE_MS + 10_000)
        assertEquals(0, store.flow.value!!.routePoints.size)

        // Good sample passes.
        store.appendSample(sample(BASE_MS + 2_000), BASE_MS + 2_000)
        assertEquals(1, store.flow.value!!.routePoints.size)
    }

    @Test
    fun `pace becomes available once distance crosses 30m floor`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON), BASE_MS + 1_000)
        store.appendSample(
            sample(BASE_MS + 4_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON),
            BASE_MS + 4_000,
        )
        assertNotNull(store.flow.value!!.paceSecPerKm)
    }

    // ─── idle reminder bookkeeping ──────────────────────────────────────────

    @Test
    fun `start sets pausedSinceMillis to null`() {
        val store = newStore()
        assertNull(store.flow.value!!.pausedSinceMillis)
    }

    @Test
    fun `manual pause sets pausedSinceMillis and resume clears it`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        store.pause()
        assertNotNull(store.flow.value!!.pausedSinceMillis)

        store.resume()
        assertNull(store.flow.value!!.pausedSinceMillis)
    }

    @Test
    fun `auto-pause records pausedSinceMillis using the wall-clock arg`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        store.appendSample(sample(BASE_MS + 10_000, speedMps = 0f), BASE_MS + 10_000)
        val live = store.flow.value!!
        assertEquals(RunState.AutoPaused, live.runState)
        // Wall-clock arg is used so the LocationTrackingService 10 min idle
        // check can compare with System.currentTimeMillis() apples-to-apples.
        assertEquals(BASE_MS + 10_000, live.pausedSinceMillis)
    }

    @Test
    fun `auto-resume on movement clears pausedSinceMillis`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f), BASE_MS + 1_000)
        store.appendSample(sample(BASE_MS + 10_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 0f), BASE_MS + 10_000)
        assertNotNull(store.flow.value!!.pausedSinceMillis)

        store.appendSample(
            sample(BASE_MS + 13_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON, speedMps = 3.0f),
            BASE_MS + 13_000,
        )
        assertNull(store.flow.value!!.pausedSinceMillis)
    }

    @Test
    fun `manual pause from AutoPaused refreshes pausedSinceMillis`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        store.appendSample(sample(BASE_MS + 10_000, speedMps = 0f), BASE_MS + 10_000)
        val autoPausedAt = store.flow.value!!.pausedSinceMillis!!

        store.pause()  // uses System.currentTimeMillis(), distinct from the wall-clock arg above
        val manualPausedAt = store.flow.value!!.pausedSinceMillis!!
        // Strictly newer (or at worst equal) — never older. Guards against
        // the bug where transitioning AutoPaused → Paused would immediately
        // re-trip the idle reminder using the older AutoPaused timestamp.
        assertTrue(
            "manual pause should refresh pausedSinceMillis (autoPausedAt=$autoPausedAt, manualPausedAt=$manualPausedAt)",
            manualPausedAt >= autoPausedAt,
        )
    }

    @Test
    fun `acknowledgeIdleReminder pushes pausedSinceMillis forward`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        store.appendSample(sample(BASE_MS + 10_000, speedMps = 0f), BASE_MS + 10_000)
        val before = store.flow.value!!.pausedSinceMillis!!

        val ackAt = before + 5 * 60_000L  // user tapped "Keep going" 5 min into the pause
        store.acknowledgeIdleReminder(nowMs = ackAt)

        val after = store.flow.value!!.pausedSinceMillis!!
        assertEquals(ackAt, after)
        assertNotEquals(before, after)
        // runState must NOT change — user is acknowledging, not resuming.
        assertEquals(RunState.AutoPaused, store.flow.value!!.runState)
    }

    @Test
    fun `acknowledgeIdleReminder is a no-op when Running`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        assertEquals(RunState.Running, store.flow.value!!.runState)
        assertNull(store.flow.value!!.pausedSinceMillis)

        store.acknowledgeIdleReminder(nowMs = BASE_MS + 60_000)

        // Running state shouldn't suddenly acquire a paused timestamp.
        assertNull(store.flow.value!!.pausedSinceMillis)
        assertEquals(RunState.Running, store.flow.value!!.runState)
    }

    @Test
    fun `snapshotAndFinish clears pausedSinceMillis`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, speedMps = 3.0f), BASE_MS + 1_000)
        store.pause()
        assertNotNull(store.flow.value!!.pausedSinceMillis)

        store.snapshotAndFinish(Instant.ofEpochMilli(BASE_MS + 5_000))
        assertNull(store.flow.value!!.pausedSinceMillis)
        assertEquals(RunState.Finished, store.flow.value!!.runState)
    }

    private companion object {
        const val BASE_MS = 1_700_000_000_000L
        const val TAIPEI_LAT = 25.0330
        const val TAIPEI_LON = 121.5654
        // 1 degree latitude ≈ 111 km, so ~100 m ≈ 0.0009°.
        const val DEG_PER_100M = 0.0009
    }
}
