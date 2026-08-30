package com.example.novelseek_ultra.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunLivenessPolicyTest {
    @Test
    fun cancelledOrSupersededRunCannotPassTheCommitFence() {
        fun matches(
            currentRevision: Long = 7L,
            currentInstance: Long = 3L,
            currentSession: String? = "session-a",
            transition: Boolean = false,
        ) = AgentRunGenerationFence.matches(
            expectedControlRevision = 7L,
            expectedInstanceToken = 3L,
            expectedSessionId = "session-a",
            currentControlRevision = currentRevision,
            currentInstanceToken = currentInstance,
            currentSessionId = currentSession,
            sessionTransitionPending = transition,
        )

        assertEquals(true, matches())
        assertEquals(false, matches(currentRevision = 8L))
        assertEquals(false, matches(currentInstance = 4L))
        assertEquals(false, matches(currentSession = "session-b"))
        assertEquals(false, matches(transition = true))
    }

    @Test
    fun malformedDurableCheckpointIsSanitizedWithoutInventingProgress() {
        val checkpoint = AgentRunCheckpoint(
            runToken = " token ".repeat(30),
            phase = "future_phase",
            lastProgressAtEpochMs = -9L,
            automaticRecoveryAttempts = -3,
            recoveryPending = true,
            lastRecoveryReason = " x ".repeat(200),
        ).sanitized()

        assertEquals(AgentRunPhase.IDLE, checkpoint.phase)
        assertEquals(80, checkpoint.runToken.length)
        assertEquals(0L, checkpoint.lastProgressAtEpochMs)
        assertEquals(0, checkpoint.automaticRecoveryAttempts)
        assertEquals(true, checkpoint.recoveryPending)
        assertEquals(240, checkpoint.lastRecoveryReason.length)
    }

    @Test
    fun inactiveRunNeverTriggersRecovery() {
        assertEquals(
            AgentRunLivenessDecision.INACTIVE,
            decision(runExpected = false, hasJob = false, ageMs = Long.MAX_VALUE),
        )
    }

    @Test
    fun runningStateWithoutJobIsDiagnosedAsOrphaned() {
        assertEquals(
            AgentRunLivenessDecision.RECOVER_ORPHANED,
            decision(runExpected = true, hasJob = false, ageMs = 1L),
        )
    }

    @Test
    fun recentModelRequestIsHealthy() {
        assertEquals(
            AgentRunLivenessDecision.HEALTHY,
            decision(
                runExpected = true,
                hasJob = true,
                ageMs = AgentRunLivenessPolicy.MODEL_TIMEOUT_MS,
                phase = AgentRunPhase.MODEL,
            ),
        )
    }

    @Test
    fun staleModelRequestIsRecovered() {
        assertEquals(
            AgentRunLivenessDecision.RECOVER_STALE,
            decision(
                runExpected = true,
                hasJob = true,
                ageMs = AgentRunLivenessPolicy.MODEL_TIMEOUT_MS + 1L,
                phase = AgentRunPhase.MODEL,
            ),
        )
    }

    @Test
    fun streamingUsesLongerDeadlineThanModelRequest() {
        val age = AgentRunLivenessPolicy.MODEL_TIMEOUT_MS + 1L
        assertEquals(
            AgentRunLivenessDecision.HEALTHY,
            decision(true, true, age, AgentRunPhase.STREAMING),
        )
    }

    @Test
    fun unknownPhaseUsesConservativeStartingDeadline() {
        assertEquals(
            AgentRunLivenessPolicy.STARTING_TIMEOUT_MS,
            AgentRunLivenessPolicy.timeoutFor("provider-specific-phase"),
        )
    }

    @Test
    fun staleToolExecutionIsRecovered() {
        assertEquals(
            AgentRunLivenessDecision.RECOVER_STALE,
            decision(
                true,
                true,
                AgentRunLivenessPolicy.TOOL_TIMEOUT_MS + 1L,
                AgentRunPhase.TOOL,
            ),
        )
    }

    @Test
    fun recoveryLimitStopsOrphanInsteadOfLoopingForever() {
        assertEquals(
            AgentRunLivenessDecision.STOP_RETRY_LIMIT,
            decision(
                runExpected = true,
                hasJob = false,
                ageMs = 0L,
                attempts = AgentRunLivenessPolicy.MAX_AUTOMATIC_RECOVERIES,
            ),
        )
    }

    @Test
    fun missingFirstHeartbeatDoesNotCancelAnExistingJob() {
        assertEquals(
            AgentRunLivenessDecision.HEALTHY,
            AgentRunLivenessPolicy.evaluate(
                AgentRunLivenessSnapshot(
                    runExpected = true,
                    hasActiveJob = true,
                    phase = AgentRunPhase.STARTING,
                    progressAgeMs = null,
                    automaticRecoveryAttempts = 0,
                ),
            ),
        )
    }

    private fun decision(
        runExpected: Boolean,
        hasJob: Boolean,
        ageMs: Long?,
        phase: String = AgentRunPhase.MODEL,
        attempts: Int = 0,
    ): AgentRunLivenessDecision = AgentRunLivenessPolicy.evaluate(
        AgentRunLivenessSnapshot(
            runExpected = runExpected,
            hasActiveJob = hasJob,
            phase = phase,
            progressAgeMs = ageMs,
            automaticRecoveryAttempts = attempts,
        ),
    )
}
