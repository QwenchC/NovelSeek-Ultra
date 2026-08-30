package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep

/** Verifies that a plan-completion claim points to a result produced by that exact action. */
object AgentPlanEvidence {
    /**
     * Finds the latest ordinary action for one step of one exact plan. Plan step ids are commonly
     * reused (p1, p2) after replanning, so matching [planStepId] alone is never sufficient.
     */
    fun latestActionIndexForPlanStep(
        steps: List<AgentStep>,
        planId: String,
        planStepId: String,
    ): Int {
        if (planId.isBlank() || planStepId.isBlank()) return -1
        return steps.indexOfLast { step ->
            step.type == AgentStep.ACTION &&
                step.planId == planId &&
                step.planStepId == planStepId &&
                step.tool != "complete_plan_step"
        }
    }

    fun hasDurableResult(steps: List<AgentStep>, actionIndex: Int): Boolean {
        val action = steps.getOrNull(actionIndex)
            ?.takeIf { it.type == AgentStep.ACTION }
            ?: return false
        return steps.drop(actionIndex + 1).any { result ->
            result.resultForActionId == action.id &&
                result.type in setOf(AgentStep.OBSERVATION, AgentStep.ANSWER, AgentStep.IMAGE) &&
                (result.resultKind.isEmpty() || result.resultKind == AgentStep.RESULT_COMMITTED)
        }
    }
}
