package com.prismcampus.app.ai

import org.junit.Assert.*
import org.junit.Test

class AppMemoryBudgetTest {
    @Test fun profilingExplicitlyDisablesOnlyGenerationBudget() {
        assertEquals(0L, AppMemoryBudget.generationLimitMiB(true))
        assertTrue(AppMemoryBudget.MIB > 0)
    }
    @Test fun normalBuildUsesSharedMonitorAndGenerationBudget() {
        assertEquals(AppMemoryBudget.MIB, AppMemoryBudget.generationLimitMiB(false))
    }
}
