package com.example.novelseek_ultra.data.model

import com.example.novelseek_ultra.agent.AgentPlan
import com.example.novelseek_ultra.agent.AgentPlanStep
import com.example.novelseek_ultra.agent.AgentPlanStepStatus
import com.example.novelseek_ultra.agent.AgentRunCheckpoint
import com.example.novelseek_ultra.agent.AgentRunPhase
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionSerializationTest {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacySessionJsonDefaultsToClassicWithoutAPlan() {
        val legacyJson = """
            {
              "id": "legacy-session",
              "title": "旧会话",
              "createdAt": "2026-08-01T12:00:00Z",
              "steps": [
                {"id":"user-1","type":"user","text":"继续写作"},
                {"id":"action-1","type":"action","text":"旧动作","tool":"list_projects"}
              ],
              "lockedProjectId": null,
              "autoApprove": false
            }
        """.trimIndent()

        val session = json.decodeFromString(AgentSession.serializer(), legacyJson)

        assertEquals("classic", session.engineMode)
        assertEquals(AgentReasoningLevels.MEDIUM, session.reasoningLevel)
        assertEquals(AgentSessionCacheMetrics(), session.cacheMetrics)
        assertEquals(AgentSessionMemory(), session.memory)
        assertNull(session.activePlan)
        assertEquals("", session.steps[1].argsJson)
        assertEquals("", session.steps[1].actionStatus)
        assertEquals("", session.steps[1].planStepId)
        assertEquals("", session.steps[1].planId)
        assertEquals("", session.steps[1].resolvedProjectId)
        assertEquals("", session.steps[1].resultKind)
        assertEquals("idle", session.runStatus)
        assertEquals(AgentRunCheckpoint(), session.runCheckpoint)
        assertNull(session.pendingPrompt)
        assertNull(session.pendingReview)
    }

    @Test
    fun dualSessionPlanRoundTripsWithRuntimeStatuses() {
        val original = AgentSession(
            id = "dual-session",
            title = "双智能体会话",
            createdAt = "2026-08-29T12:00:00Z",
            steps = listOf(
                AgentStep("user-1", AgentStep.USER, "完善第三章"),
                AgentStep(
                    id = "action-1",
                    type = AgentStep.ACTION,
                    text = "读取第三章",
                    tool = "read_chapter",
                    actionStatus = AgentStep.ACTION_SUCCEEDED,
                    planStepId = "inspect",
                    planId = "plan-1",
                ),
            ),
            lockedProjectId = "project-1",
            autoApprove = true,
            engineMode = "dual",
            reasoningLevel = AgentReasoningLevels.HIGH,
            cacheMetrics = AgentSessionCacheMetrics(
                observedRequests = 2,
                hitTokens = 150,
                missTokens = 50,
            ),
            memory = AgentSessionMemory(
                summaryVersion = 1,
                summary = "【本地压缩记忆】较早目标与证据",
                compactedThroughStepId = "user-1",
                compactedPrefixDigest = "a".repeat(64),
                compressionCount = 2,
                compressedStepCount = 1,
                lastCompressedAt = "2026-08-30T12:00:00Z",
            ),
            runStatus = "stopped",
            runCheckpoint = AgentRunCheckpoint(
                runToken = "run-token-1",
                phase = AgentRunPhase.RECOVERING,
                lastProgressAtEpochMs = 1_777_778L,
                automaticRecoveryAttempts = 1,
                recoveryPending = true,
                lastRecoveryReason = "process restored",
            ),
            pendingPrompt = "请确认第三章范围",
            activePlan = AgentPlan(
                summary = "先检查再修订",
                steps = listOf(
                    AgentPlanStep(
                        id = "inspect",
                        goal = "检查第三章",
                        successCriteria = "列出结构问题",
                        suggestedTools = listOf("read_chapter"),
                        status = AgentPlanStepStatus.COMPLETED,
                    ),
                    AgentPlanStep(
                        id = "revise",
                        goal = "局部修订第三章",
                        status = AgentPlanStepStatus.IN_PROGRESS,
                    ),
                ),
                createdAt = 1_777_777L,
                sourceCommandId = "user-1",
                planId = "plan-1",
            ),
        )

        val encoded = json.encodeToString(AgentSession.serializer(), original)
        val decoded = json.decodeFromString(AgentSession.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals("dual", decoded.engineMode)
        assertEquals(AgentReasoningLevels.HIGH, decoded.reasoningLevel)
        assertEquals(0.75, decoded.cacheMetrics.hitRate()!!, 0.0001)
        assertEquals(original.memory, decoded.memory)
        assertEquals("stopped", decoded.runStatus)
        assertEquals(original.runCheckpoint, decoded.runCheckpoint)
        assertEquals("请确认第三章范围", decoded.pendingPrompt)
        assertEquals("user-1", decoded.activePlan?.sourceCommandId)
        assertEquals("plan-1", decoded.activePlan?.planId)
        assertEquals("plan-1", decoded.steps[1].planId)
        assertEquals(AgentPlanStepStatus.IN_PROGRESS, decoded.activePlan?.steps?.get(1)?.status)
        assertTrue(encoded.contains("\"in_progress\""))
    }

    @Test
    fun pendingCandidateReviewRoundTripsWithActionAndResultKinds() {
        val pendingReview = AgentPendingReview(
            projectId = "project-1",
            chapterId = "chapter-3",
            runId = "run-1",
            candidateId = "candidate-1",
            actionId = "action-1",
        )
        val original = AgentSession(
            id = "review-session",
            steps = listOf(
                AgentStep(
                    id = "action-1",
                    type = AgentStep.ACTION,
                    text = "生成第三章候选稿",
                    tool = "generate_chapter",
                    actionStatus = AgentStep.ACTION_AWAITING_REVIEW,
                ),
                AgentStep(
                    id = "result-1",
                    type = AgentStep.OBSERVATION,
                    text = "候选稿待审核",
                    resultForActionId = "action-1",
                    resultKind = AgentStep.RESULT_PENDING_REVIEW,
                ),
            ),
            runStatus = "awaiting_review",
            pendingReview = pendingReview,
        )

        val encoded = json.encodeToString(AgentSession.serializer(), original)
        val decoded = json.decodeFromString(AgentSession.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(pendingReview, decoded.pendingReview)
        assertEquals(AgentStep.ACTION_AWAITING_REVIEW, decoded.steps[0].actionStatus)
        assertEquals(AgentStep.RESULT_PENDING_REVIEW, decoded.steps[1].resultKind)
    }
}
