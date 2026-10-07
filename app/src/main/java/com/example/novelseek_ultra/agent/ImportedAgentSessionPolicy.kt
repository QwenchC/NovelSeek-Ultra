package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentStep

/**
 * A backup transports conversation history, not permission to execute on another installation.
 * Keep historical identities and review links intact, but never restore automatic execution or
 * auto-approval. A subsequent explicit user command/continue still uses the controller's normal
 * confirmation and interrupted-action checks.
 */
object ImportedAgentSessionPolicy {
    fun quarantine(session: AgentSession): AgentSession = session.copy(
        autoApprove = false,
        runStatus = "stopped",
        runCheckpoint = session.runCheckpoint.copy(
            phase = AgentRunPhase.IDLE,
            recoveryPending = false,
        ),
        steps = session.steps.map { step ->
            if (step.type == AgentStep.ACTION && step.actionStatus == AgentStep.ACTION_RUNNING) {
                step.copy(actionStatus = AgentStep.ACTION_INTERRUPTED)
            } else {
                step
            }
        },
    )
}
