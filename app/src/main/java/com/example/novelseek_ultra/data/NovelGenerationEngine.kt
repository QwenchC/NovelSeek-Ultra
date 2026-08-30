package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.ai.ChapterCandidateValidator
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterQualityReport
import com.example.novelseek_ultra.data.model.ChapterSpec
import com.example.novelseek_ultra.data.model.GenerationContextManifest
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationTelemetry

/**
 * The single lifecycle boundary for every whole-chapter or agent-authored text candidate.
 *
 * Prompt construction remains provider-specific, but no caller may reserve, validate, finish,
 * cancel, reject or publish a candidate through a second protocol.
 */
internal class NovelGenerationEngine private constructor(
    private val port: Port,
) {
    constructor(repository: AppRepository) : this(RepositoryPort(repository))

    data class Request(
        val projectId: String,
        val chapter: Chapter,
        val targetWords: Int = 0,
        val language: String = "zh",
        val mode: String = ChapterSpec.MODE_ONE_SHOT,
        val baselineText: String = "",
        val requireNetNewBody: Boolean = false,
        val beats: List<String> = emptyList(),
        val constraints: List<String> = emptyList(),
        val contextManifest: GenerationContextManifest? = null,
        /** v1 plan/body hash captured by the caller before entering the creation lock. */
        val expectedSourceHash: String,
        val initiator: String = GenerationRun.INITIATOR_EDITOR,
        val agentEngine: String? = null,
        val agentSessionId: String? = null,
        val agentActionId: String? = null,
        val operation: String = GenerationRun.OPERATION_GENERATE,
    )

    data class ActiveCandidate(
        val request: Request,
        val run: GenerationRun,
        val candidate: CandidateChapter,
    )

    data class Completion(
        val run: GenerationRun,
        val candidate: CandidateChapter,
        val report: ChapterQualityReport,
    )

    /**
     * Supersede only an unfinished run, then atomically reserve and activate one candidate slot.
     * Completed review work remains intact until the user accepts or rejects it.
     */
    fun start(request: Request): ActiveCandidate? = startWithContextRecheck(request, null)

    /** Production prompt path: re-evaluate mutable context inside the run-creation lock. */
    fun startWithContextRecheck(
        request: Request,
        currentContextManifest: (() -> GenerationContextManifest?)?,
    ): ActiveCandidate? {
        val latest = port.latest(request.projectId, request.chapter.id)
        // A completed run is not stale work: it is an unresolved user decision. Allowing a second
        // run would hide the first candidate in the editor and could strand an agent action in its
        // durable awaiting-review state.
        if (latest?.status == GenerationRun.STATUS_COMPLETED) return null
        // Port.create owns the atomic source-check + active-run supersede boundary. Cancelling here
        // first would let a stale request terminate a newer, valid generation before its own source
        // mismatch is discovered.
        val run = port.create(request, currentContextManifest) ?: return null
        val candidate = run.candidates.singleOrNull()
            ?: run.candidates.minByOrNull { it.slot }
            ?: run.also {
                port.cancel(request.projectId, run.id, "生成任务没有可用的候选槽位")
            }.let { return null }
        val running = port.markRunning(request.projectId, run.id, candidate.id)
        if (running == null) {
            port.cancel(request.projectId, run.id, "候选稿未能进入生成状态")
            return null
        }
        val activeCandidate = running.candidates.firstOrNull { it.id == candidate.id }
        if (
            running.status != GenerationRun.STATUS_RUNNING ||
            activeCandidate?.status != CandidateChapter.STATUS_RUNNING
        ) {
            port.cancel(request.projectId, run.id, "候选稿未能进入生成状态")
            return null
        }
        return ActiveCandidate(request, running, activeCandidate)
    }

    fun complete(
        active: ActiveCandidate,
        body: String,
        completedStream: Boolean = true,
        telemetry: GenerationTelemetry? = null,
    ): Completion? {
        val report = ChapterCandidateValidator.validate(
            candidateText = body,
            baselineText = active.request.baselineText,
            completedStream = completedStream,
            targetWords = active.request.targetWords,
            requireNetNewBody = active.request.requireNetNewBody,
        )
        val run = port.complete(
            projectId = active.request.projectId,
            runId = active.run.id,
            candidateId = active.candidate.id,
            body = body,
            report = report,
            telemetry = telemetry,
        ) ?: return null
        val candidate = run.candidates.firstOrNull { it.id == active.candidate.id } ?: return null
        // Repository transforms return the current run when a stale writer loses the race. Treat
        // that as a failed completion, never as proof that this body became reviewable.
        if (
            run.status != GenerationRun.STATUS_COMPLETED ||
            candidate.status != CandidateChapter.STATUS_COMPLETED ||
            candidate.body != body ||
            candidate.qualityReport != report ||
            run.telemetry != telemetry
        ) {
            return null
        }
        return Completion(run, candidate, report)
    }

    fun fail(
        active: ActiveCandidate,
        error: String,
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun? =
        port.fail(active.request.projectId, active.run.id, error, telemetry)

    fun cancel(
        active: ActiveCandidate,
        reason: String,
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun? =
        port.cancel(active.request.projectId, active.run.id, reason, telemetry)

    fun reject(projectId: String, runId: String): GenerationRun? =
        port.reject(projectId, runId)

    fun adopt(
        projectId: String,
        runId: String,
        candidateId: String,
        currentContextManifest: GenerationContextManifest?,
    ): CandidateAdoptionResult =
        adoptWithContextRecheck(projectId, runId, candidateId) { currentContextManifest }

    /** Production path: [currentContextManifest] is evaluated inside the repository adoption lock. */
    fun adoptWithContextRecheck(
        projectId: String,
        runId: String,
        candidateId: String,
        currentContextManifest: () -> GenerationContextManifest?,
    ): CandidateAdoptionResult =
        port.adopt(projectId, runId, candidateId, currentContextManifest)

    internal interface Port {
        fun latest(projectId: String, chapterId: String): GenerationRun?
        fun create(
            request: Request,
            currentContextManifest: (() -> GenerationContextManifest?)? = null,
        ): GenerationRun?
        fun markRunning(projectId: String, runId: String, candidateId: String): GenerationRun?
        fun complete(
            projectId: String,
            runId: String,
            candidateId: String,
            body: String,
            report: ChapterQualityReport,
            telemetry: GenerationTelemetry?,
        ): GenerationRun?
        fun fail(
            projectId: String,
            runId: String,
            error: String,
            telemetry: GenerationTelemetry? = null,
        ): GenerationRun?
        fun cancel(
            projectId: String,
            runId: String,
            reason: String,
            telemetry: GenerationTelemetry? = null,
        ): GenerationRun?
        fun reject(projectId: String, runId: String): GenerationRun?
        fun adopt(
            projectId: String,
            runId: String,
            candidateId: String,
            currentContextManifest: () -> GenerationContextManifest?,
        ): CandidateAdoptionResult
    }

    private class RepositoryPort(
        private val repository: AppRepository,
    ) : Port {
        override fun latest(projectId: String, chapterId: String): GenerationRun? =
            repository.latestGenerationRun(projectId, chapterId)

        override fun create(
            request: Request,
            currentContextManifest: (() -> GenerationContextManifest?)?,
        ): GenerationRun? =
            repository.createGenerationRun(
                projectId = request.projectId,
                requestedChapter = request.chapter,
                candidateCount = 1,
                targetWords = request.targetWords,
                language = request.language,
                mode = request.mode,
                beats = request.beats,
                constraints = request.constraints,
                contextManifest = request.contextManifest,
                initiator = request.initiator,
                agentEngine = request.agentEngine,
                agentSessionId = request.agentSessionId,
                agentActionId = request.agentActionId,
                operation = request.operation,
                expectedSourceHash = request.expectedSourceHash,
                currentContextManifest = currentContextManifest,
            )

        override fun markRunning(
            projectId: String,
            runId: String,
            candidateId: String,
        ): GenerationRun? = repository.markCandidateRunning(projectId, runId, candidateId)

        override fun complete(
            projectId: String,
            runId: String,
            candidateId: String,
            body: String,
            report: ChapterQualityReport,
            telemetry: GenerationTelemetry?,
        ): GenerationRun? = repository.completeCandidate(
            projectId,
            runId,
            candidateId,
            body,
            report,
            telemetry,
        )

        override fun fail(
            projectId: String,
            runId: String,
            error: String,
            telemetry: GenerationTelemetry?,
        ): GenerationRun? = repository.failGenerationRun(projectId, runId, error, telemetry)

        override fun cancel(
            projectId: String,
            runId: String,
            reason: String,
            telemetry: GenerationTelemetry?,
        ): GenerationRun? = repository.cancelGenerationRun(projectId, runId, reason, telemetry)

        override fun reject(projectId: String, runId: String): GenerationRun? =
            repository.rejectGenerationRun(projectId, runId)

        override fun adopt(
            projectId: String,
            runId: String,
            candidateId: String,
            currentContextManifest: () -> GenerationContextManifest?,
        ): CandidateAdoptionResult = repository.adoptCandidate(
            projectId,
            runId,
            candidateId,
            currentContextManifest,
        )
    }
}
