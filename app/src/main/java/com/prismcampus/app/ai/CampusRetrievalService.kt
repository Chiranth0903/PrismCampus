package com.prismcampus.app.ai

import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.data.Room
import com.prismcampus.app.data.ScheduleManager
import com.prismcampus.app.data.TimetableSlot
import java.util.Locale
import java.util.regex.Pattern

/**
 * High-performance, offline-first Campus Retrieval Service.
 * Retrieves grounded records from local SQLite database and msrit_pack.json
 * to feed LLM context or provide instant deterministic campus answers.
 */
class CampusRetrievalService(
    val repository: CampusRepository,
    val scheduleManager: ScheduleManager,
    val database: CampusDatabase
) {
    private val nextClassPattern = Pattern.compile("(?i)\\b(next (?:class|lecture|lesson)|upcoming class|where do i go|where is my class|what is next|what's next|schedule now|which class is next)\\b")
    private val cadLabPattern = Pattern.compile("(?i)\\b(cad lab|melc19|mechanical lab|cad)\\b")
    private val softSkillsPattern = Pattern.compile("(?i)\\b(soft skills|hscp15|language lab)\\b")
    private val physicsLabPattern = Pattern.compile("(?i)\\b(physics lab|pyc12|pyc12l)\\b")
    private val cProgLabPattern = Pattern.compile("(?i)\\b(c programming lab|c lab|psclcs18)\\b")
    private val prismCampusPattern = Pattern.compile("(?i)\\b(prismcampus|prism campus|what does prismcampus do|what is this app|about this app|app features)\\b")

    /**
     * Builds structured, grounded context string from local campus records
     * to inject into Gemini or Local Qwen system prompts.
     */
    fun campusIdentity(): String {
        val pack = repository.prismPack.value
        return CampusAnswerPolicy.identity(repository.activeCampusId.value, pack?.campusName.orEmpty(), pack?.website.orEmpty())
    }

    fun buildRetrievalContext(query: String): String {
        val pack = repository.prismPack.value
        val sb = StringBuilder()

        val activeSection = repository.selectedSectionId.value
        sb.appendLine("CAMPUS CONTEXT:")
        sb.appendLine(campusIdentity())
        sb.appendLine("- Active Student Section: Section $activeSection")

        // 1. Current Schedule Evaluation
        val eval = scheduleManager.evaluateSchedule()
        if (eval.nextClass != null) {
            val n = eval.nextClass
            sb.appendLine("- Next Scheduled Class: ${n.slot.courseName} (${n.slot.courseCode}) at ${n.slot.startTime} in ${n.buildingName}, ${n.floorLabel}, Room ${n.roomId}")
        }
        sb.appendLine("- Evaluated Day: ${eval.evaluatedDay} (${eval.allDaySlots.size} slots total)")

        // 2. Active Overrides
        val overrides = repository.overrides.value
        if (overrides.isNotEmpty()) {
            sb.appendLine("- Active Classroom Overrides:")
            overrides.forEach { o ->
                sb.appendLine("  * Course ${o.targetCourseCode} relocated to ${o.newBuildingName}, ${o.newFloorLabel}, Room ${o.newRoomId}. Reason: ${o.reason}")
            }
        }

        // 3. Relevant Buildings
        val qLower = query.lowercase(Locale.US)
        pack?.buildings?.forEach { b ->
            if (qLower.contains(b.name.lowercase(Locale.US)) || qLower.contains(b.code.lowercase(Locale.US)) ||
                b.rooms.any { r -> qLower.contains(r.roomId.lowercase(Locale.US)) || qLower.contains(r.name.lowercase(Locale.US)) }) {
                sb.appendLine("- Building ${b.name} (${b.code}): ${b.description} (Floors: ${b.totalFloors})")
                val relevantRooms = b.rooms.filter { r ->
                    qLower.contains(r.roomId.lowercase(Locale.US)) || qLower.contains(r.name.lowercase(Locale.US)) ||
                            (r.assignedCourse != null && qLower.contains(r.assignedCourse.lowercase(Locale.US)))
                }
                relevantRooms.forEach { r ->
                    sb.appendLine("  * Room ${r.roomId}: ${r.name}, ${r.floorLabel}. ${r.description}")
                }
            }
        }

        return sb.toString()
    }

    /**
     * Executes fast deterministic local search when no LLM is available.
     * Returns RouterResult with TIER_1_DETERMINISTIC or null if not handled.
     */
    fun searchDeterministic(rawQuery: String, startTime: Long, lang: SupportedLanguage): RouterResult? {
        if (AssistantIntent.needsGeneralAnswer(rawQuery)) return null
        val query = rawQuery.trim()
        val qLower = query.lowercase(Locale.US)
        val pack = repository.prismPack.value
        val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)

        // 0. PrismCampus App Explanations
        if (prismCampusPattern.matcher(query).find() || qLower.contains("what is prismcampus") || qLower.contains("explain what prismcampus does")) {
            val text = when (lang) {
                SupportedLanguage.KANNADA -> "ಪ್ರಿಸಂ ಕ್ಯಾಂಪಸ್ ಎನ್ನುವುದು ಆಯ್ದ ಕ್ಯಾಂಪಸ್‌ನ ವಿದ್ಯಾರ್ಥಿಗಳಿಗಾಗಿ ವಿನ್ಯಾಸಗೊಳಿಸಲಾದ ಸ್ಥಳೀಯ-ಮೊದಲ ಸ್ಮಾರ್ಟ್ ಕ್ಯಾಂಪಸ್ ಅಪ್ಲಿಕೇಶನ್ ಆಗಿದೆ. ಇದು ಆಫ್‌ಲೈನ್ ಕ್ಯಾಂಪಸ್ ಮ್ಯಾಪ್‌ಗಳು, ಸ್ವಯಂಚಾಲಿತ ವೇಳಾಪಟ್ಟಿ ಸಂಚರಣೆ, ಧ್ವನಿ ಸಹಾಯಕ, ಮತ್ತು ಕ್ಯಾಂಪಸ್ ದೂರು ಸಲ್ಲಿಕೆಯನ್ನು ಬೆಂಬಲಿಸುತ್ತದೆ."
                SupportedLanguage.HINDI -> "प्रिज्म कैंपस चुने हुए कैंपस के छात्रों के लिए एक ऑफलाइन-फर्स्ट स्मार्ट कैंपस ऐप है। यह ऑफलाइन कैंपस मैप, स्वचालित टाइमटेबल नेविगेशन, वॉयस असिस्टेंट और कैंपस शिकायत निवारण प्रदान करता है।"
                SupportedLanguage.ENGLISH -> "PrismCampus is a smart campus companion for students at ${pack?.campusName ?: "your selected campus"}. It features offline interactive campus mapping, timetable navigation, speech assistant, ML Kit timetable OCR, and direct civic issue reporting to Uno Q edge servers."
            }
            return RouterResult(
                query = query,
                responseText = text,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                actionType = RouterAction.NONE
            )
        }

        // 1. Next Class
        if (nextClassPattern.matcher(query).find()) {
            val eval = scheduleManager.evaluateSchedule()
            val next = eval.nextClass
            return if (next != null) {
                val resp = when (lang) {
                    SupportedLanguage.KANNADA -> "ನಿಮ್ಮ ಮುಂದಿನ ತರಗತಿ ${next.slot.courseName} (${next.slot.courseCode}) ${next.slot.startTime} ರಲ್ಲಿದೆ. ಸ್ಥಳ: ${next.buildingName}, ${next.floorLabel}, ಕೊಠಡಿ ${next.roomId}."
                    SupportedLanguage.HINDI -> "आपकी अगली कक्षा ${next.slot.courseName} (${next.slot.courseCode}) ${next.slot.startTime} पर है। स्थान: ${next.buildingName}, ${next.floorLabel}, रूम ${next.roomId}."
                    SupportedLanguage.ENGLISH -> "Your next class is ${next.slot.courseName} (${next.slot.courseCode}) at ${next.slot.startTime}. Location: ${next.buildingName}, ${next.floorLabel}, Room ${next.roomId}."
                }
                RouterResult(
                    query = query,
                    responseText = resp,
                    tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                    latencyMs = latency,
                    targetDestination = next,
                    actionType = RouterAction.NAVIGATE_TO_ROOM
                )
            } else {
                val resp = when (lang) {
                    SupportedLanguage.KANNADA -> "ಇಂದು ಇನ್ನು ಯಾವುದೇ ತರಗತಿಗಳು ಇಲ್ಲ."
                    SupportedLanguage.HINDI -> "आज के लिए कोई और कक्षा निर्धारित नहीं है।"
                    SupportedLanguage.ENGLISH -> "No more classes scheduled for today (${eval.evaluatedDay}). You are all caught up!"
                }
                RouterResult(
                    query = query,
                    responseText = resp,
                    tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                    latencyMs = latency,
                    actionType = RouterAction.SHOW_TIMETABLE
                )
            }
        }

        // Search the selected campus package before consulting legacy demonstration facts.
        if (!CampusAnswerPolicy.allowsLegacyFacts(repository.activeCampusId.value)) {
            val matched = pack?.buildings?.firstOrNull { b ->
                qLower.contains(b.name.lowercase(Locale.US)) ||
                    Regex("(?i)(?<![a-z0-9])" + Regex.escape(b.code) + "(?![a-z0-9])").containsMatchIn(query) ||
                    listOf("library", "food", "workshop", "lecture hall").any { word ->
                        qLower.contains(word) && b.name.contains(word, ignoreCase = true)
                    } || b.rooms.any { r ->
                        r.roomId != "LOCATION_TBC" && Regex("(?<![0-9])" + Regex.escape(r.roomId) + "(?![0-9])").containsMatchIn(query)
                    }
            }
            if (matched != null) return RouterResult(query,
                "${matched.name}: ${matched.description} GPS: ${matched.latitude}, ${matched.longitude}.",
                ExecutionTier.TIER_1_DETERMINISTIC, latency,
                targetBuilding = matched, actionType = RouterAction.NAVIGATE_TO_BUILDING)
        }

        if (CampusAnswerPolicy.allowsLegacyFacts(repository.activeCampusId.value)) {
        // 2. CAD Lab
        if (cadLabPattern.matcher(query).find()) {
            val esb = pack?.buildings?.find { it.code == "ESB" }
            val resp = when (lang) {
                SupportedLanguage.KANNADA -> "ಸಿಎಡಿ ಲ್ಯಾಬ್ (MELC19) ಇಂಜಿನಿಯರಿಂಗ್ ಸೈನ್ಸಸ್ ಬ್ಲಾಕ್ (ESB), 4ನೇ ಮಹಡಿ, ಕೊಠಡಿ 402 ರಲ್ಲಿದೆ."
                SupportedLanguage.HINDI -> "सीएडी लैब (MELC19) इंजीनियरिंग साइंसेज ब्लॉक (ESB), चौथी मंजिल, रूम 402 में स्थित है।"
                SupportedLanguage.ENGLISH -> "CAD Lab (MELC19) is located in Engineering Sciences Block (ESB), 4th Floor, Room 402."
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = esb,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }

        // 3. Soft Skills Lab
        if (softSkillsPattern.matcher(query).find()) {
            val lhc = pack?.buildings?.find { it.code == "LHC" }
            val resp = when (lang) {
                SupportedLanguage.KANNADA -> "ಸಾಫ್ಟ್ ಸ್ಕಿಲ್ಸ್ ಲ್ಯಾಬ್ (HSCP15) ಲೆಕ್ಚರ್ ಹಾಲ್ ಕಾಂಪ್ಲೆಕ್ಸ್ (LHC), 2ನೇ ಮಹಡಿ, ಕೊಠಡಿ 212 ರಲ್ಲಿದೆ."
                SupportedLanguage.HINDI -> "सॉफ्ट स्किल्स लैब (HSCP15) लेक्चर हॉल कॉम्प्लेक्स (LHC), दूसरी मंजिल, रूम 212 में स्थित है।"
                SupportedLanguage.ENGLISH -> "Soft Skills & Language Lab (HSCP15) is located in Lecture Hall Complex (LHC), 2nd Floor, Room 212."
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = lhc,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }

        // 4. Physics Lab
        if (physicsLabPattern.matcher(query).find()) {
            val ab = pack?.buildings?.find { it.code == "AB" }
            val resp = when (lang) {
                SupportedLanguage.KANNADA -> "ಫಿಸಿಕ್ಸ್ ಲ್ಯಾಬ್ (PYC12) ಅಪೆಕ್ಸ್ ಬ್ಲಾಕ್ (AB), 4ನೇ ಮಹಡಿ, ಕೊಠಡಿ 602 ರಲ್ಲಿದೆ."
                SupportedLanguage.HINDI -> "फिजिक्स लैब (PYC12) एपेक्स ब्लॉक (AB), चौथी मंजिल, रूम 602 में है।"
                SupportedLanguage.ENGLISH -> "Engineering Physics Lab (PYC12) is located in Apex Block (AB), 4th Floor, Room 602."
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = ab,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }

        // 5. C Programming Lab
        if (cProgLabPattern.matcher(query).find()) {
            val ab = pack?.buildings?.find { it.code == "AB" }
            val resp = when (lang) {
                SupportedLanguage.KANNADA -> "ಸಿ ಪ್ರೋಗ್ರಾಮಿಂಗ್ ಲ್ಯಾಬ್ (PSCLCS18) ಅಪೆಕ್ಸ್ ಬ್ಲಾಕ್ (AB), 5ನೇ ಮಹಡಿ, ಕೊಠಡಿ 703 ರಲ್ಲಿದೆ."
                SupportedLanguage.HINDI -> "सी प्रोग्रामिंग लैब (PSCLCS18) एपेक्स ब्लॉक (AB), 5वीं मंजिल, रूम 703 में है।"
                SupportedLanguage.ENGLISH -> "C Programming Lab (PSCLCS18) is located in Apex Block (AB), 5th Floor, Room 703."
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = ab,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }

        // 6. Section Classrooms
        if (query.contains("501")) {
            val ab = pack?.buildings?.find { it.code == "AB" }
            return RouterResult(
                query = query,
                responseText = "Room AB-501 is on the 3rd Floor of Apex Block (AB). It is the default classroom for Section A (CSE).",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = ab,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }
        if (query.contains("504")) {
            val ab = pack?.buildings?.find { it.code == "AB" }
            return RouterResult(
                query = query,
                responseText = "Room AB-504 is on the 3rd Floor of Apex Block (AB). It is the default classroom for Section D (ISE).",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = ab,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }
        if (query.contains("514")) {
            val ab = pack?.buildings?.find { it.code == "AB" }
            return RouterResult(
                query = query,
                responseText = "Room AB-514 is on the 3rd Floor of Apex Block (AB). It is the default classroom for Section I (AIML).",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = ab,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }

        // 7. General Buildings in msrit_pack.json
        if (qLower.contains("apex") || qLower.contains("ab block")) {
            val ab = pack?.buildings?.find { it.code == "AB" }
            return RouterResult(
                query = query,
                responseText = "Apex Block (AB) is the primary 7-floor academic complex housing CSE, ISE, AIML departments, and Dean/Principal administrative offices.",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = ab,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }
        if (qLower.contains("esb") || qLower.contains("engineering sciences")) {
            val esb = pack?.buildings?.find { it.code == "ESB" }
            return RouterResult(
                query = query,
                responseText = "Engineering Sciences Block (ESB) houses Mechanical and Civil Engineering departments, CAD Lab (Room 402), and prototyping workshops.",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = esb,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }
        if (qLower.contains("lhc") || qLower.contains("lecture hall")) {
            val lhc = pack?.buildings?.find { it.code == "LHC" }
            return RouterResult(
                query = query,
                responseText = "Lecture Hall Complex (LHC) hosts large tiered lecture auditoriums, the Training & Placement Cell (Ground Floor), and Soft Skills Lab (Room 212).",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                targetBuilding = lhc,
                actionType = RouterAction.NAVIGATE_TO_BUILDING
            )
        }

        // 8. General MSRIT Campus Facts
        if (qLower.contains("library")) {
            return RouterResult(
                query = query,
                responseText = "The Central Library is located in Apex Annex across 4 floors with 150,000+ volumes, open Mon-Sat 8:00 AM - 10:00 PM.",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency
            )
        }
        if (qLower.contains("canteen") || qLower.contains("mess") || qLower.contains("food")) {
            return RouterResult(
                query = query,
                responseText = "The Main Campus Canteen is located behind ESB serving South Indian meals, snacks, and juices from 8:00 AM to 7:00 PM.",
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency
            )
        }
        } // End of MSRIT-only legacy campus locations.

        // 8b. Timetable & Full Day Schedule Queries
        if (qLower.contains("today's class") || qLower.contains("all classes") || qLower.contains("schedule today") ||
            qLower.contains("timetable") || qLower.contains("daily schedule") || qLower.contains("classes for today")) {
            val eval = scheduleManager.evaluateSchedule()
            val slots = eval.allDaySlots
            val resp = if (slots.isNotEmpty()) {
                val listStr = slots.joinToString("\n") { "• ${it.slot.startTime}-${it.slot.endTime}: ${it.slot.courseName} (${it.slot.courseCode}) in Room ${it.roomId}" }
                "Today's Schedule (${eval.evaluatedDay}, ${slots.size} classes):\n$listStr"
            } else {
                "No classes scheduled for today (${eval.evaluatedDay}). You are all caught up!"
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency,
                actionType = RouterAction.SHOW_TIMETABLE
            )
        }

        // 8c. Assignments & Tasks
        if (qLower.contains("assignment") || qLower.contains("homework") || qLower.contains("task") || qLower.contains("pending work")) {
            val pending = database.assignmentsFlow.value.filter { !it.isCompleted && it.campusId == repository.activeCampusId.value }
            val resp = if (pending.isNotEmpty()) {
                val listStr = pending.take(4).joinToString("\n") { "• [${it.subject}] ${it.title} (Due: ${it.dueDate})" }
                "You have ${pending.size} pending assignment(s):\n$listStr"
            } else {
                "You have no pending assignments! Great job!"
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency
            )
        }

        // 8d. Active Classroom Relocations & Overrides
        if (qLower.contains("override") || qLower.contains("relocated") || qLower.contains("class moved") || qLower.contains("shifted class")) {
            val overrides = repository.overrides.value
            val resp = if (overrides.isNotEmpty()) {
                val listStr = overrides.joinToString("\n") { "• Course ${it.targetCourseCode} shifted to ${it.newBuildingName}, Room ${it.newRoomId} (${it.reason})" }
                "Active Classroom Relocations:\n$listStr"
            } else {
                "No active classroom overrides right now. All classes are following the regular timetable."
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency
            )
        }

        // 9. Greetings
        if (qLower.matches(Regex("(hi|hello|hey|greetings|good morning|namaste|namaskara)[!. ]*"))) {
            val resp = when (lang) {
                SupportedLanguage.KANNADA -> "ನಮಸ್ಕಾರ! ನಾನು ನಿಮ್ಮ ಪ್ರಿಸಂ ಕ್ಯಾಂಪಸ್ ಸಹಾಯಕ. ನೀವು ತರಗತಿಗಳು, ಕೊಠಡಿಗಳು, ವೇಳಾಪಟ್ಟಿ ಅಥವಾ ದೂರುಗಳ ಬಗ್ಗೆ ಕೇಳಬಹುದು."
                SupportedLanguage.HINDI -> "नमस्ते! मैं आपका प्रिज्म कैंपस सहायक हूँ। आप मुझसे कक्षाओं, कमरों, टाइमटेबल या शिकायतों के बारे में पूछ सकते हैं।"
                SupportedLanguage.ENGLISH -> "Hello! I am your PrismCampus AI assistant. How can I help you with your classes, campus locations, or schedules today?"
            }
            return RouterResult(
                query = query,
                responseText = resp,
                tierUsed = ExecutionTier.TIER_1_DETERMINISTIC,
                latencyMs = latency
            )
        }

        return null
    }
}
