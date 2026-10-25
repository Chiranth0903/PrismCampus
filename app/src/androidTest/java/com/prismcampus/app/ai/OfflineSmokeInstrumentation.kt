package com.prismcampus.app.ai

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking

/** Direct on-device inference check: no network, microphone or UI interaction. */
class OfflineSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            check(LocalArithmetic.answer("eight plus five") == "The answer is 13.")
            val manager = LocalSLMManager(targetContext)
            val start = SystemClock.elapsedRealtime()
            val answer = runBlocking {
                manager.generateChatResponse(OfflinePrompt.build("What is a stack in programming?", "English"))
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            check(!answer.isNullOrBlank()) { manager.engineStatus.value }
            check(elapsed < 35000L) { "Offline response took ${elapsed}ms" }
            results.putString("stream", "Offline arithmetic passed. Native Qwen answered in ${elapsed}ms: $answer\n")
            results.putLong("latencyMs", elapsed)
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            results.putString("stream", "Offline smoke check failed: ${failure.message}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
