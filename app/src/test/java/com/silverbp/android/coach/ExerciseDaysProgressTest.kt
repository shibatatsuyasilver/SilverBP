package com.silverbp.android.coach

import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.ui.coach.countDistinctExerciseDays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ExerciseDaysProgressTest {

    private val taipei: ZoneId = ZoneId.of("Asia/Taipei")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun session(
        startedAt: Instant,
        durationMin: Long = 30,
        kind: ActivityKind = ActivityKind.Walking,
    ) = ExerciseSession(
        kind = kind,
        startedAt = startedAt,
        endedAt = startedAt.plus(Duration.ofMinutes(durationMin)),
        activeDurationMillis = Duration.ofMinutes(durationMin).toMillis(),
        distanceMeters = 1500.0,
    )

    @Test fun `empty list returns 0`() {
        assertEquals(0, countDistinctExerciseDays(emptyList(), taipei))
    }

    @Test fun `three sessions on the same Taipei day count as 1`() {
        // 2024-01-01 in Asia/Taipei (+08): 09:00, 14:00, 22:30 local.
        val sessions = listOf(
            session(Instant.parse("2024-01-01T01:00:00Z")),
            session(Instant.parse("2024-01-01T06:00:00Z")),
            session(Instant.parse("2024-01-01T14:30:00Z")),
        )
        assertEquals(1, countDistinctExerciseDays(sessions, taipei))
    }

    @Test fun `sessions on Mon Wed Fri count as 3`() {
        val sessions = listOf(
            session(Instant.parse("2024-01-01T01:00:00Z")), // Mon Taipei
            session(Instant.parse("2024-01-03T01:00:00Z")), // Wed Taipei
            session(Instant.parse("2024-01-05T01:00:00Z")), // Fri Taipei
        )
        assertEquals(3, countDistinctExerciseDays(sessions, taipei))
    }

    @Test fun `cross-zone correctness depends on supplied ZoneId`() {
        // Two instants:
        //   A = 2024-01-01T05:00:00Z → Jan 1 13:00 Taipei, Jan 1 05:00 UTC
        //   B = 2024-01-01T17:00:00Z → Jan 2 01:00 Taipei, Jan 1 17:00 UTC
        // Taipei: 2 distinct days. UTC: 1 distinct day.
        val sessions = listOf(
            session(Instant.parse("2024-01-01T05:00:00Z")),
            session(Instant.parse("2024-01-01T17:00:00Z")),
        )
        assertEquals(2, countDistinctExerciseDays(sessions, taipei))
        assertEquals(1, countDistinctExerciseDays(sessions, utc))
    }

    @Test fun `midnight boundary in Taipei produces two distinct days`() {
        // Dec 31 23:59 Taipei = 2023-12-31T15:59:00Z
        // Jan  1 00:01 Taipei = 2023-12-31T16:01:00Z
        val sessions = listOf(
            session(Instant.parse("2023-12-31T15:59:00Z")),
            session(Instant.parse("2023-12-31T16:01:00Z")),
        )
        assertEquals(2, countDistinctExerciseDays(sessions, taipei))
    }

    @Test fun `mixed walking and running on the same day count as 1`() {
        val sessions = listOf(
            session(Instant.parse("2024-01-01T01:00:00Z"), kind = ActivityKind.Walking),
            session(Instant.parse("2024-01-01T08:00:00Z"), kind = ActivityKind.Running),
        )
        assertEquals(1, countDistinctExerciseDays(sessions, taipei))
    }
}
