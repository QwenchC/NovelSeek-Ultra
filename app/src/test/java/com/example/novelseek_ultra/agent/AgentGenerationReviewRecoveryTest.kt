package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.GenerationRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AgentGenerationReviewRecoveryTest {
    @Test
    fun completedRunRestoresReviewGateAndPendingObservation() {
        val session = session(runStatus = "running")

        val recovered = AgentGenerationReviewRecovery.recover(
            session,
            listOf(run(status = GenerationRun.STATUS_COMPLETED)),
        )

        assertEquals(AgentStep.ACTION_AWAITING_REVIEW, recovered.action().actionStatus)
        assertEquals(
            AgentPendingReview("project-1", "chapter-1", "run-1", "candidate-1", "action-1"),
            recovered.pendingReview,
        )
        assertEquals("running", recovered.runStatus)
        assertEquals(1, recovered.results(AgentStep.RESULT_PENDING_REVIEW).size)
    }

    @Test
    fun interruptedActionRestoresReviewGateWithoutLosingStoppedIntent() {
        val interrupted = session(runStatus = "stopped").copy(
            steps = session().steps.map {
                it.copy(actionStatus = AgentStep.ACTION_INTERRUPTED)
            },
        )
        val recovered = AgentGenerationReviewRecovery.recover(
            interrupted,
            listOf(run(status = GenerationRun.STATUS_COMPLETED)),
        )

        assertEquals("stopped", recovered.runStatus)
        assertEquals(AgentStep.ACTION_AWAITING_REVIEW, recovered.action().actionStatus)
        assertEquals("run-1", recovered.pendingReview?.runId)
    }

    @Test
    fun awaitingReviewActionRebuildsGateWhenGatePersistenceWasLost() {
        val gateLost = session().copy(
            steps = session().steps.map {
                it.copy(actionStatus = AgentStep.ACTION_AWAITING_REVIEW)
            },
            pendingReview = null,
        )

        val recovered = AgentGenerationReviewRecovery.recover(
            gateLost,
            listOf(run(status = GenerationRun.STATUS_COMPLETED)),
        )

        assertEquals(AgentStep.ACTION_AWAITING_REVIEW, recovered.action().actionStatus)
        assertEquals(
            AgentPendingReview("project-1", "chapter-1", "run-1", "candidate-1", "action-1"),
            recovered.pendingReview,
        )
        assertEquals(1, recovered.results(AgentStep.RESULT_PENDING_REVIEW).size)
    }

    @Test
    fun acceptedRunRestoresSucceededCommittedResult() {
        assertTerminalRecovery(GenerationRun.STATUS_ACCEPTED, AgentStep.ACTION_SUCCEEDED)
    }

    @Test
    fun rejectedRunRestoresDeniedCommittedResult() {
        assertTerminalRecovery(GenerationRun.STATUS_REJECTED, AgentStep.ACTION_DENIED)
    }

    @Test
    fun failedRunRestoresFailedCommittedResult() {
        assertTerminalRecovery(GenerationRun.STATUS_FAILED, AgentStep.ACTION_FAILED)
    }

    @Test
    fun cancelledRunRestoresFailedCommittedResult() {
        assertTerminalRecovery(GenerationRun.STATUS_CANCELLED, AgentStep.ACTION_FAILED)
    }

    @Test
    fun runningOrUnrelatedRunIsLeftForGenericRecovery() {
        val session = session()
        val recovered = AgentGenerationReviewRecovery.recover(
            session,
            listOf(
                run(status = GenerationRun.STATUS_RUNNING),
                run(status = GenerationRun.STATUS_COMPLETED).copy(
                    id = "other-run",
                    agentSessionId = "other-session",
                ),
            ),
        )

        assertSame(session, recovered)
        assertEquals(AgentStep.ACTION_RUNNING, recovered.action().actionStatus)
    }

    @Test
    fun persistedGateIsNotRebuiltOrDuplicated() {
        val pending = AgentPendingReview(
            projectId = "project-1",
            chapterId = "chapter-1",
            runId = "run-1",
            candidateId = "candidate-1",
            actionId = "action-1",
        )
        val session = session().copy(pendingReview = pending)

        val recovered = AgentGenerationReviewRecovery.recover(
            session,
            listOf(run(status = GenerationRun.STATUS_COMPLETED)),
        )

        assertSame(session, recovered)
    }

    @Test
    fun recoveryIsIdempotentAndDoesNotDuplicateExistingResult() {
        val existing = AgentStep(
            id = "existing-observation",
            type = AgentStep.OBSERVATION,
            text = "already recorded",
            resultForActionId = "action-1",
            resultKind = AgentStep.RESULT_COMMITTED,
        )
        val session = session().copy(steps = session().steps + existing)
        val runs = listOf(run(status = GenerationRun.STATUS_ACCEPTED))

        val once = AgentGenerationReviewRecovery.recover(session, runs)
        val twice = AgentGenerationReviewRecovery.recover(once, runs)

        assertEquals(once, twice)
        assertEquals(1, twice.results(AgentStep.RESULT_COMMITTED).size)
    }

    private fun assertTerminalRecovery(runStatus: String, expectedActionStatus: String) {
        val recovered = AgentGenerationReviewRecovery.recover(
            session(),
            listOf(run(status = runStatus)),
        )

        assertEquals(expectedActionStatus, recovered.action().actionStatus)
        assertNull(recovered.pendingReview)
        assertEquals(1, recovered.results(AgentStep.RESULT_COMMITTED).size)
    }

    private fun session(runStatus: String = "running") = AgentSession(
        id = "session-1",
        runStatus = runStatus,
        steps = listOf(
            AgentStep(
                id = "action-1",
                type = AgentStep.ACTION,
                text = "generate",
                actionStatus = AgentStep.ACTION_RUNNING,
            ),
        ),
    )

    private fun run(status: String) = GenerationRun(
        id = "run-1",
        projectId = "project-1",
        chapterId = "chapter-1",
        initiator = GenerationRun.INITIATOR_AGENT,
        agentSessionId = "session-1",
        agentActionId = "action-1",
        status = status,
        candidates = listOf(
            CandidateChapter(
                id = "candidate-1",
                slot = 1,
                status = CandidateChapter.STATUS_COMPLETED,
                body = "candidate body",
            ),
        ),
        createdAt = "2026-08-30T01:00:00Z",
        updatedAt = "2026-08-30T01:01:00Z",
        completedAt = "2026-08-30T01:01:00Z",
    )

    private fun AgentSession.action(): AgentStep = steps.first { it.type == AgentStep.ACTION }

    private fun AgentSession.results(kind: String): List<AgentStep> = steps.filter {
        it.type == AgentStep.OBSERVATION &&
            it.resultForActionId == "action-1" &&
            it.resultKind == kind
    }
}
