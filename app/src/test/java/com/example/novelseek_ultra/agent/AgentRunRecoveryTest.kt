package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryTest {
    @Test
    fun deniesUnexecutedProposalAndOnlyMarksRunningActionInterrupted() {
        val result = AgentRunRecovery.recover(
            listOf(
                action("proposed", AgentStep.ACTION_PROPOSED),
                AgentStep("message", AgentStep.OBSERVATION, "older result"),
                action("running", AgentStep.ACTION_RUNNING),
                action("done", AgentStep.ACTION_SUCCEEDED),
            ),
        )

        assertEquals(listOf("running"), result.interruptedActionIds)
        assertEquals(AgentStep.ACTION_DENIED, result.steps[0].actionStatus)
        assertEquals(AgentStep.ACTION_INTERRUPTED, result.steps[2].actionStatus)
        assertEquals(AgentStep.ACTION_SUCCEEDED, result.steps[3].actionStatus)
        assertTrue(result.hasInterruptedActions)
    }

    @Test
    fun proposedActionAloneDoesNotCreateAnUncertainRecovery() {
        val result = AgentRunRecovery.recover(listOf(action("proposed", AgentStep.ACTION_PROPOSED)))

        assertEquals(AgentStep.ACTION_DENIED, result.steps.single().actionStatus)
        assertTrue(result.interruptedActionIds.isEmpty())
        assertFalse(result.hasInterruptedActions)
    }

    @Test
    fun leavesLegacyAndNonActionStepsUntouched() {
        val legacy = action("legacy", "")
        val user = AgentStep("user", AgentStep.USER, "继续")
        val result = AgentRunRecovery.recover(listOf(legacy, user))

        assertEquals(listOf(legacy, user), result.steps)
        assertTrue(result.interruptedActionIds.isEmpty())
        assertFalse(result.hasInterruptedActions)
    }

    @Test
    fun awaitingReviewSurvivesRecoveryWithoutBecomingInterrupted() {
        val awaitingReview = action("review", AgentStep.ACTION_AWAITING_REVIEW)
        val result = AgentRunRecovery.recover(
            listOf(awaitingReview, action("running", AgentStep.ACTION_RUNNING)),
        )

        assertEquals(AgentStep.ACTION_AWAITING_REVIEW, result.steps[0].actionStatus)
        assertEquals(AgentStep.ACTION_INTERRUPTED, result.steps[1].actionStatus)
        assertEquals(listOf("running"), result.interruptedActionIds)
    }

    private fun action(id: String, status: String) = AgentStep(
        id = id,
        type = AgentStep.ACTION,
        text = "projectId=p1",
        tool = "generate_chapter",
        argsJson = """{"projectId":"p1"}""",
        actionStatus = status,
    )
}
