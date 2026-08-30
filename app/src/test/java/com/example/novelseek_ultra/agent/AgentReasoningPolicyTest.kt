package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReasoningPolicyTest {
    @Test
    fun invalidOrMissingLevelFallsBackToMedium() {
        assertEquals(
            AgentReasoningLevels.MEDIUM,
            AgentReasoningPolicy.forLevel(null).level,
        )
        assertEquals(
            AgentReasoningLevels.MEDIUM,
            AgentReasoningPolicy.forLevel("future-level").level,
        )
        assertEquals(
            AgentReasoningLevels.HIGH,
            AgentReasoningPolicy.forLevel(" HIGH ").level,
        )
    }

    @Test
    fun depthMonotonicallyRaisesPlanAndTranscriptBudgets() {
        val low = AgentReasoningPolicy.forLevel(AgentReasoningLevels.LOW)
        val medium = AgentReasoningPolicy.forLevel(AgentReasoningLevels.MEDIUM)
        val high = AgentReasoningPolicy.forLevel(AgentReasoningLevels.HIGH)

        assertEquals(6, low.plannerMaxSteps)
        assertEquals(12, medium.plannerMaxSteps)
        assertEquals(20, high.plannerMaxSteps)
        assertTrue(low.transcriptSharePercent < medium.transcriptSharePercent)
        assertTrue(medium.transcriptSharePercent < high.transcriptSharePercent)
        assertTrue(low.plannerTranscriptBudget.maxTotalChars < medium.plannerTranscriptBudget.maxTotalChars)
        assertTrue(medium.plannerTranscriptBudget.maxTotalChars < high.plannerTranscriptBudget.maxTotalChars)
        assertTrue(low.executorTranscriptBudget.maxSteps < medium.executorTranscriptBudget.maxSteps)
        assertTrue(medium.executorTranscriptBudget.maxSteps < high.executorTranscriptBudget.maxSteps)
    }
}
