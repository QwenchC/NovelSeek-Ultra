package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.NovelGenerationEngine
import com.example.novelseek_ultra.data.ai.AiService
import com.example.novelseek_ultra.data.ai.GenerationTelemetryCollector
import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import com.example.novelseek_ultra.data.ai.TextModelRequestPolicy
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.GenerationContextManifest
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationSourceFingerprint
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.nowIso
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import java.util.Locale
import java.util.UUID

/** Persisted telemetry contracts accept only bounded lower-case identifier characters. */
internal fun writingTracePurpose(purpose: String, model: String): String =
    "$purpose.${model.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")}".take(96)

internal class PreparedChapterWriting(
    val projectId: String,
    val chapter: Chapter,
    val baseline: AppRepository.ChapterBody,
    val manifest: GenerationContextManifest,
    val stableContext: String,
    val currentManifest: () -> GenerationContextManifest?,
) {
    val sourceFingerprint: String get() = GenerationSourceFingerprint.combineWithContext(
        GenerationSourceFingerprint.plan(chapter),
        GenerationSourceFingerprint.body(baseline.draft, baseline.final), manifest.fingerprint,
    )
}

/** Shared application use case: UI and both agent engines execute exactly the same writer. */
internal class ChapterWritingService(
    private val repository: AppRepository,
    private val ai: AiService,
    private val engine: NovelGenerationEngine,
    val store: SceneWritingStore,
) {
    private fun liveFingerprint(source: PreparedChapterWriting): String {
        val live = repository.chapters(source.projectId).firstOrNull { it.id == source.chapter.id } ?: return "deleted"
        val body = repository.chapterBody(live.id)
        val manifest = source.currentManifest() ?: return "context_changed"
        return GenerationSourceFingerprint.combineWithContext(
            GenerationSourceFingerprint.plan(live), GenerationSourceFingerprint.body(body.draft, body.final), manifest.fingerprint,
        )
    }

    private fun roleConfig(projectId: String, role: String): TextModelConfig =
        TextModelRequestPolicy.normalizeForRequest(repository.textModelForRole(projectId, role))

    suspend fun plan(source: PreparedChapterWriting, language: String): ChapterScenePlan {
        val cfg = roleConfig(source.projectId, "planning")
        val usageRunId = "plan-${UUID.randomUUID()}"
        val telemetry = GenerationTelemetryCollector.forModel(cfg, "chapter.scene_plan.v1")
        fun recordUsage(snapshot: GenerationTelemetry, originalFailure: Throwable? = null) {
            try {
                repository.recordWritingUsage(source.projectId,
                    snapshot.toWritingUsage(usageRunId, nowIso(), source.chapter.id))
            } catch (storageFailure: Exception) {
                originalFailure?.addSuppressed(storageFailure)
            }
        }
        val pipeline = WritingPipeline(store,
            chat = { messages, format, purpose ->
                val prepared = PromptRequestBudgeter.validate(cfg, messages).messages
                val trace = telemetry.beginRequest(writingTracePurpose(purpose, cfg.model), prepared, false)
                try {
                    ai.chat(cfg, prepared, trace::onUsage, format).also { trace.complete() }
                } catch (cancelled: CancellationException) {
                    trace.cancel()
                    throw cancelled
                } catch (failure: Throwable) {
                    trace.fail(GenerationTelemetryCollector.failureCategory(failure))
                    throw failure
                }
            },
            stream = { _, _ -> error("Planning does not stream prose") },
        )
        try {
            val result = pipeline.generatePlan(request(source, cfg, language, WritingMode.SCENES, useSavedPlan = false))
            check(liveFingerprint(source) == source.sourceFingerprint) { "规划期间章节或设定改变，请重新规划" }
            check(store.savePlan(result)) { "当前场景任务正在运行，请先暂停" }
            recordUsage(telemetry.finishCompleted())
            return result
        } catch (cancelled: CancellationException) {
            recordUsage(telemetry.finishCancelled(), cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            recordUsage(telemetry.finishFailed(GenerationTelemetryCollector.failureCategory(failure)), failure)
            throw failure
        }
    }

    private fun request(source: PreparedChapterWriting, config: TextModelConfig, language: String, mode: WritingMode,
        resume: String? = null, continuation: String? = null, draftReference: String? = null,
        useSavedPlan: Boolean = true): WritingRequest {
        val settings = repository.writingWorkspace(source.projectId)
        val checkpoint = resume?.let {
            store.load(source.projectId, source.chapter.id)?.takeIf { saved -> saved.runId == it }
                ?: throw WritingStaleRunException("恢复任务已被替换，请重新读取任务列表")
        }
        if (checkpoint != null && checkpoint.sourceFingerprint != source.sourceFingerprint) {
            throw WritingSourceChangedException("恢复任务来源已过期，请重新规划")
        }
        val task = buildString {
            appendLine("章节：${source.chapter.title}；目标字数：3000")
            appendLine("目标：${source.chapter.outline_goal.orEmpty()}")
            appendLine("冲突：${source.chapter.conflict.orEmpty()}")
            appendLine("转折：${source.chapter.twist.orEmpty()}；章末悬念：${source.chapter.cliffhanger.orEmpty()}")
            if (!continuation.isNullOrBlank()) appendLine("续写任务：保留已有正文，只写接下来的新内容。已有正文末尾：\n${continuation.takeLast(4_000)}")
            if (!draftReference.isNullOrBlank()) appendLine("作者草稿参考（遵循事件和表达偏好）：\n$draftReference")
        }
        val previous = if (useSavedPlan) store.load(source.projectId, source.chapter.id) else null
        val plan = checkpoint?.plan ?: if (useSavedPlan && mode != WritingMode.FAST && previous?.mode != WritingMode.FAST)
            store.loadPlan(source.projectId, source.chapter.id) else null
        if (plan != null && plan.sourceFingerprint != source.sourceFingerprint) {
            throw WritingSourceChangedException("保存的场景计划已过期，请在创作工作台重新规划后再写作")
        }
        return WritingRequest(source.projectId, source.chapter.id, source.sourceFingerprint,
            checkpoint?.chapterTask?.takeIf { it.isNotBlank() } ?: task, source.stableContext,
            config, mode, plan = plan, resumeRunId = resume, language = checkpoint?.language ?: language,
            maxRequests = settings.maxRequestsPerRun, currentSourceFingerprint = { liveFingerprint(source) },
            baselineText = checkpoint?.baselineText ?: continuation.orEmpty())
    }

    suspend fun write(
        source: PreparedChapterWriting,
        language: String,
        mode: WritingMode,
        initiator: String,
        agentEngine: String? = null,
        agentSessionId: String? = null,
        agentActionId: String? = null,
        resumeRunId: String? = null,
        continuation: String? = null,
        draftReference: String? = null,
        onStarted: (NovelGenerationEngine.ActiveCandidate) -> Unit = {},
        onProgress: suspend (WritingProgress) -> Unit = {},
    ): String {
        val previous = store.load(source.projectId, source.chapter.id)
        val storedPlan = if (mode != WritingMode.FAST && (resumeRunId != null || previous?.mode != WritingMode.FAST))
            store.loadPlan(source.projectId, source.chapter.id) else null
        val needPlanning = mode != WritingMode.FAST && resumeRunId == null && storedPlan == null
        val roles = buildList {
            add("writing")
            if (needPlanning) add("planning")
            if (mode == WritingMode.POLISHED) add("review")
        }
        val configs = roles.associateWith { roleConfig(source.projectId, it) }
        check(configs.values.all { it.apiKey.isNotBlank() && it.apiUrl.isNotBlank() && it.model.isNotBlank() }) { "请先配置所选阶段模型的 API" }
        val cfg = configs.getValue("writing")
        val telemetry = GenerationTelemetryCollector.forModel(cfg, "chapter.scenes.v1")
        // The pipeline never silently trims a required scene plan. Use the smallest actual phase
        // input capacity, then validate each HTTP request again against its selected model.
        val budgetConfig = configs.values.minBy { PromptRequestBudgeter.inputBudgetTokens(it) }
        val writingRequest = request(source, budgetConfig, language, mode, resumeRunId, continuation, draftReference)
        val prefix = writingRequest.baselineText
        val hashes = GenerationSourceFingerprint.capture(source.chapter, source.baseline.draft, source.baseline.final)
        val active = engine.startWithContextRecheck(NovelGenerationEngine.Request(
            source.projectId, source.chapter, targetWords = 3_000, language = writingRequest.language, mode = "scenes:${mode.name}",
            baselineText = prefix, requireNetNewBody = prefix.isNotBlank(),
            contextManifest = source.manifest, expectedSourceHash = hashes.sourceHash, initiator = initiator,
            agentEngine = agentEngine, agentSessionId = agentSessionId, agentActionId = agentActionId,
            operation = if (prefix.isBlank()) GenerationRun.OPERATION_GENERATE else GenerationRun.OPERATION_CONTINUE,
        ), source.currentManifest) ?: error("本章已有待审核稿，或来源已变化，请先处理候选稿")
        fun configFor(purpose: String): TextModelConfig = configs.getValue(when (purpose) {
            "scene_plan" -> "planning"
            "scene_review" -> "review"
            else -> "writing"
        })
        val pipeline = WritingPipeline(store,
            chat = { messages, format, purpose ->
                val stageConfig = configFor(purpose)
                val prepared = PromptRequestBudgeter.validate(stageConfig, messages).messages
                val trace = telemetry.beginRequest(writingTracePurpose(purpose, stageConfig.model), prepared, false)
                try {
                    ai.chat(stageConfig, prepared, trace::onUsage, format).also { trace.complete() }
                } catch (cancelled: CancellationException) { trace.cancel(); throw cancelled }
                catch (failure: Throwable) { trace.fail(GenerationTelemetryCollector.failureCategory(failure)); throw failure }
            },
            stream = { messages, purpose -> flow {
                val stageConfig = configFor(purpose)
                val prepared = PromptRequestBudgeter.validate(stageConfig, messages).messages
                val trace = telemetry.beginRequest(writingTracePurpose(purpose, stageConfig.model), prepared, true)
                try {
                    ai.streamChat(stageConfig, prepared, trace::onUsage).collect { event ->
                        when (event) {
                            is AiService.StreamEvent.Delta -> trace.onContent(event.text)
                            is AiService.StreamEvent.Error -> trace.fail(if (event.kind == AiService.StreamFailureKind.OUTPUT_LIMIT) GenerationTelemetry.FAILURE_TRUNCATED else GenerationTelemetry.FAILURE_NETWORK)
                            AiService.StreamEvent.Done -> trace.complete()
                        }
                        emit(event)
                    }
                } catch (cancelled: CancellationException) { trace.cancel(); throw cancelled }
                catch (failure: Throwable) { trace.fail(GenerationTelemetryCollector.failureCategory(failure)); throw failure }
            } },
        )
        val result: WritingResult
        val snapshot: GenerationTelemetry
        try {
            onStarted(active)
            result = pipeline.run(writingRequest, onProgress)
            check(liveFingerprint(source) == source.sourceFingerprint) { "生成期间章节或设定改变，场景草稿已保留，未覆盖正文" }
            val body = result.fullBody
            snapshot = telemetry.completedSnapshot()
            check(engine.complete(active, body, telemetry = snapshot) != null) { "候选稿提交被新任务替代，正式正文未修改" }
            telemetry.sealCompleted(snapshot)
        } catch (cancelled: CancellationException) {
            val snapshot = telemetry.finishCancelled()
            engine.cancel(active, "已暂停；完成的场景已保存，可在工作台恢复", snapshot)
            try { repository.recordWritingUsage(source.projectId, snapshot.toWritingUsage(active.run.id, nowIso(), source.chapter.id)) }
            catch (storageFailure: Exception) { cancelled.addSuppressed(storageFailure) }
            throw cancelled
        } catch (failure: Throwable) {
            val snapshot = telemetry.finishFailed(GenerationTelemetryCollector.failureCategory(failure))
            engine.fail(active, failure.message ?: "场景生成失败", snapshot)
            try { repository.recordWritingUsage(source.projectId, snapshot.toWritingUsage(active.run.id, nowIso(), source.chapter.id)) }
            catch (storageFailure: Exception) { failure.addSuppressed(storageFailure) }
            throw failure
        }
        // Candidate completion has already committed atomically. Ancillary metadata failures must
        // not turn a saved, reviewable draft into a reported generation failure. Scene findings
        // remain available in the durable checkpoint even if this secondary attachment fails.
        runCatching { repository.attachSceneReview(source.projectId, active.run.id, result.findings) }
        runCatching { repository.recordWritingUsage(source.projectId, snapshot.toWritingUsage(active.run.id, nowIso(), source.chapter.id)) }
        return result.fullBody
    }
}
