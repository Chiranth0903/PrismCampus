package com.prismcampus.app.ai

import android.content.Context
import android.util.Log
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusIssueReport
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.data.ScheduleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Central AI Orchestration Service for PrismCampus.
 * Predefined campus commands answer immediately from local records.
 * General questions use Gemini online, then on-device Qwen when offline or cloud fails.
 *
 * Never returns empty, never spins forever, and never mislabels deterministic responses as LLM.
 */
class AIOrchestrator(
    private val context: Context,
    private val repository: CampusRepository,
    private val scheduleManager: ScheduleManager,
    private val database: CampusDatabase,
    private val localSlmManager: LocalSLMManager,
    val rulesManager: AssistantRulesManager = AssistantRulesManager(context),
    var geminiTimeoutMs: Long = 12000L
) {
    val retrievalService = CampusRetrievalService(repository, scheduleManager, database)
    val geminiProvider = GeminiProvider(context, retrievalService, rulesManager)
    val qwenProvider = LocalQwenProvider(context, localSlmManager, retrievalService, rulesManager)

    suspend fun processQuery(
        rawQuery: String,
        preferredLanguage: SupportedLanguage = SupportedLanguage.ENGLISH
    ): RouterResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val trimmed = rawQuery.trim()

        try {
            if (trimmed.isEmpty()) {
                val emptyMsg = when (preferredLanguage) {
                    SupportedLanguage.KANNADA -> "ನಾನು ಕೇಳುತ್ತಿದ್ದೇನೆ. ನಿಮ್ಮ ತರಗತಿಗಳು, ಲ್ಯಾಬ್‌ಗಳು ಅಥವಾ ಯಾವುದೇ ಪ್ರಶ್ನೆಗಳನ್ನು ಕೇಳಿ."
                    SupportedLanguage.HINDI -> "मैं सुन रहा हूँ। अपनी अगली कक्षा, लैब या किसी भी प्रश्न के बारे में पूछें।"
                    SupportedLanguage.ENGLISH -> "I'm listening. Ask me about classes, timetable, campus directions, or report an issue."
                }
                return@withContext RouterResult(
                    query = rawQuery,
                    responseText = emptyMsg,
                    tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                    latencyMs = 0
                )
            }

            LocalArithmetic.answer(trimmed)?.let { answer ->
                return@withContext RouterResult(trimmed, answer, ExecutionTier.TIER_1_DETERMINISTIC,
                    System.currentTimeMillis() - startTime)
            }

            // 1. Facility Issue Reporting (Special student civic complaint pipeline)
            if (isFacilityIssueQuery(trimmed)) {
                return@withContext handleFacilityIssueReport(trimmed, startTime, preferredLanguage)
            }

            // 2. High-speed Fast-Path for ALL Predefined Commands & Deterministic Campus Facts
            // Instant response (<5ms): Classes, rooms, labs, buildings, timetables, assignments, app info, greetings.
            // Bypasses network timeouts and heavy SLM generation completely!
            val predefinedResult = try {
                retrievalService.searchDeterministic(trimmed, startTime, preferredLanguage)
            } catch (t: Throwable) {
                Log.w("AIOrchestrator", "searchDeterministic error: ${t.message}")
                null
            }
            if (predefinedResult != null) {
                Log.i("AIOrchestrator", "Predefined command matched instantly (${predefinedResult.latencyMs}ms): '$trimmed'")
                return@withContext predefinedResult
            }

            // 3. For Any Other Query (Open-ended reasoning, general knowledge, non-campus questions):
            // Check connectivity:
            val isOnline = try { geminiProvider.isOnline() } catch (_: Throwable) { false }
            if (isOnline) {
                try {
                    // Hybrid router: attempt Gemini first with configurable timeout
                    val geminiResult = withTimeoutOrNull(geminiTimeoutMs) {
                        geminiProvider.generateResponse(trimmed, preferredLanguage, geminiTimeoutMs.toInt())
                    }

                    if (geminiResult != null && geminiResult.text.isNotBlank()) {
                        Log.i("AIOrchestrator", "Gemini succeeded: '${geminiResult.providerName}'")
                        return@withContext RouterResult(
                            query = trimmed,
                            responseText = geminiResult.text,
                            tierUsed = ExecutionTier.TIER_3_CLOUD_GEMINI,
                            latencyMs = geminiResult.latencyMs,
                            actionType = RouterAction.NONE
                        )
                    }

                    Log.i("AIOrchestrator", "Gemini timed out (> ${geminiTimeoutMs}ms) or unreachable, attempting Qwen fallback")
                } catch (t: Throwable) {
                    Log.w("AIOrchestrator", "Gemini generation exception: ${t.message}, attempting Qwen fallback")
                }
            } else {
                Log.i("AIOrchestrator", "Device is offline (Airplane mode or disconnected) -> Routing directly to local Qwen SLM")
            }

            // Offline or Gemini timed out/failed -> Use Local / Edge Qwen2.5-0.5B
            try {
                val qwenResult = qwenProvider.generateResponse(trimmed, preferredLanguage)
                if (qwenResult != null && qwenResult.text.isNotBlank()) {
                    Log.i("AIOrchestrator", "Dispatched to Qwen: '${qwenResult.providerName}'")
                    return@withContext RouterResult(
                        query = trimmed,
                        responseText = qwenResult.text,
                        tierUsed = ExecutionTier.TIER_2_EDGE_SLM,
                        latencyMs = qwenResult.latencyMs,
                        actionType = RouterAction.NONE
                    )
                }
            } catch (t: Throwable) {
                Log.w("AIOrchestrator", "Qwen generation exception: ${t.message}")
            }

            // 4. Grounded Campus Fallback if neither LLM responded
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val fallbackResponse = buildInformativeOfflineFallback(trimmed, preferredLanguage)
            return@withContext RouterResult(
                query = trimmed,
                responseText = fallbackResponse,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                actionType = RouterAction.NONE
            )
        } catch (t: Throwable) {
            Log.e("AIOrchestrator", "Unexpected exception in processQuery: ${t.message}", t)
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            return@withContext RouterResult(
                query = trimmed,
                responseText = buildInformativeOfflineFallback(trimmed, preferredLanguage),
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                actionType = RouterAction.NONE
            )
        }
    }

    private fun isFacilityIssueQuery(query: String): Boolean {
        return AssistantIntent.isFacilityReport(query)
    }

    private suspend fun handleFacilityIssueReport(query: String, startTime: Long, lang: SupportedLanguage): RouterResult {
        val lower = query.lowercase(Locale.US)

        val category = when {
            lower.contains("fan") || lower.contains("light") || lower.contains("switch") || lower.contains("spark") || lower.contains("power") || lower.contains("bulb") || lower.contains("tube") -> "Electrical"
            lower.contains("tap") || lower.contains("leak") || lower.contains("pipe") || lower.contains("washroom") || lower.contains("toilet") || lower.contains("sink") || lower.contains("flush") -> "Plumbing"
            lower.contains("ac") || lower.contains("air conditioner") || lower.contains("workstation") || lower.contains("projector") || lower.contains("cooler") -> "Hardware/AC"
            lower.contains("bench") || lower.contains("chair") || lower.contains("desk") || lower.contains("podium") || lower.contains("door") -> "Furniture"
            else -> "Lab Equipment"
        }

        val building = when {
            lower.contains("hostel") || lower.contains("mess") -> "Hostel Complex"
            lower.contains("canteen") -> "Engineering Sciences Block"
            lower.contains("library") -> "Apex Annex"
            lower.contains("esb") || lower.contains("engineering sciences") -> "Engineering Sciences Block"
            lower.contains("lhc") || lower.contains("lecture hall") -> "Lecture Hall Complex"
            else -> "Apex Block"
        }

        val room = when {
            lower.contains("mess") -> "Hostel Mess"
            lower.contains("canteen") -> "Main Canteen"
            lower.contains("library") -> "Central Library"
            lower.contains("402") -> "Room 402"
            lower.contains("212") -> "Room 212"
            lower.contains("602") -> "Room 602"
            lower.contains("703") -> "Room 703"
            lower.contains("501") -> "AB-501"
            lower.contains("504") -> "AB-504"
            lower.contains("514") -> "AB-514"
            lower.contains("hostel") -> "Hostel Room"
            else -> "General Campus Area"
        }

        val floor = when {
            room.contains("402") || room.contains("602") -> 4
            room.contains("703") -> 5
            room.contains("212") -> 2
            room.contains("50") -> 3
            else -> 1
        }

        val urgency = when {
            lower.contains("danger") || lower.contains("spark") || lower.contains("hazard") || lower.contains("fire") || lower.contains("emergency") -> "Critical/Safety Hazard"
            lower.contains("not working") || lower.contains("leaking") || lower.contains("broken") || lower.contains("loud") -> "Medium"
            else -> "Low"
        }

        val issue = CampusIssueReport(
            campusId = repository.activeCampusId.value,
            category = category,
            building = building,
            floor = floor,
            room = room,
            description = query,
            urgency = urgency,
            timestamp = System.currentTimeMillis(),
            isSynced = false
        )
        database.insertIssue(issue)
        val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(8L)

        val resp = when (lang) {
            SupportedLanguage.KANNADA -> "ನಿಮ್ಮ ದೂರನ್ನು ಉಳಿಸಲಾಗಿದೆ. ಸರ್ವರ್ ಸಂಪರ್ಕ ಸಿಕ್ಕಾಗ ಕಳುಹಿಸಲಾಗುತ್ತದೆ."
            SupportedLanguage.HINDI -> "आपकी शिकायत सुरक्षित है। सर्वर से जुड़ने पर भेज दी जाएगी।"
            SupportedLanguage.ENGLISH -> "Saved your report. It will sync when a campus server is reachable."
        }

        return RouterResult(
            query = query,
            responseText = resp,
            tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
            latencyMs = latency,
            createdIssue = issue,
            actionType = RouterAction.ISSUE_RECORDED
        )
    }

    private fun buildInformativeOfflineFallback(query: String, lang: SupportedLanguage): String {
        if (lang == SupportedLanguage.ENGLISH && localSlmManager.isModelLoaded.value) {
            val status = localSlmManager.engineStatus.value
            return when {
                status.contains("too long", true) -> "Offline AI took too long to answer. Try a shorter question. Classes, timetables and simple calculations still work instantly."
                status.contains("memory budget", true) -> "Offline AI reached the ${AppMemoryBudget.MIB} MiB memory budget. Try again after closing the map. Classes and simple calculations still work instantly."
                else -> "I couldn't complete that offline AI answer. Please try again. You can still ask about your classes or calculate something like eight plus five."
            }
        }
        return when (lang) {
            SupportedLanguage.KANNADA -> "ಆಫ್‌ಲೈನ್ ಮೋಡ್: ನೈಜ-ಸಮಯದ ಎಐ ಉತ್ತರಗಳಿಗಾಗಿ ಇಂಟರ್ನೆಟ್ (ಜೆಮಿನಿ) ಅಥವಾ ಸ್ಥಳೀಯ ಕ್ವೆನ್ ಮಾದರಿಯ ಅಗತ್ಯವಿದೆ. ನೀವು ನಿಮ್ಮ ಕ್ಯಾಂಪಸ್‌ನ ತರಗತಿಗಳು, ಕೊಠಡಿಗಳು ಅಥವಾ ವೇಳಾಪಟ್ಟಿಯ ಬಗ್ಗೆ ಕೇಳಬಹುದು."
            SupportedLanguage.HINDI -> "ऑफलाइन मोड: विस्तृत एआई उत्तरों के लिए इंटरनेट (जेमिनी) या स्थानीय क्वेन मॉडल की आवश्यकता है। आप मुझसे कक्षाओं, कमरों, या टाइमटेबल के बारे में पूछ सकते हैं।"
            SupportedLanguage.ENGLISH -> "I can answer questions about your selected campus buildings, class schedules, timetable, and campus complaints offline. For open-ended AI discussions, connect to the internet (Gemini) or install local Qwen weights."
        }
    }
}
