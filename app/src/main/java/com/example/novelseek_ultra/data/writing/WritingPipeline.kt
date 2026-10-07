package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.ai.AiService
import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.ChatResponseFormat
import com.example.novelseek_ultra.data.ai.LongOutputFormat
import com.example.novelseek_ultra.data.ai.LongOutputRecovery
import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import com.example.novelseek_ultra.data.ai.TextModelRequestPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** Shared draft pipeline for the editor and either agent engine. All network access is injected. */
class WritingPipeline(
    private val store: SceneWritingStore,
    private val chat: suspend (List<ChatMessage>, ChatResponseFormat, purpose: String) -> String,
    private val stream: (List<ChatMessage>, purpose: String) -> Flow<AiService.StreamEvent>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun generatePlan(request: WritingRequest): ChapterScenePlan {
        validateRequest(request)
        return generatePlan(request, Session(request))
    }

    suspend fun run(
        request: WritingRequest,
        onProgress: suspend (WritingProgress) -> Unit = {},
    ): WritingResult {
        validateRequest(request)
        val session = Session(request)
        val existing = store.load(request.projectId, request.chapterId)
        if (request.resumeRunId == null && existing?.status == WritingStatus.RUNNING) {
            throw WritingStaleRunException("本章已有运行中的场景任务，请先暂停或选择恢复")
        }
        val saved = if (request.resumeRunId != null) {
            existing
                ?: throw WritingStaleRunException("找不到待恢复的场景任务")
        } else null
        if (saved != null) {
            if (saved.runId != request.resumeRunId) throw WritingStaleRunException("场景任务已被替换")
            if (saved.sourceFingerprint != request.sourceFingerprint) {
                throw WritingSourceChangedException("章节或设定已变更，请重新规划后生成")
            }
            require(request.plan == null || request.plan == saved.plan) { "不能用修改后的场景计划恢复旧任务" }
            require(request.mode == saved.mode) { "恢复任务的写作模式必须保持不变" }
        }
        val effectiveRequest = if (saved != null && saved.chapterTask.isNotBlank()) request.copy(
            chapterTask = saved.chapterTask, language = saved.language, baselineText = saved.baselineText,
        ) else request
        val plan = saved?.plan ?: request.plan ?: if (request.mode == WritingMode.FAST) {
            fastPlan(request)
        } else {
            onProgress(WritingProgress("planning"))
            val reusable = if (existing?.mode == WritingMode.FAST) null
                else store.loadPlan(request.projectId, request.chapterId)
            reusable?.takeIf { it.sourceFingerprint == request.sourceFingerprint }
                ?: generatePlan(request, session)
        }
        validateBinding(request, plan)
        require(request.mode != WritingMode.FAST || plan.scenes.size == 1) { "快速写作只接受单场景计划" }
        session.checkSource()
        session.checkpoint = store.begin(plan, request.mode, request.resumeRunId, session.requestCount, clock(),
            chapterTask = effectiveRequest.chapterTask, language = effectiveRequest.language,
            baselineText = effectiveRequest.baselineText)
        if (session.checkpoint!!.status == WritingStatus.COMPLETED) return WritingResult(session.checkpoint!!)
        try {
            onProgress(WritingProgress("writing", session.checkpoint))
            for (scene in plan.scenes.drop(session.checkpoint!!.completedScenes.size)) {
                session.checkSource()
                val base = sceneMessages(effectiveRequest, plan, scene, session.checkpoint!!.completedScenes)
                var streamRequests = 0
                val body = LongOutputRecovery.collect(
                    config = session.config,
                    initialMessages = base,
                    format = LongOutputFormat.MARKDOWN,
                    taskLabel = "场景「${scene.title}」",
                    language = effectiveRequest.language,
                    maxRequests = 1 + request.maxContinuationsPerScene,
                    request = { messages ->
                        session.consume(messages)
                        val purpose = if (streamRequests++ > 0) PURPOSE_SCENE_CONTINUE
                            else if (request.mode == WritingMode.FAST) PURPOSE_QUICK_DRAFT else PURPOSE_SCENE_DRAFT
                        stream(messages, purpose)
                    },
                    onCumulative = { preview ->
                        require(preview.length <= MAX_SCENE_BODY_CHARS) { "场景正文过长，已暂停以保护设备内存" }
                        onProgress(WritingProgress("writing", session.checkpoint, scene.id, preview))
                    },
                ).trim()
                require(body.isNotBlank()) { "场景正文为空" }
                session.commit { current -> current.copy(completedScenes = current.completedScenes +
                    CompletedScene(scene.id, body, scene.exitState, clock())) }
                onProgress(WritingProgress("scene_completed", session.checkpoint, scene.id))
            }
            if (request.optionalReview && !session.checkpoint!!.reviewCompleted) {
                onProgress(WritingProgress("reviewing", session.checkpoint))
                val findings = review(effectiveRequest, session)
                session.commit { it.copy(reviewFindings = findings, reviewCompleted = true) }
            }
            session.commit { it.copy(status = WritingStatus.COMPLETED, error = null) }
            onProgress(WritingProgress("completed", session.checkpoint))
            return WritingResult(session.checkpoint!!)
        } catch (cancelled: CancellationException) {
            session.preserveFailure(cancelled, WritingStatus.INTERRUPTED, "任务已暂停，已完成场景可继续")
            throw cancelled
        } catch (failure: Exception) {
            session.preserveFailure(failure, WritingStatus.FAILED,
                failure.message?.take(2_000) ?: "写作任务意外中断，已完成场景保留")
            throw failure
        }
    }

    private suspend fun generatePlan(request: WritingRequest, session: Session): ChapterScenePlan {
        val messages = stableMessages(PLAN_SYSTEM, request.stableContext) + ChatMessage("user",
            "写作语言：${request.language}\n章节任务：${request.chapterTask}\n" +
                "章节目标字数：${request.targetWords}。拆分为 1 到 ${ScenePlanProtocol.MAX_SCENES} 个场景，" +
                "各场景目标总字数不超过 ${ScenePlanProtocol.MAX_CHAPTER_WORDS}。只输出 JSON。")
        return strictJson(request, session, messages, PURPOSE_SCENE_PLAN) { raw ->
            ScenePlanProtocol.parse(raw, request.projectId, request.chapterId, request.sourceFingerprint,
                clock(), maxJsonTokens = PromptRequestBudgeter.maxOutputTokens(session.config))
        }
    }

    private suspend fun review(request: WritingRequest, session: Session): List<ReviewFinding> {
        val current = session.checkpoint!!
        val payload = ScenePlanProtocol.json.encodeToString(ChapterScenePlan.serializer(), current.plan)
        val completed = ScenePlanProtocol.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CompletedScene.serializer()), current.completedScenes)
        val messages = stableMessages(REVIEW_SYSTEM, request.stableContext) + ChatMessage("user",
            "审稿语言：${request.language}\n场景计划 JSON：\n$payload\n已完成场景 JSON（body 是证据原文）：\n$completed\n" +
                "仅输出 findings JSON；不修改正文，不编造引用。")
        return strictJson(request, session, messages, PURPOSE_SCENE_REVIEW) { raw ->
            ReviewProtocol.parse(raw, current.plan, current.completedScenes)
        }
    }

    private suspend fun <T> strictJson(
        request: WritingRequest,
        session: Session,
        initial: List<ChatMessage>,
        purpose: String,
        parse: (String) -> T,
    ): T {
        var messages = initial
        repeat(request.maxRetries + 1) { attempt ->
            currentCoroutineContext().ensureActive()
            session.consume(messages)
            val response = chat(messages, ChatResponseFormat.JSON_OBJECT, purpose)
            currentCoroutineContext().ensureActive()
            session.checkSource()
            try {
                return parse(response)
            } catch (failure: IllegalArgumentException) {
                if (attempt == request.maxRetries) throw IllegalArgumentException(
                    "${if (purpose == PURPOSE_SCENE_PLAN) "场景规划" else "审稿"} JSON 格式无效，" +
                        "请检查模型是否支持严格 JSON 输出：${failure.message}", failure)
                messages = initial + ChatMessage("assistant", unicodePrefix(response, 4_096)) +
                    ChatMessage("user", "上一个 JSON 不符合协议：${failure.message?.take(600)}。" +
                        "重新输出完整 JSON 对象，所有字段严格遵循系统协议，不能包含解释、代码围栏或省略号。")
            }
        }
        error("unreachable")
    }

    private fun sceneMessages(request: WritingRequest, plan: ChapterScenePlan, scene: SceneSpec,
        completed: List<CompletedScene>): List<ChatMessage> {
        val planJson = ScenePlanProtocol.json.encodeToString(ChapterScenePlan.serializer(), plan)
        val stable = stableMessages(DRAFT_SYSTEM, request.stableContext) + ChatMessage("user", "完整场景计划 JSON：\n$planJson")
        val priorStates = completed.joinToString("\n") { "已完成 ${it.sceneId}；计划结束约束：${it.exitState}" }
        val tail = unicodeSuffix(completed.lastOrNull()?.body.orEmpty(), MAX_BODY_TAIL_CHARS)
        return stable + ChatMessage("user",
            "写作语言：${request.language}\n章节任务：${request.chapterTask}\n" +
                "当前只写场景：${scene.id}（${scene.title}），目标约 ${scene.targetWords} 字。\n" +
                "此前已完成场景及其结束约束（不是事实核验结论）：\n$priorStates\n" +
                "上一场正文尾部（用于准确衔接）：\n$tail\n" +
                "遵守完整计划中的视角、知情范围、必需事件和禁止事件；未来场景尚未发生。" +
                "从上一场自然续接，只返回本场正文，不复述上一场，不输出计划、场景标题或审稿说明。")
    }

    private inner class Session(val request: WritingRequest) {
        val config = TextModelRequestPolicy.normalizeForRequest(request.config)
        var checkpoint: WritingCheckpoint? = null
        var requestCount: Int = 0

        fun checkSource() {
            val live = request.currentSourceFingerprint?.invoke() ?: return
            if (live != request.sourceFingerprint) {
                throw WritingSourceChangedException("章节或设定在任务期间已变更，已保留旧来源草稿；请重新规划")
            }
        }

        fun consume(messages: List<ChatMessage>) {
            checkSource()
            PromptRequestBudgeter.validate(config, messages)
            val used = checkpoint?.requestCount ?: requestCount
            if (used >= request.maxRequests) throw WritingQuotaException("已达到本任务 ${request.maxRequests} 次请求上限；完成场景已保留")
            if (checkpoint != null) commit { it.copy(requestCount = used + 1) }
            requestCount = used + 1
        }

        fun commit(transform: (WritingCheckpoint) -> WritingCheckpoint) {
            checkSource()
            val current = checkNotNull(checkpoint)
            val next = transform(current).copy(revision = current.revision + 1, updatedAt = clock())
            if (!store.compareAndSet(current, next)) throw WritingStaleRunException("此任务已被恢复或替换，过期结果不再写入")
            checkpoint = next
        }

        fun preserveFailure(error: Throwable, status: WritingStatus, message: String) {
            val current = checkpoint ?: return
            if (current.status == WritingStatus.COMPLETED) return
            try {
                // Do not check cancellation or current source here: preserve the old source-bound
                // draft, without ever rebinding it to a modified chapter.
                val next = current.copy(status = status, error = message,
                    revision = current.revision + 1, updatedAt = clock())
                if (store.compareAndSet(current, next)) checkpoint = next
            } catch (storageFailure: Exception) {
                error.addSuppressed(storageFailure)
            }
        }
    }

    companion object {
        const val PURPOSE_SCENE_PLAN = "scene_plan"
        const val PURPOSE_SCENE_REVIEW = "scene_review"
        const val PURPOSE_SCENE_DRAFT = "scene_draft"
        const val PURPOSE_SCENE_CONTINUE = "scene_continue"
        const val PURPOSE_QUICK_DRAFT = "quick_draft"
        private const val MAX_BODY_TAIL_CHARS = 6_000
        private const val MAX_SCENE_BODY_CHARS = 256_000

        private fun validateRequest(request: WritingRequest) {
            require(request.projectId.isNotBlank() && request.chapterId.isNotBlank() && request.sourceFingerprint.isNotBlank())
            require(request.chapterTask.isNotBlank()) { "章节任务不能为空" }
            require(request.targetWords in 100..ScenePlanProtocol.MAX_CHAPTER_WORDS)
            require(request.maxRequests in 1..64 && request.maxRetries in 0..2 && request.maxContinuationsPerScene in 0..4) {
                "任务请求或重试限制无效"
            }
            request.plan?.let { validateBinding(request, it) }
            request.currentSourceFingerprint?.invoke()?.let {
                if (it != request.sourceFingerprint) throw WritingSourceChangedException("章节来源已变更，请重新读取章节")
            }
        }

        private fun validateBinding(request: WritingRequest, plan: ChapterScenePlan) {
            ScenePlanProtocol.validate(plan)
            require(plan.projectId == request.projectId && plan.chapterId == request.chapterId) { "场景计划属于其他章节" }
            if (plan.sourceFingerprint != request.sourceFingerprint) throw WritingSourceChangedException("场景计划来源已过期")
        }

        private fun fastPlan(request: WritingRequest) = ChapterScenePlan(
            request.projectId, request.chapterId, request.sourceFingerprint,
            listOf(SceneSpec("chapter", "整章快速草稿", goal = request.chapterTask,
                targetWords = request.targetWords.coerceAtMost(ScenePlanProtocol.MAX_SCENE_WORDS))),
            System.currentTimeMillis(),
        )

        private fun stableMessages(system: String, context: String) = listOf(
            ChatMessage("system", system), ChatMessage("user", "作者设定与上下文资料：\n$context"))

        private fun unicodePrefix(text: String, limit: Int): String {
            var end = minOf(text.length, limit)
            if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            return text.substring(0, end)
        }

        private fun unicodeSuffix(text: String, limit: Int): String {
            var start = (text.length - limit).coerceAtLeast(0)
            if (start in 1 until text.length && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) start++
            return text.substring(start)
        }

        private val PLAN_SYSTEM = """
            你是小说场景规划编辑。把本章任务拆成可独立生成且能衔接的场景，区分作者知识与视角角色知识。
            只返回严格 JSON 对象，根字段仅 scenes，不能带说明或代码围栏。每个场景必须完整包含：
            {"id":"s1","title":"场景标题","pov":"视角人物","time":"时间","location":"地点",
            "goal":"场景目标","conflict":"冲突","turn":"转折","entryState":"入场状态与知情范围",
            "exitState":"结束状态与知情范围","requiredEvents":["必须发生的具体事件"],
            "forbiddenEvents":["不能提前发生或泄露的事件"],"targetWords":1500}
            id 唯一且非空，title/goal 非空，字数字段为整数，每场100到8000字，总计不超过30000字；最多8场。
            没有约束的数组填[]，不要发明与作者资料冲突的设定，后场事件不能提前到前场。
        """.trimIndent()

        private val DRAFT_SYSTEM = """
            你是小说正文作者，按照完整场景计划逐场写正文。作者资料与计划优先于临时发挥。
            保持指定视角、时间地点、角色动机与知情边界。满足本场必需事件，禁止提前发生后场剧情。
            仅输出可直接阅读的本场小说正文，不附解释、JSON、Markdown围栏或场景标题。不得重复已完成场景。
        """.trimIndent()

        private val REVIEW_SYSTEM = """
            你是小说审稿编辑，只审阅给出的正文和明确场景约束。输出严格 JSON：{"findings":[]}，最多48项。
            每项完整字段为 {"sceneId":"s1","category":"FACT_CONFLICT","severity":"BLOCKING",
            "quote":"证据原文","startOffset":0,"endOffset":4,"constraint":"逐字引用本场明确约束",
            "explanation":"问题说明","suggestion":"局部修订建议"}。
            startOffset/endOffset 是证据在对应scene body中的 UTF-16 起止偏移；quote必须和原文精确一致。
            category只能为FACT_CONFLICT、KNOWLEDGE_LEAK、MISSING_EVENT、STYLE、PACING、OTHER。
            severity只能为BLOCKING、WARNING、SUGGESTION。STYLE、PACING、OTHER只能用SUGGESTION。
            BLOCKING只用于违反明确事实约束，并须逐字引用该场 requiredEvents、forbiddenEvents、entryState或exitState。
            文学偏好不能伪装成阻断；缺少证据时不输出该意见。没有问题返回空findings，不编造问题，不修改正文。
        """.trimIndent()
    }
}
