package com.silverbp.android.ui.chat

import com.silverbp.android.core.Arm
import com.silverbp.android.core.BpReading
import com.silverbp.android.core.PartOfDay
import com.silverbp.android.core.Posture
import com.silverbp.android.core.Source
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Task 3 static estimate: builds a worst-case context string against synthetic
 * data and verifies it stays within the 1200-character budget threshold.
 *
 * Rule of thumb: 1 token ≈ 2 Chinese chars or 4 Latin chars.
 * The 1200-char threshold corresponds to roughly 400–600 tokens, acceptable for
 * Gemma 4 E2B's 4K context (system prompt + context + multi-turn chat).
 */
class RecordsContextBuilderTest {

    @Test
    fun `full output with 30 readings stays within 1200 char budget`() {
        val readings = syntheticReadings(count = 30)
        val output = RecordsContextBuilder.build(readings)

        println("=== RecordsContextBuilder output (${readings.size} readings) ===")
        println(output)
        println("=== chars=${output.length} ===")

        assertTrue(
            "Context too long: ${output.length} chars > 1200 (budget). " +
                "30日趨勢 section should have been auto-dropped.",
            output.length <= 1200,
        )
    }

    @Test
    fun `empty readings produces minimal output`() {
        val output = RecordsContextBuilder.build(emptyList())
        println("Empty readings output: $output (${output.length} chars)")
        assertTrue(output.isNotBlank())
        assertTrue(output.length < 300)
    }

    @Test
    fun `output for 5 readings does not exceed budget`() {
        val output = RecordsContextBuilder.build(syntheticReadings(count = 5))
        assertTrue(output.length <= 1200)
    }

    private fun syntheticReadings(count: Int): List<BpReading> {
        val now = Instant.now()
        return (0 until count).map { i ->
            val daysAgo = (count - i).toLong()
            BpReading(
                id = UUID.randomUUID(),
                systolic = 120 + (i % 15),
                diastolic = 75 + (i % 10),
                pulse = 70 + (i % 12),
                timestamp = now.minus(daysAgo, ChronoUnit.DAYS),
                arm = Arm.Left,
                posture = Posture.Sitting,
                partOfDay = if (i % 2 == 0) PartOfDay.Morning else PartOfDay.Evening,
                beforeMedication = true,
                source = Source.CameraGemma,
            )
        }
    }
}
