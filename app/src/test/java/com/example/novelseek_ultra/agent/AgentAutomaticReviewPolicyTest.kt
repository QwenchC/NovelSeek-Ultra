package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterQualityReport
import com.example.novelseek_ultra.data.model.GenerationRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAutomaticReviewPolicyTest {
    @Test
    fun newSessionsDefaultToManualReviewEvenWithGeneralAutoApproval() {
        val session = AgentSession(id = SESSION_ID, autoApprove = true)

        assertFalse(session.skipManualReview)
        assertManualWithoutAdoption(
            enabledAtStart = session.skipManualReview,
            enabledNow = session.skipManualReview,
        )
    }

    @Test
    fun disabledPermissionNeverAdopts() {
        assertManualWithoutAdoption(enabledAtStart = false, enabledNow = false)
    }

    @Test
    fun revokingPermissionDuringGenerationReturnsToManualReview() {
        assertManualWithoutAdoption(enabledAtStart = true, enabledNow = false)
    }

    @Test
    fun enablingPermissionDuringGenerationDoesNotRetroactivelyAcceptExistingCandidate() {
        assertManualWithoutAdoption(enabledAtStart = false, enabledNow = true)
    }

    @Test
    fun absentOrBlankLockedProjectReturnsToManualReview() {
        listOf(null, "", " \t").forEach { projectId ->
            assertManualWithoutAdoption(lockedProjectId = projectId)
        }
    }

    @Test
    fun anotherLockedProjectReturnsToManualReview() {
        assertManualWithoutAdoption(lockedProjectId = "different-project")
    }

    @Test
    fun absentOrBlankSessionReturnsToManualReview() {
        listOf(null, "", " \t").forEach { sessionId ->
            assertManualWithoutAdoption(sessionId = sessionId)
        }
    }

    @Test
    fun blankActionOrReviewForAnotherActionReturnsToManualReview() {
        listOf("", " \t", "different-action").forEach { actionId ->
            assertManualWithoutAdoption(actionId = actionId)
        }
    }

    @Test
    fun reviewForAnotherProjectReturnsToManualReview() {
        assertManualWithoutAdoption(review = review().copy(projectId = "different-project"))
    }

    @Test
    fun missingRunBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = null)
    }

    @Test
    fun anotherRunBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(id = "different-run"))
    }

    @Test
    fun runForAnotherProjectBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(projectId = "different-project"))
    }

    @Test
    fun runForAnotherChapterBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(chapterId = "different-chapter"))
    }

    @Test
    fun editorRunIsNeverAutomaticallyAdoptedByAgent() {
        assertBlockedWithoutAdoption(run = run().copy(initiator = GenerationRun.INITIATOR_EDITOR))
    }

    @Test
    fun runForAnotherOrMissingSessionBlocksWithoutAttemptingAdoption() {
        listOf(null, "", "different-session").forEach { sessionId ->
            assertBlockedWithoutAdoption(run = run().copy(agentSessionId = sessionId))
        }
    }

    @Test
    fun runForAnotherOrMissingActionBlocksWithoutAttemptingAdoption() {
        listOf(null, "", "different-action").forEach { actionId ->
            assertBlockedWithoutAdoption(run = run().copy(agentActionId = actionId))
        }
    }

    @Test
    fun everyNonCompletedRunStatusBlocksWithoutAttemptingAdoption() {
        listOf(
            GenerationRun.STATUS_RUNNING,
            GenerationRun.STATUS_FAILED,
            GenerationRun.STATUS_CANCELLED,
            GenerationRun.STATUS_ACCEPTED,
            GenerationRun.STATUS_REJECTED,
            "unknown",
        ).forEach { status ->
            assertBlockedWithoutAdoption(run = run().copy(status = status))
        }
    }

    @Test
    fun missingOrDifferentCandidateBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(candidates = emptyList()))
        assertBlockedWithoutAdoption(
            run = run().copy(candidates = listOf(candidate().copy(id = "different-candidate"))),
        )
    }

    @Test
    fun ambiguousDuplicateCandidateIdentityBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(candidates = listOf(candidate(), candidate())))
    }

    @Test
    fun everyNonCompletedCandidateStatusBlocksWithoutAttemptingAdoption() {
        listOf(
            CandidateChapter.STATUS_PENDING,
            CandidateChapter.STATUS_RUNNING,
            CandidateChapter.STATUS_FAILED,
            CandidateChapter.STATUS_CANCELLED,
            "unknown",
        ).forEach { status ->
            assertBlockedWithoutAdoption(run = run().copy(candidates = listOf(candidate().copy(status = status))))
        }
    }

    @Test
    fun emptyOrWhitespaceProseBlocksWithoutAttemptingAdoption() {
        listOf("", " \n\t").forEach { body ->
            assertBlockedWithoutAdoption(run = run().copy(candidates = listOf(candidate().copy(body = body))))
        }
    }

    @Test
    fun blockingQualityReportBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(candidates = listOf(
            candidate().copy(qualityReport = ChapterQualityReport(completedStream = true, blocking = true)),
        )))
    }

    @Test
    fun missingQualityReportBlocksWithoutAttemptingAdoption() {
        assertBlockedWithoutAdoption(run = run().copy(candidates = listOf(candidate().copy(qualityReport = null))))
    }

    @Test
    fun incompleteStreamBlocksWithoutAttemptingAdoptionEvenWithCompletedStatus() {
        assertBlockedWithoutAdoption(run = run().copy(candidates = listOf(
            candidate().copy(qualityReport = ChapterQualityReport(completedStream = false, blocking = false)),
        )))
    }

    @Test
    fun validScopedCandidateUsesNormalAdoptionExactlyOnceAndReturnsItsVerifiedResult() {
        val adoption = adopted(run())
        var adoptionCalls = 0

        val outcome = resolve {
            adoptionCalls++
            adoption
        }

        assertEquals(1, adoptionCalls)
        assertTrue(outcome is AgentAutomaticReviewPolicy.Outcome.Accepted)
        assertSame(adoption, (outcome as AgentAutomaticReviewPolicy.Outcome.Accepted).result)
    }

    @Test
    fun unrelatedAdditionalCandidateDoesNotChangeSelectedReviewIdentity() {
        val currentRun = run().copy(candidates = listOf(candidate().copy(id = "other-candidate"), candidate()))
        val adoption = adopted(currentRun)
        var adoptionCalls = 0

        val outcome = resolve(run = currentRun) {
            adoptionCalls++
            adoption
        }

        assertEquals(1, adoptionCalls)
        assertTrue(outcome is AgentAutomaticReviewPolicy.Outcome.Accepted)
        assertEquals(CANDIDATE_ID, (outcome as AgentAutomaticReviewPolicy.Outcome.Accepted).result.run.selectedCandidateId)
    }

    @Test
    fun eachSourceConflictStopsAutomaticAdoptionInsteadOfClaimingSuccess() {
        listOf(
            CandidateAdoptionResult.SourceChanged(planChanged = true, bodyChanged = false),
            CandidateAdoptionResult.SourceChanged(planChanged = false, bodyChanged = true),
            CandidateAdoptionResult.SourceChanged(planChanged = false, bodyChanged = false, contextChanged = true),
        ).forEach { result ->
            assertBlockedAfterOneAdoption(result)
        }
    }

    @Test
    fun unavailableAdoptionNeverClaimsSuccessOrRetriesCommit() {
        listOf(
            CandidateAdoptionResult.RUN_NOT_FOUND,
            CandidateAdoptionResult.RUN_NOT_READY,
            CandidateAdoptionResult.CANDIDATE_NOT_FOUND,
            CandidateAdoptionResult.CANDIDATE_NOT_READY,
            CandidateAdoptionResult.STREAM_INCOMPLETE,
            CandidateAdoptionResult.QUALITY_BLOCKED,
            CandidateAdoptionResult.EMPTY_BODY,
            CandidateAdoptionResult.CHAPTER_NOT_FOUND,
            CandidateAdoptionResult.ALREADY_ACCEPTED,
            "persistence_failure",
        ).forEach { reason ->
            assertBlockedAfterOneAdoption(CandidateAdoptionResult.Unavailable(reason))
        }
    }

    @Test
    fun acceptedResultForDifferentRunProjectOrChapterIsBlocked() {
        val result = adopted(run())
        listOf(
            result.run.copy(id = "different-run"),
            result.run.copy(projectId = "different-project"),
            result.run.copy(chapterId = "different-chapter"),
        ).forEach { differentRun ->
            assertBlockedAfterOneAdoption(result.copy(run = differentRun))
        }
    }

    @Test
    fun acceptedResultForDifferentSessionOrActionIsBlocked() {
        val result = adopted(run())
        listOf(
            result.run.copy(agentSessionId = null),
            result.run.copy(agentSessionId = "different-session"),
            result.run.copy(agentActionId = null),
            result.run.copy(agentActionId = "different-action"),
        ).forEach { differentRun ->
            assertBlockedAfterOneAdoption(result.copy(run = differentRun))
        }
    }

    @Test
    fun uncommittedOrDifferentlySelectedAdoptionResultIsBlocked() {
        val result = adopted(run())
        listOf(
            result.run.copy(status = GenerationRun.STATUS_COMPLETED),
            result.run.copy(status = GenerationRun.STATUS_FAILED),
            result.run.copy(selectedCandidateId = null),
            result.run.copy(selectedCandidateId = "different-candidate"),
        ).forEach { differentRun ->
            assertBlockedAfterOneAdoption(result.copy(run = differentRun))
        }
    }

    @Test
    fun returnedChapterForDifferentProjectOrChapterIsBlocked() {
        val result = adopted(run())
        listOf(
            result.chapter.copy(id = "different-chapter"),
            result.chapter.copy(project_id = "different-project"),
        ).forEach { differentChapter ->
            assertBlockedAfterOneAdoption(result.copy(chapter = differentChapter))
        }
    }

    @Test
    fun twoConsecutiveChapterCandidatesAreAcceptedWithoutManualReviewOutcome() {
        var adoptionCalls = 0
        val outcomes = (1..2).map { ordinal ->
            val actionId = "action-$ordinal"
            val chapterId = "chapter-$ordinal"
            val runId = "run-$ordinal"
            val candidateId = "candidate-$ordinal"
            val currentRun = run().copy(
                id = runId,
                chapterId = chapterId,
                agentActionId = actionId,
                candidates = listOf(candidate().copy(id = candidateId)),
            )
            val currentReview = AgentPendingReview(PROJECT_ID, chapterId, runId, candidateId, actionId)
            resolve(actionId = actionId, review = currentReview, run = currentRun) {
                adoptionCalls++
                adopted(currentRun, candidateId)
            }
        }

        assertEquals(2, adoptionCalls)
        assertTrue(outcomes.all { it is AgentAutomaticReviewPolicy.Outcome.Accepted })
        assertEquals(
            listOf("chapter-1", "chapter-2"),
            outcomes.map { (it as AgentAutomaticReviewPolicy.Outcome.Accepted).result.chapter.id },
        )
    }

    private fun assertManualWithoutAdoption(
        enabledAtStart: Boolean = true,
        enabledNow: Boolean = true,
        lockedProjectId: String? = PROJECT_ID,
        sessionId: String? = SESSION_ID,
        actionId: String = ACTION_ID,
        review: AgentPendingReview = review(),
    ) {
        var adoptionCalls = 0
        val outcome = resolve(enabledAtStart, enabledNow, lockedProjectId, sessionId, actionId, review) {
            adoptionCalls++
            adopted(run())
        }

        assertSame(AgentAutomaticReviewPolicy.Outcome.Manual, outcome)
        assertEquals(0, adoptionCalls)
    }

    private fun assertBlockedWithoutAdoption(run: GenerationRun?) {
        var adoptionCalls = 0
        val outcome = resolve(run = run) {
            adoptionCalls++
            adopted(run ?: run())
        }

        assertTrue(outcome is AgentAutomaticReviewPolicy.Outcome.Blocked)
        assertTrue((outcome as AgentAutomaticReviewPolicy.Outcome.Blocked).message.isNotBlank())
        assertEquals(0, adoptionCalls)
    }

    private fun assertBlockedAfterOneAdoption(result: CandidateAdoptionResult) {
        var adoptionCalls = 0
        val outcome = resolve {
            adoptionCalls++
            result
        }

        assertTrue(outcome is AgentAutomaticReviewPolicy.Outcome.Blocked)
        assertTrue((outcome as AgentAutomaticReviewPolicy.Outcome.Blocked).message.isNotBlank())
        assertEquals(1, adoptionCalls)
    }

    private fun resolve(
        enabledAtStart: Boolean = true,
        enabledNow: Boolean = true,
        lockedProjectId: String? = PROJECT_ID,
        sessionId: String? = SESSION_ID,
        actionId: String = ACTION_ID,
        review: AgentPendingReview = review(),
        run: GenerationRun? = run(),
        adopt: () -> CandidateAdoptionResult,
    ): AgentAutomaticReviewPolicy.Outcome = AgentAutomaticReviewPolicy.resolve(
        enabledAtStart = enabledAtStart,
        enabledNow = enabledNow,
        lockedProjectId = lockedProjectId,
        sessionId = sessionId,
        actionId = actionId,
        review = review,
        run = run,
        adopt = adopt,
    )

    private fun review() = AgentPendingReview(PROJECT_ID, CHAPTER_ID, RUN_ID, CANDIDATE_ID, ACTION_ID)

    private fun candidate() = CandidateChapter(
        id = CANDIDATE_ID,
        slot = 1,
        status = CandidateChapter.STATUS_COMPLETED,
        body = "完整且通过校验的章节正文。",
        qualityReport = ChapterQualityReport(completedStream = true, blocking = false),
    )

    private fun run() = GenerationRun(
        id = RUN_ID,
        projectId = PROJECT_ID,
        chapterId = CHAPTER_ID,
        initiator = GenerationRun.INITIATOR_AGENT,
        agentSessionId = SESSION_ID,
        agentActionId = ACTION_ID,
        status = GenerationRun.STATUS_COMPLETED,
        candidates = listOf(candidate()),
    )

    private fun adopted(run: GenerationRun, selectedCandidateId: String = CANDIDATE_ID) =
        CandidateAdoptionResult.Adopted(
            run = run.copy(status = GenerationRun.STATUS_ACCEPTED, selectedCandidateId = selectedCandidateId),
            chapter = Chapter(run.chapterId, run.projectId, "章节标题", 1),
        )

    companion object {
        private const val PROJECT_ID = "project-1"
        private const val CHAPTER_ID = "chapter-1"
        private const val RUN_ID = "run-1"
        private const val CANDIDATE_ID = "candidate-1"
        private const val SESSION_ID = "session-1"
        private const val ACTION_ID = "action-1"
    }
}
