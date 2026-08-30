package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.ai.ChapterCandidateValidator
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterQualityReport
import com.example.novelseek_ultra.data.model.ChapterSpec
import com.example.novelseek_ultra.data.model.GenerationContextManifest
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationModelSnapshot
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelGenerationEngineTest {
    @Test
    fun `start delegates atomic supersede to create and forwards the complete request`() {
        val stale = run("stale", GenerationRun.STATUS_RUNNING)
        val created = run("fresh", GenerationRun.STATUS_RUNNING)
        val running = created.copy(
            candidates = created.candidates.map { it.copy(status = CandidateChapter.STATUS_RUNNING) },
        )
        val port = FakePort(
            latestResult = stale,
            createResult = created,
            markRunningResult = running,
        )
        val request = request().copy(
            initiator = GenerationRun.INITIATOR_AGENT,
            agentEngine = "dual",
            agentSessionId = "session-1",
            agentActionId = "action-1",
            operation = GenerationRun.OPERATION_REVISE,
            expectedSourceHash = "expected-source",
        )

        val active = requireNotNull(engine(port).start(request))

        assertEquals(request, port.createdRequest)
        assertTrue(port.cancelCalls.isEmpty())
        assertEquals("fresh", active.run.id)
        assertEquals(CandidateChapter.STATUS_RUNNING, active.candidate.status)

    }

    @Test
    fun `start forwards a lazy context recheck to run creation`() {
        val created = run("fresh", GenerationRun.STATUS_RUNNING)
        val running = created.copy(
            candidates = created.candidates.map { it.copy(status = CandidateChapter.STATUS_RUNNING) },
        )
        val port = FakePort(createResult = created, markRunningResult = running)
        val manifest = GenerationContextManifest(scope = "prompt:CHAPTER", fingerprint = "context")
        var evaluations = 0

        val active = engine(port).startWithContextRecheck(request()) {
            evaluations += 1
            manifest
        }

        assertTrue(active != null)
        assertEquals(1, evaluations)
        assertEquals(manifest, port.createdContextManifest)
    }

    @Test
    fun `completed review work blocks a newer run for the same chapter`() {
        val completedReview = run("review", GenerationRun.STATUS_COMPLETED)
        val port = FakePort(
            latestResult = completedReview,
            createResult = run("must-not-create", GenerationRun.STATUS_RUNNING),
        )

        assertNull(engine(port).start(request()))
        assertNull(port.createdRequest)
        assertTrue(port.cancelCalls.isEmpty())
    }

    @Test
    fun `start cancels a malformed or unactivatable reservation`() {
        val noSlots = run("no-slots", GenerationRun.STATUS_RUNNING).copy(candidates = emptyList())
        val noSlotsPort = FakePort(createResult = noSlots)

        assertNull(engine(noSlotsPort).start(request()))
        assertEquals("no-slots", noSlotsPort.cancelCalls.single().runId)
        assertTrue(noSlotsPort.cancelCalls.single().reason.contains("没有可用"))

        val reserved = run("reserved", GenerationRun.STATUS_RUNNING)
        val activationPort = FakePort(createResult = reserved, markRunningResult = null)

        assertNull(engine(activationPort).start(request()))
        assertEquals("reserved", activationPort.cancelCalls.single().runId)
        assertTrue(activationPort.cancelCalls.single().reason.contains("未能进入生成状态"))

        val unchangedPort = FakePort(createResult = reserved, markRunningResult = reserved)

        assertNull(engine(unchangedPort).start(request()))
        assertEquals("reserved", unchangedPort.cancelCalls.single().runId)
    }

    @Test
    fun `complete applies request validation policy before persisting the candidate`() {
        val running = run("run-1", GenerationRun.STATUS_RUNNING).copy(
            candidates = listOf(
                CandidateChapter(
                    id = "candidate-1",
                    slot = 1,
                    status = CandidateChapter.STATUS_RUNNING,
                ),
            ),
        )
        val request = request().copy(
            baselineText = "这是明显更长的旧正文，用于证明整章修订允许缩短。",
            requireNetNewBody = false,
        )
        val active = NovelGenerationEngine.ActiveCandidate(
            request = request,
            run = running,
            candidate = running.candidates.single(),
        )
        val port = FakePort()
        val telemetry = GenerationTelemetry(
            model = GenerationModelSnapshot(
                provider = "deepseek",
                model = "deepseek-chat",
                endpointFingerprint = "a".repeat(64),
            ),
            promptContract = "chapter.one_shot.v1",
            totalMillis = 123,
        )
        port.completeFactory = { body, report, storedTelemetry ->
            running.copy(
                status = GenerationRun.STATUS_COMPLETED,
                telemetry = storedTelemetry,
                candidates = listOf(
                    active.candidate.copy(
                        status = CandidateChapter.STATUS_COMPLETED,
                        body = body,
                        qualityReport = report,
                    ),
                ),
            )
        }

        val completion = requireNotNull(
            engine(port).complete(active, "精简改稿。", telemetry = telemetry),
        )

        assertFalse(completion.report.blocking)
        assertFalse(
            completion.report.findings.any {
                it.code == ChapterCandidateValidator.CODE_CONTINUATION_WITHOUT_NEW_BODY
            },
        )
        assertEquals("精简改稿。", port.completeCall?.body)
        assertEquals(completion.report, port.completeCall?.report)
        assertEquals(telemetry, port.completeCall?.telemetry)
        assertEquals(telemetry, completion.run.telemetry)
        assertEquals("精简改稿。", completion.candidate.body)
    }

    @Test
    fun `adopt forwards a lazy context recheck to the persistence port`() {
        val port = FakePort()
        val manifest = GenerationContextManifest(scope = "prompt:CHAPTER", fingerprint = "context")
        var evaluations = 0

        engine(port).adoptWithContextRecheck("project-1", "run-1", "candidate-1") {
            evaluations += 1
            manifest
        }

        assertEquals(1, evaluations)
        assertEquals(manifest, port.adoptedContextManifest)
    }

    @Test
    fun `complete rejects a stale repository result that never stored this body`() {
        val running = run("run-stale", GenerationRun.STATUS_RUNNING).copy(
            candidates = listOf(
                CandidateChapter(
                    id = "candidate-stale",
                    slot = 1,
                    status = CandidateChapter.STATUS_RUNNING,
                ),
            ),
        )
        val active = NovelGenerationEngine.ActiveCandidate(
            request = request(),
            run = running,
            candidate = running.candidates.single(),
        )
        val port = FakePort()
        port.completeFactory = { _, _, _ ->
            running.copy(
                status = GenerationRun.STATUS_CANCELLED,
                candidates = listOf(
                    active.candidate.copy(status = CandidateChapter.STATUS_CANCELLED),
                ),
            )
        }

        assertNull(engine(port).complete(active, "迟到的正文"))
    }

    private fun engine(port: NovelGenerationEngine.Port): NovelGenerationEngine {
        val constructor = NovelGenerationEngine::class.java.getDeclaredConstructor(
            NovelGenerationEngine.Port::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(port)
    }

    private fun request() = NovelGenerationEngine.Request(
        projectId = "project-1",
        expectedSourceHash = "expected-source",
        chapter = Chapter(
            id = "chapter-1",
            project_id = "project-1",
            title = "第一章",
            order_index = 1,
        ),
    )

    private fun run(id: String, status: String) = GenerationRun(
        id = id,
        projectId = "project-1",
        chapterId = "chapter-1",
        spec = ChapterSpec(projectId = "project-1", chapterId = "chapter-1"),
        sourceHash = "source-$id",
        baselinePlanHash = "plan-$id",
        baselineBodyHash = "body-$id",
        status = status,
        candidates = listOf(CandidateChapter(id = "candidate-$id", slot = 1)),
    )

    private data class CancelCall(val projectId: String, val runId: String, val reason: String)

    private data class CompleteCall(
        val projectId: String,
        val runId: String,
        val candidateId: String,
        val body: String,
        val report: ChapterQualityReport,
        val telemetry: GenerationTelemetry?,
    )

    private class FakePort(
        var latestResult: GenerationRun? = null,
        var createResult: GenerationRun? = null,
        var markRunningResult: GenerationRun? = null,
    ) : NovelGenerationEngine.Port {
        var createdRequest: NovelGenerationEngine.Request? = null
        var createdContextManifest: GenerationContextManifest? = null
        val cancelCalls = mutableListOf<CancelCall>()
        var completeCall: CompleteCall? = null
        var completeFactory:
            ((String, ChapterQualityReport, GenerationTelemetry?) -> GenerationRun?)? = null
        var adoptedContextManifest: GenerationContextManifest? = null

        override fun latest(projectId: String, chapterId: String): GenerationRun? = latestResult

        override fun create(
            request: NovelGenerationEngine.Request,
            currentContextManifest: (() -> GenerationContextManifest?)?,
        ): GenerationRun? {
            createdRequest = request
            createdContextManifest = currentContextManifest?.invoke()
            return createResult
        }

        override fun markRunning(
            projectId: String,
            runId: String,
            candidateId: String,
        ): GenerationRun? = markRunningResult

        override fun complete(
            projectId: String,
            runId: String,
            candidateId: String,
            body: String,
            report: ChapterQualityReport,
            telemetry: GenerationTelemetry?,
        ): GenerationRun? {
            completeCall = CompleteCall(projectId, runId, candidateId, body, report, telemetry)
            return completeFactory?.invoke(body, report, telemetry)
        }

        override fun fail(
            projectId: String,
            runId: String,
            error: String,
            telemetry: GenerationTelemetry?,
        ): GenerationRun? = null

        override fun cancel(
            projectId: String,
            runId: String,
            reason: String,
            telemetry: GenerationTelemetry?,
        ): GenerationRun? {
            cancelCalls += CancelCall(projectId, runId, reason)
            return null
        }

        override fun reject(projectId: String, runId: String): GenerationRun? = null

        override fun adopt(
            projectId: String,
            runId: String,
            candidateId: String,
            currentContextManifest: () -> GenerationContextManifest?,
        ): CandidateAdoptionResult {
            adoptedContextManifest = currentContextManifest()
            return CandidateAdoptionResult.Unavailable("not-configured")
        }
    }
}
