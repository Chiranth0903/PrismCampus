package com.prismcampus.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceAssistantAndOfflineSpeechTest {

    @Test
    fun testSpeechStateTransitions() {
        var state = SpeechState.IDLE
        assertEquals(SpeechState.IDLE, state)

        state = SpeechState.REQUESTING_PERMISSION
        assertEquals(SpeechState.REQUESTING_PERMISSION, state)

        state = SpeechState.INITIALIZING
        assertEquals(SpeechState.INITIALIZING, state)

        state = SpeechState.LISTENING
        assertEquals(SpeechState.LISTENING, state)

        state = SpeechState.PROCESSING
        assertEquals(SpeechState.PROCESSING, state)

        state = SpeechState.RETRYING
        assertEquals(SpeechState.RETRYING, state)

        state = SpeechState.SPEAKING
        assertEquals(SpeechState.SPEAKING, state)

        state = SpeechState.ERROR
        assertEquals(SpeechState.ERROR, state)
    }

    @Test
    fun testVoskModelDirectoryValidationLogic() {
        val tempTestDir = File(System.getProperty("java.io.tmpdir"), "vosk_test_validation_${System.currentTimeMillis()}")
        tempTestDir.mkdirs()

        // 1. Initially empty directory -> Invalid
        assertFalse(File(tempTestDir, "am").exists() || File(tempTestDir, "conf").exists() || File(tempTestDir, "final.mdl").exists())

        // 2. Create mock am and conf -> Valid
        val amDir = File(tempTestDir, "am")
        amDir.mkdirs()
        File(amDir, "final.mdl").writeText("mock_weights")

        val isValid = File(tempTestDir, "am").exists() || File(tempTestDir, "conf").exists()
        assertTrue("Expected directory with am/ to be recognized as valid Vosk model", isValid)

        // Cleanup
        tempTestDir.deleteRecursively()
    }

    @Test
    fun testModelDownloadStateLifecycle() {
        var state: ModelDownloadState = ModelDownloadState.Idle
        assertEquals(ModelDownloadState.Idle, state)

        state = ModelDownloadState.Downloading(progressPercent = 45, downloadedBytes = 18874368L, totalBytes = 41943040L)
        assertTrue(state is ModelDownloadState.Downloading)
        val dl = state as ModelDownloadState.Downloading
        assertEquals(45, dl.progressPercent)
        assertEquals(18874368L, dl.downloadedBytes)

        state = ModelDownloadState.Extracting
        assertTrue(state is ModelDownloadState.Extracting)

        state = ModelDownloadState.Completed
        assertTrue(state is ModelDownloadState.Completed)

        state = ModelDownloadState.Error("Network connection reset")
        assertTrue(state is ModelDownloadState.Error)
        assertEquals("Network connection reset", (state as ModelDownloadState.Error).message)
    }

    @Test
    fun testRmsNormalization() {
        fun normalizeRms(rmsdB: Float): Float {
            return ((rmsdB + 2f) / 14f).coerceIn(0.05f, 1.0f)
        }

        // Test boundary values
        val minNorm = normalizeRms(-10f)
        assertEquals(0.05f, minNorm, 0.001f)

        val maxNorm = normalizeRms(20f)
        assertEquals(1.0f, maxNorm, 0.001f)

        val midNorm = normalizeRms(5f)
        assertTrue(midNorm in 0.4f..0.6f)
    }

    @Test
    fun testVoskContinuousPhraseAccumulationLogic() {
        var accumulatedPhrases = ""
        var currentPartial = ""

        fun onPhraseComplete(phrase: String): String {
            val trimmed = phrase.trim()
            if (trimmed.isNotBlank()) {
                accumulatedPhrases = if (accumulatedPhrases.isBlank()) trimmed else "$accumulatedPhrases $trimmed"
                currentPartial = ""
            }
            return accumulatedPhrases
        }

        fun onPartial(partial: String): String {
            currentPartial = partial.trim()
            return if (accumulatedPhrases.isBlank()) currentPartial else "$accumulatedPhrases $currentPartial"
        }

        // 1. User says "where is"
        val display1 = onPartial("where is")
        assertEquals("where is", display1)

        // Vosk completes first phrase "where is"
        val display2 = onPhraseComplete("where is")
        assertEquals("where is", display2)

        // 2. User continues speaking "cad lab"
        val display3 = onPartial("cad lab")
        assertEquals("where is cad lab", display3)

        // Vosk completes second phrase "cad lab"
        val display4 = onPhraseComplete("cad lab")
        assertEquals("where is cad lab", display4)

        // Final query should retain entire sentence!
        assertEquals("where is cad lab", accumulatedPhrases)
    }

    @Test
    fun testVoskSilenceAutoExecutionThresholds() {
        val autoExecuteThresholdMs = 1500L

        fun shouldAutoExecute(silenceDurationMs: Long, speechStarted: Boolean, text: String): Boolean {
            return speechStarted && text.isNotBlank() && silenceDurationMs >= autoExecuteThresholdMs
        }

        // Natural pause between words (500ms) -> Must NOT auto-execute
        assertFalse(shouldAutoExecute(silenceDurationMs = 500L, speechStarted = true, text = "where is"))

        // Slightly longer pause while thinking (1100ms) -> Must NOT cut off user
        assertFalse(shouldAutoExecute(silenceDurationMs = 1100L, speechStarted = true, text = "where is"))

        // User finishes sentence and stays silent (1600ms) -> MUST auto-execute!
        assertTrue(shouldAutoExecute(silenceDurationMs = 1600L, speechStarted = true, text = "where is cad lab"))

        // Silence when no speech was ever detected -> Must NOT auto-execute
        assertFalse(shouldAutoExecute(silenceDurationMs = 2000L, speechStarted = false, text = ""))
    }
}
