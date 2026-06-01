package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable

/**
 * One entry in the agent's top-to-bottom execution chain, persisted so the session survives app
 * restarts. [type] drives both the UI rendering and how the entry is replayed into the model's
 * context when the user resumes a run.
 */
@Serializable
data class AgentStep(
    val id: String,
    val type: String,                 // user | thought | action | observation | message | question | answer | error | image
    val text: String,
    val tool: String = "",            // tool name for type == "action"
    val createdAt: String = "",
    val image: String = "",           // local file path of a generated image (type == "image")
) {
    companion object {
        const val USER = "user"
        const val THOUGHT = "thought"
        const val ACTION = "action"
        const val OBSERVATION = "observation"
        const val MESSAGE = "message"
        const val QUESTION = "question"
        const val ANSWER = "answer"
        const val ERROR = "error"
        const val IMAGE = "image"     // an image the agent generated, shown as a preview bubble
    }
}

/**
 * One agent conversation. Persisted per-file at `agent/sessions/{id}.json`.
 * - [lockedProjectId]: the project this session operates on (set on create/focus).
 * - [autoApprove]: when true (and a project is locked), the agent runs sensitive steps WITHOUT
 *   pausing for per-step confirmation — the user has pre-authorized auto-continue for this project.
 */
@Serializable
data class AgentSession(
    val id: String,
    val title: String = "",
    val createdAt: String = "",
    val steps: List<AgentStep> = emptyList(),
    val lockedProjectId: String? = null,
    val autoApprove: Boolean = false,
)

/** Lightweight session descriptor for the session list / index. */
@Serializable
data class AgentSessionMeta(val id: String, val title: String, val createdAt: String)

/** Index of all sessions + which one is current. Persisted at `agent/index.json`. */
@Serializable
data class AgentIndex(val currentId: String? = null, val items: List<AgentSessionMeta> = emptyList())
