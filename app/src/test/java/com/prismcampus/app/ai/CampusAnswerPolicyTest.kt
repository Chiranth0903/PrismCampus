package com.prismcampus.app.ai

import org.junit.Assert.*
import org.junit.Test

class CampusAnswerPolicyTest {
    @Test fun generalJobAndPackageQuestionsDoNotTriggerCampusFacts() {
        listOf("what is job", "What is a job?", "what is package", "What are jobs?",
            "What is a Java package?").forEach { assertTrue(it, AssistantIntent.needsGeneralAnswer(it)) }
        assertFalse(AssistantIntent.needsGeneralAnswer("Where is my next class?"))
        assertFalse(AssistantIntent.needsGeneralAnswer("What is my timetable?"))
    }

    @Test fun campusIdentityUsesSelectedPackWithoutRamaiahDefaults() {
        val identity = CampusAnswerPolicy.identity("IITG_77356889E768", "IIT Guwahati", "https://www.iitg.ac.in/")
        assertTrue(identity.contains("IIT Guwahati"))
        assertTrue(identity.contains("https://www.iitg.ac.in/"))
        assertFalse(identity.contains("MSRIT"))
        assertFalse(identity.contains("Bengaluru"))
        assertFalse(CampusAnswerPolicy.allowsLegacyFacts("IITG_77356889E768"))
        assertFalse(CampusAnswerPolicy.allowsLegacyFacts("IITG_DEMO"))
        assertTrue(CampusAnswerPolicy.allowsLegacyFacts("MSRIT"))
    }

    @Test fun persistedOldRulesAreNeutralizedAndOfflineGuardSurvivesTruncation() {
        val rules = CampusAnswerPolicy.rules("When answering questions about Ramaiah Institute of Technology (MSRIT) buildings, use supplied ground truth.")
        assertFalse(rules.contains("MSRIT"))
        assertTrue(rules.take(300).contains("Never substitute another college"))
        assertTrue(rules.take(300).contains("Answer general questions generally"))
    }
}
