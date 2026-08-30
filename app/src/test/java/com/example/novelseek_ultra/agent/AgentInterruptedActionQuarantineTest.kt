package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInterruptedActionQuarantineTest {
    private val mutatingTools = setOf("write_a", "write_b")

    @Test
    fun `an unresolved write quarantines every later write but permits reads`() {
        val steps = listOf(action("write_a", AgentStep.ACTION_INTERRUPTED))

        assertTrue(
            AgentInterruptedActionQuarantine.requiresConfirmation(
                steps,
                candidateTool = "write_b",
                mutatingTools = mutatingTools,
            ),
        )
        assertFalse(
            AgentInterruptedActionQuarantine.requiresConfirmation(
                steps,
                candidateTool = "read",
                mutatingTools = mutatingTools,
            ),
        )
    }

    @Test
    fun `explicit reconciliation clears only interrupted mutations`() {
        val interruptedWrite = action("write_a", AgentStep.ACTION_INTERRUPTED)
        val interruptedRead = action("read", AgentStep.ACTION_INTERRUPTED)
        val runningWrite = action("write_b", AgentStep.ACTION_RUNNING)

        val reconciled = AgentInterruptedActionQuarantine.reconcileMutations(
            listOf(interruptedWrite, interruptedRead, runningWrite),
            mutatingTools,
        )

        assertEquals(AgentStep.ACTION_RECONCILED, reconciled[0].actionStatus)
        assertEquals(AgentStep.ACTION_INTERRUPTED, reconciled[1].actionStatus)
        assertEquals(AgentStep.ACTION_RUNNING, reconciled[2].actionStatus)
    }

    @Test
    fun `unknown candidate defaults safely when caller includes it as mutating`() {
        val steps = listOf(action("write_a", AgentStep.ACTION_INTERRUPTED))
        assertTrue(
            AgentInterruptedActionQuarantine.requiresConfirmation(
                steps,
                candidateTool = "new_tool",
                mutatingTools = mutatingTools + "new_tool",
            ),
        )
    }

    private fun action(tool: String, status: String) = AgentStep(
        id = "$tool-$status",
        type = AgentStep.ACTION,
        text = tool,
        tool = tool,
        actionStatus = status,
    )
}
