package com.example.novelseek_ultra.agent

import kotlinx.serialization.Serializable

/**
 * Durable progress marker for an agent run. Wall-clock time is persisted only so a recreated
 * process can explain when progress stopped; live timeout decisions use elapsed realtime in the
 * controller and therefore are not affected by clock or timezone changes.
 */
@Serializable
data class AgentRunCheckpoint(
    /** Stable across automatic retries; replaced only by an explicit user-started run. */
    val runToken: String = "",
    val phase: String = AgentRunPhase.IDLE,
    val lastProgressAtEpochMs: Long = 0L,
    val automaticRecoveryAttempts: Int = 0,
    val recoveryPending: Boolean = false,
    val lastRecoveryReason: String = "",
) {
    fun sanitized(): AgentRunCheckpoint = copy(
        runToken = runToken.trim().take(MAX_TOKEN_CHARS),
        phase = AgentRunPhase.normalize(phase),
        lastProgressAtEpochMs = lastProgressAtEpochMs.coerceAtLeast(0L),
        automaticRecoveryAttempts = automaticRecoveryAttempts.coerceAtLeast(0),
        lastRecoveryReason = lastRecoveryReason.trim().take(MAX_REASON_CHARS),
    )

    private companion object {
        const val MAX_TOKEN_CHARS = 80
        const val MAX_REASON_CHARS = 240
    }
}

/** Coarse phases deliberately stay provider-agnostic and serialization-stable. */
object AgentRunPhase {
    const val IDLE = "idle"
    const val STARTING = "starting"
    const val PLANNING = "planning"
    const val MODEL = "model"
    const val TOOL = "tool"
    const val STREAMING = "streaming"
    const val RECOVERING = "recovering"

    fun normalize(value: String?): String = when (value?.trim()?.lowercase()) {
        STARTING -> STARTING
        PLANNING -> PLANNING
        MODEL -> MODEL
        TOOL -> TOOL
        STREAMING -> STREAMING
        RECOVERING -> RECOVERING
        else -> IDLE
    }
}

enum class AgentRunLivenessDecision {
    /** No autonomous run is expected; the watchdog must not mutate anything. */
    INACTIVE,

    /** A live job has reported progress inside the phase-specific deadline. */
    HEALTHY,

    /** Persisted/UI state says running, but no execution job exists in this process. */
    RECOVER_ORPHANED,

    /** A job still exists, but it has exceeded the longest credible no-progress interval. */
    RECOVER_STALE,

    /** Recovery already failed repeatedly; expose an actionable stopped state instead of looping. */
    STOP_RETRY_LIMIT,
}

/** Identifies why a diagnosis ran; it changes the explanation, never the safety policy. */
enum class AgentRunDiagnosisTrigger {
    BACKGROUND_HEARTBEAT,
    APP_FOREGROUND,
    PROCESS_RESTORE,
    FOREGROUND_SERVICE_TIMEOUT,
}

/** Pure commit fence used to reject state writes from a cancelled or superseded coroutine. */
object AgentRunGenerationFence {
    fun matches(
        expectedControlRevision: Long,
        expectedInstanceToken: Long,
        expectedSessionId: String?,
        currentControlRevision: Long,
        currentInstanceToken: Long,
        currentSessionId: String?,
        sessionTransitionPending: Boolean,
    ): Boolean =
        expectedControlRevision == currentControlRevision &&
            expectedInstanceToken == currentInstanceToken &&
            expectedSessionId == currentSessionId &&
            !sessionTransitionPending
}

data class AgentRunLivenessSnapshot(
    val runExpected: Boolean,
    val hasActiveJob: Boolean,
    val phase: String,
    /** Null only before the first in-process progress marker. */
    val progressAgeMs: Long?,
    val automaticRecoveryAttempts: Int,
)

/**
 * Pure policy shared by the foreground-service heartbeat and foreground Activity diagnosis.
 *
 * Deadlines are intentionally longer than the one-shot model HTTP deadline. Streaming/tool work
 * receives a larger window and should refresh progress from callbacks, preventing a slow but live
 * novel generation from being cancelled merely because the screen is off.
 */
object AgentRunLivenessPolicy {
    const val MAX_AUTOMATIC_RECOVERIES = 2

    const val STARTING_TIMEOUT_MS = 2 * 60 * 1000L
    const val MODEL_TIMEOUT_MS = 3 * 60 * 1000L
    const val TOOL_TIMEOUT_MS = 6 * 60 * 1000L
    const val STREAMING_TIMEOUT_MS = 6 * 60 * 1000L
    const val RECOVERING_TIMEOUT_MS = 2 * 60 * 1000L

    fun evaluate(snapshot: AgentRunLivenessSnapshot): AgentRunLivenessDecision {
        if (!snapshot.runExpected) return AgentRunLivenessDecision.INACTIVE

        val attempts = snapshot.automaticRecoveryAttempts.coerceAtLeast(0)
        if (!snapshot.hasActiveJob) {
            return if (attempts >= MAX_AUTOMATIC_RECOVERIES) {
                AgentRunLivenessDecision.STOP_RETRY_LIMIT
            } else {
                AgentRunLivenessDecision.RECOVER_ORPHANED
            }
        }

        val age = snapshot.progressAgeMs ?: return AgentRunLivenessDecision.HEALTHY
        if (age.coerceAtLeast(0L) <= timeoutFor(snapshot.phase)) {
            return AgentRunLivenessDecision.HEALTHY
        }
        return if (attempts >= MAX_AUTOMATIC_RECOVERIES) {
            AgentRunLivenessDecision.STOP_RETRY_LIMIT
        } else {
            AgentRunLivenessDecision.RECOVER_STALE
        }
    }

    fun timeoutFor(phase: String): Long = when (AgentRunPhase.normalize(phase)) {
        AgentRunPhase.MODEL, AgentRunPhase.PLANNING -> MODEL_TIMEOUT_MS
        AgentRunPhase.TOOL -> TOOL_TIMEOUT_MS
        AgentRunPhase.STREAMING -> STREAMING_TIMEOUT_MS
        AgentRunPhase.RECOVERING -> RECOVERING_TIMEOUT_MS
        else -> STARTING_TIMEOUT_MS
    }
}
