package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.GenerationRun

/**
 * Repairs the narrow crash window after an agent generation run was durably finalized but before
 * its action/review gate was saved in the agent session.
 *
 * GenerationRun is authoritative here. Unknown or still-running runs deliberately remain untouched
 * so the general [AgentRunRecovery] policy can conservatively mark their actions interrupted.
 */
object AgentGenerationReviewRecovery {
    private const val REVIEW_PROMPT =
        "请先审核章节候选稿；采用或拒绝后才能继续执行。"
    private val RECOVERABLE_ACTION_STATUSES = setOf(
        AgentStep.ACTION_RUNNING,
        AgentStep.ACTION_INTERRUPTED,
        AgentStep.ACTION_AWAITING_REVIEW,
    )

    fun recover(session: AgentSession, runs: List<GenerationRun>): AgentSession {
        // A persisted gate is already authoritative and will be reconciled by AgentController.
        if (session.pendingReview != null) return session

        val runsByActionId = runs
            .asSequence()
            .filter {
                it.initiator == GenerationRun.INITIATOR_AGENT &&
                    it.agentSessionId == session.id &&
                    !it.agentActionId.isNullOrBlank()
            }
            .groupBy { it.agentActionId.orEmpty() }
            .mapValues { (_, matches) ->
                matches.maxWithOrNull(
                    compareBy<GenerationRun>({ it.updatedAt }, { it.completedAt.orEmpty() }, { it.id }),
                )
            }

        var pendingReview: AgentPendingReview? = null
        var pendingPrompt = session.pendingPrompt
        var changed = false
        val observations = mutableListOf<RecoveredObservation>()

        val recoveredSteps = session.steps.map { step ->
            if (
                step.type != AgentStep.ACTION ||
                step.actionStatus !in RECOVERABLE_ACTION_STATUSES
            ) {
                return@map step
            }
            val run = runsByActionId[step.id] ?: return@map step
            when (run.status) {
                GenerationRun.STATUS_COMPLETED -> {
                    val candidate = completedCandidate(run) ?: return@map step
                    // Agent execution is sequential. If corrupted legacy data contains multiple
                    // running completed actions, recover one review gate and let generic recovery
                    // conservatively interrupt the others.
                    if (pendingReview != null) return@map step
                    pendingReview = AgentPendingReview(
                        projectId = run.projectId,
                        chapterId = run.chapterId,
                        runId = run.id,
                        candidateId = candidate.id,
                        actionId = step.id,
                    )
                    pendingPrompt = REVIEW_PROMPT
                    observations += RecoveredObservation(
                        actionId = step.id,
                        run = run,
                        resultKind = AgentStep.RESULT_PENDING_REVIEW,
                        text = "检测到上次已完成的章节候选稿；请预览并采用或拒绝后，智能体才会继续。",
                    )
                    changed = true
                    step.copy(actionStatus = AgentStep.ACTION_AWAITING_REVIEW)
                }

                GenerationRun.STATUS_ACCEPTED -> recoverTerminal(
                    step = step,
                    run = run,
                    actionStatus = AgentStep.ACTION_SUCCEEDED,
                    text = "检测到章节候选稿已被采用；正式正文已经更新。",
                    observations = observations,
                ).also { changed = true }

                GenerationRun.STATUS_REJECTED -> recoverTerminal(
                    step = step,
                    run = run,
                    actionStatus = AgentStep.ACTION_DENIED,
                    text = "检测到章节候选稿已被拒绝；正式正文保持不变。",
                    observations = observations,
                ).also { changed = true }

                GenerationRun.STATUS_FAILED -> recoverTerminal(
                    step = step,
                    run = run,
                    actionStatus = AgentStep.ACTION_FAILED,
                    text = "检测到章节候选稿生成失败；正式正文未改变。",
                    observations = observations,
                ).also { changed = true }

                GenerationRun.STATUS_CANCELLED -> recoverTerminal(
                    step = step,
                    run = run,
                    actionStatus = AgentStep.ACTION_FAILED,
                    text = "检测到章节候选稿生成已取消；正式正文未改变。",
                    observations = observations,
                ).also { changed = true }

                else -> step
            }
        }

        if (!changed) return session

        val stepsWithObservations = recoveredSteps.toMutableList()
        observations.forEach { observation ->
            val alreadyRecorded = stepsWithObservations.any {
                it.type == AgentStep.OBSERVATION &&
                    it.resultForActionId == observation.actionId &&
                    it.resultKind == observation.resultKind
            }
            if (!alreadyRecorded) {
                stepsWithObservations += AgentStep(
                    id = "generation-recovery-${observation.run.id}-${observation.resultKind}",
                    type = AgentStep.OBSERVATION,
                    text = observation.text,
                    createdAt = observation.run.completedAt
                        ?.takeIf(String::isNotBlank)
                        ?: observation.run.updatedAt.takeIf(String::isNotBlank)
                        ?: observation.run.createdAt,
                    resultForActionId = observation.actionId,
                    resultKind = observation.resultKind,
                )
            }
        }

        // In particular, do not alter runStatus: a persisted "stopped" is an explicit user intent.
        return session.copy(
            steps = stepsWithObservations,
            pendingPrompt = pendingPrompt,
            pendingReview = pendingReview,
        )
    }

    private fun completedCandidate(run: GenerationRun): CandidateChapter? {
        val completed = run.candidates.filter { it.status == CandidateChapter.STATUS_COMPLETED }
        return run.selectedCandidateId
            ?.let { selected -> completed.firstOrNull { it.id == selected } }
            ?: completed.minWithOrNull(compareBy<CandidateChapter>({ it.slot }, { it.id }))
    }

    private fun recoverTerminal(
        step: AgentStep,
        run: GenerationRun,
        actionStatus: String,
        text: String,
        observations: MutableList<RecoveredObservation>,
    ): AgentStep {
        observations += RecoveredObservation(
            actionId = step.id,
            run = run,
            resultKind = AgentStep.RESULT_COMMITTED,
            text = text,
        )
        return step.copy(actionStatus = actionStatus)
    }

    private data class RecoveredObservation(
        val actionId: String,
        val run: GenerationRun,
        val resultKind: String,
        val text: String,
    )
}
