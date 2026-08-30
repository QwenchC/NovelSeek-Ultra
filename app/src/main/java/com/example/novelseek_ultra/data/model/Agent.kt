package com.example.novelseek_ultra.data.model

import com.example.novelseek_ultra.agent.AgentPlan
import com.example.novelseek_ultra.agent.AgentRunCheckpoint
import kotlinx.serialization.Serializable

/** Cross-provider reasoning depth used only by the dual planner/executor engine. */
object AgentReasoningLevels {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    fun normalize(value: String?): String = when (value?.trim()?.lowercase()) {
        LOW -> LOW
        HIGH -> HIGH
        else -> MEDIUM
    }
}

/**
 * Provider-reported prompt-cache accounting for one agent conversation.
 *
 * Only requests that reported a complete cache hit/miss pair contribute to these totals. Missing
 * provider usage therefore remains unknown instead of being converted to a synthetic zero hit.
 */
@Serializable
data class AgentSessionCacheMetrics(
    val observedRequests: Long = 0,
    val hitTokens: Long = 0,
    val missTokens: Long = 0,
) {
    /** Weighted cache hit ratio in the inclusive range 0.0..1.0, or null when no rate is known. */
    fun hitRate(): Double? {
        val normalized = sanitized()
        if (normalized.observedRequests == 0L) return null
        val observedTokens = normalized.hitTokens.toDouble() + normalized.missTokens.toDouble()
        return if (observedTokens > 0.0) {
            (normalized.hitTokens.toDouble() / observedTokens).coerceIn(0.0, 1.0)
        } else {
            null
        }
    }

    /** Ignore incomplete/invalid provider usage and saturate durable counters on overflow. */
    fun record(cacheHitTokens: Long?, cacheMissTokens: Long?): AgentSessionCacheMetrics {
        if (cacheHitTokens == null || cacheMissTokens == null) return this
        if (cacheHitTokens < 0L || cacheMissTokens < 0L) return this
        val current = sanitized()
        return current.copy(
            observedRequests = saturatedAdd(current.observedRequests, 1L),
            hitTokens = saturatedAdd(current.hitTokens, cacheHitTokens),
            missTokens = saturatedAdd(current.missTokens, cacheMissTokens),
        )
    }

    /** Imported sessions may be hand-edited; never expose negative counters to the UI. */
    fun sanitized(): AgentSessionCacheMetrics = copy(
        observedRequests = observedRequests.coerceAtLeast(0L),
        hitTokens = hitTokens.coerceAtLeast(0L),
        missTokens = missTokens.coerceAtLeast(0L),
    )

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

/** Persisted local memory overlay; the original [AgentSession.steps] remain the audit source. */
@Serializable
data class AgentSessionMemory(
    val summaryVersion: Int = 0,
    val summary: String = "",
    val compactedThroughStepId: String = "",
    /** SHA-256 of the exact durable prefix represented by [summary]. */
    val compactedPrefixDigest: String = "",
    val compressionCount: Int = 0,
    val compressedStepCount: Int = 0,
    val lastCompressedAt: String? = null,
) {
    fun sanitized(): AgentSessionMemory = copy(
        summaryVersion = summaryVersion.coerceAtLeast(0),
        compressionCount = compressionCount.coerceAtLeast(0),
        compressedStepCount = compressedStepCount.coerceAtLeast(0),
        lastCompressedAt = lastCompressedAt?.takeIf { it.isNotBlank() },
    )
}

/** Exact conservative estimate of the agent's next/most recent model request. */
data class AgentContextUsage(
    val observable: Boolean = false,
    /** Usable context capacity after the request guard's internal safety reserve. */
    val capacityTokens: Long = 0,
    val inputBudgetTokens: Long = 0,
    val fixedTokens: Long = 0,
    val historyTokens: Long = 0,
    /** Reserved output is shown independently and is not counted as consumed input. */
    val outputReserveTokens: Long = 0,
    val remainingTokens: Long = 0,
    /** (fixed + history) / inputBudget, in 0.0..1.0 for a valid prepared request. */
    val usageRatio: Double? = null,
    val compressionCount: Int = 0,
    val lastCompressedAt: String? = null,
) {
    /** UI-friendly aliases retained alongside the shorter request-budget terminology. */
    val contextWindowTokens: Long get() = capacityTokens
    val fixedPromptTokens: Long get() = fixedTokens
}

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
    /** Original validated arguments for action steps. Kept for audit/recovery; empty for legacy data. */
    val argsJson: String = "",
    /** proposed | running | awaiting_review | succeeded | failed | denied | interrupted. */
    val actionStatus: String = "",
    /** Dual-engine plan step associated with this action. Empty in classic sessions. */
    val planStepId: String = "",
    /** Stable dual-engine plan identity. Prevents evidence leaking across plans that reuse p1/p2. */
    val planId: String = "",
    /** Project target resolved at the execution boundary, including implicit focused-project args. */
    val resolvedProjectId: String = "",
    /** Action whose concrete result this observation/answer/image belongs to. */
    val resultForActionId: String = "",
    /** Empty for legacy results; pending_review is not completion evidence, committed is. */
    val resultKind: String = "",
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
        const val PLAN = "plan"
        /** Synthetic prompt-only entry created from [AgentSessionMemory], never stored in steps. */
        const val CONTEXT_SUMMARY = "context_summary"

        const val ACTION_PROPOSED = "proposed"
        const val ACTION_RUNNING = "running"
        const val ACTION_AWAITING_REVIEW = "awaiting_review"
        const val ACTION_SUCCEEDED = "succeeded"
        const val ACTION_FAILED = "failed"
        const val ACTION_DENIED = "denied"
        const val ACTION_INTERRUPTED = "interrupted"
        const val ACTION_RECONCILED = "reconciled"

        const val RESULT_PENDING_REVIEW = "pending_review"
        const val RESULT_COMMITTED = "committed"
    }
}

/** Durable link between an agent action and the chapter candidate awaiting a user decision. */
@Serializable
data class AgentPendingReview(
    val projectId: String,
    val chapterId: String,
    val runId: String,
    val candidateId: String,
    val actionId: String,
)

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
    /** Pinned per conversation. Old serialized sessions remain on the classic engine. */
    val engineMode: String = "classic",
    /** Session-level dual-engine depth; adjustable while idle and persisted with the conversation. */
    val reasoningLevel: String = AgentReasoningLevels.MEDIUM,
    /** Complete provider-reported cache observations for planner and executor requests. */
    val cacheMetrics: AgentSessionCacheMetrics = AgentSessionCacheMetrics(),
    /** Prompt-memory overlay; full steps remain intact for UI, recovery and evidence checks. */
    val memory: AgentSessionMemory = AgentSessionMemory(),
    val activePlan: AgentPlan? = null,
    /** Persisted terminal state; legacy sessions default to idle. */
    val runStatus: String = "idle",
    /** Durable liveness marker used to diagnose an orphaned or stalled autonomous run. */
    val runCheckpoint: AgentRunCheckpoint = AgentRunCheckpoint(),
    /** Restores an unanswered ask_user question; confirmations are intentionally never restored. */
    val pendingPrompt: String? = null,
    /** Restores a candidate review gate without replaying the action that produced it. */
    val pendingReview: AgentPendingReview? = null,
)

/** Lightweight session descriptor for the session list / index. */
@Serializable
data class AgentSessionMeta(val id: String, val title: String, val createdAt: String)

/** Index of all sessions + which one is current. Persisted at `agent/index.json`. */
@Serializable
data class AgentIndex(val currentId: String? = null, val items: List<AgentSessionMeta> = emptyList())
