package com.prismcampus.app.ai

/** Campus identity and boundaries shared by cloud and on-device prompts. */
object CampusAnswerPolicy {
    const val GROUNDING = "Use only the selected campus records for campus facts. Never substitute another college or invent placements, salaries, rooms or timings. Answer general questions generally, without unsolicited campus examples. Say when campus data is missing."

    fun identity(campusId: String, campusName: String, website: String = ""): String =
        "You are PrismCampus AI. Selected campus: ${campusName.ifBlank { campusId }} (ID: $campusId)." +
            if (website.isBlank()) "" else " Campus website: $website."

    fun rules(raw: String): String = GROUNDING + "\n" + raw
        .replace("Ramaiah Institute of Technology (MSRIT)", "the selected campus")
        .replace("MSRIT campus", "the selected campus")

    fun allowsLegacyFacts(campusId: String): Boolean = campusId == "MSRIT"
}
