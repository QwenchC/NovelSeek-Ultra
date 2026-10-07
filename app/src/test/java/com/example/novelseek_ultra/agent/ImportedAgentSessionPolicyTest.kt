package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentSessionCacheMetrics
import com.example.novelseek_ultra.data.model.AgentSessionMemory
import com.example.novelseek_ultra.data.model.AgentStep
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedAgentSessionPolicyTest {
    @Test
    fun runningImportCannotAutoRecoverOrReuseApproval() {
        val original = session().copy(
            steps = listOf(
                action("running", AgentStep.ACTION_RUNNING),
                action("proposed", AgentStep.ACTION_PROPOSED),
                action("succeeded", AgentStep.ACTION_SUCCEEDED),
                AgentStep("observation", AgentStep.OBSERVATION, "真实结果", actionStatus = AgentStep.ACTION_RUNNING),
            ),
            pendingReview = null,
        )

        val imported = ImportedAgentSessionPolicy.quarantine(original)

        assertFalse(imported.autoApprove)
        assertEquals("stopped", imported.runStatus)
        assertEquals(AgentRunPhase.IDLE, imported.runCheckpoint.phase)
        assertFalse(imported.runCheckpoint.recoveryPending)
        assertEquals(AgentStep.ACTION_INTERRUPTED, imported.steps[0].actionStatus)
        assertSame(original.steps[1], imported.steps[1])
        assertSame(original.steps[2], imported.steps[2])
        assertSame(original.steps[3], imported.steps[3])
        assertTrue(original.autoApprove)
        assertEquals("running", original.runStatus)
        assertEquals(AgentStep.ACTION_RUNNING, original.steps[0].actionStatus)
        assertFalse(shouldAutoRecover(imported))
    }

    @Test
    fun everyImportedStatusStopsEvenWithAnOrphanedRecoveryMarker() {
        listOf("idle", "running", "RUNNING", "awaiting_user", "awaiting_confirm", "awaiting_review", "stopped", "done", "error")
            .forEach { savedStatus ->
                val imported = ImportedAgentSessionPolicy.quarantine(
                    session().copy(runStatus = savedStatus, pendingReview = null),
                )

                assertEquals("stopped", imported.runStatus)
                assertFalse(shouldAutoRecover(imported))
                assertEquals(
                    AgentRunLivenessDecision.INACTIVE,
                    AgentRunLivenessPolicy.evaluate(
                        AgentRunLivenessSnapshot(
                            runExpected = imported.runStatus == "running" || imported.runCheckpoint.recoveryPending,
                            hasActiveJob = false,
                            phase = imported.runCheckpoint.phase,
                            progressAgeMs = null,
                            automaticRecoveryAttempts = imported.runCheckpoint.automaticRecoveryAttempts,
                        ),
                    ),
                )
            }
    }

    @Test
    fun quarantineRetainsHistoricalIdentitiesAndRoundTripsWithoutInventingEvidence() {
        val original = session()
        val imported = ImportedAgentSessionPolicy.quarantine(original)

        assertEquals(original.copy(
            autoApprove = false,
            runStatus = "stopped",
            runCheckpoint = original.runCheckpoint.copy(phase = AgentRunPhase.IDLE, recoveryPending = false),
        ), imported)
        assertSame(original.activePlan, imported.activePlan)
        assertSame(original.pendingReview, imported.pendingReview)
        assertSame(original.memory, imported.memory)
        assertSame(original.cacheMetrics, imported.cacheMetrics)
        assertSame(original.steps[0], imported.steps[0])
        assertEquals(original.runCheckpoint.runToken, imported.runCheckpoint.runToken)
        assertEquals(original.runCheckpoint.lastProgressAtEpochMs, imported.runCheckpoint.lastProgressAtEpochMs)
        assertEquals(original.runCheckpoint.automaticRecoveryAttempts, imported.runCheckpoint.automaticRecoveryAttempts)
        assertEquals(original.runCheckpoint.lastRecoveryReason, imported.runCheckpoint.lastRecoveryReason)
        assertEquals(imported, ImportedAgentSessionPolicy.quarantine(imported))
        assertEquals(imported, Json.decodeFromString(AgentSession.serializer(), Json.encodeToString(AgentSession.serializer(), imported)))
    }

    private fun shouldAutoRecover(session: AgentSession): Boolean = session.steps.isNotEmpty() &&
        session.pendingReview == null &&
        (session.runStatus.equals("running", ignoreCase = true) || session.runCheckpoint.recoveryPending)

    private fun action(id: String, status: String) = AgentStep(
        id = id,
        type = AgentStep.ACTION,
        text = "历史动作",
        tool = "generate_chapter",
        argsJson = "{\"chapterId\":\"chapter-1\"}",
        actionStatus = status,
        planStepId = "p1",
        planId = "plan-1",
        resolvedProjectId = "project-1",
    )

    private fun session() = AgentSession(
        id = "session-1",
        title = "跨端会话",
        createdAt = "2026-10-07T10:00:00Z",
        lockedProjectId = "project-1",
        autoApprove = true,
        engineMode = "dual",
        reasoningLevel = "high",
        steps = listOf(action("action-1", AgentStep.ACTION_AWAITING_REVIEW)),
        activePlan = AgentPlan(
            summary = "历史计划",
            steps = listOf(AgentPlanStep("p1", "生成章节", status = AgentPlanStepStatus.IN_PROGRESS)),
            createdAt = 123L,
            sourceCommandId = "command-1",
            planId = "plan-1",
        ),
        runStatus = "running",
        runCheckpoint = AgentRunCheckpoint(
            runToken = "run-token-1",
            phase = AgentRunPhase.RECOVERING,
            lastProgressAtEpochMs = 123L,
            automaticRecoveryAttempts = 1,
            recoveryPending = true,
            lastRecoveryReason = "来源设备离线",
        ),
        pendingPrompt = "历史审核提示",
        pendingReview = AgentPendingReview("project-1", "chapter-1", "run-1", "candidate-1", "action-1"),
        memory = AgentSessionMemory(summary = "历史摘要", compactedThroughStepId = "action-1"),
        cacheMetrics = AgentSessionCacheMetrics(observedRequests = 1, hitTokens = 5, missTokens = 10),
    )
}
