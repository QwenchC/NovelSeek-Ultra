package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.ai.AiService
import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.ChatResponseFormat
import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import com.example.novelseek_ultra.data.model.TextModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritingPipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plan() = ChapterScenePlan("p", "c", "source", listOf(
        SceneSpec("s1", "调查", goal = "找线索", exitState = "得到药方"),
        SceneSpec("s2", "追踪", goal = "追踪药商", entryState = "得到药方", exitState = "失去线索")), 1)
    private fun request() = WritingRequest("p", "c", "source", "调查失踪案", "凶手身份未知",
        TextModelConfig(contextWindowTokens = 64_000, maxOutputTokens = 4_096), plan = plan())
    private fun done(body: String) = flowOf(AiService.StreamEvent.Delta(body), AiService.StreamEvent.Done)

    @Test
    fun sharedPipelinePersistsEachSceneAndIncludesCompletePlanStatesAndTail() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        val calls = mutableListOf<Pair<List<ChatMessage>, String>>()
        val pipeline = WritingPipeline(store, { _, _, _ -> error("Unexpected chat") }, { messages, purpose ->
            calls += messages to purpose
            done(if (calls.size == 1) "他找到了药方。" else "药商消失在人群中。")
        })
        val progress = mutableListOf<WritingProgress>()
        val result = pipeline.run(request()) { progress += it }
        assertEquals("他找到了药方。\n\n药商消失在人群中。", result.body)
        assertEquals(WritingStatus.COMPLETED, store.load("p", "c")!!.status)
        assertEquals(2, result.checkpoint.requestCount)
        assertTrue(progress.any { it.stage == "scene_completed" && it.checkpoint!!.completedScenes.size == 1 })
        assertEquals(calls[0].first.take(3), calls[1].first.take(3))
        assertTrue(calls[1].first.last().content.contains("他找到了药方。"))
        assertTrue(calls[1].first.last().content.contains("得到药方"))
        assertTrue(calls.all { it.second == WritingPipeline.PURPOSE_SCENE_DRAFT })
    }

    @Test
    fun cancellationRetainsCompletedSceneAndResumeDoesNotRewriteIt() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        var calls = 0
        val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ ->
            if (++calls == 1) done("调查完成。") else flow { emit(AiService.StreamEvent.Delta("未完成")); throw CancellationException("screen closed") }
        })
        assertTrue(runCatching { pipeline.run(request()) }.exceptionOrNull() is CancellationException)
        val partial = store.load("p", "c")!!
        assertEquals(WritingStatus.INTERRUPTED, partial.status)
        assertEquals(listOf("调查完成。"), partial.completedScenes.map { it.body })
        var resumedCalls = 0
        val resumed = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ -> resumedCalls++; done("追踪完成。") })
            .run(request().copy(resumeRunId = partial.runId))
        assertEquals(1, resumedCalls)
        assertEquals("调查完成。\n\n追踪完成。", resumed.body)
        assertEquals(3, resumed.checkpoint.requestCount)
    }

    @Test
    fun untypedEofAndTransportErrorsDoNotCommitPartialTextOrRetry() = runBlocking {
        for (events in listOf(flowOf(AiService.StreamEvent.Delta("截断")),
            flowOf(AiService.StreamEvent.Error("网络断开")))) {
            val store = SceneWritingStore(temporary.newFolder())
            var calls = 0
            val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ -> calls++; events })
            assertTrue(runCatching { pipeline.run(request()) }.isFailure)
            assertEquals(1, calls)
            assertTrue(store.load("p", "c")!!.completedScenes.isEmpty())
        }
    }

    @Test
    fun typedOutputLimitContinuesWithinQuotaAndCommitsOnlyCompletedScene() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        val purposes = mutableListOf<String>()
        val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, purpose ->
            purposes += purpose
            if (purposes.size == 1) flowOf(AiService.StreamEvent.Delta("林澈推开了药铺的木门。"),
                AiService.StreamEvent.Error("length", AiService.StreamFailureKind.OUTPUT_LIMIT))
            else done("他发现了旧药方。")
        })
        val result = pipeline.run(request().copy(plan = plan().copy(scenes = plan().scenes.take(1))))
        assertEquals("林澈推开了药铺的木门。他发现了旧药方。", result.body)
        assertEquals(listOf("scene_draft", "scene_continue"), purposes)
        assertEquals(2, result.checkpoint.requestCount)
    }

    @Test
    fun quotaIncludesFailedRequestsAndPersistsAcrossResume() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        var calls = 0
        val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ -> calls++; done("完整场景") })
        assertTrue(runCatching { pipeline.run(request().copy(maxRequests = 1)) }.exceptionOrNull() is WritingQuotaException)
        assertEquals(1, calls)
        val checkpoint = store.load("p", "c")!!
        assertEquals(1, checkpoint.completedScenes.size)
        assertEquals(1, checkpoint.requestCount)
        assertTrue(runCatching { pipeline.run(request().copy(maxRequests = 1, resumeRunId = checkpoint.runId)) }
            .exceptionOrNull() is WritingQuotaException)
        assertEquals(1, calls)
    }

    @Test
    fun sourceChangesDuringRequestPreventSceneCommitAndOldDraftResume() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        var source = "source"
        val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ -> source = "changed"; done("旧来源正文") })
        assertTrue(runCatching { pipeline.run(request().copy(currentSourceFingerprint = { source })) }
            .exceptionOrNull() is WritingSourceChangedException)
        val checkpoint = store.load("p", "c")!!
        assertTrue(checkpoint.completedScenes.isEmpty())
        assertEquals("source", checkpoint.sourceFingerprint)
        assertTrue(runCatching { pipeline.run(request().copy(sourceFingerprint = "changed", plan = null,
            resumeRunId = checkpoint.runId)) }.exceptionOrNull() is WritingSourceChangedException)
    }

    @Test
    fun lateStreamCannotOverwriteNewResumeLease() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        var resumed: WritingCheckpoint? = null
        val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ ->
            val current = store.load("p", "c")!!
            resumed = store.begin(current.plan, current.mode, current.runId)
            done("过期结果")
        })
        assertTrue(runCatching { pipeline.run(request()) }.exceptionOrNull() is WritingStaleRunException)
        assertEquals(resumed, store.load("p", "c"))
        assertTrue(store.load("p", "c")!!.completedScenes.isEmpty())
    }

    @Test
    fun continuationResumePreservesOriginalTaskLanguageAndFullBodyPrefix() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        var calls = 0
        val original = request().copy(chapterTask = "续写：保留原文，从药铺木门接下去。",
            baselineText = "作者已有的正文。", language = "zh")
        val first = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ ->
            if (++calls == 1) done("找到药方。") else flow { throw CancellationException("pause") }
        })
        assertTrue(runCatching { first.run(original) }.exceptionOrNull() is CancellationException)
        val checkpoint = store.load("p", "c")!!
        val prompts = mutableListOf<List<ChatMessage>>()
        val result = WritingPipeline(store, { _, _, _ -> error("No chat") }, { messages, _ ->
            prompts += messages; done("追上药商。")
        }).run(request().copy(resumeRunId = checkpoint.runId, language = "en", baselineText = "错误的新前缀"))
        assertEquals("作者已有的正文。\n\n找到药方。\n\n追上药商。", result.fullBody)
        assertEquals("找到药方。\n\n追上药商。", result.body)
        assertTrue(prompts.single().last().content.contains("写作语言：zh"))
        assertTrue(prompts.single().last().content.contains("从药铺木门接下去"))
        assertFalse(prompts.single().last().content.contains("错误的新前缀"))
    }

    @Test
    fun generationAndReviewUseNativeJsonAndInvalidProtocolGetsBoundedCorrection() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        val purposes = mutableListOf<String>()
        var attempts = 0
        val planJson = "{\"scenes\":${ScenePlanProtocol.json.encodeToString(ListSerializer(SceneSpec.serializer()), plan().scenes)}}"
        val pipeline = WritingPipeline(store, { _, format, purpose ->
            assertEquals(ChatResponseFormat.JSON_OBJECT, format)
            purposes += purpose
            if (purpose == "scene_plan" && ++attempts == 1) "说明：$planJson"
            else if (purpose == "scene_plan") planJson else "{\"findings\":[]}"
        }, { _, _ -> done("场景正文") })
        val result = pipeline.run(request().copy(plan = null, mode = WritingMode.POLISHED, optionalReview = true))
        assertEquals(listOf("scene_plan", "scene_plan", "scene_review"), purposes)
        assertEquals(5, result.checkpoint.requestCount)
        assertTrue(result.checkpoint.reviewCompleted)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun generatePlanDoesNotOverwriteExistingCompletedDraft() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        val first = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, _ -> done("已完成正文") }).run(request())
        val planJson = "{\"scenes\":${ScenePlanProtocol.json.encodeToString(ListSerializer(SceneSpec.serializer()), plan().scenes)}}"
        WritingPipeline(store, { _, _, _ -> planJson }, { _, _ -> error("No stream") }).generatePlan(request())
        assertEquals(first.checkpoint, store.load("p", "c"))
    }

    @Test
    fun fastModeMakesOneDraftRequestAndBudgetFailureMakesNoNetworkCall() = runBlocking {
        val store = SceneWritingStore(temporary.newFolder())
        val purposes = mutableListOf<String>()
        val pipeline = WritingPipeline(store, { _, _, _ -> error("No chat") }, { _, purpose -> purposes += purpose; done("快速正文") })
        val fast = request().copy(mode = WritingMode.FAST, plan = null)
        assertEquals("快速正文", pipeline.run(fast).body)
        assertEquals(listOf("quick_draft"), purposes)
        purposes.clear()
        assertTrue(runCatching { pipeline.run(fast.copy(chapterId = "other", stableContext = "资料".repeat(100_000))) }
            .exceptionOrNull() is PromptRequestBudgeter.BudgetExceededException)
        assertTrue(purposes.isEmpty())
    }
}
