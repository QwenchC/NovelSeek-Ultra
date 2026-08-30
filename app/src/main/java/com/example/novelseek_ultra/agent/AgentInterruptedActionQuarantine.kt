package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep

/**
 * Prevents a recovered run from stacking a new write on top of an earlier write whose outcome is
 * unknown. Unknown/new tools are classified by the caller and should default to mutating.
 */
object AgentInterruptedActionQuarantine {
    fun requiresConfirmation(
        steps: List<AgentStep>,
        candidateTool: String,
        mutatingTools: Set<String>,
    ): Boolean = candidateTool in mutatingTools && steps.any { step ->
        step.type == AgentStep.ACTION &&
            step.actionStatus == AgentStep.ACTION_INTERRUPTED &&
            step.tool in mutatingTools
    }

    fun reconcileMutations(
        steps: List<AgentStep>,
        mutatingTools: Set<String>,
    ): List<AgentStep> = steps.map { step ->
        if (
            step.type == AgentStep.ACTION &&
            step.actionStatus == AgentStep.ACTION_INTERRUPTED &&
            step.tool in mutatingTools
        ) {
            step.copy(actionStatus = AgentStep.ACTION_RECONCILED)
        } else {
            step
        }
    }
}
