package com.silverbp.android.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Covers the auto-pause / auto-resume state machine in [ExerciseSessionLiveStore].
 *
 * Regression target: a runner stopping briefly at a traffic light used to flip
 * the session into Paused with no auto-resume, silently discarding every metre
 * of the rest of the run (observed: 2.34 km recorded vs 3.68 km on Fitbit).
 */
class ExerciseSessionLiveStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): ExerciseSessionLiveStore =
        ExerciseSessionLiveStore().also {
            it.start(ActivityKind.Running, Instant.ofEpochMilli(BASE_MS), stepBaseline = null)
        }

    private fun newCheckpoint() = SessionCheckpointStore(File(tmp.root, "cp.json"))

    private fun newStoreWith(checkpoint: SessionCheckpointStore): ExerciseSessionLiveStore =
        ExerciseSessionLiveStore(checkpoint).also {
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
    fun `distance does not jump across a long gap between fixes`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON), BASE_MS + 1_000)
        // A fix 60 s later, 100 m away — a GPS dropout/tunnel, or the service
        // killed and the session restored from a checkpoint. The straight-line
        // teleport must NOT accrue as distance.
        store.appendSample(
            sample(BASE_MS + 61_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON),
            BASE_MS + 61_000,
        )
        val live = store.flow.value!!
        assertEquals(2, live.routePoints.size)
        assertEquals(0.0, live.accumulatedDistanceMeters, 1e-6)
    }

    @Test
    fun `active duration does not jump across a long GPS dropout`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f), BASE_MS + 1_000)
        val beforeGap = store.flow.value!!.activeDurationMillis
        // A 10 min dropout while still Running (a moving fix resumes far later).
        // The gap sample must accrue at most the cap, not the full 10 min.
        store.appendSample(
            sample(BASE_MS + 601_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f),
            BASE_MS + 601_000,
        )
        val gapDelta = store.flow.value!!.activeDurationMillis - beforeGap
        assertTrue(
            "active duration delta should be capped at MAX_DURATION_GAP_MS, got $gapDelta",
            gapDelta <= ExerciseSessionLiveStore.MAX_DURATION_GAP_MS,
        )
    }

    @Test
    fun `stationary jitter while AutoPaused accrues no distance and no route point`() {
        val store = newStore()
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f), BASE_MS + 1_000)
        // Force AutoPaused.
        store.appendSample(sample(BASE_MS + 10_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 0f), BASE_MS + 10_000)
        assertEquals(RunState.AutoPaused, store.flow.value!!.runState)
        val distanceBefore = store.flow.value!!.accumulatedDistanceMeters
        val pointsBefore = store.flow.value!!.routePoints.size

        // A jittery, non-moving fix that has drifted ~100 m while standing still.
        store.appendSample(
            sample(BASE_MS + 13_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON, speedMps = 0f),
            BASE_MS + 13_000,
        )

        val live = store.flow.value!!
        assertEquals(RunState.AutoPaused, live.runState)
        assertEquals("jitter while AutoPaused must add 0 m", distanceBefore, live.accumulatedDistanceMeters, 1e-6)
        assertEquals("jitter while AutoPaused must not append a route point", pointsBefore, live.routePoints.size)
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

    @Test
    fun `snapshotAndFinish carries activeDurationMillis into the persisted session`() {
        val store = newStore()
        // Two moving samples 3 s apart → activeDurationMillis = 3000.
        store.appendSample(sample(BASE_MS + 1_000, TAIPEI_LAT, TAIPEI_LON, speedMps = 3.0f), BASE_MS + 1_000)
        store.appendSample(
            sample(BASE_MS + 4_000, TAIPEI_LAT + DEG_PER_100M, TAIPEI_LON, speedMps = 3.0f),
            BASE_MS + 4_000,
        )
        val activeBefore = store.flow.value!!.activeDurationMillis
        assertTrue("active duration should accumulate from moving samples", activeBefore >= 3_000L)

        // 30 s wall-clock gap, then stop. The wall-clock window (endedAt -
        // startedAt) is ~30 s; activeDurationMillis stays at 3 s — that's the
        // whole point of the field.
        val (session, _) = store.snapshotAndFinish(Instant.ofEpochMilli(BASE_MS + 30_000))!!

        assertEquals(activeBefore, session.activeDurationMillis)
        val wallClockMs = session.endedAt.toEpochMilli() - session.startedAt.toEpochMilli()
        assertTrue(
            "wall-clock duration ($wallClockMs ms) must exceed active duration (${session.activeDurationMillis} ms)",
            wallClockMs > session.activeDurationMillis,
        )
    }

    // ─── checkpoint lifecycle (Stop → Save 之間的資料保命) ───────────────────

    @Test
    fun `snapshotAndFinish keeps a Finished checkpoint instead of clearing it`() {
        // Regression target: snapshotAndFinish used to clear the checkpoint at
        // Stop, so a process kill on the summary screen (before Save wrote the
        // Room row) silently lost the whole workout.
        val cp = newCheckpoint()
        val store = newStoreWith(cp)
        store.appendSample(sample(BASE_MS + 1_000), BASE_MS + 1_000)

        store.snapshotAndFinish(Instant.ofEpochMilli(BASE_MS + 5_000))

        val saved = cp.load()
        assertNotNull("checkpoint must survive until summary Save/Discard", saved)
        assertEquals(RunState.Finished, saved!!.runState)
        assertEquals(1, saved.routePoints.size)
    }

    @Test
    fun `clear after snapshotAndFinish removes the checkpoint`() {
        // Summary Save and Discard both end in liveStore.clear() — the
        // checkpoint must be gone exactly then, not at Stop.
        val cp = newCheckpoint()
        val store = newStoreWith(cp)
        store.appendSample(sample(BASE_MS + 1_000), BASE_MS + 1_000)
        store.snapshotAndFinish(Instant.ofEpochMilli(BASE_MS + 5_000))
        assertNotNull(cp.load())

        store.clear()
        assertNull(cp.load())
        assertNull(store.flow.value)
    }

    @Test
    fun `Finished checkpoint is recoverable after process death`() {
        val cp = newCheckpoint()
        val store = newStoreWith(cp)
        store.appendSample(sample(BASE_MS + 1_000), BASE_MS + 1_000)
        store.snapshotAndFinish(Instant.ofEpochMilli(BASE_MS + 5_000))

        // Simulate process death: a fresh store over the same checkpoint file.
        val reborn = ExerciseSessionLiveStore(cp)
        val orphan = reborn.recoverableCheckpoint()
        assertNotNull(orphan)
        assertEquals(RunState.Finished, orphan!!.runState)
    }

    @Test
    fun `restore keeps a Finished snapshot Finished`() {
        // Finished 檢查點只差摘要頁儲存 — 不得退回可追蹤的 Paused 狀態。
        val cp = newCheckpoint()
        val store = newStoreWith(cp)
        store.appendSample(sample(BASE_MS + 1_000), BASE_MS + 1_000)
        store.snapshotAndFinish(Instant.ofEpochMilli(BASE_MS + 5_000))
        val orphan = ExerciseSessionLiveStore(cp).recoverableCheckpoint()!!

        val target = ExerciseSessionLiveStore()
        target.restore(orphan)
        assertEquals(RunState.Finished, target.flow.value!!.runState)
    }

    @Test
    fun `restore forces a non-finished snapshot to Paused`() {
        val orphan = newStore().flow.value!!  // Running
        val target = ExerciseSessionLiveStore()
        target.restore(orphan)

        val live = target.flow.value!!
        assertEquals(RunState.Paused, live.runState)
        assertNotNull(live.pausedSinceMillis)
    }

    private companion object {
        const val BASE_MS = 1_700_000_000_000L
        const val TAIPEI_LAT = 25.0330
        const val TAIPEI_LON = 121.5654
        // 1 degree latitude ≈ 111 km, so ~100 m ≈ 0.0009°.
        const val DEG_PER_100M = 0.0009
    }
}
