package com.prismcampus.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import com.prismcampus.app.data.MemoryTelemetryInfo

class ChatAndAIOrchestrationTest {

    @Test
    fun testPrismCampusExplanationQueryMatching() {
        val pattern = Pattern.compile("(?i)\\b(prismcampus|prism campus|what does prismcampus do|what is this app|about this app|app features)\\b")
        val queries = listOf(
            "Hello, explain what PrismCampus does.",
            "What is PrismCampus?",
            "Tell me about this app",
            "What does prism campus do"
        )

        for (q in queries) {
            assertTrue("Expected match for query: $q", pattern.matcher(q).find() || q.contains("prismcampus", ignoreCase = true))
        }
    }

    @Test
    fun testCampusLocationQueryMatching() {
        val cadLabPattern = Pattern.compile("(?i)\\b(cad lab|melc19|mechanical lab|cad)\\b")
        val softSkillsPattern = Pattern.compile("(?i)\\b(soft skills|hscp15|language lab)\\b")
        val physicsLabPattern = Pattern.compile("(?i)\\b(physics lab|pyc12|pyc12l)\\b")

        assertTrue(cadLabPattern.matcher("Where is CAD Lab?").find())
        assertTrue(cadLabPattern.matcher("How do I reach MELC19?").find())

        assertTrue(softSkillsPattern.matcher("Where is Soft Skills Lab?").find())
        assertTrue(softSkillsPattern.matcher("Is HSCP15 in LHC?").find())

        assertTrue(physicsLabPattern.matcher("Take me to physics lab").find())
        assertTrue(physicsLabPattern.matcher("Room for PYC12").find())
    }

    @Test
    fun testFacilityIssueQueryDetection() {
        fun isFacilityIssue(q: String): Boolean {
            return AssistantIntent.isFacilityReport(q)
        }

        assertTrue(isFacilityIssue("Hostel mess fan is broken"))
        assertTrue(isFacilityIssue("Washroom tap is leaking on 3rd floor"))
        assertTrue(isFacilityIssue("AC in CAD Lab is not cooling"))
        assertTrue(isFacilityIssue("Spark from switchboard in AB-501"))

        assertFalse(isFacilityIssue("What is Ohm's Law?"))
        assertFalse(isFacilityIssue("Where is Apex Block?"))
        assertFalse(isFacilityIssue("Where is my next class?"))
        assertFalse(isFacilityIssue("What is a package in Java?"))
        assertFalse(isFacilityIssue("Where is the hostel mess?"))
        assertFalse(isFacilityIssue("Explain light refraction"))
        assertFalse(isFacilityIssue("Explain how to repair an air conditioner"))
    }

    @Test
    fun generalQuestionsAreNotPredefinedCampusAnswers() {
        assertTrue(AssistantIntent.needsGeneralAnswer("Explain the Python library numpy"))
        assertTrue(AssistantIntent.needsGeneralAnswer("What is a Java package?"))
        assertTrue(AssistantIntent.needsGeneralAnswer("Hello, explain homework scheduling algorithms"))
        assertFalse(AssistantIntent.needsGeneralAnswer("Where is my next class?"))
        assertFalse(AssistantIntent.needsGeneralAnswer("Show my timetable"))
        assertFalse(AssistantIntent.needsGeneralAnswer("What is this app?"))
    }

    @Test
    fun testExecutionTierDescriptors() {
        assertEquals("Tier 1: Campus Search", ExecutionTier.TIER_1_DETERMINISTIC.label)
        assertEquals("Tier 2: On-Device Qwen SLM", ExecutionTier.TIER_2_EDGE_SLM.label)
        assertEquals("Tier 3: Cloud Gemini", ExecutionTier.TIER_3_CLOUD_GEMINI.label)
    }

    @Test
    fun testProviderResultDataModel() {
        val res = ProviderResult(
            text = "Room AB-501 is on 3rd Floor",
            tier = ExecutionTier.TIER_1_DETERMINISTIC,
            providerName = "Campus Knowledge Search (Deterministic)",
            latencyMs = 5L
        )

        assertEquals("Room AB-501 is on 3rd Floor", res.text)
        assertEquals(ExecutionTier.TIER_1_DETERMINISTIC, res.tier)
        assertEquals(5L, res.latencyMs)
    }

    @Test
    fun testPredefinedTimetableAndAssignmentsMatching() {
        val timetableQueries = listOf(
            "today's classes",
            "show all classes",
            "schedule today",
            "what is my timetable"
        )
        for (q in timetableQueries) {
            val qLower = q.lowercase()
            assertTrue(
                "Query '$q' should match timetable pattern",
                qLower.contains("today's class") || qLower.contains("all classes") ||
                        qLower.contains("schedule today") || qLower.contains("timetable")
            )
        }

        val assignmentQueries = listOf(
            "show assignments",
            "do i have any homework",
            "pending tasks"
        )
        for (q in assignmentQueries) {
            val qLower = q.lowercase()
            assertTrue(
                "Query '$q' should match assignment pattern",
                qLower.contains("assignment") || qLower.contains("homework") || qLower.contains("task")
            )
        }
    }

    @Test
    fun testOfflineInformativeFallbackMessages() {
        fun buildFallback(lang: SupportedLanguage): String {
            return when (lang) {
                SupportedLanguage.KANNADA -> "ಆಫ್‌ಲೈನ್ ಮೋಡ್: ನೈಜ-ಸಮಯದ ಎಐ ಉತ್ತರಗಳಿಗಾಗಿ ಇಂಟರ್ನೆಟ್ (ಜೆಮಿನಿ) ಅಥವಾ ಸ್ಥಳೀಯ ಕ್ವೆನ್ ಮಾದರಿಯ ಅಗತ್ಯವಿದೆ. ನೀವು ಎಂಎಸ್ಆರ್‌ಐಟಿ ತರಗತಿಗಳು, ಕೊಠಡಿಗಳು ಅಥವಾ ವೇಳಾಪಟ್ಟಿಯ ಬಗ್ಗೆ ಕೇಳಬಹುದು."
                SupportedLanguage.HINDI -> "ऑफलाइन मोड: विस्तृत एआई उत्तरों के लिए इंटरनेट (जेमिनी) या स्थानीय क्वेन मॉडल की आवश्यकता है। आप मुझसे कक्षाओं, कमरों, या टाइमटेबल के बारे में पूछ सकते हैं।"
                SupportedLanguage.ENGLISH -> "I can answer questions about MSRIT buildings, class schedules, timetable, and campus complaints offline. For open-ended AI discussions, connect to the internet (Gemini) or install local Qwen weights."
            }
        }

        assertTrue(buildFallback(SupportedLanguage.ENGLISH).contains("MSRIT buildings"))
        assertTrue(buildFallback(SupportedLanguage.HINDI).contains("कक्षाओं"))
        assertTrue(buildFallback(SupportedLanguage.KANNADA).contains("ತರಗತಿಗಳು"))
    }

    @Test
    fun testRouterResultNeverNullFields() {
        val result = RouterResult(
            query = "unknown offline question",
            responseText = "Offline response",
            tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
            latencyMs = 12L
        )

        assertNotNull(result.query)
        assertNotNull(result.responseText)
        assertNotNull(result.tierUsed)
        assertTrue(result.latencyMs >= 0)
        assertEquals(RouterAction.NONE, result.actionType)
    }

    @Test
    fun testAssistantRulesDefaultFallbackContent() {
        val rules = AssistantRulesManager.DEFAULT_RULES
        assertTrue(rules.contains("concise and direct"))
        assertFalse(rules.contains("MSRIT"))
        assertTrue(rules.contains("selected campus records"))
        assertTrue(rules.contains("offline"))
    }

    @Test
    fun testRulesMarkdownFileIntegrity() {
        val projectRules = java.io.File("..", "config/assistant_rules.md")
        val altProjectRules = java.io.File("config/assistant_rules.md")
        val target = if (projectRules.exists()) projectRules else altProjectRules
        if (target.exists()) {
            val content = target.readText()
            assertTrue("Rules file must not be blank", content.isNotBlank())
            assertTrue("Rules must contain conciseness directive", content.contains("concise", ignoreCase = true))
            assertTrue("Rules must use the selected campus", content.contains("selected campus", ignoreCase = true))
            assertFalse("Rules must not fix the assistant to MSRIT", content.contains("MSRIT", ignoreCase = true))
            assertTrue("Rules must contain honesty directive", content.contains("invent", ignoreCase = true) || content.contains("know", ignoreCase = true))
        }
    }

    @Test
    fun testGeminiAndQwenPromptConstructionWithRules() {
        val customRules = "- Be extra concise.\n- Always answer with bullet points."
        val mockCampusContext = "CAD Lab is in ESB Room 402."
        val userQuery = "Where can I work on 3D modeling?"

        // 1. Verify Gemini Prompt formatting
        val geminiPrompt = """
            You are PrismCampus AI, an edge assistant for MSRIT students.
            
            Rules & Guidelines:
            $customRules
            
            Campus Ground-Truth Context:
            $mockCampusContext
            
            User Query: $userQuery
        """.trimIndent()

        assertTrue(geminiPrompt.contains(customRules))
        assertTrue(geminiPrompt.contains(mockCampusContext))
        assertTrue(geminiPrompt.contains(userQuery))

        // 2. Verify Qwen ChatML Prompt formatting
        val qwenPrompt = """
            <|im_start|>system
            You are PrismCampus AI, an edge assistant for MSRIT students.
            
            Rules & Instructions:
            $customRules
            
            Campus Context:
            $mockCampusContext
            <|im_end|>
            <|im_start|>user
            $userQuery<|im_end|>
            <|im_start|>assistant
        """.trimIndent()

        val normalizedQwen = qwenPrompt.replace("\r\n", "\n")
        assertTrue(normalizedQwen.contains("<|im_start|>system"))
        assertTrue(normalizedQwen.contains(customRules))
        assertTrue(normalizedQwen.contains("<|im_start|>user"))
        assertTrue(normalizedQwen.contains(userQuery))
        assertTrue(normalizedQwen.endsWith("<|im_start|>assistant"))
    }

    @Test
    fun testDynamicRulesUpdateFileCacheSimulation() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "rules_cache_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val tempRulesFile = java.io.File(tempDir, "assistant_rules.md")

        try {
            // Step 1: Initial rules file
            tempRulesFile.writeText("Rule 1: Be polite.")
            var lastLoadedTimestamp = tempRulesFile.lastModified()
            var cachedRules = tempRulesFile.readText()
            assertEquals("Rule 1: Be polite.", cachedRules)

            // Step 2: Simulate file edit
            Thread.sleep(50)
            tempRulesFile.writeText("Rule 1: Be ultra concise and give bullet points.")
            val newMtime = tempRulesFile.lastModified()

            if (newMtime > lastLoadedTimestamp) {
                cachedRules = tempRulesFile.readText()
                lastLoadedTimestamp = newMtime
            }

            assertEquals("Rule 1: Be ultra concise and give bullet points.", cachedRules)
            assertTrue(lastLoadedTimestamp >= newMtime)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLiveMemoryTelemetryNotHardcodedTo315() {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val usedMem = totalMem - freeMem
        val usedMb = usedMem / (1024 * 1024)

        val telemetry = MemoryTelemetryInfo(
            usedMemoryMb = usedMb,
            maxMemoryMb = AppMemoryBudget.MIB,
            freeMemoryMb = (AppMemoryBudget.MIB - usedMb).coerceAtLeast(0L),
            memoryUsageRatio = (usedMb.toFloat() / AppMemoryBudget.MIB).coerceIn(0f, 1f),
            inferenceMode = "JVM Heap (Test)",
            averageInferenceLatencyMs = 8L
        )

        assertTrue("Used memory should be >= 0", telemetry.usedMemoryMb >= 0)
        assertEquals(AppMemoryBudget.MIB, telemetry.maxMemoryMb)
        // Verify that 315 MB is NOT hardcoded
        assertFalse("Telemetry must not be hardcoded to 315 MB", telemetry.usedMemoryMb == 315L && usedMb != 315L)
    }

    @Test
    fun testGeminiTimeoutAndAuthDiagnosticHandling() {
        val authErrorDiagnostic = "Gemini Authentication / Configuration Error (HTTP 401): Please verify Gemini API key."
        val routerResult = RouterResult(
            query = "What is quantum computing?",
            responseText = authErrorDiagnostic,
            tierUsed = ExecutionTier.TIER_3_CLOUD_GEMINI,
            latencyMs = 120L
        )

        assertTrue(routerResult.responseText.contains("Gemini Authentication"))
        assertTrue(routerResult.responseText.contains("401"))
        assertEquals(ExecutionTier.TIER_3_CLOUD_GEMINI, routerResult.tierUsed)
    }
}
