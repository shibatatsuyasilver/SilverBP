package com.silverbp.android.coach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BpAnomalyDedupTest {

    private val cooldown = BpAnomalyWatcher.COOLDOWN_MILLIS

    @Test fun `alerts on a brand-new episode`() {
        // Nothing alerted yet (lastAlertedAt=0, lastFiredAt=0).
        assertTrue(
            BpAnomalyWatcher.shouldAlert(
                lastAlertedAt = 0L,
                anomalyTriggeredAt = 1_000L,
                now = 10_000L,
                lastFiredAt = 0L,
                cooldownMillis = cooldown,
            )
        )
    }

    @Test fun `skips the same episode re-detected later`() {
        // Same triggering window as already alerted → never re-fire, even after
        // the cooldown has elapsed.
        val triggered = 5_000L
        assertFalse(
            BpAnomalyWatcher.shouldAlert(
                lastAlertedAt = triggered,
                anomalyTriggeredAt = triggered,
                now = triggered + cooldown * 2,
                lastFiredAt = triggered,
                cooldownMillis = cooldown,
            )
        )
    }

    @Test fun `skips an older window than the last alerted`() {
        assertFalse(
            BpAnomalyWatcher.shouldAlert(
                lastAlertedAt = 5_000L,
                anomalyTriggeredAt = 4_000L,
                now = 100_000L,
                lastFiredAt = 5_000L,
                cooldownMillis = cooldown,
            )
        )
    }

    @Test fun `cooldown blocks a newer episode within the window`() {
        // Newer triggering window, but the 30-min cooldown hasn't elapsed.
        assertFalse(
            BpAnomalyWatcher.shouldAlert(
                lastAlertedAt = 1_000L,
                anomalyTriggeredAt = 2_000L,
                now = 1_000L + cooldown - 1,
                lastFiredAt = 1_000L,
                cooldownMillis = cooldown,
            )
        )
    }

    @Test fun `alerts on a newer episode once cooldown elapses`() {
        assertTrue(
            BpAnomalyWatcher.shouldAlert(
                lastAlertedAt = 1_000L,
                anomalyTriggeredAt = 2_000L,
                now = 1_000L + cooldown,
                lastFiredAt = 1_000L,
                cooldownMillis = cooldown,
            )
        )
    }
}
