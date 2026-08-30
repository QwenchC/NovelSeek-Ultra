package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep

data class AgentRunRecoveryResult(
    val steps: List<AgentStep>,
    val interruptedActionIds: List<String>,
) {
    val hasInterruptedActions: Boolean get() = interruptedActionIds.isNotEmpty()
}

/**
 * Resolves transient action states after process death without inventing side effects.
 *
 * A proposed action has not crossed the execution boundary, so it is explicitly denied and may be
 * proposed again later. Only a running action is uncertain: its tool may have committed immediately
 * before the process died, so it is marked interrupted and must never be replayed silently.
 */
object AgentRunRecovery {
    fun recover(steps: List<AgentStep>): AgentRunRecoveryResult {
        val interrupted = mutableListOf<String>()
        val recovered = steps.map { step ->
            when {
                step.type != AgentStep.ACTION -> step
                step.actionStatus == AgentStep.ACTION_PROPOSED ->
                    step.copy(actionStatus = AgentStep.ACTION_DENIED)
                step.actionStatus == AgentStep.ACTION_RUNNING -> {
                    interrupted += step.id
                    step.copy(actionStatus = AgentStep.ACTION_INTERRUPTED)
                }
                else -> step
            }
        }
        return AgentRunRecoveryResult(recovered, interrupted)
    }
}
