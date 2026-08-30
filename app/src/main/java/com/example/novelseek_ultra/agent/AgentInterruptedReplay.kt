package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Structural matching for an action whose process died after crossing the execution boundary. */
object AgentInterruptedReplay {
    private val json = Json

    fun matches(
        step: AgentStep,
        tool: String,
        resolvedProjectId: String,
        fallbackProjectId: String,
    ): Boolean {
        if (
            step.type != AgentStep.ACTION ||
            step.actionStatus != AgentStep.ACTION_INTERRUPTED ||
            step.tool != tool
        ) {
            return false
        }
        val savedArgs = runCatching {
            json.parseToJsonElement(step.argsJson).let { it as? JsonObject }
        }.getOrNull() ?: JsonObject(emptyMap())
        val savedProjectId = step.resolvedProjectId.ifBlank {
            (savedArgs["projectId"] as? JsonPrimitive)?.contentOrNull ?: fallbackProjectId
        }
        // Raw JSON is not a safe idempotency key: tools deliberately accept equivalent booleans,
        // integers, and omitted defaults in multiple representations. Conservatively require one
        // review for any unresolved interruption of the same tool/scope instead of risking replay.
        return tool == "create_project" || savedProjectId == resolvedProjectId
    }
}
