package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanEvidenceTest {
    private val ask = AgentStep(
        id = "ask-1",
        type = AgentStep.ACTION,
        text = "",
        tool = "ask_user",
        actionStatus = AgentStep.ACTION_SUCCEEDED,
        planStepId = "p1",
        planId = "plan-a",
    )

    @Test
    fun `completion rejection cannot become evidence for an unanswered question`() {
        val steps = listOf(
            ask,
            AgentStep("q1", AgentStep.QUESTION, "请选择"),
            AgentStep("reject", AgentStep.OBSERVATION, "尚无持久化证据"),
        )

        assertFalse(AgentPlanEvidence.hasDurableResult(steps, 0))
    }

    @Test
    fun `answer and tool observation must reference the exact action`() {
        val answered = listOf(
            ask,
            AgentStep(
                id = "answer-1",
                type = AgentStep.ANSWER,
                text = "选 A",
                resultForActionId = ask.id,
            ),
        )
        val tool = ask.copy(id = "tool-1", tool = "list_projects")
        val observed = listOf(
            tool,
            AgentStep(
                id = "obs-1",
                type = AgentStep.OBSERVATION,
                text = "结果",
                resultForActionId = tool.id,
            ),
        )
        val wrongAction = observed[1].copy(resultForActionId = "other")

        assertTrue(AgentPlanEvidence.hasDurableResult(answered, 0))
        assertTrue(AgentPlanEvidence.hasDurableResult(observed, 0))
        assertFalse(AgentPlanEvidence.hasDurableResult(listOf(tool, wrongAction), 0))
    }

    @Test
    fun `old plan p1 evidence cannot complete a new plan p1`() {
        val oldAction = ask.copy(id = "old-action", planId = "plan-old")
        val transcript = listOf(
            oldAction,
            AgentStep(
                id = "old-result",
                type = AgentStep.OBSERVATION,
                text = "旧计划完成",
                resultForActionId = oldAction.id,
            ),
        )

        assertEquals(
            -1,
            AgentPlanEvidence.latestActionIndexForPlanStep(transcript, "plan-new", "p1"),
        )
        assertEquals(
            0,
            AgentPlanEvidence.latestActionIndexForPlanStep(transcript, "plan-old", "p1"),
        )
    }

    @Test
    fun `pending review is never evidence while committed and legacy results are`() {
        val action = ask.copy(id = "write-1", tool = "generate_chapter")
        fun result(id: String, kind: String) = AgentStep(
            id = id,
            type = AgentStep.OBSERVATION,
            text = "章节结果",
            resultForActionId = action.id,
            resultKind = kind,
        )

        assertFalse(
            AgentPlanEvidence.hasDurableResult(
                listOf(action, result("pending", AgentStep.RESULT_PENDING_REVIEW)),
                0,
            ),
        )
        assertTrue(
            AgentPlanEvidence.hasDurableResult(
                listOf(action, result("committed", AgentStep.RESULT_COMMITTED)),
                0,
            ),
        )
        assertTrue(
            AgentPlanEvidence.hasDurableResult(listOf(action, result("legacy", "")), 0),
        )
    }
}
