package com.example.novelseek_ultra.agent

import android.util.Base64
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.WebSearchService
import com.example.novelseek_ultra.data.model.AgentIndex
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentSessionMeta
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.data.model.ChapterPromo
import com.example.novelseek_ultra.data.model.Container
import com.example.novelseek_ultra.data.model.ContainerEntry
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.data.model.CultivationRealm
import com.example.novelseek_ultra.data.model.Illustration
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.data.nowIso
import com.example.novelseek_ultra.ui.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume

/**
 * The autonomous writing agent. It drives the app's operations by repeatedly asking the active text
 * model for the next action (a strict JSON object naming one [AgentTool]), executing it, and
 * feeding the result back — a ReAct-style loop that works on any OpenAI-compatible chat model
 * (no provider function-calling required).
 *
 * Semi-autonomous: irreversible / expensive tools ([AgentTool.sensitive]) pause for user confirm.
 * The user can stop, continue, answer questions, and inject new instructions at any time. Foreground
 * only for now — the run stops if the process is killed; the chain is persisted so it can resume.
 */
class AgentController(
    private val vm: AppViewModel,
    private val repo: AppRepository,
    private val scope: CoroutineScope,
    private val appContext: android.content.Context,
) {
    enum class Status { IDLE, RUNNING, AWAITING_USER, AWAITING_CONFIRM, DONE, ERROR, STOPPED }

    private val web = WebSearchService()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()
    /** Text shown to the user while the run is paused for an answer/confirmation. */
    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()
    /** Live partial text while a long generation tool (outline/chapter/revise) is streaming. */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()
    /** When true (and a project is locked), sensitive steps run without per-step confirmation. */
    private val _autoApprove = MutableStateFlow(false)
    val autoApprove: StateFlow<Boolean> = _autoApprove.asStateFlow()
    /** All sessions (newest-first) + which one is current. */
    private val _sessions = MutableStateFlow<List<AgentSessionMeta>>(emptyList())
    val sessions: StateFlow<List<AgentSessionMeta>> = _sessions.asStateFlow()
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private var currentTitle: String = ""
    private var currentCreatedAt: String = ""

    private var job: Job? = null
    private var gate: CompletableDeferred<String?>? = null

    init {
        val idx = repo.loadAgentIndex()
        _sessions.value = idx.items.sortedByDescending { it.createdAt }
        val curId = idx.currentId ?: idx.items.firstOrNull()?.id
        if (curId != null && repo.loadAgentSessionById(curId) != null) loadSession(curId)
        else newSession()
        active = this
        // Drive the foreground service from the run status: alive while running/awaiting, off otherwise.
        scope.launch {
            _status.collect { st ->
                val running = st == Status.RUNNING || st == Status.AWAITING_USER || st == Status.AWAITING_CONFIRM
                if (running) AgentForegroundService.start(appContext, notifText(st)) else AgentForegroundService.stop(appContext)
            }
        }
    }

    private fun notifText(st: Status): String = when (st) {
        Status.AWAITING_USER -> "等待你的回复…"
        Status.AWAITING_CONFIRM -> "等待确认操作…"
        else -> _steps.value.lastOrNull()?.text?.take(50) ?: "执行中"
    }

    // ── control surface ──────────────────────────────────────────────────────

    fun start(command: String) {
        if (_status.value == Status.RUNNING) { sendInput(command); return }
        addStep(AgentStep.USER, command)
        launchLoop()
    }

    fun continueRun() {
        if (_status.value == Status.RUNNING) return
        if (_steps.value.isEmpty()) return
        launchLoop()
    }

    /** Send an instruction. During a question/confirm it answers; while running it's injected as a
     *  new instruction; while paused it's added and the run resumes. */
    fun sendInput(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        when (_status.value) {
            Status.AWAITING_USER -> { addStep(AgentStep.ANSWER, t); _pendingPrompt.value = null; gate?.complete(t) }
            Status.AWAITING_CONFIRM -> {
                // A free-form reply during a confirm = treat as guidance + decline the pending action.
                addStep(AgentStep.USER, t); _pendingPrompt.value = null; gate?.complete("no:$t")
            }
            Status.RUNNING -> addStep(AgentStep.USER, t)   // picked up on the next loop turn via the transcript
            else -> { addStep(AgentStep.USER, t); launchLoop() }
        }
    }

    fun confirm(approve: Boolean) {
        if (_status.value != Status.AWAITING_CONFIRM) return
        _pendingPrompt.value = null
        gate?.complete(if (approve) "yes" else "no")
    }

    fun stop() {
        job?.cancel()
        gate?.complete(null)
        _streamingText.value = ""
        if (_status.value == Status.RUNNING || _status.value == Status.AWAITING_USER || _status.value == Status.AWAITING_CONFIRM) {
            _status.value = Status.STOPPED
        }
        persist()
    }

    /** Clear the CURRENT session's chain (keeps the session). */
    fun clearSession() {
        stop()
        _steps.value = emptyList()
        _status.value = Status.IDLE
        persist()
    }

    /** Lock the session to a project the agent operates on. */
    fun lockProject(projectId: String?) { _activeProjectId.value = projectId; persist() }

    /** Pre-authorize auto-continue (skip per-step confirmation) for this session. */
    fun setAutoApprove(enabled: Boolean) { _autoApprove.value = enabled; persist() }

    // ── session management ──────────────────────────────────────────────────────

    private fun loadSession(id: String) {
        val s = repo.loadAgentSessionById(id) ?: return
        _currentSessionId.value = s.id
        currentTitle = s.title
        currentCreatedAt = s.createdAt.ifBlank { nowIso() }
        _steps.value = s.steps
        _activeProjectId.value = s.lockedProjectId
        _autoApprove.value = s.autoApprove
        _status.value = Status.IDLE
        _streamingText.value = ""
        _pendingPrompt.value = null
    }

    fun newSession() {
        stop()
        val id = "sess-${System.currentTimeMillis()}"
        currentTitle = "会话 ${_sessions.value.size + 1}"
        currentCreatedAt = nowIso()
        _currentSessionId.value = id
        _steps.value = emptyList()
        _activeProjectId.value = null
        _autoApprove.value = false
        _status.value = Status.IDLE
        _streamingText.value = ""
        _sessions.value = listOf(AgentSessionMeta(id, currentTitle, currentCreatedAt)) + _sessions.value
        persist()
    }

    fun switchSession(id: String) {
        if (id == _currentSessionId.value) return
        stop()
        loadSession(id)
        repo.saveAgentIndex(AgentIndex(id, _sessions.value))
    }

    fun deleteSession(id: String) {
        repo.deleteAgentSessionById(id)
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_currentSessionId.value == id) {
            val next = _sessions.value.firstOrNull()?.id
            if (next != null) loadSession(next) else newSession()
        }
        repo.saveAgentIndex(AgentIndex(_currentSessionId.value, _sessions.value))
    }

    fun renameSession(id: String, title: String) {
        if (id == _currentSessionId.value) currentTitle = title
        _sessions.value = _sessions.value.map { if (it.id == id) it.copy(title = title) else it }
        persist()
    }

    // ── the loop ───────────────────────────────────────────────────────────────

    private fun launchLoop() {
        job?.cancel()
        _status.value = Status.RUNNING
        job = scope.launch(Dispatchers.IO) {
            try {
                runLoop()
            } catch (_: CancellationException) {
                // stopped by user
            } catch (e: Exception) {
                addStep(AgentStep.ERROR, e.message ?: e::class.simpleName ?: "未知错误")
                _status.value = Status.ERROR
            }
            persist()
        }
    }

    private suspend fun runLoop() {
        if (!vm.agentTextModelReady()) {
            addStep(AgentStep.ERROR, "未配置可用的文本模型，请先到「设置」配置。")
            _status.value = Status.ERROR; return
        }
        var iterations = 0
        while (currentCoroutineContext().isActive && iterations < MAX_STEPS) {
            iterations++
            val reply = callModel() ?: return   // null → already paused with an error message
            val action = parseAction(reply)
            if (action == null) {
                addStep(AgentStep.OBSERVATION, "（无法解析动作，请只输出规定格式的单个 JSON 动作）")
                continue
            }
            if (action.thought.isNotBlank()) addStep(AgentStep.THOUGHT, action.thought)

            when (action.name) {
                "final" -> {
                    addStep(AgentStep.MESSAGE, action.args.str("message") ?: "任务完成。")
                    _status.value = Status.DONE; return
                }
                "ask_user" -> {
                    val q = action.args.str("question") ?: "需要你的补充信息。"
                    addStep(AgentStep.QUESTION, q)
                    _status.value = Status.AWAITING_USER; _pendingPrompt.value = q; persist()
                    val ans = awaitGate() ?: return        // null → stopped
                    _status.value = Status.RUNNING
                    continue
                }
                else -> {
                    val tool = TOOLS.firstOrNull { it.name == action.name }
                    if (tool == null) {
                        addStep(AgentStep.OBSERVATION, "未知工具：${action.name}")
                        continue
                    }
                    if (tool.sensitive && !_autoApprove.value) {
                        val preview = argsPreview(action.args)
                        val stepId = addStep(AgentStep.ACTION, "（待确认）$preview", tool.name)
                        _status.value = Status.AWAITING_CONFIRM
                        _pendingPrompt.value = "确认执行：${tool.name}？"
                        persist()
                        val decision = awaitGate() ?: return   // stopped
                        _status.value = Status.RUNNING
                        if (decision != "yes") {
                            replaceStepText(stepId, "（已拒绝）$preview")
                            val note = if (decision.startsWith("no:")) "用户拒绝并补充：${decision.removePrefix("no:")}" else "用户拒绝了该操作"
                            addStep(AgentStep.OBSERVATION, note); continue
                        }
                        // Approved — drop the "待确认" marker so the chain reflects it actually ran.
                        replaceStepText(stepId, preview)
                    } else {
                        addStep(AgentStep.ACTION, argsPreview(action.args), tool.name)
                    }
                    persist()
                    val obs = runCatching { tool.run(action.args) }.getOrElse { "执行出错：${it.message}" }
                    _streamingText.value = ""   // clear any live generation preview
                    addStep(AgentStep.OBSERVATION, obs)
                }
            }
        }
        if (iterations >= MAX_STEPS) {
            addStep(AgentStep.MESSAGE, "已达到本轮最大步数并暂停，可点击「继续」。")
            _status.value = Status.STOPPED
        }
    }

    /** Call the model for the next action, retrying transient failures. Returns null (and pauses
     *  the run with an explanatory step) if it keeps failing — so the user can hit 继续 to retry. */
    private suspend fun callModel(): String? {
        var lastErr: String? = null
        repeat(MODEL_RETRIES) { attempt ->
            currentCoroutineContext().ensureActive()
            val r = runCatching { vm.agentChat(buildMessages()) }
            val out = r.getOrNull()
            if (!out.isNullOrBlank()) return out
            lastErr = r.exceptionOrNull()?.let { it.message ?: it::class.simpleName } ?: "空响应"
            if (attempt < MODEL_RETRIES - 1) delay(2000)
        }
        addStep(AgentStep.ERROR, "模型多次无响应（${lastErr}）。已暂停，可点「继续」重试或补充指令。")
        _status.value = Status.STOPPED
        return null
    }

    private suspend fun awaitGate(): String? {
        val d = CompletableDeferred<String?>()
        gate = d
        return try { d.await() } finally { gate = null }
    }

    // ── model conversation ──────────────────────────────────────────────────────

    private fun buildMessages(): List<ChatMessage> {
        val transcript = buildString {
            _steps.value.takeLast(60).forEach { s ->
                val tag = when (s.type) {
                    AgentStep.USER -> "用户指令"
                    AgentStep.THOUGHT -> "你的思考"
                    AgentStep.ACTION -> "你执行的动作[${s.tool}]"
                    AgentStep.OBSERVATION -> "结果"
                    AgentStep.MESSAGE -> "你的回复"
                    AgentStep.QUESTION -> "你向用户提问"
                    AgentStep.ANSWER -> "用户回答"
                    AgentStep.ERROR -> "错误"
                    else -> s.type
                }
                appendLine("【$tag】${s.text}")
            }
        }
        val state = buildString {
            val pid = _activeProjectId.value
            appendLine("当前聚焦项目：${pid ?: "（无，可用 create_project 新建或 list_projects 查看）"}")
            if (pid != null) repo.project(pid)?.let { p ->
                appendLine("项目《${p.title}》类型=${repo.novelType(pid)} 章节=${repo.chapters(pid).size} 副本=${repo.volumes(pid).size} 弧线=${repo.plotArcs(pid).size} 角色=${repo.characters(pid).size}")
            }
        }
        return listOf(
            ChatMessage("system", AgentPrompts.system(toolDocs())),
            ChatMessage("user", "## 当前状态\n$state\n\n## 执行链（节选）\n${transcript.ifBlank { "（空，等待第一条指令）" }}\n\n请决定下一步，只输出一个 JSON 动作。"),
        )
    }

    private data class Action(val thought: String, val name: String, val args: JsonObject)

    private fun parseAction(raw: String): Action? = runCatching {
        val s = raw.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = json.parseToJsonElement(s.substring(start, end + 1)).jsonObject
        val name = (obj["action"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        val args = (obj["args"] as? JsonObject) ?: JsonObject(emptyMap())
        Action((obj["thought"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(), name, args)
    }.getOrNull()

    private fun argsPreview(args: JsonObject): String =
        args.entries.joinToString(", ") { (k, v) ->
            val s = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k=${s.take(60)}"
        }

    // ── persistence ───────────────────────────────────────────────────────────

    private fun addStep(type: String, text: String, tool: String = "", image: String = ""): String {
        val step = AgentStep("a-${System.nanoTime()}", type, text, tool, nowIso(), image)
        _steps.value = _steps.value + step
        // Keep the foreground notification's text current while running.
        if (_status.value == Status.RUNNING) AgentForegroundService.update(appContext, text.take(50))
        return step.id
    }

    /** Persist generated image bytes to a preview file and add an image bubble to the chain. */
    private fun addImageStep(label: String, bytes: ByteArray) {
        val dir = java.io.File(appContext.filesDir, "agent_images").apply { if (!exists()) mkdirs() }
        val file = java.io.File(dir, "img-${System.currentTimeMillis()}.png")
        runCatching { file.writeBytes(bytes) }
        addStep(AgentStep.IMAGE, label, image = file.absolutePath)
    }

    private fun addImageStepFromBase64(label: String, b64: String) {
        runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.let { addImageStep(label, it) }
    }

    private fun replaceStepText(id: String, newText: String) {
        _steps.value = _steps.value.map { if (it.id == id) it.copy(text = newText) else it }
    }

    private fun persist() {
        val id = _currentSessionId.value ?: return
        repo.saveAgentSessionById(
            AgentSession(id, currentTitle, currentCreatedAt, _steps.value, _activeProjectId.value, _autoApprove.value),
        )
        // Keep the index meta (title) in sync and mark this session current.
        val items = _sessions.value.map { if (it.id == id) it.copy(title = currentTitle) else it }
        _sessions.value = items
        repo.saveAgentIndex(AgentIndex(id, items))
    }

    // ── tool helpers ────────────────────────────────────────────────────────────

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.intOr(k: String, def: Int): Int = (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: def
    private fun JsonObject.intOrNull(k: String): Int? = (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    private fun JsonObject.boolOr(k: String, def: Boolean): Boolean =
        (this[k] as? JsonPrimitive)?.contentOrNull?.let { it == "true" || it == "1" } ?: def
    private fun JsonObject.boolOrNull(k: String): Boolean? =
        (this[k] as? JsonPrimitive)?.contentOrNull?.let { it.equals("true", true) || it == "1" }
    private fun pid(args: JsonObject): String? = args.str("projectId") ?: _activeProjectId.value

    private inner class AgentTool(
        val name: String,
        val desc: String,
        val sensitive: Boolean = false,
        val run: suspend (JsonObject) -> String,
    )

    private val TOOLS: List<AgentTool> by lazy { buildTools() }

    /** Exposed for the system prompt. */
    fun toolDocs(): String = TOOLS.joinToString("\n") { "- ${it.name}${if (it.sensitive) " (需确认)" else ""}: ${it.desc}" }

    private fun buildTools(): List<AgentTool> = listOf(
        AgentTool("list_projects", "列出所有小说项目（id/标题/类型）") { _ ->
            val ps = repo.projects.value
            if (ps.isEmpty()) "（暂无项目）" else ps.joinToString("\n") { "- ${it.id} | ${it.title} | ${repo.novelType(it.id)}" }
        },
        AgentTool("create_project", "新建项目。args: title, genre, description, novelType(long|short)。会自动聚焦到新项目") { a ->
            val title = a.str("title") ?: return@AgentTool "缺少 title"
            val now = nowIso()
            val p = Project(id = "proj-${System.currentTimeMillis()}", title = title, genre = a.str("genre"),
                description = a.str("description"), created_at = now, updated_at = now)
            repo.createProject(p)
            repo.setNovelType(p.id, if (a.str("novelType") == "short") "short" else "long")
            _activeProjectId.value = p.id
            "已创建项目《$title》(id=${p.id}) 并聚焦"
        },
        AgentTool("focus_project", "聚焦到某个已存在项目。args: projectId") { a ->
            val id = a.str("projectId") ?: return@AgentTool "缺少 projectId"
            if (repo.project(id) == null) return@AgentTool "项目不存在"
            _activeProjectId.value = id; "已聚焦项目 $id"
        },
        AgentTool("get_overview", "查看当前/指定项目概览（大纲节选、副本/弧线/角色/章节）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val p = repo.project(id) ?: return@AgentTool "项目不存在"
            buildString {
                appendLine("《${p.title}》题材=${p.genre.orEmpty()} 简介=${p.description.orEmpty().take(120)}")
                appendLine("世界观：${repo.worldSetting(id).take(120).ifBlank { "(空)" }}")
                appendLine("大纲节选：${repo.outline(id).take(200).ifBlank { "(空)" }}")
                appendLine("副本：" + (repo.volumes(id).joinToString("；") { it.name }.ifBlank { "(无)" }))
                appendLine("角色：" + (repo.characters(id).joinToString("、") { it.name }.ifBlank { "(无)" }))
                appendLine("章节数：${repo.chapters(id).size}")
            }
        },
        AgentTool("set_world_setting", "覆盖世界观。args: projectId?, text", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            repo.setWorldSetting(id, a.str("text").orEmpty()); "已更新世界观"
        },
        AgentTool("set_timeline", "覆盖时间线。args: projectId?, text", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            repo.setTimeline(id, a.str("text").orEmpty()); "已更新时间线"
        },
        AgentTool("generate_outline", "用 AI 生成长篇大纲并保存（依题材/简介/境界）。args: projectId?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val out = vm.agentGenerateOutline(id) { _streamingText.value = it } ?: return@AgentTool "大纲生成失败"
            "已生成大纲（${out.length} 字符）。节选：${out.take(150)}…"
        },
        AgentTool("extract_characters_from_chapter", "从某章正文识别并把新出场的角色同步到角色管理（剧情推进新增角色时用）。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            val added = vm.agentExtractCharactersFromChapter(id, cid)
            if (added.isEmpty()) "本章未发现需要新增的角色" else "已新增 ${added.size} 个角色：${added.joinToString("、")}"
        },
        AgentTool("import_characters_from_outline", "从大纲识别并导入角色。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            suspendCancellableCoroutine { cont ->
                vm.generateCharactersFromOutline(id) { parsed ->
                    val existing = repo.characters(id)
                    val names = existing.map { it.name }.toSet()
                    val add = parsed.filter { it.name !in names }
                    if (add.isNotEmpty()) repo.setCharacters(id, existing + add)
                    if (cont.isActive) cont.resume("已导入 ${add.size} 个角色：${add.joinToString("、") { it.name }}")
                }
            }
        },
        AgentTool("generate_character", "用一句话描述生成一个契合设定的角色。args: projectId?, brief") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val brief = a.str("brief") ?: return@AgentTool "缺少 brief"
            val c = vm.generateCharacterFromBrief(id, brief) ?: return@AgentTool "生成失败"
            val withId = c.copy(id = "char-${System.currentTimeMillis()}")
            repo.setCharacters(id, repo.characters(id) + withId)
            "已创建角色：${c.name}"
        },
        AgentTool("generate_portrait", "为角色生成立绘（可传 prompt 自定义画面，否则用角色形象）。args: projectId?, character(姓名或id), prompt?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val ch = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            val prompt = a.str("prompt")
                ?: ch.portraitPrompt?.takeIf { it.isNotBlank() } ?: ch.appearance.takeIf { it.isNotBlank() }
                ?: return@AgentTool "该角色暂无外貌描述，请提供 prompt 或先补充形象"
            val bytes = vm.generatePortraitImage(prompt) ?: return@AgentTool "立绘生成失败"
            repo.setCharacters(id, repo.characters(id).map {
                if (it.id == ch.id) it.copy(portraitBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)) else it
            })
            addImageStep("${ch.name} 立绘", bytes)
            "已为 ${ch.name} 生成立绘"
        },
        AgentTool("generate_cover", "为项目生成封面（可传 prompt 自定义画面，否则按项目信息默认生成）。args: projectId?, prompt?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val p = repo.project(id) ?: return@AgentTool "项目不存在"
            val prompt = a.str("prompt") ?: "${p.genre.orEmpty()} ${p.title} cover art, ${p.description.orEmpty()}"
            val bytes = vm.generatePortraitImage(prompt, 1080, 1920) ?: return@AgentTool "封面生成失败"
            val item = CoverImageItem(id = "cover-${System.currentTimeMillis()}",
                name = "AI 封面", imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP), prompt = prompt, createdAt = nowIso())
            repo.setCoverImages(id, repo.getCoverImages(id) + item, item.id)
            addImageStep("项目封面", bytes)
            "已生成封面并设为默认"
        },
        AgentTool("create_volume", "新建副本。args: projectId?, name, description?, realmPlan?(本副本修为/境界上限的硬约束，如\"主角只突破到第一大境界巅峰，逐层稳步突破\")") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val realmPlan = a.str("realmPlan").orEmpty()
            val v = vm.createVolume(id, a.str("name") ?: "新副本", a.str("description").orEmpty(), realmPlan)
            "已创建副本《${v.name}》(id=${v.id})" + (if (realmPlan.isNotBlank()) "（已设修为上限）" else "")
        },
        AgentTool("generate_volumes", "AI 生成若干副本（不生成弧线）。args: projectId?, count, requirements?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            suspendCancellableCoroutine { cont ->
                vm.generateVolumes(id, a.intOr("count", 3), a.str("requirements")) { n ->
                    if (cont.isActive) cont.resume("已生成 $n 个副本")
                }
            }
        },
        AgentTool("list_volumes", "列出副本及其弧线。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vols = repo.volumes(id)
            if (vols.isEmpty()) "（无副本）" else vols.joinToString("\n") { v ->
                val arcs = repo.plotArcs(id).filter { it.volumeId == v.id }
                "副本 ${v.id} 《${v.name}》: " + (arcs.joinToString("；") { "${it.id}:${it.title}" }.ifBlank { "(无弧线)" })
            }
        },
        AgentTool("generate_arcs_for_volume", "在某副本内 AI 生成若干弧线。args: projectId?, volumeId, count, requirements?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            suspendCancellableCoroutine { cont ->
                vm.generateArcsForVolume(id, vid, a.intOr("count", 3), a.str("requirements")) { n ->
                    if (cont.isActive) cont.resume("已在副本生成 $n 条弧线")
                }
            }
        },
        AgentTool("plan_arc_chapters", "为某弧线规划并创建若干章节。args: projectId?, arcId, count", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val n = vm.agentPlanArcChapters(id, arcId, a.intOr("count", 5))
            "已为弧线规划并创建 $n 个章节"
        },
        AgentTool("list_chapters", "列出章节（序号/id/标题/字数/所属副本+弧线）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cs = repo.chapters(id).sortedBy { it.order_index }
            if (cs.isEmpty()) return@AgentTool "（无章节）"
            val arcs = repo.plotArcs(id); val vols = repo.volumes(id)
            cs.joinToString("\n") { ch ->
                val arc = vm.chapterArcId(id, ch)?.let { aid -> arcs.firstOrNull { it.id == aid } }
                val vol = arc?.volumeId?.let { vid -> vols.firstOrNull { it.id == vid } }
                val loc = if (arc != null) " [副本:${vol?.name ?: "?"} / 弧线:${arc.title}]" else " [未归属弧线]"
                "第${ch.order_index}章 ${ch.id} 《${ch.title}》 ${ch.word_count}字$loc"
            }
        },
        AgentTool("get_chapter", "查看某章正文节选。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val b = repo.chapterBody(cid); val t = b.final.ifBlank { b.draft }
            if (t.isBlank()) "（该章暂无正文）" else "长度 ${t.length}；节选：${t.take(300)}…"
        },
        AgentTool("refine_chapter_plan", "生成正文前先细化本章规划（目标/核心冲突）——批量建的空白章规划很粗糙，写前先勘误细化。args: projectId?, chapterId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            vm.agentRefineChapterPlan(id, cid)?.let { "已细化章节规划——\n$it" } ?: "细化失败"
        },
        AgentTool("generate_chapter", "为某章生成正文（建议先 refine_chapter_plan 细化规划）。args: projectId?, chapterId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val text = vm.agentGenerateChapterText(id, cid) { _streamingText.value = it } ?: return@AgentTool "章节生成失败"
            "已生成正文（${text.length} 字符）。开头：${text.take(80)}…"
        },
        AgentTool("revise_chapter", "按要求润色/修改某章正文（AI 整章重写）。小问题请优先用 read_chapter + replace_in_chapter 局部修改。args: projectId?, chapterId, instruction", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ins = a.str("instruction") ?: return@AgentTool "缺少 instruction"
            vm.agentReviseChapter(id, cid, ins) { _streamingText.value = it }?.let { "已润色（${it.length} 字符）" } ?: "润色失败"
        },
        AgentTool("read_chapter", "读取某章完整正文（用于定位要局部修改的原文片段）。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            val b = repo.chapterBody(cid); val t = b.final.ifBlank { b.draft }
            if (t.isBlank()) "（该章暂无正文）"
            else if (t.length > 8000) t.take(8000) + "\n…（已截断，共 ${t.length} 字符；如需后半段请告知）" else t
        },
        AgentTool("replace_in_chapter", "局部修改：把某章正文中的一段原文精确替换为新文本（不重写整章）。先用 read_chapter 拿到准确原文。args: projectId?, chapterId, find(要替换的原文片段，需逐字一致), replace(新文本；留空=删除)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val find = a.str("find") ?: return@AgentTool "缺少 find"
            val replace = a["replace"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val b = repo.chapterBody(cid); val text = b.final.ifBlank { b.draft }
            if (!text.contains(find)) return@AgentTool "未在正文中找到该片段，请先用 read_chapter 复制逐字一致的原文"
            val count = text.split(find).size - 1
            vm.agentApplyChapterText(id, cid, text.replace(find, replace))
            "已替换 $count 处"
        },
        AgentTool("edit_paragraph", "局部修改：用新文本替换某章的第 N 段（按非空行计数）。args: projectId?, chapterId, paragraphIndex(从1开始), newText") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val idx = a.intOr("paragraphIndex", 0); if (idx < 1) return@AgentTool "paragraphIndex 需≥1"
            val newText = a.str("newText") ?: return@AgentTool "缺少 newText"
            val b = repo.chapterBody(cid); val text = b.final.ifBlank { b.draft }
            if (text.isBlank()) return@AgentTool "该章暂无正文"
            val lines = text.split("\n").toMutableList()
            var count = 0; var target = -1
            for (i in lines.indices) if (lines[i].isNotBlank()) { count++; if (count == idx) { target = i; break } }
            if (target < 0) return@AgentTool "未找到第 $idx 段（共 $count 段）"
            lines[target] = newText
            vm.agentApplyChapterText(id, cid, lines.joinToString("\n"))
            "已替换第 $idx 段"
        },
        AgentTool("list_paragraphs", "列出某章各段落（带序号，便于定位局部修改）。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val b = repo.chapterBody(cid); val text = b.final.ifBlank { b.draft }
            if (text.isBlank()) return@AgentTool "（该章暂无正文）"
            text.split("\n").filter { it.isNotBlank() }.mapIndexed { i, p ->
                "段${i + 1}：${if (p.length > 120) p.take(120) + "…" else p}"
            }.joinToString("\n")
        },
        AgentTool("create_container", "新建资料容器。args: projectId?, name, type(by_character|by_chapter|single), autoUpdate?, affectsGeneration?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val type = a.str("type") ?: Container.SINGLE
            val c = Container(id = "ctn-${System.currentTimeMillis()}", name = a.str("name") ?: "新容器",
                type = type, autoUpdatePerChapter = a.boolOr("autoUpdate", false),
                affectsGeneration = a.boolOr("affectsGeneration", false), createdAt = nowIso())
            repo.createContainer(id, c)
            "已创建容器《${c.name}》(${type})"
        },
        AgentTool("retrieve", "就当前项目内容提问/检索（角色/境界/关系/事件/正文片段）。args: projectId?, question") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val q = a.str("question") ?: return@AgentTool "缺少 question"
            vm.agentAnswerQuestion(id, q)
        },
        AgentTool("web_search", "用 DuckDuckGo 联网搜索。args: query") { a ->
            val q = a.str("query") ?: return@AgentTool "缺少 query"
            val res = web.search(q, 5)
            if (res.isEmpty()) "（未找到结果或搜索不可用）"
            else res.joinToString("\n") { "- ${it.title}: ${it.snippet.take(120)} (${it.url})" }
        },
        AgentTool("insert_chapter", "在某章前/后插入新空白章。args: projectId?, referenceChapterId, before(true=前/false=后)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val ref = a.str("referenceChapterId") ?: return@AgentTool "缺少 referenceChapterId"
            val c = vm.insertChapter(id, ref, a.boolOr("before", false), "新章节") ?: return@AgentTool "插入失败（参考章节不存在）"
            "已插入新章节 ${c.id}（第${c.order_index}章），可用 generate_chapter 生成正文"
        },
        AgentTool("update_arc", "修改弧线信息。args: projectId?, arcId, title?, summary?, chapterCount?, status?(upcoming|active|completed)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            var found = false
            repo.setPlotArcs(id, repo.plotArcs(id).map { arc ->
                if (arc.id != arcId) arc else {
                    found = true
                    arc.copy(
                        title = a.str("title") ?: arc.title,
                        summary = a.str("summary") ?: arc.summary,
                        chapterCount = a.intOr("chapterCount", arc.chapterCount),
                        status = a.str("status") ?: arc.status,
                    )
                }
            })
            if (found) "已更新弧线 $arcId" else "未找到弧线"
        },
        AgentTool("update_volume", "修改副本信息。args: projectId?, volumeId, name?, description?, realmPlan?(本副本修为/境界上限的硬约束；设定后规划与生成都会严格遵守)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            if (repo.volumes(id).none { it.id == vid }) return@AgentTool "未找到副本"
            vm.updateVolume(id, vid) { it.copy(name = a.str("name") ?: it.name, description = a.str("description") ?: it.description, realmPlan = a.str("realmPlan") ?: it.realmPlan) }
            "已更新副本 $vid" + (if (a.str("realmPlan") != null) "（已更新修为上限）" else "")
        },
        AgentTool("update_chapter", "修改章节标题/目标/冲突（真正改章节名，勿把标题写进正文）。args: projectId?, chapterId, title?, goal?, conflict?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            repo.upsertChapter(id, ch.copy(
                title = a.str("title") ?: ch.title,
                outline_goal = a.str("goal") ?: ch.outline_goal,
                conflict = a.str("conflict") ?: ch.conflict,
                updated_at = nowIso(),
            ))
            "已更新章节 $cid（标题：${a.str("title") ?: ch.title}）"
        },
        AgentTool("reorder_volume", "调整副本顺序，移动到第几位。args: projectId?, volumeId, position(从1开始)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            val pos = a.intOr("position", 0); if (pos < 1) return@AgentTool "position 需≥1"
            if (repo.volumes(id).none { it.id == vid }) return@AgentTool "未找到副本"
            vm.moveVolumeToPosition(id, vid, pos)
            "已把副本移到第 $pos 位"
        },
        AgentTool("reorder_arc", "调整某弧线在其所属副本内的顺序，移动到第几位。args: projectId?, arcId, position(从1开始)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val pos = a.intOr("position", 0); if (pos < 1) return@AgentTool "position 需≥1"
            if (repo.plotArcs(id).none { it.id == arcId }) return@AgentTool "未找到弧线"
            vm.moveArcToPosition(id, arcId, pos)
            "已把弧线移到（所属副本内）第 $pos 位"
        },
        AgentTool("delete_chapter", "删除某章节（正文/草稿一并删除，并清理弧线与知识库引用）。args: projectId?, chapterId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            vm.deleteChapterFully(id, cid)
            "已删除第${ch.order_index}章《${ch.title}》"
        },
        AgentTool("assign_chapter_to_arc", "把章节归属到某弧线（建立 章节↔弧线↔副本 索引）。args: projectId?, chapterId, arcId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            if (repo.plotArcs(id).none { it.id == arcId }) return@AgentTool "未找到弧线"
            repo.upsertChapter(id, ch.copy(arcId = arcId))
            repo.setPlotArcs(id, repo.plotArcs(id).map { arc ->
                if (arc.id == arcId) arc.copy(builtChapterIds = ((arc.builtChapterIds ?: emptyList()) + cid).distinct()) else arc
            })
            "已将章节 $cid 归属到弧线 $arcId"
        },
        AgentTool("get_structure", "查看项目结构树：副本 → 弧线 → 章节（用于发现归属/顺序问题）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vols = repo.volumes(id).sortedBy { it.order }
            val arcs = repo.plotArcs(id).sortedBy { it.order }
            val chapters = repo.chapters(id).sortedBy { it.order_index }
            fun chaptersOfArc(arcId: String) = chapters.filter { vm.chapterArcId(id, it) == arcId }
            buildString {
                vols.forEach { v ->
                    appendLine("副本 ${v.id}《${v.name}》")
                    arcs.filter { it.volumeId == v.id }.forEach { arc ->
                        appendLine("  弧线 ${arc.id}《${arc.title}》(${arc.status})")
                        chaptersOfArc(arc.id).forEach { ch -> appendLine("    第${ch.order_index}章 ${ch.id}《${ch.title}》") }
                    }
                }
                val orphanArcs = arcs.filter { it.volumeId == null || vols.none { v -> v.id == it.volumeId } }
                if (orphanArcs.isNotEmpty()) { appendLine("未归属副本的弧线："); orphanArcs.forEach { appendLine("  ${it.id}《${it.title}》") } }
                val orphanCh = chapters.filter { vm.chapterArcId(id, it) == null }
                if (orphanCh.isNotEmpty()) { appendLine("未归属弧线的章节："); orphanCh.forEach { appendLine("  第${it.order_index}章 ${it.id}《${it.title}》") } }
            }.trim().ifBlank { "（项目结构为空）" }
        },
        AgentTool("review_consistency", "审阅章节之间的前后矛盾 / 逻辑谬误 / 设定不一致。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            vm.agentReviewConsistency(id) { _streamingText.value = it } ?: "审阅失败"
        },
        AgentTool("generate_promo", "为某章生成【章节推文】＝整章头图(每章最多一张，概括全章；不同于段落插图)。仅对【已有正文】的章节生成，【已有推文的章节自动跳过】(force=true 才强制重做)。给 prompt 用自定义画面，不给则 AI 依正文出图。批量生成头图时直接对每个章节调用即可，会自动跳过空章/已有头图。args: projectId?, chapterId, prompt?, force?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            // 章节推文只针对【已有正文】的章节。
            val body = repo.chapterBody(cid); val content = body.final.ifBlank { body.draft }
            if (content.length < 100) return@AgentTool "第${ch.order_index}章《${ch.title}》正文为空/过少，章节推文仅针对已有正文的章节，已跳过"
            // 【已有推文则跳过】，除非显式 force=true 强制重做。
            val existing = repo.getChapterPromo(cid)
            if (existing?.imageBase64 != null && !a.boolOr("force", false))
                return@AgentTool "第${ch.order_index}章《${ch.title}》已有推文，已跳过（如需重做请加 force=true）"
            val custom = a.str("prompt")
            if (custom != null) {
                val bytes = vm.generatePortraitImage(custom, 1024, 1024) ?: return@AgentTool "推文配图生成失败"
                repo.setChapterPromo(cid, ChapterPromo(imagePrompt = custom,
                    summary = existing?.summary.orEmpty(), imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)))
                addImageStep("推文配图", bytes)
                return@AgentTool "已用自定义提示词生成并应用推文配图"
            }
            suspendCancellableCoroutine { cont ->
                vm.generateChapterPromo(cid, ch.title, content, null, "zimage", 1024, 1024) { promo, err ->
                    if (promo != null) promo.imageBase64?.let { addImageStepFromBase64("推文配图", it) }
                    if (cont.isActive) cont.resume(if (promo != null) "已生成推文：${promo.summary.take(60)}" else "推文生成失败：${err.orEmpty()}")
                }
            }
        },
        AgentTool("generate_illustration", "为某章生成【段落插图】＝锚定到正文【某一段】的插画(一章可多张，对应具体场景；不同于章节推文头图)。给 prompt 用自定义画面，不给则按该段正文节选自动出图。args: projectId?, chapterId, prompt?, paragraphIndex?(锚定第几段,默认1)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val body = repo.chapterBody(cid); val content = body.final.ifBlank { body.draft }
            val prompt = a.str("prompt") ?: content.take(220).ifBlank { return@AgentTool "无 prompt 且本章无正文，无法生成插图" }
            val bytes = vm.generatePortraitImage(prompt, 768, 1024) ?: return@AgentTool "插图生成失败"
            val anchor = a.intOr("paragraphIndex", 1).coerceAtLeast(1)
            repo.upsertIllustration(cid, Illustration(
                id = "ill-${System.currentTimeMillis()}", anchorIndex = anchor, paragraphIndices = listOf(anchor),
                prompt = prompt, imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP), createdAt = nowIso()))
            addImageStep("第${anchor}段插图", bytes)
            "已生成并插入插图（锚定第 $anchor 段）"
        },
        AgentTool("create_snapshot", "保存当前项目为版本快照。args: projectId?, label?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            suspendCancellableCoroutine { cont ->
                vm.saveSnapshot(id, a.str("label") ?: "智能体存档") { ok -> if (cont.isActive) cont.resume(if (ok) "已保存版本快照" else "保存失败") }
            }
        },
        AgentTool("list_snapshots", "列出项目的版本快照。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val list = vm.listSnapshots(id)
            if (list.isEmpty()) "（无快照）" else list.joinToString("\n") { "- ${it.id} | ${it.label.ifBlank { "(无备注)" }} | ${it.chapterCount}章 | ${it.createdAt}" }
        },
        AgentTool("restore_snapshot", "回退到某版本快照（覆盖当前内容，回退前会自动备份）。args: projectId?, snapshotId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val sid = a.str("snapshotId") ?: return@AgentTool "缺少 snapshotId"
            suspendCancellableCoroutine { cont ->
                vm.restoreSnapshot(id, sid) { res -> if (cont.isActive) cont.resume("已回退；知识库待重建章节数 ${res.staleChapterIds.size}") }
            }
        },
        AgentTool("rename_snapshot", "重命名版本快照。args: projectId?, snapshotId, label") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val sid = a.str("snapshotId") ?: return@AgentTool "缺少 snapshotId"
            vm.renameSnapshot(id, sid, a.str("label").orEmpty()); "已重命名快照"
        },
        AgentTool("delete_snapshot", "删除版本快照。args: projectId?, snapshotId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val sid = a.str("snapshotId") ?: return@AgentTool "缺少 snapshotId"
            vm.deleteSnapshot(id, sid); "已删除快照"
        },

        // ── 境界体系 (cultivation realms) ──
        AgentTool("list_realms", "查看修炼境界体系。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val realms = repo.cultivationRealms(id).sortedBy { it.order }
            if (realms.isEmpty()) "（暂无境界体系）"
            else realms.joinToString("\n") { r ->
                val subs = r.subRealms?.joinToString("、") { it.name }.orEmpty()
                "${r.order + 1}. ${r.id} 《${r.name}》${r.description?.let { "：$it" }.orEmpty()}" + if (subs.isNotBlank()) "（子境界：$subs）" else ""
            }
        },
        AgentTool("set_realms", "设置/重建整套境界体系（覆盖）。args: projectId?, realms=JSON数组 [{\"name\":..,\"description\":..,\"subRealms\":[{\"name\":..,\"description\":..}]}]", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arr = a["realms"]?.toString() ?: return@AgentTool "缺少 realms（JSON 数组）"
            val n = vm.agentSetRealmsFromJson(id, arr)
            if (n > 0) "已设置 $n 个境界" else "境界解析失败（需为 JSON 数组）"
        },
        AgentTool("add_realm", "追加一个境界到体系末尾。args: projectId?, name, description?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val name = a.str("name") ?: return@AgentTool "缺少 name"
            val existing = repo.cultivationRealms(id)
            val order = (existing.maxOfOrNull { it.order } ?: -1) + 1
            repo.setCultivationRealms(id, existing + CultivationRealm(
                id = "realm-${System.currentTimeMillis()}", order = order, name = name, description = a.str("description")))
            "已追加境界《$name》"
        },
        AgentTool("delete_realm", "删除某境界。args: projectId?, realmId 或 name", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("realmId") ?: a.str("name") ?: return@AgentTool "缺少 realmId/name"
            val before = repo.cultivationRealms(id)
            val after = before.filterNot { it.id == key || it.name == key }
            if (after.size == before.size) return@AgentTool "未找到境界"
            repo.setCultivationRealms(id, after.mapIndexed { i, r -> r.copy(order = i) }); "已删除境界"
        },

        // ── 角色读取 / 编辑 / 删除 ──
        AgentTool("list_characters", "列出角色（id/姓名/身份/性别/当前境界/主角）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val chars = repo.characters(id); if (chars.isEmpty()) return@AgentTool "（暂无角色）"
            val realms = repo.cultivationRealms(id)
            chars.joinToString("\n") { c ->
                val realm = c.currentRealmId?.let { rid -> realms.firstOrNull { it.id == rid }?.name }
                "- ${c.id} | ${c.name}${if (c.isProtagonist) "(主角)" else ""} | ${c.role.ifBlank { "?" }}${realm?.let { " | 境界:$it" }.orEmpty()}"
            }
        },
        AgentTool("get_character", "查看角色详情。args: projectId?, character(姓名或id)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            buildString {
                appendLine("${c.name}${if (c.isProtagonist) "（主角）" else ""} id=${c.id}")
                if (c.role.isNotBlank()) appendLine("身份：${c.role}")
                if (c.gender.isNotBlank()) appendLine("性别：${c.gender}")
                if (c.personality.isNotBlank()) appendLine("性格：${c.personality}")
                if (c.background.isNotBlank()) appendLine("背景：${c.background}")
                if (c.motivation.isNotBlank()) appendLine("动机：${c.motivation}")
                if (c.appearance.isNotBlank()) appendLine("形象：${c.appearance}")
                c.currentRealmId?.let { rid -> repo.cultivationRealms(id).firstOrNull { it.id == rid }?.let { appendLine("当前境界：${it.name}") } }
            }.trim()
        },
        AgentTool("update_character", "修改角色信息（含指定境界）。args: projectId?, characterId, name?, gender?, role?, personality?, background?, motivation?, appearance?, isProtagonist?, realm?(境界名), subRealm?(子境界名)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("characterId") ?: a.str("character") ?: return@AgentTool "缺少 characterId"
            val c = repo.characters(id).firstOrNull { it.id == cid || it.name == cid } ?: return@AgentTool "未找到角色"
            var realmId = c.currentRealmId; var subId = c.currentSubRealmId
            a.str("realm")?.let { rn -> repo.cultivationRealms(id).firstOrNull { it.name == rn }?.let { realmId = it.id; subId = null } }
            a.str("subRealm")?.let { sn -> repo.cultivationRealms(id).forEach { r -> r.subRealms?.firstOrNull { it.name == sn }?.let { realmId = realmId ?: r.id; subId = it.id } } }
            repo.setCharacters(id, repo.characters(id).map { ch ->
                if (ch.id != c.id) ch else ch.copy(
                    name = a.str("name") ?: ch.name, gender = a.str("gender") ?: ch.gender,
                    role = a.str("role") ?: ch.role, personality = a.str("personality") ?: ch.personality,
                    background = a.str("background") ?: ch.background, motivation = a.str("motivation") ?: ch.motivation,
                    appearance = a.str("appearance") ?: ch.appearance, isProtagonist = a.boolOrNull("isProtagonist") ?: ch.isProtagonist,
                    currentRealmId = realmId, currentSubRealmId = subId,
                )
            })
            "已更新角色 ${c.name}"
        },
        AgentTool("get_character_growth", "查看某角色的成长路线（按章演进的发展记录）。args: projectId?, character(姓名或id)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            val chain = repo.characterGrowth(id, c.id)
            if (chain.isEmpty()) "（${c.name} 暂无成长记录）"
            else chain.joinToString("\n") { e ->
                val src = e.chapterOrder?.let { "第${it}章" } ?: if (e.manual) "手动" else ""
                "- $src：${e.value.take(200)}"
            }
        },
        AgentTool("add_character_growth", "为某角色追加一条成长记录（绑定章节，会软引导后续生成）。args: projectId?, character(姓名或id), value, chapterId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val value = a.str("value") ?: return@AgentTool "缺少 value"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            val ch = a.str("chapterId")?.let { cid -> repo.chapters(id).firstOrNull { it.id == cid } }
            vm.addCharacterGrowth(id, c.id, value, ch, manual = ch == null)
            "已记录 ${c.name} 的成长" + (ch?.let { "（第${it.order_index}章）" } ?: "")
        },
        AgentTool("delete_character", "删除角色。args: projectId?, character(姓名或id)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            repo.setCharacters(id, repo.characters(id).filterNot { it.id == c.id }); "已删除角色 ${c.name}"
        },

        // ── 结构删除 / 章节移动 ──
        AgentTool("delete_arc", "删除某剧情弧线（不删章节）。args: projectId?, arcId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            if (repo.plotArcs(id).none { it.id == arcId }) return@AgentTool "未找到弧线"
            repo.setPlotArcs(id, repo.plotArcs(id).filterNot { it.id == arcId }); "已删除弧线"
        },
        AgentTool("delete_volume", "删除某副本及其下所有弧线（不删章节）。args: projectId?, volumeId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            if (repo.volumes(id).none { it.id == vid }) return@AgentTool "未找到副本"
            vm.deleteVolume(id, vid); "已删除副本及其弧线"
        },
        AgentTool("move_chapter", "调整章节顺序，移动到第几位。args: projectId?, chapterId, position(从1开始)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val pos = a.intOr("position", 0); if (pos < 1) return@AgentTool "position 需≥1"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            vm.moveChapterToPosition(id, cid, pos); "已把章节移到第 $pos 位"
        },
        AgentTool("renumber_chapters", "把章节序号重排为连续的 1..N（修复删除后留下的跳号；不影响章节与弧线的归属索引）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val changed = vm.renumberChapters(id)
            if (changed) "已将章节序号重排为连续编号" else "章节序号本已连续，无需重排"
        },

        // ── 项目编辑 / 删除 ──
        AgentTool("update_project", "修改项目信息。args: projectId?, title?, genre?, description?, status?, targetWordCount?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            if (repo.project(id) == null) return@AgentTool "项目不存在"
            vm.updateProject(id) {
                it.copy(
                    title = a.str("title") ?: it.title, genre = a.str("genre") ?: it.genre,
                    description = a.str("description") ?: it.description, status = a.str("status") ?: it.status,
                    target_word_count = a.intOrNull("targetWordCount") ?: it.target_word_count,
                )
            }
            "已更新项目信息"
        },
        AgentTool("delete_project", "删除整个项目（不可恢复）。args: projectId", sensitive = true) { a ->
            val id = a.str("projectId") ?: _activeProjectId.value ?: return@AgentTool "缺少 projectId"
            val title = repo.project(id)?.title ?: return@AgentTool "项目不存在"
            vm.deleteProject(id)
            if (_activeProjectId.value == id) _activeProjectId.value = null
            "已删除项目《$title》"
        },

        // ── 大纲 / 正文 直接读写 ──
        AgentTool("get_outline", "查看完整大纲文本。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val o = repo.outline(id)
            if (o.isBlank()) "（暂无大纲）" else if (o.length > 2500) o.take(2500) + "\n…（已截断，共 ${o.length} 字符）" else o
        },
        AgentTool("set_outline", "直接写入/覆盖大纲文本。args: projectId?, text", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            repo.setOutline(id, a.str("text").orEmpty()); "已更新大纲"
        },
        AgentTool("set_chapter_body", "直接写入/覆盖某章正文（用于精确设定或清理脏数据，不走 AI）。args: projectId?, chapterId, text", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            val text = a.str("text").orEmpty()
            repo.saveChapterBody(cid, repo.chapterBody(cid).copy(final = text))
            repo.upsertChapter(id, ch.copy(updated_at = nowIso()))
            "已写入正文（${text.length} 字符）"
        },

        // ── 摘要 / 知识库 ──
        AgentTool("generate_book_summary", "生成全书梗概（汇总章节/弧线摘要）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            if (!vm.agentTextModelReady()) return@AgentTool "未配置文本模型"
            suspendCancellableCoroutine { cont ->
                vm.generateBookSummary(id) { sp -> if (cont.isActive) cont.resume(if (sp != null) "已生成全书梗概" else "生成失败（可能需先生成章节摘要）") }
            }
        },
        AgentTool("generate_chapter_summaries", "为所有章节生成摘要（供长程记忆/审阅）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            if (!vm.agentTextModelReady()) return@AgentTool "未配置文本模型"
            suspendCancellableCoroutine { cont ->
                vm.generateChapterSummariesForAll(id, onDone = { ok, err -> if (cont.isActive) cont.resume("章节摘要：成功 $ok，失败 $err") })
            }
        },
        AgentTool("rebuild_kb", "重建本地知识库向量索引（需已配置 Embedding）。args: projectId?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cfg = vm.embeddingConfig()
            if (cfg.apiKey.isBlank() || cfg.apiUrl.isBlank() || cfg.model.isBlank()) return@AgentTool "未配置 Embedding，无法重建"
            suspendCancellableCoroutine { cont ->
                vm.rebuildKnowledgeBase(id, onDone = { ch, chunks, errs, firstErr ->
                    if (cont.isActive) cont.resume("知识库重建：$ch 章 / $chunks 块 / 失败 $errs${firstErr?.let { "（$it）" }.orEmpty()}")
                })
            }
        },
        AgentTool("set_kb_features", "开关本地知识库 / 摘要 / 实体功能。args: knowledgeBase?, summaries?, entities?") { a ->
            a.boolOrNull("knowledgeBase")?.let { vm.setKnowledgeBaseEnabled(it) }
            a.boolOrNull("summaries")?.let { vm.setSummariesEnabled(it) }
            a.boolOrNull("entities")?.let { vm.setEntitiesEnabled(it) }
            "已更新知识库功能开关（KB=${vm.knowledgeBaseEnabled()} 摘要=${vm.summariesEnabled()} 实体=${vm.entitiesEnabled()}）"
        },

        // ── 容器管理 ──
        AgentTool("list_containers", "列出资料容器（id/名称/类型/开关）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cs = repo.containers(id); if (cs.isEmpty()) return@AgentTool "（暂无容器）"
            cs.joinToString("\n") { c ->
                "- ${c.id} | ${c.name} | ${c.type}" +
                    (if (c.autoUpdatePerChapter) " 按章更新" else "") + (if (c.affectsGeneration) " 影响章节" else "") +
                    (if (c.affectsVolumeGeneration) " 影响副本" else "") + (if (c.affectsArcGeneration) " 影响弧线" else "")
            }
        },
        AgentTool("update_container", "修改容器名称/开关（类型不可改）。args: projectId?, containerId, name?, autoUpdate?, affectsGeneration?, affectsVolumeGeneration?, affectsArcGeneration?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("containerId") ?: return@AgentTool "缺少 containerId"
            val c = repo.container(id, cid) ?: return@AgentTool "未找到容器"
            repo.updateContainerMeta(id, cid,
                name = a.str("name") ?: c.name,
                autoUpdatePerChapter = a.boolOrNull("autoUpdate") ?: c.autoUpdatePerChapter,
                affectsGeneration = a.boolOrNull("affectsGeneration") ?: c.affectsGeneration,
                affectsVolumeGeneration = a.boolOrNull("affectsVolumeGeneration") ?: c.affectsVolumeGeneration,
                affectsArcGeneration = a.boolOrNull("affectsArcGeneration") ?: c.affectsArcGeneration)
            "已更新容器 ${c.name}"
        },
        AgentTool("append_container_entry", "向容器某分块追加一个值。args: projectId?, containerId, blockKey(角色id/章节id/“main”), value") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("containerId") ?: return@AgentTool "缺少 containerId"
            val block = a.str("blockKey") ?: Container.SINGLE_BLOCK_KEY
            val value = a.str("value") ?: return@AgentTool "缺少 value"
            if (repo.container(id, cid) == null) return@AgentTool "未找到容器"
            repo.appendContainerEntry(id, cid, block, ContainerEntry(id = "e-${System.currentTimeMillis()}", value = value, createdAt = nowIso(), manual = true))
            "已写入容器值"
        },
        AgentTool("delete_container", "删除容器及其全部值。args: projectId?, containerId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("containerId") ?: return@AgentTool "缺少 containerId"
            if (repo.container(id, cid) == null) return@AgentTool "未找到容器"
            repo.deleteContainer(id, cid); "已删除容器"
        },

        // ── 封面管理 ──
        AgentTool("list_covers", "列出项目封面（id/名称/是否默认）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val covers = vm.getCoverImages(id); if (covers.isEmpty()) return@AgentTool "（暂无封面）"
            val def = repo.project(id)?.default_cover_id
            covers.joinToString("\n") { "- ${it.id} | ${it.name}${if (it.id == def) " (默认)" else ""}" }
        },
        AgentTool("set_default_cover", "设为默认封面。args: projectId?, coverId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cov = a.str("coverId") ?: return@AgentTool "缺少 coverId"
            if (vm.getCoverImages(id).none { it.id == cov }) return@AgentTool "未找到封面"
            vm.setDefaultCover(id, cov); "已设为默认封面"
        },
        AgentTool("delete_cover", "删除某封面。args: projectId?, coverId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cov = a.str("coverId") ?: return@AgentTool "缺少 coverId"
            if (vm.getCoverImages(id).none { it.id == cov }) return@AgentTool "未找到封面"
            vm.deleteProjectCover(id, cov); "已删除封面"
        },

        // ── 设置：文本模型 / 图片引擎（绝不输出任何密钥） ──
        AgentTool("get_settings", "查看当前生成设置（文本模型/图片引擎/知识库开关，不含任何密钥）。args: 无") { _ ->
            val cfg = vm.activeTextModelConfig()
            "文本模型：${cfg.provider} / ${cfg.model}；图片引擎：${vm.imageEngine()}；" +
                "知识库=${vm.knowledgeBaseEnabled()} 摘要=${vm.summariesEnabled()} 实体=${vm.entitiesEnabled()}"
        },
        AgentTool("list_text_models", "列出可选的文本模型 profile（id/名称/provider/模型，不含密钥）。args: 无") { _ ->
            val active = vm.activeTextModelProfileId()
            val profiles = vm.textModelProfiles()
            if (profiles.isEmpty()) "（无 profile）"
            else profiles.joinToString("\n") { "- ${it.id} | ${it.name} | ${it.provider}/${it.model}${if (it.id == active) " (当前)" else ""}" }
        },
        AgentTool("set_text_model", "切换当前文本模型 profile。args: profileId") { a ->
            val pidv = a.str("profileId") ?: return@AgentTool "缺少 profileId"
            if (vm.textModelProfiles().none { it.id == pidv }) return@AgentTool "未找到该 profile"
            vm.setActiveProfile(pidv); "已切换文本模型 profile"
        },
        AgentTool("set_image_engine", "切换图片生成引擎。args: engine(pollinations|comfyui)") { a ->
            val e = a.str("engine") ?: return@AgentTool "缺少 engine"
            if (e != "pollinations" && e != "comfyui") return@AgentTool "engine 仅支持 pollinations / comfyui"
            vm.setImageEngine(e); "已切换图片引擎为 $e"
        },
    )

    companion object {
        private const val MAX_STEPS = 40
        private const val MODEL_RETRIES = 3

        /** The active controller, so [AgentForegroundService]'s Stop action can reach it. */
        @Volatile
        var active: AgentController? = null
    }
}
