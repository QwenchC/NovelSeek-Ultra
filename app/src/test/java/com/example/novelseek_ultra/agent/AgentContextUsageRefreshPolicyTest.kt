package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import com.example.novelseek_ultra.data.model.AgentSessionMemory
import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextUsageRefreshPolicyTest {
    private val sharedSteps = listOf(
        AgentStep(id = "u1", type = AgentStep.USER, text = "继续第三章"),
    )

    @Test
    fun `exact latest immutable snapshot is publishable`() {
        val expected = identity()

        assertTrue(
            AgentContextUsageRefreshPolicy.isCurrent(
                expectedRevision = 7L,
                currentRevision = 7L,
                transitionPending = false,
                expected = expected,
                current = expected.copy(),
            ),
        )
    }

    @Test
    fun `newer request transition or equal but replaced steps reject stale estimate`() {
        val expected = identity()
        val equalButReplacedSteps = sharedSteps.map { it.copy() }

        assertFalse(
            AgentContextUsageRefreshPolicy.isCurrent(
                expectedRevision = 6L,
                currentRevision = 7L,
                transitionPending = false,
                expected = expected,
                current = expected,
            ),
        )
        assertFalse(
            AgentContextUsageRefreshPolicy.isCurrent(
                expectedRevision = 7L,
                currentRevision = 7L,
                transitionPending = true,
                expected = expected,
                current = expected,
            ),
        )
        assertFalse(
            AgentContextUsageRefreshPolicy.isCurrent(
                expectedRevision = 7L,
                currentRevision = 7L,
                transitionPending = false,
                expected = expected,
                current = expected.copy(steps = equalButReplacedSteps),
            ),
        )
        assertFalse(
            AgentContextUsageRefreshPolicy.isCurrent(
                expectedRevision = 7L,
                currentRevision = 7L,
                transitionPending = false,
                expected = expected,
                current = expected.copy(sessionId = "session-2"),
            ),
        )
    }

    @Test
    fun `same session with changed memory plan reasoning or engine rejects stale estimate`() {
        val expected = identity()
        val plan = AgentPlan(
            summary = "续写",
            steps = listOf(AgentPlanStep(id = "p1", goal = "写第三章")),
            createdAt = 1L,
            sourceCommandId = "u1",
            planId = "plan-1",
        )

        listOf(
            expected.copy(memory = AgentSessionMemory(summaryVersion = 1, summary = "changed")),
            expected.copy(activePlan = plan),
            expected.copy(reasoningLevel = AgentReasoningLevels.HIGH),
            expected.copy(engineMode = AgentController.ENGINE_CLASSIC),
        ).forEach { current ->
            assertFalse(
                AgentContextUsageRefreshPolicy.isCurrent(
                    expectedRevision = 7L,
                    currentRevision = 7L,
                    transitionPending = false,
                    expected = expected,
                    current = current,
                ),
            )
        }
    }

    private fun identity() = AgentContextUsageRefreshIdentity(
        sessionId = "session-1",
        steps = sharedSteps,
        memory = AgentSessionMemory(),
        activePlan = null,
        reasoningLevel = AgentReasoningLevels.MEDIUM,
        engineMode = AgentController.ENGINE_DUAL,
    )
}
