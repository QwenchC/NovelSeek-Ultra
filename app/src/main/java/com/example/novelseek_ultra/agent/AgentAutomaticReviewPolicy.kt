package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.GenerationRun

/** Session consent is not permission to accept old, imported, or another action's candidate. */
internal object AgentAutomaticReviewPolicy {
    sealed interface Outcome {
        data object Manual : Outcome
        data class Accepted(val result: CandidateAdoptionResult.Adopted) : Outcome
        data class Blocked(val message: String) : Outcome
    }

    fun resolve(
        enabledAtStart: Boolean,
        enabledNow: Boolean,
        lockedProjectId: String?,
        sessionId: String?,
        actionId: String,
        review: AgentPendingReview,
        run: GenerationRun?,
        adopt: () -> CandidateAdoptionResult,
    ): Outcome {
        if (!enabledAtStart || !enabledNow || lockedProjectId.isNullOrBlank() ||
            sessionId.isNullOrBlank() || actionId.isBlank() || review.projectId != lockedProjectId ||
            review.actionId != actionId) return Outcome.Manual

        if (run == null || run.id != review.runId || run.projectId != lockedProjectId ||
            run.chapterId != review.chapterId || run.initiator != GenerationRun.INITIATOR_AGENT ||
            run.agentSessionId != sessionId || run.agentActionId != actionId ||
            run.status != GenerationRun.STATUS_COMPLETED) {
            return Outcome.Blocked("自动采用已暂停：候选稿身份或状态已变化，请手动核对。")
        }
        val candidate = run.candidates.singleOrNull { it.id == review.candidateId }
        if (candidate == null || candidate.status != CandidateChapter.STATUS_COMPLETED ||
            candidate.body.isBlank() ||
            candidate.qualityReport?.let { it.completedStream && !it.blocking } != true) {
            return Outcome.Blocked("自动采用已暂停：候选稿未完成或未通过内容校验，请手动核对。")
        }

        // This callback must use the normal transactional source/context recheck, never a direct
        // body write. The controller holds its run fence through this synchronous commit.
        return when (val result = adopt()) {
            is CandidateAdoptionResult.Adopted -> {
                if (result.run.id == run.id && result.run.projectId == run.projectId &&
                    result.run.chapterId == run.chapterId &&
                    result.run.agentSessionId == sessionId && result.run.agentActionId == actionId &&
                    result.run.status == GenerationRun.STATUS_ACCEPTED &&
                    result.run.selectedCandidateId == review.candidateId &&
                    result.chapter.id == run.chapterId && result.chapter.project_id == run.projectId) {
                    Outcome.Accepted(result)
                } else {
                    Outcome.Blocked("自动采用结果身份不一致，智能体已暂停，请检查正式正文。")
                }
            }
            is CandidateAdoptionResult.SourceChanged -> Outcome.Blocked(
                "自动采用已暂停：正文、规划或生成上下文发生变化；候选稿已保留，不覆盖新内容。",
            )
            is CandidateAdoptionResult.Unavailable -> Outcome.Blocked(
                "自动采用已暂停：候选稿不能采用（${result.reason}），请手动核对。",
            )
        }
    }
}
