package com.prismcampus.app.ai

/** Keep ordinary questions out of the complaint pipeline (which writes to storage). */
object AssistantIntent {
    private val generalQuestion = Regex("^(?:(?:hi|hello|hey)[,!. ]+)?(?:explain|define|compare|write|code|implement|how (?:does|do|can)|what (?:is|are))\\b", RegexOption.IGNORE_CASE)

    fun needsGeneralAnswer(query: String): Boolean = generalQuestion.containsMatchIn(query.trim()) &&
        !Regex("\\b(my (?:next )?class|next class|timetable|prismcampus|prism campus|this app)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)

    private val equipment = Regex("\\b(fan|tap|light|water|washroom|toilet|projector|switch|door|bench|ac|air conditioner|hostel|mess|bulb|tube|flush|sink|cooler|equipment|chair|pipe|switchboard)\\b", RegexOption.IGNORE_CASE)
    private val failure = Regex("\\b(broken|leak(?:ing)?|not working|not cooling|out of order|repair|overheating|sparks?|hazard|damaged)\\b", RegexOption.IGNORE_CASE)
    private val explicitReport = Regex("\\b(report|complaint|complain)\\b", RegexOption.IGNORE_CASE)

    fun isFacilityReport(query: String): Boolean =
        !needsGeneralAnswer(query) && equipment.containsMatchIn(query) &&
            (failure.containsMatchIn(query) || explicitReport.containsMatchIn(query))
}
