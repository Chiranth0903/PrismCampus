package com.prismcampus.app.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPersistenceAndOfflineTest {
    @Test fun chatRestoresBothQuestionsAndAnswersWithActions() = runTest {
        var saved = ""
        val session = ChatSession(this, emptyList(), { saved = ChatHistoryCodec.encode(it) },
            { query, _ -> RouterResult(query, "Answer: $query", ExecutionTier.TIER_1_DETERMINISTIC, 1,
                actionType = RouterAction.SHOW_TIMETABLE) }, {})
        session.submit("next class", SupportedLanguage.ENGLISH)
        advanceUntilIdle()
        session.submit("timetable", SupportedLanguage.ENGLISH)
        advanceUntilIdle()
        val restored = ChatHistoryCodec.decode(saved)
        assertEquals(session.history.value, restored)
        assertEquals(listOf("next class", "timetable"), restored.filter { it.isUser }.map { it.text })
        assertEquals(2, restored.count { it.result?.actionType == RouterAction.SHOW_TIMETABLE })
        assertFalse(session.isProcessing.value)
    }

    @Test fun leavingScreenDoesNotCancelPendingAnswer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val screenScope = CoroutineScope(SupervisorJob() + dispatcher)
        val response = CompletableDeferred<RouterResult>()
        var saved = ""
        val spoken = mutableListOf<String>()
        val session = ChatSession(appScope, emptyList(), { saved = ChatHistoryCodec.encode(it) },
            { _, _ -> response.await() }, { spoken.add(it) })
        screenScope.launch { session.history.collect {} }
        session.submit("question", SupportedLanguage.ENGLISH)
        runCurrent()
        screenScope.cancel() // Home leaves composition when a different tab opens.
        assertTrue(session.isProcessing.value)
        response.complete(RouterResult("question", "Still answered", ExecutionTier.TIER_2_EDGE_SLM, 20))
        advanceUntilIdle()
        assertEquals("Still answered", ChatHistoryCodec.decode(saved).last().text)
        assertEquals(listOf("Still answered"), spoken)
        assertFalse(session.isProcessing.value)
        appScope.cancel()
    }

    @Test fun repeatedOfflinePartialsDoNotPreventCompletion() {
        val endpointer = SpeechEndpointer(0)
        endpointer.observe("eight plus five", 100)
        for (time in 200L..1300L step 100) endpointer.observe("eight plus five", time)
        assertFalse(endpointer.shouldFinish(1299))
        assertTrue(endpointer.shouldFinish(1300))
    }

    @Test fun offlineSpeechHasAnOverallDeadline() {
        val endpointer = SpeechEndpointer(0)
        for (time in 1000L..15000L step 1000) endpointer.observe("continuing speech $time", time)
        assertTrue(endpointer.shouldFinish(15000))
        assertFalse(SpeechEndpointer(0).shouldFinish(15000))
    }

    @Test fun arithmeticAnswersTypedAndSpokenQuestionsImmediately() {
        assertEquals("The answer is 13.", LocalArithmetic.answer("8+5"))
        assertEquals("The answer is 13.", LocalArithmetic.answer("What is eight plus five?"))
        assertEquals("The answer is 27.", LocalArithmetic.answer("twenty two plus five"))
        assertEquals("The answer is 14.", LocalArithmetic.answer("2+3*4"))
        assertEquals("The answer is 20.", LocalArithmetic.answer("(2+3)*4"))
        assertEquals("The answer is 0.3.", LocalArithmetic.answer("0.1+0.2"))
        assertEquals("Division by zero is undefined.", LocalArithmetic.answer("8/0"))
        assertNull(LocalArithmetic.answer("Explain recursion"))
        assertNull(LocalArithmetic.answer("next class at 8+5"))
        assertNull(LocalArithmetic.answer("8++"))
    }

    @Test fun malformedSavedChatDoesNotCrashAndGeneralPromptsStayShort() {
        assertTrue(ChatHistoryCodec.decode("{bad json").isEmpty())
        val prompt = OfflinePrompt.build("Explain recursion", "English")
        assertTrue(prompt.contains("Explain recursion"))
        assertTrue(prompt.length < 300)
        assertTrue(OfflinePrompt.build("x".repeat(10000), "English", "y".repeat(10000), "z".repeat(10000)).length < 1700)
    }
}
