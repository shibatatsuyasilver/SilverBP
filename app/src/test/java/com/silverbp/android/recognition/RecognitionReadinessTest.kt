package com.silverbp.android.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionReadinessTest {

    @Test
    fun `cloud is ready regardless of model phase`() {
        val readiness = RecognitionReadiness(RecognitionBackend.Cloud, ModelLoadPhase.Idle)

        assertTrue(readiness.ready)
        assertFalse(readiness.showModelBanner)
    }

    @Test
    fun `local waits for ready phase`() {
        assertFalse(RecognitionReadiness(RecognitionBackend.Local, ModelLoadPhase.Idle).ready)
        assertTrue(RecognitionReadiness(RecognitionBackend.Local, ModelLoadPhase.Ready).ready)
    }

    @Test
    fun `aicore waits for ready phase`() {
        val loading = RecognitionReadiness(RecognitionBackend.AICore, ModelLoadPhase.Loading)

        assertFalse(loading.ready)
        assertTrue(loading.showModelBanner)
        assertTrue(RecognitionReadiness(RecognitionBackend.AICore, ModelLoadPhase.Ready).ready)
    }
}
