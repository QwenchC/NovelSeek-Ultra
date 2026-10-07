package com.example.novelseek_ultra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.ChapterSourceVersion
import com.example.novelseek_ultra.data.DerivedChapterMutation
import com.example.novelseek_ultra.data.DerivedSourceFingerprint
import com.example.novelseek_ultra.data.FactEvidenceLedger
import com.example.novelseek_ultra.data.NovelGenerationEngine
import com.example.novelseek_ultra.data.writing.WritingWorkspace
import com.example.novelseek_ultra.data.writing.StoryNoteSelector
import com.example.novelseek_ultra.data.writing.ChapterWritingService
import com.example.novelseek_ultra.data.writing.PreparedChapterWriting
import com.example.novelseek_ultra.data.writing.SceneWritingStore
import com.example.novelseek_ultra.data.writing.ChapterScenePlan
import com.example.novelseek_ultra.data.writing.WritingCheckpoint
import com.example.novelseek_ultra.data.writing.WritingMode
import com.example.novelseek_ultra.data.writing.WritingStatus
import com.example.novelseek_ultra.data.writing.ManuscriptPreview
import com.example.novelseek_ultra.data.writing.toWritingUsage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.example.novelseek_ultra.data.ai.AiService
import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.ChatResponseFormat
import com.example.novelseek_ultra.data.ai.CharacterImportProtocol
import com.example.novelseek_ultra.data.ai.EntityReconciliation
import com.example.novelseek_ultra.data.ai.GenerationTelemetryCollector
import com.example.novelseek_ultra.data.ai.KbService
import com.example.novelseek_ultra.data.ai.LongOutputFormat
import com.example.novelseek_ultra.data.ai.LongOutputRecovery
import com.example.novelseek_ultra.data.ai.Prompts
import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import com.example.novelseek_ultra.data.ai.TextModelRequestPolicy
import com.example.novelseek_ultra.data.ai.StoryStateCompiler
import com.example.novelseek_ultra.data.ai.StoryStateVisibility
import com.example.novelseek_ultra.data.ai.StreamUsage
import com.example.novelseek_ultra.data.ai.collectCompleted
import com.example.novelseek_ultra.data.ai.isUsableApiConfig
import com.example.novelseek_ultra.data.ai.isDirectDeepSeek
import com.example.novelseek_ultra.data.ai.launchWithFailureBoundary
import com.example.novelseek_ultra.data.ai.toPromptContext
import com.example.novelseek_ultra.data.model.BackupBundle
import com.example.novelseek_ultra.data.model.BackupSummary
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterFactEvidenceBatch
import com.example.novelseek_ultra.data.model.ChapterPromo
import com.example.novelseek_ultra.data.model.ChapterSpec
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.CharacterGrowthEntry
import com.example.novelseek_ultra.data.model.Container
import com.example.novelseek_ultra.data.model.ContainerEntry
import com.example.novelseek_ultra.data.model.CoverImageConfig
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.data.model.CultivationRealm
import com.example.novelseek_ultra.data.model.CultivationSubRealm
import com.example.novelseek_ultra.data.model.EmbeddingConfig
import com.example.novelseek_ultra.data.model.EntityPayload
import com.example.novelseek_ultra.data.model.GenerationContextFingerprint
import com.example.novelseek_ultra.data.model.GenerationContextManifest
import com.example.novelseek_ultra.data.model.GenerationContextMaterial
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.GenerationSourceFingerprint
import com.example.novelseek_ultra.data.model.Illustration
import com.example.novelseek_ultra.data.model.KbChunk
import com.example.novelseek_ultra.data.model.NovelChatMessage
import com.example.novelseek_ultra.data.model.PlotArc
import com.example.novelseek_ultra.data.model.RestoreResult
import com.example.novelseek_ultra.data.model.SnapshotMeta
import com.example.novelseek_ultra.data.model.SummaryPayload
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.data.model.Volume
import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.model.TextModelThinkingModes
import com.example.novelseek_ultra.data.nowIso
import com.example.novelseek_ultra.util.buildRealmSystemContext
import com.example.novelseek_ultra.util.buildVolumeRealmConstraint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private data class ChapterTextMutation(
    val replacement: String?,
    val observation: String,
)

private data class StreamingGenerationTicket(
    val revision: Long,
    val buffer: StringBuilder,
)

/** Process-wide execution ownership so a superseded durable run also stops its real provider call. */
private object ChapterGenerationExecutions {
    private data class Key(val projectId: String, val chapterId: String)
    private data class Entry(val runId: String, val job: Job)

    private val lock = Any()
    private val entries = mutableMapOf<Key, Entry>()
    private val jobsByRunId = mutableMapOf<String, Job>()
    private val cancellationCategories = java.util.WeakHashMap<Job, String>()

    fun replace(
        projectId: String,
        chapterId: String,
        runId: String,
        job: Job,
    ) {
        val previous = synchronized(lock) {
            jobsByRunId[runId] = job
            entries.put(Key(projectId, chapterId), Entry(runId, job)).also { old ->
                if (old != null && old.runId != runId && old.job !== job) {
                    cancellationCategories[old.job] = GenerationTelemetry.FAILURE_SUPERSEDED
                }
            }
        }
        if (previous != null && previous.runId != runId && previous.job !== job) {
            previous.job.cancel(CancellationException("Generation superseded"))
        }
    }

    fun cancelExisting(projectId: String, chapterId: String) {
        val previous = synchronized(lock) {
            entries.remove(Key(projectId, chapterId))?.also {
                cancellationCategories[it.job] = GenerationTelemetry.FAILURE_SUPERSEDED
            }
        }
        previous?.job?.cancel(CancellationException("Generation superseded"))
    }

    fun cancel(job: Job?, category: String) {
        if (job == null) return
        synchronized(lock) { cancellationCategories[job] = category }
        val message = if (category == GenerationTelemetry.FAILURE_SUPERSEDED) {
            "Generation superseded"
        } else {
            "Generation cancelled by user"
        }
        job.cancel(CancellationException(message))
    }

    fun cancellationCategory(runId: String, job: Job?): String? = synchronized(lock) {
        job?.let(cancellationCategories::get)
            ?: jobsByRunId[runId]?.let(cancellationCategories::get)
    }

    fun unregister(projectId: String, chapterId: String, runId: String) {
        synchronized(lock) {
            val key = Key(projectId, chapterId)
            if (entries[key]?.runId == runId) entries.remove(key)
            jobsByRunId.remove(runId)?.let(cancellationCategories::remove)
        }
    }
}

/** Repository sources that can affect an AI prompt. A source snapshot is captured twice before
 * network I/O (to avoid accepting a torn multi-store read) and compared again at the write edge. */
private enum class PromptSourceScope {
    OUTLINE,
    CHAPTER,
    PLANNING,
    VOLUME_COLLECTION,
    ARC_COLLECTION,
    PLOT_ARC,
    CHARACTERS_FROM_OUTLINE,
}

private data class PromptProjectSource(
    val id: String,
    val title: String = "",
    val genre: String = "",
    val description: String = "",
)

private data class PromptCharacterSource(
    val id: String,
    val name: String,
    val gender: String = "",
    val role: String = "",
    val personality: String = "",
    val appearance: String = "",
    val motivation: String = "",
    val currentRealmId: String? = null,
    val currentSubRealmId: String? = null,
)

private data class PromptVolumeSource(
    val id: String,
    val name: String,
    val description: String,
    val order: Int,
    val realmPlan: String,
)

private data class PromptArcSource(
    val id: String,
    val title: String,
    val summary: String,
    val order: Int,
    val status: String,
    val chapterCount: Int,
    val volumeId: String?,
    val builtChapterIds: List<String>?,
)

private data class PromptChapterSource(
    val id: String,
    val title: String,
    val orderIndex: Int,
    val outlineGoal: String? = null,
    val conflict: String? = null,
    val wordCount: Int = 0,
    val arcId: String? = null,
)

private data class PromptContainerSource(
    val id: String,
    val name: String,
    val type: String,
    val affectsGeneration: Boolean,
    val affectsVolumeGeneration: Boolean,
    val affectsArcGeneration: Boolean,
)

private data class PromptContainerValueSource(
    val containerId: String,
    val blockKey: String,
    val latest: ContainerEntry?,
)

private data class PromptGrowthSource(
    val characterId: String,
    val latest: CharacterGrowthEntry?,
)

private data class BudgetedChapterContext(
    val worldSetting: String?,
    val timeline: String?,
    val charactersInfo: String?,
    val storyState: String?,
    val targetConstraints: String?,
    val chapterList: String?,
    val draftReference: String?,
)

data class FactEvidenceCoverage(
    val claims: Int,
    val coveredChapters: Int,
    val eligibleChapters: Int,
)

private data class PromptSourceSnapshot(
    val project: PromptProjectSource,
    val writingWorkspace: WritingWorkspace = WritingWorkspace(),
    val outline: String? = null,
    val worldSetting: String? = null,
    val timeline: String? = null,
    val realms: List<CultivationRealm> = emptyList(),
    val characters: List<PromptCharacterSource> = emptyList(),
    val characterGrowth: List<PromptGrowthSource> = emptyList(),
    val volumes: List<PromptVolumeSource> = emptyList(),
    val arcs: List<PromptArcSource> = emptyList(),
    val chapters: List<PromptChapterSource> = emptyList(),
    val previousBodies: List<Pair<String, AppRepository.ChapterBody>> = emptyList(),
    val summariesEnabled: Boolean? = null,
    val summaries: List<SummaryPayload> = emptyList(),
    val entitiesEnabled: Boolean? = null,
    val entities: List<EntityPayload> = emptyList(),
    val factEvidence: List<ChapterFactEvidenceBatch> = emptyList(),
    val knowledgeBaseEnabled: Boolean? = null,
    val embeddingConfig: EmbeddingConfig? = null,
    val chunks: List<KbChunk> = emptyList(),
    val containers: List<PromptContainerSource> = emptyList(),
    val containerValues: List<PromptContainerValueSource> = emptyList(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo: AppRepository = AppRepository.get(application)
    private val ai = AiService()
    private val novelGenerationEngine = NovelGenerationEngine(repo)
    private val sceneWritingStore = repo.sceneWritingStore
    private val chapterWriter = ChapterWritingService(repo, ai, novelGenerationEngine, sceneWritingStore)
    private val writingJobs = ConcurrentHashMap<String, Job>()
    private val reviewLocks = ConcurrentHashMap<String, Mutex>()
    private val _writingRevision = MutableStateFlow(0)
    val writingRevision: StateFlow<Int> = _writingRevision.asStateFlow()
    private val _writingStages = MutableStateFlow<Map<String, String>>(emptyMap())
    val writingStages: StateFlow<Map<String, String>> = _writingStages.asStateFlow()
    /** Session-scoped provider token/cache telemetry; populated when the API returns usage. */
    val textUsageStats = ai.usageStats

    /** Audiobook (听书) playback engine — observed directly by the listen screen. */
    val audiobook = com.example.novelseek_ultra.data.audio.AudiobookController(application, repo, viewModelScope)

    /** Autonomous agent (智能体) — drives the app's operations via tool calls. */
    val agent = com.example.novelseek_ultra.agent.AgentController(this, repo, viewModelScope, application)

    // ── observable state ───────────────────────────────────────────────────

    val projects: StateFlow<List<Project>> = repo.projects
    val state = repo.state
    /** Bumped when durable AI chapter candidates are created, completed, accepted, or rejected. */
    val generationRunRevision: StateFlow<Int> = repo.generationRunRevision

    private val _uiLanguage = MutableStateFlow(repo.uiLanguage())
    val uiLanguage: StateFlow<String> = _uiLanguage.asStateFlow()

    /** "light" | "dark" — observed by AppRoot to choose Material color scheme. */
    private val _theme = MutableStateFlow(repo.themePref())
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    private val _statusMessage = MutableStateFlow(
        if (repo.recoveredInterruptedBackupImport) {
            "检测到上次备份导入意外中断，已自动恢复导入前的数据。"
        } else {
            ""
        },
    )
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** Live preview of the currently streaming AI generation, if any. */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var streamingJob: Job? = null
    private val streamingGenerationLock = Any()
    private var streamingGenerationRevision = 0L
    /** Revisions that already crossed the point where Stop can safely prevent persistence. */
    private val streamingCommittingRevisions = mutableSetOf<Long>()

    /** True only while a chapter body is streaming — guards EditorScreen from mini-outline bleed. */
    private val _isChapterGenerating = MutableStateFlow(false)
    val isChapterGenerating: StateFlow<Boolean> = _isChapterGenerating.asStateFlow()

    /** Streaming result for the AI-fill (title/goal/conflict) dialog in EditorScreen. */
    private val _aiFillText = MutableStateFlow("")
    val aiFillText: StateFlow<String> = _aiFillText.asStateFlow()
    private val _isAiFilling = MutableStateFlow(false)
    val isAiFilling: StateFlow<Boolean> = _isAiFilling.asStateFlow()
    private var aiFillJob: Job? = null
    @Volatile
    private var aiFillRevision = 0L

    /** Live streaming answer for the "ask the novel" Q&A agent (per-project chat). */
    private val _qaStreamingText = MutableStateFlow("")
    val qaStreamingText: StateFlow<String> = _qaStreamingText.asStateFlow()
    private val _qaGenerating = MutableStateFlow(false)
    val qaGenerating: StateFlow<Boolean> = _qaGenerating.asStateFlow()
    private var qaJob: Job? = null
    private val qaGenerationLock = Any()
    private var qaGenerationRevision = 0L

    init {
        viewModelScope.launch {
            repo.state.collectLatest {
                _uiLanguage.value = repo.uiLanguage()
                _theme.value = repo.themePref()
                agent.refreshContextUsage()
            }
        }
    }

    override fun onCleared() {
        writingJobs.values.forEach { it.cancel() }
        audiobook.release()
        agent.close()
        super.onCleared()
    }

    // ── settings ───────────────────────────────────────────────────────────

    fun setUiLanguage(lang: String) = repo.setUiLanguage(lang)
    fun setTheme(theme: String) = repo.setTheme(theme)
    fun currentTheme(): String = repo.themePref()
    fun agentEngine(): String = repo.agentEngine()
    fun setAgentEngine(engine: String) {
        repo.setAgentEngine(engine)
        agent.refreshDefaultsForBlankSession()
    }
    fun dualAgentReasoningLevel(): String = repo.dualAgentReasoningLevel()
    fun setDualAgentReasoningLevel(level: String) {
        repo.setDualAgentReasoningLevel(level)
        agent.refreshDefaultsForBlankSession()
    }

    fun textModelProfiles(): List<TextModelProfile> = repo.textModelProfiles()
    fun saveTextModelProfile(profile: TextModelProfile) = repo.saveTextModelProfile(profile)
    fun deleteTextModelProfile(profileId: String) = repo.deleteTextModelProfile(profileId)
    fun setActiveProfile(profileId: String) = repo.setActiveProfile(profileId)
    fun activeTextModelConfig(): TextModelConfig = repo.activeTextModelConfig()

    fun writingWorkspace(projectId: String): WritingWorkspace = repo.writingWorkspace(projectId)
    fun saveWritingWorkspace(projectId: String, workspace: WritingWorkspace) = repo.saveWritingWorkspace(projectId, workspace)
    fun writingUsage(projectId: String) = repo.writingUsage(projectId)
    fun storyNoteStatusReport(projectId: String): Map<String, String> {
        val workspace = repo.writingWorkspace(projectId)
        val chapterIds = repo.chapters(projectId).map { it.id }.toSet()
        val characterIds = repo.characters(projectId).map { it.id }.toSet()
        val hashes = workspace.notes.mapNotNull { it.sourceChapterId }.distinct().associateWith { id ->
            if (id !in chapterIds) null else repo.chapterBody(id).let { body ->
                com.example.novelseek_ultra.data.writing.TextRangeReader.hash(body.final.ifBlank { body.draft })
            }
        }
        return workspace.notes.associate { note -> note.id to when {
            note.knownByCharacterIds.any { it !in characterIds } -> "知情角色已删除，不用于生成"
            note.sourceChapterId == null -> "作者设定/计划；不代表角色已知"
            note.sourceChapterId !in chapterIds -> "来源章节已删除，不用于生成"
            note.sourceBodyHash == null || note.sourceBodyHash != hashes[note.sourceChapterId] -> "来源已变化或未核对，不用于生成"
            else -> "已绑定当前来源正文；仅在来源章之后可见"
        } }
    }
    fun scenePlans(projectId: String): List<ChapterScenePlan> {
        val chapterIds = repo.chapters(projectId).map { it.id }.toSet()
        return sceneWritingStore.listPlans(projectId).filter { it.chapterId in chapterIds }
    }
    private fun writingStageLabel(stage: String): String = when (stage) {
        "planning" -> if (_uiLanguage.value == "en") "Planning scenes" else "正在规划场景"
        "writing" -> if (_uiLanguage.value == "en") "Writing scene" else "正在写作场景"
        "scene_completed" -> if (_uiLanguage.value == "en") "Scene saved" else "场景已保存"
        "reviewing" -> if (_uiLanguage.value == "en") "Reviewing candidate" else "正在审稿"
        "completed" -> if (_uiLanguage.value == "en") "Candidate ready" else "候选稿已完成"
        else -> stage
    }
    fun writingCheckpoints(projectId: String): List<WritingCheckpoint> {
        val chapterIds = repo.chapters(projectId).map { it.id }.toSet()
        return sceneWritingStore.list(projectId).filter { it.plan.chapterId in chapterIds }
    }
    fun saveScenePlan(plan: ChapterScenePlan) {
        require(repo.chapters(plan.projectId).any { it.id == plan.chapterId }) { "章节已不存在" }
        check(sceneWritingStore.savePlan(plan)) { "当前场景正在生成，请先暂停" }
        _writingRevision.update { it + 1 }
    }

    private suspend fun prepareChapterWriting(projectId: String, chapter: Chapter): PreparedChapterWriting {
        val sources = captureStablePromptSources(projectId, PromptSourceScope.CHAPTER, chapter.order_index)
            ?: error("项目上下文正在变化，请稍后再试")
        val baseline = repo.chapterBody(chapter.id)
        val storyState = buildStoryStateContext(sources, chapter, _uiLanguage.value)
        val cards = StoryNoteSelector.select(sources.writingWorkspace, chapter, repo.chapters(projectId),
            sourceHashes = sources.previousBodies.associate { (id, body) -> id to com.example.novelseek_ultra.data.writing.TextRangeReader.hash(body.final.ifBlank { body.draft }) },
            characterNames = repo.characters(projectId).associate { it.id to it.name })
        val arcs = sources.arcs.map { it.toPlotArc() }
        val arc = chapter.arcId?.let { id -> arcs.firstOrNull { it.id == id } }
            ?: arcs.firstOrNull { it.builtChapterIds?.contains(chapter.id) == true }
        val ceiling = arc?.volumeId?.let { id -> sources.volumes.firstOrNull { it.id == id } }
            ?.let { buildVolumeRealmConstraint(it.realmPlan, it.name, _uiLanguage.value, phase = "generate") }
        val stableContext = buildString {
            appendLine("作者偏好：\n${sources.writingWorkspace.preferencePrompt()}")
            appendLine("世界观：\n${sources.worldSetting.orEmpty().ifBlank { sources.outline.orEmpty() }}")
            appendLine("时间线：\n${sources.timeline.orEmpty()}")
            appendLine("角色资料：\n${buildCharactersInfo(projectId)}")
            appendLine("境界体系：\n${buildRealmSystemContext(sources.realms, _uiLanguage.value)}")
            if (!ceiling.isNullOrBlank()) appendLine("本卷硬约束：\n$ceiling")
            appendLine("目标章节之前的故事状态：\n$storyState")
            appendLine("分类故事卡片：\n${cards.prompt}")
            appendLine("作者设定和未来计划不代表视角人物已知；人物只能依据正文中的知情过程行动。")
        }
        return PreparedChapterWriting(projectId, chapter, baseline,
            promptContextManifest(PromptSourceScope.CHAPTER, sources), stableContext) {
            capturePromptSources(projectId, PromptSourceScope.CHAPTER, chapter.order_index)?.let {
                promptContextManifest(PromptSourceScope.CHAPTER, it)
            }
        }
    }

    fun generateScenePlan(projectId: String, chapterId: String) {
        val key = "$projectId/$chapterId"
        if (writingJobs[key]?.isActive == true) return
        val job = viewModelScope.launchWithFailureBoundary(context = Dispatchers.IO, start = CoroutineStart.LAZY,
            onFailure = { _statusMessage.value = it.message ?: "场景规划失败" }) {
            try {
                _writingStages.update { it + (key to "正在规划场景") }
                val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: error("章节已不存在")
                chapterWriter.plan(prepareChapterWriting(projectId, chapter), _uiLanguage.value)
                _statusMessage.value = "场景计划已保存，可编辑后开始写作"
            } finally {
                if (writingJobs.remove(key, currentCoroutineContext()[Job])) _writingStages.update { it - key }
                _writingRevision.update { it + 1 }
            }
        }
        if (writingJobs.putIfAbsent(key, job) != null) { job.cancel(); return }
        job.start()
    }

    fun startWorkspaceChapter(projectId: String, chapterId: String, resumeRunId: String? = null) {
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return
        val checkpoint = resumeRunId?.let { sceneWritingStore.load(projectId, chapterId) }
        val mode = checkpoint?.mode ?: when (repo.writingWorkspace(projectId).mode) {
            "quick" -> WritingMode.FAST
            "polish" -> WritingMode.POLISHED
            else -> WritingMode.SCENES
        }
        startUnifiedChapter(projectId, chapter, mode, resumeRunId = resumeRunId)
    }

    fun pauseWritingTask(projectId: String, chapterId: String) {
        writingJobs["$projectId/$chapterId"]?.cancel(CancellationException("用户暂停场景任务"))
    }

    fun cancelWritingTask(projectId: String, chapterId: String) {
        val key = "$projectId/$chapterId"
        val previousOwner = writingJobs[key]
        val control = viewModelScope.launchWithFailureBoundary(context = Dispatchers.IO, start = CoroutineStart.LAZY,
            onFailure = { _statusMessage.value = it.message ?: "取消任务失败" }) {
            val owner = checkNotNull(currentCoroutineContext()[Job])
            try {
                previousOwner?.cancel(CancellationException("用户取消场景任务"))
                previousOwner?.join()
                val run = repo.latestGenerationRun(projectId, chapterId)
                if (run?.status == GenerationRun.STATUS_COMPLETED) {
                    _statusMessage.value = "候选稿已经完成，请审核后采用或拒绝"
                    return@launchWithFailureBoundary
                }
                if (run?.status == GenerationRun.STATUS_RUNNING) repo.cancelGenerationRun(projectId, run.id, "用户取消已中断任务")
                sceneWritingStore.withExclusiveAccess {
                    var checkpoint = sceneWritingStore.load(projectId, chapterId)
                    if (checkpoint?.status == WritingStatus.RUNNING) {
                        val interrupted = checkpoint.copy(status = WritingStatus.INTERRUPTED, revision = checkpoint.revision + 1,
                            updatedAt = System.currentTimeMillis(), error = "无运行执行者，用户已取消")
                        check(sceneWritingStore.compareAndSet(checkpoint, interrupted)) { "任务已变化，请刷新后重试" }
                        checkpoint = interrupted
                    }
                }
                _statusMessage.value = "任务已取消；完成场景和计划保留，正式正文未修改"
            } finally {
                if (writingJobs.remove(key, owner)) _writingStages.update { it - key }
                _writingRevision.update { it + 1 }
            }
        }
        val registered = if (previousOwner == null) writingJobs.putIfAbsent(key, control) == null
            else writingJobs.replace(key, previousOwner, control)
        if (!registered) { control.cancel(); _statusMessage.value = "任务状态已变化，请刷新后重试"; return }
        _writingStages.update { it + (key to "正在取消任务") }
        control.start()
    }

    private fun startUnifiedChapter(projectId: String, chapter: Chapter, mode: WritingMode,
        resumeRunId: String? = null, continuation: String? = null, draftReference: String? = null) {
        val key = "$projectId/${chapter.id}"
        if (writingJobs[key]?.isActive == true) { _statusMessage.value = "本章已有任务正在运行"; return }
        val ticket = claimStreamingGeneration(continuation.orEmpty(), chapterGeneration = true)
        launchStreamingGeneration(ticket) { revision, buffer ->
            val owner = checkNotNull(currentCoroutineContext()[Job])
            check(writingJobs.putIfAbsent(key, owner) == null) { "本章已有任务正在运行" }
            var generationId: String? = null
            try {
                val source = prepareChapterWriting(projectId, chapter)
                val body = chapterWriter.write(source, _uiLanguage.value, mode, GenerationRun.INITIATOR_EDITOR,
                    resumeRunId = resumeRunId, continuation = continuation, draftReference = draftReference,
                    onStarted = { active ->
                        generationId = active.run.id
                        ChapterGenerationExecutions.replace(projectId, chapter.id, active.run.id, owner)
                    },
                    onProgress = { progress ->
                        ensureStreamingOwner(revision)
                        _writingStages.update { it + (key to writingStageLabel(progress.stage)) }
                        val checkpoint = progress.checkpoint
                        val completed = checkpoint?.body.orEmpty()
                        val text = listOfNotNull((checkpoint?.baselineText ?: continuation)?.takeIf(String::isNotBlank), completed.takeIf(String::isNotBlank),
                            progress.preview.takeIf(String::isNotBlank)).joinToString("\n\n")
                        replaceStreamingText(revision, buffer, text)
                        if (progress.preview.isEmpty()) _writingRevision.update { it + 1 }
                    })
                ensureStreamingOwner(revision)
                replaceStreamingText(revision, buffer, body)
                _statusMessage.value = "候选稿已生成，请审核后采用"
            } finally {
                generationId?.let { ChapterGenerationExecutions.unregister(projectId, chapter.id, it) }
                if (writingJobs.remove(key, owner)) _writingStages.update { it - key }
                _writingRevision.update { it + 1 }
            }
        }
    }

    fun importManuscript(projectId: String, preview: ManuscriptPreview) {
        val count = repo.importManuscript(projectId, preview)
        _statusMessage.value = "已导入 $count 章；未调用 AI，可按需提取资料"
    }
    fun pollinationsKey(): String = repo.pollinationsKey()
    fun setPollinationsKey(key: String) = repo.setPollinationsKey(key)

    // ── Image engine selection (Pollinations / ComfyUI) ───────────────────────
    fun imageEngine(): String = repo.imageEngine()
    fun setImageEngine(engine: String) = repo.setImageEngine(engine)
    fun comfyUIUrl(): String = repo.comfyUIUrl()
    fun setComfyUIUrl(url: String) = repo.setComfyUIUrl(url)

    suspend fun testComfyUIConnection(): Boolean = withContext(Dispatchers.IO) {
        ai.testComfyUIConnection(repo.comfyUIUrl())
    }

    /**
     * Engine-agnostic image generation used by every image feature (portrait / illustration /
     * promo / cover). Routes to ComfyUI when the user picked it in Settings, otherwise Pollinations.
     * `model` only applies to Pollinations; the ComfyUI workflow (`t2i-lumicreate.json`) is fixed
     * to z-image-turbo. Returns raw PNG/JPEG bytes either way. Must be called off the main thread.
     */
    private suspend fun generateImageBytes(
        prompt: String,
        width: Int,
        height: Int,
        model: String = "zimage",
    ): ByteArray {
        val raw = if (repo.imageEngine() == "comfyui") {
            ai.generateImageComfyUI(prompt = prompt, width = width, height = height, baseUrl = repo.comfyUIUrl())
        } else {
            ai.generateImage(
                prompt = prompt,
                width = width,
                height = height,
                model = model,
                pollinationsKey = repo.pollinationsKey().ifBlank { null },
            )
        }
        // Downscale + JPEG-compress before the bytes get base64'd into app state. ComfyUI returns
        // multi-MB PNGs that otherwise bloat app_state.json and OOM on save — see ImageUtils.
        return com.example.novelseek_ultra.util.ImageUtils.compressForStorage(raw)
    }
    fun embeddingConfig(): EmbeddingConfig = repo.embeddingConfig()
    fun saveEmbeddingConfig(cfg: EmbeddingConfig) = repo.saveEmbeddingConfig(cfg)

    suspend fun testTextConnection(): Boolean = withContext(Dispatchers.IO) {
        ai.testConnection(repo.activeTextModelConfig())
    }

    // ── projects ───────────────────────────────────────────────────────────

    fun project(projectId: String): Project? = repo.project(projectId)

    fun createProject(title: String, genre: String?, description: String?, isLong: Boolean = false): String {
        val now = nowIso()
        val id = "p-${System.currentTimeMillis()}"
        repo.createProject(
            Project(
                id = id,
                title = title.trim(),
                genre = genre?.takeIf { it.isNotBlank() },
                description = description?.takeIf { it.isNotBlank() },
                language = _uiLanguage.value,
                created_at = now,
                updated_at = now,
            )
        )
        if (isLong) repo.setNovelType(id, "long")
        return id
    }

    fun updateProject(id: String, patch: (Project) -> Project) = repo.updateProject(id, patch)
    fun deleteProject(id: String) = repo.deleteProject(id)

    fun novelType(projectId: String): String = repo.novelType(projectId)
    fun setNovelType(projectId: String, type: String) = repo.setNovelType(projectId, type)

    // ── chapters ───────────────────────────────────────────────────────────

    fun chapters(projectId: String): List<Chapter> = repo.chapters(projectId)
    fun setChapters(projectId: String, chapters: List<Chapter>) = repo.setChapters(projectId, chapters)
    fun upsertChapter(projectId: String, ch: Chapter) = repo.upsertChapter(projectId, ch)
    fun deleteChapter(projectId: String, chapterId: String): Boolean =
        deleteChapterFully(projectId, chapterId)

    /** Renumber chapters to a contiguous 1..n by current order (only display order_index changes;
     *  the agent's chapter index is by id/arcId so it is NOT affected). */
    fun renumberChapters(projectId: String): Boolean {
        val sorted = repo.chapters(projectId).sortedBy { it.order_index }
        if (sorted.isEmpty()) return false
        val needs = sorted.withIndex().any { (i, c) -> c.order_index != i + 1 }
        if (needs) repo.setChapters(projectId, sorted.mapIndexed { i, c -> c.copy(order_index = i + 1) })
        return needs
    }

    /** Delete one exact chapter version together with all of its derived references and files. */
    fun deleteChapterFully(projectId: String, chapterId: String): Boolean {
        val expected = repo.chapters(projectId).firstOrNull { it.id == chapterId }
        if (expected == null) {
            _statusMessage.value = "章节已不存在，未执行删除"
            return false
        }
        val deleted = repo.deleteChapterFullyIfUnchanged(projectId, expected)
        if (!deleted) {
            _statusMessage.value = "内容冲突：章节在确认删除后已被修改，未执行删除"
        }
        return deleted
    }

    /** Resolve a chapter's arc id (explicit, else inferred from any arc's builtChapterIds). */
    fun chapterArcId(projectId: String, chapter: Chapter): String? =
        chapter.arcId ?: repo.plotArcs(projectId).firstOrNull { it.builtChapterIds?.contains(chapter.id) == true }?.id

    /** Compute one deterministic agent edit, but publish it only as a review candidate. */
    private fun stageAgentChapterText(
        projectId: String,
        chapterId: String,
        operation: String,
        agentEngineMode: String,
        agentSessionId: String,
        agentActionId: String,
        transform: (String) -> ChapterTextMutation,
    ): String {
        if (repo.project(projectId) == null) return "项目已不存在，未修改正文"
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId }
            ?: return "未找到章节"
        val body = repo.chapterBody(chapterId)
        val current = body.final.ifBlank { body.draft }
        val mutation = transform(current)
        val replacement = mutation.replacement ?: return mutation.observation
        if (replacement == current) return "未产生任何变化：正文与目标文本相同"

        val expectedSource = GenerationSourceFingerprint.capture(
            chapter,
            body.draft,
            body.final,
        )
        val staticManifest = GenerationContextFingerprint.capture(
            scope = "agent:$operation",
            materials = listOf(
                GenerationContextMaterial("operation", listOf(operation)),
            ),
        )
        val telemetry = GenerationTelemetryCollector.local("chapter.agent_local.v1")
        val active = novelGenerationEngine.start(
            NovelGenerationEngine.Request(
                projectId = projectId,
                chapter = chapter,
                targetWords = 0,
                language = _uiLanguage.value,
                baselineText = current,
                requireNetNewBody = false,
                contextManifest = staticManifest,
                expectedSourceHash = expectedSource.sourceHash,
                initiator = GenerationRun.INITIATOR_AGENT,
                agentEngine = agentEngineMode,
                agentSessionId = agentSessionId.takeIf { it.isNotBlank() },
                agentActionId = agentActionId.takeIf { it.isNotBlank() },
                operation = operation,
            ),
        ) ?: return "候选稿创建失败：章节正文或规划已变化，请重新读取后再修改"
        ChapterGenerationExecutions.cancelExisting(projectId, chapterId)

        val stillCurrent = repo.project(projectId) != null &&
            repo.chapters(projectId).firstOrNull { it.id == chapterId } == chapter &&
            repo.chapterBody(chapterId) == body
        if (!stillCurrent) {
            val category = GenerationTelemetry.FAILURE_SOURCE_CONFLICT
            novelGenerationEngine.fail(
                active,
                GenerationTelemetryCollector.persistedFailureMessage(category),
                telemetry.finishFailed(category),
            )
            return "内容冲突：章节正文或规划已变化，未创建可采用的候选稿"
        }
        val completion = completeGenerationCandidate(active, replacement, telemetry)
        if (completion == null) {
            if (generationRunWasSuperseded(projectId, active.run.id)) {
                val category = GenerationTelemetry.FAILURE_SUPERSEDED
                novelGenerationEngine.cancel(
                    active,
                    GenerationTelemetryCollector.persistedFailureMessage(category),
                    telemetry.finishCancelled(category),
                )
                return "候选稿生成任务已被新的任务替代，正式正文未改变"
            }
            val category = GenerationTelemetry.FAILURE_PERSISTENCE
            novelGenerationEngine.fail(
                active,
                GenerationTelemetryCollector.persistedFailureMessage(category),
                telemetry.finishFailed(category),
            )
            return "候选稿保存失败，正式正文未改变"
        }
        return if (completion.report.blocking) {
            "${mutation.observation}；候选稿已保存但未通过硬性质量检查，正式正文未改变"
        } else {
            "${mutation.observation}；已生成候选稿，等待用户审核，正式正文未改变"
        }
    }

    fun agentReplaceInChapter(
        projectId: String,
        chapterId: String,
        find: String,
        replacement: String,
        agentEngineMode: String = repo.agentEngine(),
        agentSessionId: String = "",
        agentActionId: String = "",
    ): String = stageAgentChapterText(
        projectId,
        chapterId,
        GenerationRun.OPERATION_REPLACE,
        agentEngineMode,
        agentSessionId,
        agentActionId,
    ) { current ->
        if (find.isBlank()) {
            ChapterTextMutation(null, "find 不能为空")
        } else if (!current.contains(find)) {
            ChapterTextMutation(null, "未在正文中找到该片段，请先用 read_chapter 复制逐字一致的原文")
        } else {
            val count = current.split(find).size - 1
            ChapterTextMutation(current.replace(find, replacement), "已替换 $count 处")
        }
    }

    fun agentEditChapterParagraph(
        projectId: String,
        chapterId: String,
        paragraphIndex: Int,
        newText: String,
        agentEngineMode: String = repo.agentEngine(),
        agentSessionId: String = "",
        agentActionId: String = "",
    ): String = stageAgentChapterText(
        projectId,
        chapterId,
        GenerationRun.OPERATION_EDIT_PARAGRAPH,
        agentEngineMode,
        agentSessionId,
        agentActionId,
    ) { current ->
        if (current.isBlank()) {
            ChapterTextMutation(null, "该章暂无正文")
        } else {
            val lines = current.split("\n").toMutableList()
            var paragraphCount = 0
            var target = -1
            for (index in lines.indices) {
                if (lines[index].isNotBlank()) {
                    paragraphCount++
                    if (paragraphCount == paragraphIndex) {
                        target = index
                        break
                    }
                }
            }
            if (target < 0) {
                ChapterTextMutation(null, "未找到第 $paragraphIndex 段（共 $paragraphCount 段）")
            } else {
                lines[target] = newText
                ChapterTextMutation(lines.joinToString("\n"), "已替换第 $paragraphIndex 段")
            }
        }
    }

    fun agentSetChapterText(
        projectId: String,
        chapterId: String,
        text: String,
        agentEngineMode: String = repo.agentEngine(),
        agentSessionId: String = "",
        agentActionId: String = "",
    ): String =
        stageAgentChapterText(
            projectId,
            chapterId,
            GenerationRun.OPERATION_SET_BODY,
            agentEngineMode,
            agentSessionId,
            agentActionId,
        ) {
            ChapterTextMutation(text, "已写入正文（${text.length} 字符）")
        }

    /** Move a chapter to [newPos1Based] (1-based) in the chapter list, renumbering order_index 1..n. */
    fun moveChapterToPosition(projectId: String, chapterId: String, newPos1Based: Int) {
        val list = repo.chapters(projectId).sortedBy { it.order_index }.toMutableList()
        val idx = list.indexOfFirst { it.id == chapterId }
        if (idx < 0) return
        val target = newPos1Based.coerceIn(1, list.size) - 1
        if (target == idx) return
        val moved = list.removeAt(idx)
        list.add(target, moved)
        repo.setChapters(projectId, list.mapIndexed { i, c -> c.copy(order_index = i + 1) })
    }

    /** Build the cultivation-realm system from a JSON array (agent tool). Returns realm count. */
    fun agentSetRealmsFromJson(projectId: String, jsonText: String): Int {
        val s = jsonText.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = s.indexOf('['); val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return 0
        val arr = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(s.substring(start, end + 1)).jsonArray }
            .getOrNull() ?: return 0
        val ts = System.currentTimeMillis()
        fun str(o: kotlinx.serialization.json.JsonObject, k: String) =
            (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        val realms = arr.mapIndexedNotNull { i, el ->
            val o = el.jsonObject
            val name = str(o, "name").orEmpty(); if (name.isBlank()) return@mapIndexedNotNull null
            val subs = (o["subRealms"] as? kotlinx.serialization.json.JsonArray)?.mapIndexedNotNull { j, se ->
                val so = se.jsonObject
                val sn = str(so, "name").orEmpty(); if (sn.isBlank()) return@mapIndexedNotNull null
                CultivationSubRealm(id = "sub-$ts-$i-$j", order = j, name = sn, description = str(so, "description"))
            }?.ifEmpty { null }
            CultivationRealm(id = "realm-$ts-$i", order = i, name = name, description = str(o, "description"), subRealms = subs)
        }
        repo.setCultivationRealms(projectId, realms)
        return realms.size
    }
    fun chapterBody(chapterId: String): AppRepository.ChapterBody = repo.chapterBody(chapterId)
    fun saveChapterBody(chapterId: String, body: AppRepository.ChapterBody) = repo.saveChapterBody(chapterId, body)

    /** Newest completed run that still awaits an explicit accept/reject decision. */
    fun latestReviewableGenerationRun(projectId: String, chapterId: String): GenerationRun? =
        repo.listGenerationRuns(projectId, chapterId)
            .firstOrNull { it.status == GenerationRun.STATUS_COMPLETED }

    /** Exact durable generation run used by agent review links. Call from a worker dispatcher. */
    fun chapterGenerationRun(projectId: String, runId: String): GenerationRun? =
        repo.getGenerationRun(projectId, runId)

    fun reviewBaselineText(run: GenerationRun): String {
        val body = repo.chapterBody(run.chapterId)
        return body.final.ifBlank { body.draft }
    }

    private fun currentReviewManifest(projectId: String, run: GenerationRun): GenerationContextManifest? {
        val expected = run.contextManifest ?: return null
        return when {
            expected.scope == "prompt:${PromptSourceScope.CHAPTER.name}" -> {
                val chapter = repo.chapters(projectId).firstOrNull { it.id == run.chapterId } ?: return null
                captureStablePromptSources(projectId, PromptSourceScope.CHAPTER, chapter.order_index)?.let {
                    promptContextManifest(PromptSourceScope.CHAPTER, it)
                }
            }
            expected.scope.startsWith("agent:") -> expected
            else -> null
        }
    }

    /** Failed or stale revisions never replace either the candidate or the official chapter. */
    suspend fun reviseChapterCandidate(projectId: String, runId: String, candidateId: String,
        instruction: String): GenerationRun = withContext(Dispatchers.IO) {
        reviewLocks.getOrPut("$projectId/$runId") { Mutex() }.withLock {
        require(instruction.isNotBlank() && instruction.length <= 4_000) { "请填写不超过 4,000 字符的修改意见" }
        val run = repo.getGenerationRun(projectId, runId) ?: error("候选稿已不存在")
        check(run.status == GenerationRun.STATUS_COMPLETED) { "候选稿已处理" }
        val candidate = run.candidates.firstOrNull { it.id == candidateId } ?: error("候选稿已不存在")
        check(run.reviewRevisions.size < 8) { "本稿修改轮数已达上限，请采用或拒绝后重新生成" }
        val originalRequests = maxOf(run.telemetry?.requestCount ?: 0,
            sceneWritingStore.load(projectId, run.chapterId)?.takeIf { it.sourceFingerprint == run.sourceHash }?.requestCount ?: 0)
        val revisionRequests = repo.writingUsage(projectId).filter { it.parentRunId == runId }.sumOf { it.requestCount.toLong() }
        check(originalRequests + revisionRequests < repo.writingWorkspace(projectId).maxRequestsPerRun) {
            "本任务请求额度不足，请在写作偏好中提高额度后再修订"
        }
        val cfg = TextModelRequestPolicy.normalizeForRequest(repo.textModelForRole(projectId, "review"))
        require(cfg.apiKey.isNotBlank()) { "请配置审稿模型 API" }
        val source = repo.chapters(projectId).firstOrNull { it.id == run.chapterId } ?: error("章节已不存在")
        val context = prepareChapterWriting(projectId, source)
        val messages = listOf(ChatMessage("system", "你是小说编辑。依据作者意见修订候选稿，保持原稿语言，以及未要求修改的剧情和设定。只输出完整修订正文，不要说明、标题或代码块。"),
            ChatMessage("user", "写作约束：\n${context.stableContext}\n章节规划：${source.outline_goal.orEmpty()}\n作者修改意见：\n$instruction\n待修订候选全文：\n${candidate.body}"))
        val prepared = PromptRequestBudgeter.validate(cfg, messages).messages
        val usageId = "review-${java.util.UUID.randomUUID()}"
        // Reserve one attempt durably before calling the provider, including failed retries.
        repo.recordWritingUsage(projectId, com.example.novelseek_ultra.data.writing.WritingUsageEntry(
            runId = usageId, model = cfg.model, completedAt = nowIso(), requestCount = 1,
            chapterId = run.chapterId, parentRunId = runId))
        val telemetry = GenerationTelemetryCollector.forModel(cfg, "chapter.review.revise.v1")
        val trace = telemetry.beginRequest("review_revision", prepared, false)
        val revised = try {
            ai.chat(cfg, prepared, onUsage = trace::onUsage).also { trace.onContent(it); trace.complete() }
        } catch (cancelled: CancellationException) {
            trace.cancel()
            runCatching { repo.recordWritingUsage(projectId, telemetry.finishCancelled().toWritingUsage(usageId, nowIso(), run.chapterId, runId)) }
            throw cancelled
        } catch (failure: Throwable) {
            trace.fail(GenerationTelemetryCollector.failureCategory(failure))
            runCatching { repo.recordWritingUsage(projectId, telemetry.finishFailed(GenerationTelemetryCollector.failureCategory(failure)).toWritingUsage(usageId, nowIso(), run.chapterId, runId)) }
            throw failure
        }
        runCatching { repo.recordWritingUsage(projectId, telemetry.completedSnapshot().toWritingUsage(usageId, nowIso(), run.chapterId, runId)) }
        check(repo.reviseReviewCandidate(projectId, runId, candidateId, candidate.body, revised, instruction) {
            currentReviewManifest(projectId, run)
        }) { "修改稿未提交：来源或候选稿已改变，或输出不完整；原候选稿已保留" }
        checkNotNull(repo.getGenerationRun(projectId, runId))
        }
    }

    fun adoptReviewedCandidate(projectId: String, runId: String, candidateId: String,
        expectedBody: String, selectedText: String): CandidateAdoptionResult {
        val run = repo.getGenerationRun(projectId, runId)
            ?: return CandidateAdoptionResult.Unavailable("missing_run")
        val candidate = run.candidates.firstOrNull { it.id == candidateId }
            ?: return CandidateAdoptionResult.Unavailable("missing_candidate")
        if (candidate.body != expectedBody || selectedText == reviewBaselineText(run))
            return CandidateAdoptionResult.Unavailable("stale_or_no_changes")
        if (selectedText != expectedBody && !repo.reviseReviewCandidate(projectId, runId, candidateId,
                expectedBody, selectedText, "作者逐段选择采用") { currentReviewManifest(projectId, run) })
            return CandidateAdoptionResult.Unavailable("source_or_candidate_changed")
        return adoptChapterCandidate(projectId, runId, candidateId)
    }

    fun adoptChapterCandidate(
        projectId: String,
        runId: String,
        candidateId: String,
    ): CandidateAdoptionResult {
        val run = repo.getGenerationRun(projectId, runId)
        val currentContextManifest = {
            run?.contextManifest?.let { expected ->
            when {
                expected.scope == "prompt:${PromptSourceScope.CHAPTER.name}" -> {
                    val liveChapter = repo.chapters(projectId).firstOrNull { chapter ->
                        chapter.id == run.chapterId
                    }
                    liveChapter?.let { chapter ->
                        captureStablePromptSources(
                            projectId,
                            PromptSourceScope.CHAPTER,
                            chapter.order_index,
                        )?.let { snapshot ->
                            promptContextManifest(PromptSourceScope.CHAPTER, snapshot)
                        }
                    }
                }
                // Deterministic agent edits use only the exact chapter body/plan guarded by the
                // repository source hash; their static request manifest has no mutable live source.
                expected.scope.startsWith("agent:") -> expected
                else -> null
            }
            }
        }
        val result = novelGenerationEngine.adoptWithContextRecheck(
            projectId,
            runId,
            candidateId,
            currentContextManifest,
        )
        when (result) {
            is CandidateAdoptionResult.Adopted -> {
                _statusMessage.value = if (_uiLanguage.value == "en") {
                    "Candidate accepted; official chapter updated."
                } else {
                    "已采用候选稿并更新正式正文"
                }
                result.run.candidates.firstOrNull { it.id == candidateId }?.let { candidate ->
                    onChapterSaved(
                        projectId,
                        result.chapter.id,
                        result.chapter.title,
                        candidate.body,
                    )
                }
            }
            is CandidateAdoptionResult.SourceChanged -> {
                val changed = buildList {
                    if (result.planChanged) add(if (_uiLanguage.value == "en") "plan" else "章节规划")
                    if (result.bodyChanged) add(if (_uiLanguage.value == "en") "body" else "正文/草稿")
                    if (result.contextChanged) {
                        add(if (_uiLanguage.value == "en") "generation context" else "世界观/角色/前文等生成上下文")
                    }
                }.joinToString(if (_uiLanguage.value == "en") " and " else "和")
                _statusMessage.value = if (_uiLanguage.value == "en") {
                    "Candidate kept, but cannot be accepted because the $changed changed after generation."
                } else {
                    "候选稿已保留，但生成后的${changed}发生了变化，不能覆盖新内容"
                }
            }
            is CandidateAdoptionResult.Unavailable -> {
                _statusMessage.value = if (_uiLanguage.value == "en") {
                    "Candidate is not available for acceptance (${result.reason})."
                } else {
                    "该候选稿当前不能采用（${result.reason}）"
                }
            }
        }
        return result
    }

    fun rejectChapterGeneration(projectId: String, runId: String): Boolean {
        val rejected = novelGenerationEngine.reject(projectId, runId) ?: return false
        _statusMessage.value = if (_uiLanguage.value == "en") {
            "Candidate rejected; official chapter was not changed."
        } else {
            "已拒绝候选稿，正式正文未改变"
        }
        return rejected.status == GenerationRun.STATUS_REJECTED
    }

    fun addChapter(projectId: String, title: String, goal: String?): Chapter {
        val list = repo.chapters(projectId)
        val now = nowIso()
        val ch = Chapter(
            id = "c-${System.currentTimeMillis()}",
            project_id = projectId,
            title = title,
            order_index = (list.maxOfOrNull { it.order_index } ?: 0) + 1,
            outline_goal = goal,
            created_at = now,
            updated_at = now,
        )
        repo.upsertChapter(projectId, ch)
        return ch
    }

    /**
     * Insert a new (empty) chapter immediately before or after [referenceChapterId], shifting the
     * order_index of all following chapters by +1 so the sequence stays contiguous. Returns the new
     * chapter (caller typically navigates to it), or null if the reference chapter is missing.
     */
    fun insertChapter(projectId: String, referenceChapterId: String, before: Boolean, title: String): Chapter? {
        val list = repo.chapters(projectId).sortedBy { it.order_index }
        val ref = list.firstOrNull { it.id == referenceChapterId } ?: return null
        val targetOrder = if (before) ref.order_index else ref.order_index + 1
        val now = nowIso()
        val shifted = list.map { if (it.order_index >= targetOrder) it.copy(order_index = it.order_index + 1) else it }
        val newCh = Chapter(
            id = "c-${System.currentTimeMillis()}",
            project_id = projectId,
            title = title,
            order_index = targetOrder,
            created_at = now,
            updated_at = now,
        )
        repo.setChapters(projectId, shifted + newCh)
        return newCh
    }

    // ── per-project metadata ───────────────────────────────────────────────

    fun worldSetting(projectId: String): String = repo.worldSetting(projectId)
    fun setWorldSetting(projectId: String, value: String) = repo.setWorldSetting(projectId, value)
    fun timeline(projectId: String): String = repo.timeline(projectId)
    fun setTimeline(projectId: String, value: String) = repo.setTimeline(projectId, value)
    fun lastListenProjectId(): String? = repo.lastListenProjectId()

    fun outlineText(projectId: String): String = repo.outline(projectId)
    fun setOutlineText(projectId: String, value: String) = repo.setOutline(projectId, value)

    fun characters(projectId: String): List<Character> = repo.characters(projectId)
    fun setCharacters(projectId: String, list: List<Character>) = repo.setCharacters(projectId, list)
    fun mergeGeneratedCharacters(
        projectId: String,
        candidates: List<Character>,
        expectedOutline: String? = null,
    ): List<Character> = if (expectedOutline == null) {
        repo.mergeCharactersIfProjectExists(projectId, candidates)
    } else {
        repo.mergeCharactersIfOutlineUnchanged(projectId, expectedOutline, candidates)
    }

    // ── Character growth route (角色成长) ──
    fun characterGrowth(projectId: String, characterId: String): List<CharacterGrowthEntry> =
        repo.characterGrowth(projectId, characterId)

    fun addCharacterGrowth(projectId: String, characterId: String, value: String, chapter: Chapter? = null, manual: Boolean = true) {
        repo.appendCharacterGrowth(projectId, characterId, CharacterGrowthEntry(
            id = "grow-${System.currentTimeMillis()}", value = value,
            chapterId = chapter?.id, chapterOrder = chapter?.order_index, chapterTitle = chapter?.title,
            createdAt = nowIso(), manual = manual,
        ))
    }

    fun updateCharacterGrowthLatest(projectId: String, characterId: String, value: String) {
        repo.updateLatestCharacterGrowth(projectId, characterId, value)
    }

    fun deleteCharacterGrowth(projectId: String, characterId: String, entryId: String) =
        repo.deleteCharacterGrowthEntry(projectId, characterId, entryId)

    fun plotArcs(projectId: String): List<PlotArc> = repo.plotArcs(projectId)
    fun setPlotArcs(projectId: String, arcs: List<PlotArc>) = repo.setPlotArcs(projectId, arcs)

    // ── 副本 (Volumes) ────────────────────────────────────────────────────────
    fun volumes(projectId: String): List<Volume> = repo.volumes(projectId).sortedBy { it.order }
    fun setVolumes(projectId: String, volumes: List<Volume>) = repo.setVolumes(projectId, volumes)
    fun ensureVolumes(projectId: String) = repo.ensureVolumes(projectId)

    fun arcsForVolume(projectId: String, volumeId: String): List<PlotArc> =
        repo.plotArcs(projectId).filter { it.volumeId == volumeId }.sortedBy { it.order }

    fun createVolume(projectId: String, name: String, description: String, realmPlan: String = ""): Volume {
        val order = (repo.volumes(projectId).maxOfOrNull { it.order } ?: -1) + 1
        val v = Volume(id = "vol-${System.currentTimeMillis()}", name = name, description = description, order = order, createdAt = nowIso(), realmPlan = realmPlan)
        repo.setVolumes(projectId, repo.volumes(projectId) + v)
        return v
    }

    fun updateVolume(projectId: String, volumeId: String, patch: (Volume) -> Volume) =
        repo.setVolumes(projectId, repo.volumes(projectId).map { if (it.id == volumeId) patch(it) else it })

    /** Delete a volume AND the arcs it contains. */
    fun deleteVolume(projectId: String, volumeId: String) {
        repo.setVolumes(projectId, repo.volumes(projectId).filterNot { it.id == volumeId })
        repo.setPlotArcs(projectId, repo.plotArcs(projectId).filterNot { it.volumeId == volumeId })
    }

    /**
     * Move [arcId] to [newPos1Based] (1-based) within its own volume: arcs before the target keep
     * their position, arcs at/after the target shift back by one. Reuses the volume's existing order
     * slots so other volumes are untouched.
     */
    fun moveArcToPosition(projectId: String, arcId: String, newPos1Based: Int) {
        val all = repo.plotArcs(projectId)
        val arc = all.firstOrNull { it.id == arcId } ?: return
        val volArcs = all.filter { it.volumeId == arc.volumeId }.sortedBy { it.order }.toMutableList()
        val curIdx = volArcs.indexOfFirst { it.id == arcId }
        if (curIdx < 0) return
        val target = newPos1Based.coerceIn(1, volArcs.size) - 1
        if (target == curIdx) return
        val orderSlots = volArcs.map { it.order }          // pool of order values for this volume
        val moved = volArcs.removeAt(curIdx)
        volArcs.add(target, moved)
        val byId = volArcs.mapIndexed { i, a -> a.id to a.copy(order = orderSlots[i]) }.toMap()
        repo.setPlotArcs(projectId, all.map { byId[it.id] ?: it })
    }

    /** Move a volume to [newPos1Based] (1-based) among the project's volumes. */
    fun moveVolumeToPosition(projectId: String, volumeId: String, newPos1Based: Int) {
        val vols = repo.volumes(projectId).sortedBy { it.order }.toMutableList()
        val idx = vols.indexOfFirst { it.id == volumeId }
        if (idx < 0) return
        val target = newPos1Based.coerceIn(1, vols.size) - 1
        if (target == idx) return
        val slots = vols.map { it.order }
        val moved = vols.removeAt(idx)
        vols.add(target, moved)
        repo.setVolumes(projectId, vols.mapIndexed { i, v -> v.copy(order = slots[i]) })
    }

    /** Swap a volume with its neighbour (reorder). */
    fun moveVolume(projectId: String, volumeId: String, up: Boolean) {
        val vols = repo.volumes(projectId).sortedBy { it.order }.toMutableList()
        val i = vols.indexOfFirst { it.id == volumeId }
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in vols.indices) return
        val a = vols[i]; val b = vols[j]
        vols[i] = a.copy(order = b.order); vols[j] = b.copy(order = a.order)
        repo.setVolumes(projectId, vols)
    }

    fun cultivationRealms(projectId: String): List<CultivationRealm> = repo.cultivationRealms(projectId)
    fun setCultivationRealms(projectId: String, realms: List<CultivationRealm>) =
        repo.setCultivationRealms(projectId, realms)

    // ── AI orchestration ──────────────────────────────────────────────────

    fun stopGenerating() {
        synchronized(streamingGenerationLock) {
            streamingGenerationRevision++
            ChapterGenerationExecutions.cancel(
                streamingJob,
                GenerationTelemetry.FAILURE_USER_CANCELLED,
            )
            streamingJob = null
            _isGenerating.value = false
            _isChapterGenerating.value = false
        }
    }

    /**
     * Starts one UI streaming generation with an isolated buffer. A monotonically increasing
     * revision prevents a cancelled predecessor from appending to, committing, or clearing the
     * flags of its successor even if the provider delivers one last callback after cancellation.
     */
    private fun claimStreamingGeneration(
        initialText: String,
        chapterGeneration: Boolean = false,
    ): StreamingGenerationTicket = synchronized(streamingGenerationLock) {
            val revision = ++streamingGenerationRevision
            ChapterGenerationExecutions.cancel(
                streamingJob,
                GenerationTelemetry.FAILURE_SUPERSEDED,
            )
            streamingJob = null
            _streamingText.value = initialText
            _isGenerating.value = true
            _isChapterGenerating.value = chapterGeneration
            StreamingGenerationTicket(revision, StringBuilder(initialText))
        }

    private fun launchStreamingGeneration(
        initialText: String,
        chapterGeneration: Boolean = false,
        block: suspend (revision: Long, buffer: StringBuilder) -> Unit,
    ): Job = launchStreamingGeneration(
        ticket = claimStreamingGeneration(initialText, chapterGeneration),
        block = block,
    )

    private fun launchStreamingGeneration(
        ticket: StreamingGenerationTicket,
        block: suspend (revision: Long, buffer: StringBuilder) -> Unit,
    ): Job {
        val job = synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision != ticket.revision) return@synchronized null
            viewModelScope.launchWithFailureBoundary(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY,
                onFailure = { error ->
                    streamingError(
                        ticket.revision,
                        "生成失败：${error.message ?: error::class.simpleName ?: "未知错误"}",
                    )
                },
            ) {
                try {
                    block(ticket.revision, ticket.buffer)
                } finally {
                    synchronized(streamingGenerationLock) {
                        if (streamingGenerationRevision == ticket.revision) {
                            _isGenerating.value = false
                            _isChapterGenerating.value = false
                            streamingJob = null
                        }
                    }
                }
            }.also { streamingJob = it }
        }
        if (job == null) return Job().also { it.cancel() }
        job.start()
        return job
    }

    private fun setStreamingInitialText(ticket: StreamingGenerationTicket, text: String): Boolean =
        synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision != ticket.revision) {
                false
            } else {
                ticket.buffer.clear()
                ticket.buffer.append(text)
                _streamingText.value = text
                true
            }
        }

    private fun abandonStreamingGeneration(ticket: StreamingGenerationTicket, message: String) {
        synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision == ticket.revision) {
                streamingGenerationRevision++
                streamingJob = null
                _isGenerating.value = false
                _isChapterGenerating.value = false
                _statusMessage.value = message
            }
        }
    }

    private suspend fun ensureStreamingOwner(revision: Long) {
        currentCoroutineContext().ensureActive()
        val current = synchronized(streamingGenerationLock) { streamingGenerationRevision }
        if (current != revision) throw CancellationException("Streaming generation was superseded")
    }

    private suspend fun appendStreamingText(
        revision: Long,
        buffer: StringBuilder,
        text: String,
    ) {
        ensureStreamingOwner(revision)
        synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision != revision) {
                throw CancellationException("Streaming generation was superseded")
            }
            buffer.append(text)
            _streamingText.value = buffer.toString()
        }
    }

    private fun streamingError(revision: Long, message: String) {
        synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision == revision) _statusMessage.value = message
        }
    }

    /**
     * Linearizes the decision to commit against stop/new-generation token claims, then performs
     * the potentially heavy repository/snapshot write without holding the UI-facing monitor.
     * Once a revision enters COMMITTING, Stop may hide/cancel the job but does not roll back the
     * already-authorized atomic write.
     */
    private fun commitIfStreamingOwner(revision: Long, commit: () -> Boolean): Boolean {
        val authorized = synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision != revision) {
                false
            } else {
                streamingCommittingRevisions.add(revision)
            }
        }
        if (!authorized) return false
        return try {
            commit()
        } finally {
            synchronized(streamingGenerationLock) {
                streamingCommittingRevisions.remove(revision)
            }
        }
    }

    fun generateOutline(
        projectId: String,
        title: String,
        genre: String,
        description: String,
        chapterCount: Int?,
        requirements: String?,
        appendToExisting: Boolean,
        isLong: Boolean = false,
        // Continuation mode: keeps every original prompt block intact (world/timeline/arcs/chars/
        // realm context/requirements/structure spec) AND additionally tells the model to pick up
        // from the existing outline's tail instead of rewriting from scratch.
        continueFromExisting: Boolean = false,
        onComplete: (String) -> Unit = {},
    ) {
        val cfg = repo.textModelForRole(projectId, "planning")
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
        val ticket = claimStreamingGeneration("")
        val currentOutline = repo.outline(projectId)
        // Continuations keep the original text in the isolated buffer, never in a shared mutable
        // buffer that a cancelled predecessor could still append to.
        val prefix = when {
            continueFromExisting && currentOutline.isNotBlank() -> "$currentOutline\n\n"
            appendToExisting -> currentOutline.let { if (it.isEmpty()) "" else "$it\n\n" }
            else -> ""
        }
        if (!setStreamingInitialText(ticket, prefix)) return
        val lang = _uiLanguage.value
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang).ifBlank { null }
        val existingWorld = repo.worldSetting(projectId).ifBlank { null }
        val existingTimeline = repo.timeline(projectId).ifBlank { null }
        val existingVolumes: String? = if (isLong) {
            repo.volumes(projectId).sortedBy { it.order }.takeIf { it.isNotEmpty() }
                ?.mapIndexed { i, v ->
                    val label = if (lang == "en") "Volume ${i + 1}: ${v.name}" else "副本${i + 1}：${v.name}"
                    if (v.description.isNotBlank()) "$label\n  ${v.description}" else label
                }?.joinToString("\n")
        } else null
        val existingCharsInfo = buildCharactersInfo(projectId)
        val messages = listOf(
            ChatMessage("system", Prompts.outlineSystem(lang, isLong)),
            ChatMessage(
                "user",
                Prompts.outlineUser(
                    title, genre, description, chapterCount, requirements, lang,
                    isLong = isLong,
                    realmContext = realmCtx,
                    existingWorld = existingWorld,
                    existingTimeline = existingTimeline,
                    existingVolumes = existingVolumes,
                    charactersInfo = existingCharsInfo,
                    isContinuation = continueFromExisting && currentOutline.isNotBlank(),
                    currentOutline = currentOutline.takeIf { it.isNotBlank() },
                ),
            ),
        )
        launchStreamingGeneration(ticket) { revision, buffer ->
            val generated = LongOutputRecovery.collect(
                config = cfg,
                initialMessages = messages,
                format = LongOutputFormat.MARKDOWN,
                taskLabel = if (lang == "en") "Outline generation" else "大纲生成",
                language = lang,
                request = { requestMessages -> ai.streamChat(cfg, requestMessages) },
                onCumulative = { cumulative ->
                    replaceStreamingText(revision, buffer, prefix + cumulative)
                },
            )
            ensureStreamingOwner(revision)
            val final = (prefix + generated).trim()
            if (final.isNotEmpty()) {
                val committed = commitIfStreamingOwner(revision) {
                    repo.setOutlineIfUnchanged(projectId, currentOutline, final)
                }
                if (committed) {
                    onComplete(final)
                } else {
                    streamingError(
                        revision,
                        "内容冲突：大纲在 AI 生成期间已被修改或项目已删除，已保留新内容",
                    )
                }
            }
        }
    }

    fun generateChapter(
        projectId: String,
        chapter: Chapter,
        currentContent: String? = null,  // full existing final text; null → fresh generation
        stepwise: Boolean = false,       // logic-chain path: blueprint → per-beat prose (off = legacy one-shot)
        draftReference: String? = null,  // user's chapter draft (from the Draft tab), injected as a strong reference
    ) {
        val workspaceMode = repo.writingWorkspace(projectId).mode
        startUnifiedChapter(projectId, chapter,
            if (stepwise || workspaceMode == "scene") WritingMode.SCENES
            else if (workspaceMode == "polish") WritingMode.POLISHED else WritingMode.FAST,
            continuation = currentContent, draftReference = draftReference)
        return
    }

    /** Replace the visible cumulative preview (used by multi-request continuation/batch flows). */
    private suspend fun replaceStreamingText(
        revision: Long,
        buffer: StringBuilder,
        text: String,
    ) {
        ensureStreamingOwner(revision)
        synchronized(streamingGenerationLock) {
            if (streamingGenerationRevision != revision) {
                throw CancellationException("Streaming generation was superseded")
            }
            buffer.clear()
            buffer.append(text)
            _streamingText.value = text
        }
    }


    /** AI fill: generates a 3-line title/goal/conflict suggestion for the given chapter index. */
    fun generateChapterOutline(
        projectId: String,
        chapterOrderIndex: Int,
        userRequirements: String,
    ) {
        val cfg = repo.textModelForRole(projectId, "planning")
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
        val lang = _uiLanguage.value
        val arcContext = buildArcContext(repo.plotArcs(projectId), lang)
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val worldSettingRaw = repo.worldSetting(projectId).ifBlank { repo.outline(projectId) }
        val worldSetting = if (realmCtx.isNotBlank()) "$worldSettingRaw\n\n$realmCtx" else worldSettingRaw
        val charactersInfo = buildCharactersInfo(projectId)
        val prevSummary = buildPreviousChapterSummary(projectId, chapterOrderIndex)
        val messages = listOf(
            ChatMessage("system", Prompts.chapterOutlineSystem(lang)),
            ChatMessage("user", Prompts.chapterOutlineUser(
                previousSummary = prevSummary,
                arcContext = arcContext,
                chapterIndex = chapterOrderIndex,
                worldSetting = worldSetting.takeIf { it.isNotBlank() },
                charactersInfo = charactersInfo,
                userRequirements = userRequirements,
                language = lang,
            )),
        )
        val revision = ++aiFillRevision
        aiFillJob?.cancel()
        _aiFillText.value = ""
        _isAiFilling.value = true
        val job = viewModelScope.launchWithFailureBoundary(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
            onFailure = { error ->
                if (aiFillRevision == revision) {
                    // A truncated three-line response must never become an applyable suggestion.
                    _aiFillText.value = ""
                    _statusMessage.value =
                        "AI助填失败：${error.message ?: error::class.simpleName ?: "未知错误"}"
                }
            },
        ) {
            try {
                ai.streamChat(cfg, messages).collectCompleted(
                    onDelta = { text ->
                        currentCoroutineContext().ensureActive()
                        if (aiFillRevision != revision) {
                            throw CancellationException("AI fill request was superseded")
                        }
                        _aiFillText.value = _aiFillText.value + text
                    },
                    onFailure = { message ->
                        if (aiFillRevision == revision) {
                            _aiFillText.value = ""
                            _statusMessage.value = "AI助填失败：$message"
                        }
                    },
                )
            } finally {
                if (aiFillRevision == revision) {
                    _isAiFilling.value = false
                    aiFillJob = null
                }
            }
        }
        aiFillJob = job
        job.start()
    }

    fun stopAiFill() {
        aiFillRevision++
        aiFillJob?.cancel()
        aiFillJob = null
        _isAiFilling.value = false
    }
    fun clearAiFill() { _aiFillText.value = "" }

    /** Generates a chapter-by-chapter plan for [arcId] and saves it as arc.miniOutline. */
    fun generateArcMiniOutline(projectId: String, arcId: String) {
        val cfg = repo.textModelForRole(projectId, "planning")
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
        val ticket = claimStreamingGeneration("")
        val lang = _uiLanguage.value
        val project = repo.project(projectId)
        val arc = repo.plotArcs(projectId).find { it.id == arcId }
        if (project == null || arc == null) {
            abandonStreamingGeneration(ticket, "项目或目标弧线已不存在")
            return
        }
        val planningOrder = (repo.chapters(projectId).maxOfOrNull { it.order_index } ?: 0) + 1
        val promptSources = captureStablePromptSources(
            projectId,
            PromptSourceScope.PLANNING,
            planningOrder,
        )
        if (promptSources == null) {
            abandonStreamingGeneration(ticket, "弧线规划上下文正在变化或项目已删除，请稍后重试")
            return
        }
        launchStreamingGeneration(ticket) { revision, buffer ->
            val chapters = repo.chapters(projectId)
            val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
            val charactersInfo = buildCharactersInfo(projectId)
            val startChapterNumber = (chapters.maxOfOrNull { it.order_index } ?: 0) + 1
            val prevCtx = chapters.sortedByDescending { it.order_index }.take(5).reversed()
                .joinToString("\n") { c -> "第${c.order_index}章《${c.title}》：${c.outline_goal ?: "(无目标)"}" }
                // Planning sources not already in the prompt's dedicated slots: 境界体系、所属副本、
                // 全局弧线进度、角色成长、容器知识库（手动+追踪）、设置页知识库索引检索 — so the manual
                // planner reads the same full context the agent does and won't plan blind.
                val supplemental = buildString {
                    if (realmCtx.isNotBlank()) { appendLine(realmCtx); appendLine() }
                    arc.volumeId?.let { vid -> repo.volumes(projectId).firstOrNull { it.id == vid } }?.let { vol ->
                        appendLine("【所属副本】${vol.name}" + (vol.description.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")); appendLine()
                        // Per-volume realm ceiling: the mini-outline sets the whole arc's breakthrough pacing.
                        buildVolumeRealmConstraint(vol.realmPlan, vol.name, lang, phase = "plan").takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
                    }
                    buildArcContext(repo.plotArcs(projectId), lang).takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
                    buildCharacterGrowthGuidance(projectId, lang)?.let { appendLine(it); appendLine() }
                    buildContainerKnowledgeForPlanning(projectId, lang)?.let { appendLine(it); appendLine() }
                    buildKbIndexRetrieval(projectId, "${arc.title}\n${arc.summary}", lang)?.let { appendLine(it) }
                }.trim()
                val projectOutline = buildString {
                    append(repo.outline(projectId))
                    if (supplemental.isNotBlank()) { append("\n\n"); append(supplemental) }
                }
                val messages = listOf(
                    ChatMessage("system", Prompts.arcMiniOutlineSystem(lang)),
                    ChatMessage("user", Prompts.arcMiniOutlineUser(
                        projectTitle = promptSources.project.title,
                        projectOutline = projectOutline,
                        arcTitle = arc.title,
                        arcSummary = arc.summary,
                        chapterCount = arc.chapterCount.takeIf { it > 0 } ?: 8,
                        startChapterNumber = startChapterNumber,
                        prevChaptersContext = prevCtx,
                        charactersInfo = charactersInfo,
                        language = lang,
                    )),
                )
            if (!promptSourcesStillCurrent(
                    projectId,
                    PromptSourceScope.PLANNING,
                    promptSources,
                    planningOrder,
                ) || repo.plotArcs(projectId).firstOrNull { it.id == arcId } != arc
            ) {
                streamingError(revision, "内容冲突：弧线规划上下文在 AI 生成前已被修改，请重新生成")
                return@launchStreamingGeneration
            }
            ai.streamChat(cfg, messages).collectCompleted(
                onDelta = { text -> appendStreamingText(revision, buffer, text) },
                onFailure = { message -> streamingError(revision, "弧线计划生成失败：$message") },
            )
            ensureStreamingOwner(revision)
            val result = buffer.toString()
            if (result.isNotEmpty()) {
                val committed = commitIfStreamingOwner(revision) {
                    promptSourcesStillCurrent(
                        projectId,
                        PromptSourceScope.PLANNING,
                        promptSources,
                        planningOrder,
                    ) && repo.updatePlotArcIfUnchanged(projectId, arc) {
                            it.copy(miniOutline = result, builtChapterIds = emptyList())
                        }
                }
                if (!committed) {
                    streamingError(
                        revision,
                        "内容冲突：弧线或完整规划上下文在 AI 规划期间已被修改，已保留新内容",
                    )
                }
            }
        }
    }

    /** Creates chapters from parsed arc mini-outline rows and updates arc.builtChapterIds. */
    fun addChaptersBatch(
        projectId: String,
        arcId: String?,
        items: List<Pair<String, String?>>,   // (title, goal?)
    ): List<Chapter> {
        val expectedArc = arcId?.let { id -> repo.plotArcs(projectId).firstOrNull { it.id == id } }
        if (arcId != null && expectedArc == null) return emptyList()
        return addChaptersBatchAgainst(projectId, expectedArc, items)
    }

    private fun addChaptersBatchAgainst(
        projectId: String,
        expectedArc: PlotArc?,
        items: List<Pair<String, String?>>,
        expectedChapters: List<Chapter>? = null,
    ): List<Chapter> {
        if (repo.project(projectId) == null) return emptyList()
        val ts = System.currentTimeMillis()
        val now = nowIso()
        val candidates = items.mapIndexed { i, (title, goal) ->
            Chapter(
                id = "c-$ts-$i",
                project_id = projectId,
                title = title,
                order_index = 0,
                outline_goal = goal,
                created_at = now,
                updated_at = now,
                arcId = expectedArc?.id,
            )
        }
        return repo.appendChaptersToArcIfUnchanged(
            projectId,
            expectedArc,
            candidates,
            expectedChapters = expectedChapters,
        )
    }

    /** Convenience wrapper so LongNovelScreen can patch a single arc without replacing the whole list. */
    fun updatePlotArc(projectId: String, arcId: String, patch: (PlotArc) -> PlotArc) {
        repo.setPlotArcs(projectId, repo.plotArcs(projectId).map { if (it.id == arcId) patch(it) else it })
    }

    /** Parses arc mini-outline text into (title, goal?) pairs. */
    fun parseArcMiniOutline(text: String): List<Pair<String, String?>> {
        val regex = Regex("""^(第\d+章|Chapter \d+)[：:]\s*(.+?)(?:\s*[—–-]+\s*(.+))?$""")
        return text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val match = regex.find(line.trim()) ?: return@mapNotNull null
            val title = match.groupValues[2].trim()
            val goal = match.groupValues[3].trim().takeIf { it.isNotBlank() }
            if (title.isBlank()) null else title to goal
        }
    }

    suspend fun reviseSelection(text: String, goals: String?): String? {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return null
        }
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            runSuspendCatching {
                ai.chat(
                    cfg,
                    listOf(
                        ChatMessage("system", Prompts.revisionSystem(lang)),
                        ChatMessage("user", Prompts.revisionUser(text, goals, lang)),
                    ),
                )
            }.getOrNull()?.trim()
        }
    }

    suspend fun generateCharacterAppearance(
        name: String,
        role: String?,
        personality: String?,
        background: String?,
        motivation: String?,
        style: String?,
    ): Pair<String, String>? {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) return null
        val lang = _uiLanguage.value
        val reply = withContext(Dispatchers.IO) {
            runSuspendCatching {
                ai.chat(
                    cfg,
                    listOf(
                        ChatMessage("system", Prompts.characterAppearanceSystem(lang)),
                        ChatMessage("user", Prompts.characterAppearanceUser(
                            name, role, personality, background, motivation, style, lang
                        )),
                    ),
                )
            }.getOrNull()
        } ?: return null
        return parseAppearanceJson(reply)
    }

    /**
     * Quick-generate one full character from a free-form user brief. The brief is grounded in the
     * project's outline + cultivation-realm system so the AI returns a character that fits the
     * novel (not a generic one). Returns a [Character] with fields filled and a blank id (the caller
     * merges it into the in-progress character, preserving id/portrait). Null on failure.
     */
    suspend fun generateCharacterFromBrief(projectId: String, brief: String): Character? {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid() || brief.isBlank()) return null
        val lang = _uiLanguage.value
        val realms = repo.cultivationRealms(projectId)
        val context = buildString {
            val outline = repo.outline(projectId)
            if (outline.isNotBlank()) {
                append(if (lang == "en") "[Outline]\n" else "【大纲】\n"); append(outline)
            }
            val world = repo.worldSetting(projectId)
            if (world.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(if (lang == "en") "[World setting]\n" else "【世界观】\n"); append(world)
            }
            val realmCtx = buildRealmSystemContext(realms, lang)
            if (realmCtx.isNotEmpty()) { if (isNotEmpty()) append("\n\n"); append(realmCtx) }
        }
        val reply = withContext(Dispatchers.IO) {
            runSuspendCatching {
                ai.chat(
                    cfg,
                    listOf(
                        ChatMessage("system", Prompts.characterFromBriefSystem(lang)),
                        ChatMessage("user", Prompts.characterFromBriefUser(brief.trim(), context, lang)),
                    ),
                )
            }.getOrNull()
        } ?: return null
        return parseCharacterObject(reply, realms)
    }

    /** Parse a single JSON character object from AI text, mapping the realm name back to an id. */
    private fun parseCharacterObject(text: String, realms: List<CultivationRealm>): Character? = runCatching {
        val stripped = text.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(stripped.substring(start, end + 1)).jsonObject
        fun str(key: String) = obj[key]?.let { it.toString().trim('"') }
            ?.takeIf { it.isNotBlank() && it != "null" } ?: ""
        val name = str("name")
        if (name.isBlank()) return null
        val isProta = obj["isProtagonist"]?.toString()?.trim('"')?.equals("true", ignoreCase = true) ?: false

        // Map the AI's realm name back onto a realm/sub-realm id (best-effort exact match).
        var realmId: String? = null
        var subId: String? = null
        val realmName = str("currentRealm")
        if (realmName.isNotBlank()) {
            realms.firstOrNull { it.name == realmName }?.let { realmId = it.id }
            if (realmId == null) {
                realms.forEach { r ->
                    r.subRealms?.firstOrNull { it.name == realmName }?.let { sub ->
                        realmId = r.id; subId = sub.id
                    }
                }
            }
        }

        Character(
            id = "",
            name = name,
            gender = str("gender"),
            role = str("role"),
            personality = str("personality"),
            motivation = str("motivation"),
            background = str("background"),
            appearance = str("appearance"),
            isProtagonist = isProta,
            currentRealmId = realmId,
            currentSubRealmId = subId,
        )
    }.getOrNull()

    suspend fun generatePortraitImage(prompt: String, width: Int = 768, height: Int = 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            runSuspendCatching {
                generateImageBytes(prompt, width, height)
            }.getOrNull()
        }

    // ── Chapter illustrations (PC parity) ────────────────────────────────────────────────

    fun chapterIllustrations(chapterId: String): List<Illustration> =
        repo.chapterIllustrations(chapterId)

    fun deleteIllustration(chapterId: String, illustrationId: String) =
        repo.deleteIllustration(chapterId, illustrationId)

    fun updateIllustration(chapterId: String, illustration: Illustration) =
        repo.upsertIllustration(chapterId, illustration)

    /**
     * Generate a single illustration for the given chapter:
     *   1. Ask the text model for an English image prompt that summarises [paragraphText] (PC's
     *      `generate_illustration_prompt` Tauri command — we use chat completion instead).
     *   2. Hand that prompt to the image provider (Pollinations).
     *   3. Encode the returned bytes as Base64 and persist as an [Illustration] anchored to
     *      [anchorIndex] (1-based paragraph index in the chapter body).
     *
     * The whole flow is fire-and-forget on the VM coroutine scope; callers get an `onDone`
     * callback with the new illustration (or `null` if any step failed) so they can refresh UI
     * state without blocking the editor.
     */
    fun generateIllustration(
        projectId: String,
        chapterId: String,
        paragraphText: String,
        anchorIndex: Int,
        paragraphIndices: List<Int>,
        style: String?,
        model: String,
        width: Int,
        height: Int,
        // Optional characters whose appearances are woven into the prompt for visual consistency
        // (pre-formatted "- name：appearance" lines; null/blank = no constraint).
        charactersInfo: String? = null,
        onDone: (Illustration?, errorMessage: String?) -> Unit = { _, _ -> },
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            onDone(null, "请先在「设置」中配置可用的文本模型 / Configure a text model first.")
            return
        }
        if (paragraphText.isBlank()) {
            onDone(null, "请先勾选需要生成插图的段落 / Select paragraphs before generating.")
            return
        }
        val source = repo.captureChapterSource(projectId, chapterId)
        if (source == null) {
            onDone(null, "项目或章节已不存在，无法生成插图。")
            return
        }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val prompt = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.illustrationPromptSystem(lang)),
                    ChatMessage("user", Prompts.illustrationPromptUser(
                        paragraphText, style, lang, charactersInfo)),
                ))
            }.getOrElse {
                onDone(null, "生成提示词失败：${it.message}"); return@launch
            }.trim().lines().firstOrNull { it.isNotBlank() }.orEmpty()

            if (prompt.isBlank()) {
                onDone(null, "模型未返回可用的图像提示词。")
                return@launch
            }
            val bytes = runSuspendCatching {
                generateImageBytes(prompt = prompt, width = width, height = height, model = model)
            }.getOrElse {
                onDone(null, "图像生成失败：${it.message}"); return@launch
            }
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val illustration = Illustration(
                id = "ill-${System.currentTimeMillis()}",
                anchorIndex = anchorIndex,
                paragraphIndices = paragraphIndices,
                prompt = prompt,
                imageBase64 = b64,
                createdAt = nowIso(),
            )
            currentCoroutineContext().ensureActive()
            if (repo.upsertIllustrationIfChapterSourceCurrent(projectId, source.source, illustration)) {
                onDone(illustration, null)
            } else {
                onDone(null, "章节在插图生成期间已被修改或删除，过期插图未保存。")
            }
        }
    }

    // ── Chapter promo (PC parity — "推文" / chapter banner) ──────────────────────────────────

    fun getChapterPromo(chapterId: String): ChapterPromo? = repo.getChapterPromo(chapterId)

    fun generateChapterPromo(
        projectId: String,
        chapterId: String,
        chapterTitle: String,
        chapterContent: String,
        style: String?,
        model: String,
        width: Int,
        height: Int,
        onDone: (ChapterPromo?, errorMessage: String?) -> Unit,
    ): Job? {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            onDone(null, "请先在「设置」中配置可用的文本模型 / Configure a text model first.")
            return null
        }
        if (chapterContent.length < 100) {
            onDone(null, "章节内容太少（至少需要100字）/ Chapter content too short (need 100+ chars).")
            return null
        }
        val source = repo.captureChapterMediaSource(projectId, chapterId)
        if (
            source == null ||
            source.chapter.source.title != chapterTitle ||
            source.chapter.effectiveText != chapterContent
        ) {
            onDone(null, "章节标题或正文已变化，请保存并刷新后重试。")
            return null
        }
        val lang = _uiLanguage.value
        return viewModelScope.launch(Dispatchers.IO) {
            val json = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.chapterPromoSystem(lang)),
                    ChatMessage("user", Prompts.chapterPromoUser(chapterTitle, chapterContent, style, lang)),
                ))
            }.getOrElse { onDone(null, "生成推文数据失败：${it.message}"); return@launch }.trim()
            val cleaned = json.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val el = runCatching { Json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
            val imagePrompt = (el?.get("image_prompt") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val summary = (el?.get("summary") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (imagePrompt.isBlank()) { onDone(null, "模型未返回可用的图像提示词。"); return@launch }
            val bytes = runSuspendCatching {
                generateImageBytes(prompt = imagePrompt, width = width, height = height, model = model)
            }.getOrElse { onDone(null, "图像生成失败：${it.message}"); return@launch }
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val promo = ChapterPromo(imagePrompt = imagePrompt, summary = summary, imageBase64 = b64)
            currentCoroutineContext().ensureActive()
            if (
                repo.setChapterPromoIfSourceCurrent(
                    projectId,
                    source.chapter.source,
                    source.promo,
                    promo,
                )
            ) {
                onDone(promo, null)
            } else {
                onDone(null, "章节在推文生成期间已被修改或删除，过期推文未保存。")
            }
        }
    }

    // ── Project cover images (PC parity — "封面") ────────────────────────────────────────────

    fun getCoverImages(projectId: String): List<CoverImageItem> = repo.getCoverImages(projectId)

    fun generateProjectCover(
        projectId: String,
        style: String?,
        model: String,
        width: Int,
        height: Int,
        existingCount: Int,
        // Optional characters the cover's figures should match (pre-formatted lines; null = none).
        charactersInfo: String? = null,
        onDone: (CoverImageItem?, errorMessage: String?) -> Unit,
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            onDone(null, "请先在「设置」中配置可用的文本模型 / Configure a text model first.")
            return
        }
        val proj = repo.project(projectId) ?: run { onDone(null, "Project not found"); return }
        val lang = _uiLanguage.value
        val outline = repo.outline(projectId)
        viewModelScope.launch(Dispatchers.IO) {
            val prompt = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.projectCoverSystem(lang)),
                    ChatMessage("user", Prompts.projectCoverUser(
                        proj.title, proj.description, outline.ifBlank { null }, style, lang,
                        charactersInfo)),
                ))
            }.getOrElse { onDone(null, "生成封面提示词失败：${it.message}"); return@launch }
                .trim().lines().firstOrNull { it.isNotBlank() }.orEmpty()
            if (prompt.isBlank()) { onDone(null, "模型未返回可用的图像提示词。"); return@launch }
            val bytes = runSuspendCatching {
                generateImageBytes(prompt = prompt, width = width, height = height, model = model)
            }.getOrElse { onDone(null, "图像生成失败：${it.message}"); return@launch }
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val labelPrefix = if (lang == "en") "Cover" else "封面"
            val item = CoverImageItem(
                id = "cover-${System.currentTimeMillis()}",
                name = "$labelPrefix ${existingCount + 1}",
                imageBase64 = b64,
                prompt = prompt,
                createdAt = nowIso(),
                config = CoverImageConfig(model = model, style = style.orEmpty(), width = width, height = height),
            )
            val committed = repo.upsertCoverImage(projectId, item, makeDefault = false)
            if (!committed) {
                onDone(null, "项目已删除，生成的封面未保存。")
                return@launch
            }
            onDone(item, null)
        }
    }

    fun deleteProjectCover(projectId: String, coverId: String) {
        val existing = repo.getCoverImages(projectId)
        val newList = existing.filterNot { it.id == coverId }
        val curDefaultId = repo.project(projectId)?.default_cover_id
        val newDefaultId = if (curDefaultId == coverId) newList.firstOrNull()?.id else curDefaultId
        repo.setCoverImages(projectId, newList, newDefaultId)
    }

    fun setDefaultCover(projectId: String, coverId: String) {
        repo.setCoverImages(projectId, repo.getCoverImages(projectId), coverId)
    }

    suspend fun generatePlotArc(
        projectId: String,
        userIdea: String,
        targetChapterCount: Int?,
    ): PlotArc? {
        val cfg = repo.textModelForRole(projectId, "planning")
        if (!cfg.isValid()) return null
        val lang = _uiLanguage.value
        val project = repo.project(projectId) ?: return null
        val promptSources = captureStablePromptSources(projectId, PromptSourceScope.PLOT_ARC)
            ?: return null
        val baselineArcs = repo.plotArcs(projectId)
        val existingArcs = baselineArcs.joinToString("\n") { "- ${it.title} (${it.status})" }
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val charSummary = repo.characters(projectId).joinToString("\n") { "- ${it.name}: ${it.role}" }
        val bookOutline = repo.outline(projectId).ifBlank { null }
        if (!promptSourcesStillCurrent(projectId, PromptSourceScope.PLOT_ARC, promptSources) ||
            repo.plotArcs(projectId) != baselineArcs
        ) {
            contentConflict("剧情弧线生成上下文")
        }
        val reply = withContext(Dispatchers.IO) {
            runSuspendCatching {
                ai.chat(
                    cfg,
                    listOf(
                        ChatMessage("system", Prompts.plotArcSystem(lang)),
                        ChatMessage("user", Prompts.plotArcUser(
                            userIdea = userIdea,
                            bookTitle = promptSources.project.title,
                            bookDescription = promptSources.project.description,
                            bookOutline = bookOutline,
                            existingArcsSummary = existingArcs.ifBlank { null },
                            realmSystemContext = realmCtx.ifBlank { null },
                            charactersSummary = charSummary.ifBlank { null },
                            targetChapterCount = targetChapterCount,
                            language = lang,
                        )),
                    ),
                )
            }.getOrNull()
        } ?: return null
        val parsed = parsePlotArcJson(reply) ?: return null
        currentCoroutineContext().ensureActive()
        val candidate = PlotArc(
            id = "arc-${System.currentTimeMillis()}",
            title = parsed.title,
            summary = parsed.summary,
            order = 0,
            status = "upcoming",
            chapterCount = parsed.chapterCount,
            miniOutline = parsed.miniOutline,
        )
        if (!promptSourcesStillCurrent(projectId, PromptSourceScope.PLOT_ARC, promptSources) ||
            repo.plotArcs(projectId) != baselineArcs
        ) {
            contentConflict("剧情弧线生成上下文或弧线集合")
        }
        val saved = repo.appendPlotArcsIfTargetExists(
            projectId,
            listOf(candidate),
            expectedArcs = baselineArcs,
        ).singleOrNull() ?: contentConflict("剧情弧线生成上下文或弧线集合")
        return saved
    }

    // ── 副本 / 弧线 AI generation ─────────────────────────────────────────────

    /** Generate [count] volumes from the outline + realm system + influencing containers (no arcs).
     *  [requirements] is the user's free-form instruction (e.g. what the first/later volumes cover). */
    fun generateVolumes(projectId: String, count: Int, requirements: String? = null, onDone: (Int) -> Unit = {}): Job? {
        val cfg = repo.textModelForRole(projectId, "planning")
        if (!cfg.isValid()) { _statusMessage.value = "请先在「设置」中配置可用的文本模型"; onDone(0); return null }
        val lang = _uiLanguage.value
        return viewModelScope.launch(Dispatchers.IO) {
            val promptSources = captureStablePromptSources(projectId, PromptSourceScope.VOLUME_COLLECTION)
            if (promptSources == null) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "副本生成上下文正在变化或项目已删除，请稍后重试"
                    onDone(0)
                }
                return@launch
            }
            val baselineVolumes = repo.volumes(projectId)
            val context = buildString {
                repo.outline(projectId).takeIf { it.isNotBlank() }?.let {
                    append(if (lang == "en") "[Outline]\n" else "【大纲】\n"); append(it.take(4000))
                }
                buildRealmSystemContext(repo.cultivationRealms(projectId), lang).takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
                containerGuidanceFor(projectId, lang) { it.affectsVolumeGeneration }.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
            }
            val existing = baselineVolumes.sortedBy { it.order }.joinToString("\n") { "- ${it.name}" }.ifBlank { null }
            if (!promptSourcesStillCurrent(projectId, PromptSourceScope.VOLUME_COLLECTION, promptSources) ||
                repo.volumes(projectId) != baselineVolumes
            ) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "内容冲突：副本生成上下文或副本集合已修改，请重新生成"
                    onDone(0)
                }
                return@launch
            }
            val reply = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.volumePlanSystem(lang)),
                    ChatMessage("user", Prompts.volumePlanUser(count, context, existing, requirements?.trim()?.ifBlank { null }, lang)),
                ))
            }.getOrNull()
            val parsed = reply?.let { parseVolumeArray(it) } ?: emptyList()
            if (parsed.isEmpty()) { withContext(Dispatchers.Main) { _statusMessage.value = "副本生成失败"; onDone(0) }; return@launch }
            currentCoroutineContext().ensureActive()
            if (!promptSourcesStillCurrent(projectId, PromptSourceScope.VOLUME_COLLECTION, promptSources) ||
                repo.volumes(projectId) != baselineVolumes
            ) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "内容冲突：副本生成上下文或副本集合在 AI 生成期间已修改，结果未保存"
                    onDone(0)
                }
                return@launch
            }
            val ts = System.currentTimeMillis()
            val candidates = parsed.mapIndexed { i, (name, desc) ->
                Volume(id = "vol-$ts-$i", name = name, description = desc, order = 0, createdAt = nowIso())
            }
            val newVols = repo.appendVolumesIfProjectExists(
                projectId,
                candidates,
                expectedVolumes = baselineVolumes,
            )
            withContext(Dispatchers.Main) {
                if (newVols.isEmpty()) _statusMessage.value = "内容冲突：项目或副本集合已修改，结果未保存"
                else _statusMessage.value = "已生成 ${newVols.size} 个副本"
                onDone(newVols.size)
            }
        }
    }

    /** Generate [count] plot arcs inside [volumeId] (no chapter planning). [requirements] is the
     *  user's free-form instruction (e.g. what specific arcs should cover). */
    fun generateArcsForVolume(projectId: String, volumeId: String, count: Int, requirements: String? = null, onDone: (Int) -> Unit = {}): Job? {
        val cfg = repo.textModelForRole(projectId, "planning")
        if (!cfg.isValid()) { _statusMessage.value = "请先在「设置」中配置可用的文本模型"; onDone(0); return null }
        val lang = _uiLanguage.value
        val volume = repo.volumes(projectId).firstOrNull { it.id == volumeId } ?: run { onDone(0); return null }
        return viewModelScope.launch(Dispatchers.IO) {
            val promptSources = captureStablePromptSources(projectId, PromptSourceScope.ARC_COLLECTION)
            if (promptSources == null) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "弧线生成上下文正在变化或项目已删除，请稍后重试"
                    onDone(0)
                }
                return@launch
            }
            val baselineArcs = repo.plotArcs(projectId)
            val context = buildString {
                repo.outline(projectId).takeIf { it.isNotBlank() }?.let {
                    append(if (lang == "en") "[Outline]\n" else "【大纲】\n"); append(it.take(3000))
                }
                buildRealmSystemContext(repo.cultivationRealms(projectId), lang).takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
                // Per-volume realm ceiling: keep the generated arcs' breakthrough pacing within this volume's limit.
                buildVolumeRealmConstraint(volume.realmPlan, volume.name, lang, phase = "plan").takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
                containerGuidanceFor(projectId, lang) { it.affectsArcGeneration }.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
            }
            val existingArcs = baselineArcs.filter { it.volumeId == volumeId }
                .joinToString("\n") { "- ${it.title}" }.ifBlank { null }
            if (!promptSourcesStillCurrent(projectId, PromptSourceScope.ARC_COLLECTION, promptSources) ||
                repo.volumes(projectId).firstOrNull { it.id == volumeId } != volume ||
                repo.plotArcs(projectId) != baselineArcs
            ) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "内容冲突：弧线生成上下文、目标副本或弧线集合已修改，请重新生成"
                    onDone(0)
                }
                return@launch
            }
            val reply = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.arcsForVolumeSystem(lang)),
                    ChatMessage("user", Prompts.arcsForVolumeUser(count, volume.name, volume.description.ifBlank { null }, context, existingArcs, requirements?.trim()?.ifBlank { null }, lang)),
                ))
            }.getOrNull()
            val parsed = reply?.let { parseArcArray(it) } ?: emptyList()
            if (parsed.isEmpty()) { withContext(Dispatchers.Main) { _statusMessage.value = "弧线生成失败"; onDone(0) }; return@launch }
            currentCoroutineContext().ensureActive()
            if (!promptSourcesStillCurrent(projectId, PromptSourceScope.ARC_COLLECTION, promptSources) ||
                repo.volumes(projectId).firstOrNull { it.id == volumeId } != volume ||
                repo.plotArcs(projectId) != baselineArcs
            ) {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "内容冲突：弧线生成上下文、目标副本或弧线集合在 AI 生成期间已修改，结果未保存"
                    onDone(0)
                }
                return@launch
            }
            val ts = System.currentTimeMillis()
            val candidates = parsed.mapIndexed { i, p ->
                PlotArc(id = "arc-$ts-$i", title = p.title, summary = p.summary, order = 0,
                    status = "upcoming", chapterCount = p.chapterCount, volumeId = volumeId)
            }
            val newArcs = repo.appendPlotArcsIfTargetExists(
                projectId,
                candidates,
                expectedVolume = volume,
                expectedArcs = baselineArcs,
            )
            withContext(Dispatchers.Main) {
                if (newArcs.isEmpty()) _statusMessage.value = "内容冲突：目标副本或弧线集合已修改，结果未保存"
                else _statusMessage.value = "已生成 ${newArcs.size} 条弧线"
                onDone(newArcs.size)
            }
        }
    }

    /** Compact "latest values" guidance for the containers matching [predicate] (volume/arc gen). */
    private fun containerGuidanceFor(projectId: String, lang: String, predicate: (Container) -> Boolean): String {
        val containers = repo.containers(projectId).filter(predicate)
        if (containers.isEmpty()) return ""
        val blocks = mutableListOf<String>()
        containers.forEach { c ->
            when (c.type) {
                Container.BY_CHARACTER -> {
                    val lines = repo.characters(projectId).mapNotNull { ch ->
                        val v = repo.containerEntries(projectId, c.id, ch.id).lastOrNull()?.value?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        "${ch.name}：${v.take(200)}"
                    }
                    if (lines.isNotEmpty()) blocks += "《${c.name}》\n" + lines.joinToString("\n")
                }
                Container.BY_CHAPTER -> {
                    val recent = repo.chapters(projectId).sortedBy { it.order_index }.takeLast(3)
                    val lines = recent.mapNotNull { ch ->
                        val v = repo.containerEntries(projectId, c.id, ch.id).lastOrNull()?.value?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        "第${ch.order_index}章：${v.take(150)}"
                    }
                    if (lines.isNotEmpty()) blocks += "《${c.name}》\n" + lines.joinToString("\n")
                }
                else -> {
                    val v = repo.containerEntries(projectId, c.id, Container.SINGLE_BLOCK_KEY).lastOrNull()?.value
                    if (!v.isNullOrBlank()) blocks += "《${c.name}》：${v.take(300)}"
                }
            }
        }
        if (blocks.isEmpty()) return ""
        val header = if (lang == "en") "[Containers — reference state]" else "【资料容器 — 参考状态】"
        return header + "\n" + blocks.joinToString("\n\n")
    }

    private fun parseVolumeArray(raw: String): List<Pair<String, String>> = runCatching {
        val s = raw.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = s.indexOf('['); val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        Json { ignoreUnknownKeys = true }.parseToJsonElement(s.substring(start, end + 1)).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            name to (o["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
        }
    }.getOrDefault(emptyList())

    private fun parseArcArray(raw: String): List<ParsedArc> = runCatching {
        val s = raw.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = s.indexOf('['); val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        Json { ignoreUnknownKeys = true }.parseToJsonElement(s.substring(start, end + 1)).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val summary = (o["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            val cc = (o["chapter_count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 10
            ParsedArc(title, summary, cc, null)
        }
    }.getOrDefault(emptyList())

    // ── Agent AI primitives — blocking (suspend) variants that return results ───
    // These let the AgentController perform AI operations deterministically (await completion +
    // get the result), reusing the same prompts/context builders as the interactive UI flows.

    /** Raw chat call on the active text model — the agent's reasoning step. Throws on network /
     *  HTTP / timeout errors so the caller can retry and surface the real cause. */
    suspend fun agentChat(
        messages: List<ChatMessage>,
        onUsage: (StreamUsage) -> Unit = {},
    ): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
        return withContext(Dispatchers.IO) {
            ai.chat(
                config = cfg,
                messages = messages,
                onUsage = onUsage,
                responseFormat = ChatResponseFormat.JSON_OBJECT,
            )
        }
    }

    fun agentTextModelReady(): Boolean = repo.activeTextModelConfig().isValid()

    fun agentName(): String = repo.agentName()
    fun setAgentName(name: String) = repo.setAgentName(name)
    fun activeTextModelProfileId(): String? = repo.activeTextModelProfileId()

    private fun generationRunWasSuperseded(projectId: String, runId: String): Boolean {
        val stored = repo.getGenerationRun(projectId, runId)
        return stored?.status == GenerationRun.STATUS_CANCELLED &&
            stored.error == GenerationRun.CANCEL_REASON_SUPERSEDED
    }

    private suspend fun generationCancellationCategory(projectId: String, runId: String): String {
        ChapterGenerationExecutions.cancellationCategory(
            runId,
            currentCoroutineContext()[Job],
        )?.let { return it }
        return if (generationRunWasSuperseded(projectId, runId)) {
            GenerationTelemetry.FAILURE_SUPERSEDED
        } else {
            GenerationTelemetry.FAILURE_USER_CANCELLED
        }
    }

    /** Atomically persist a completed candidate before sealing the collector as successful. */
    private fun completeGenerationCandidate(
        active: NovelGenerationEngine.ActiveCandidate,
        body: String,
        telemetry: GenerationTelemetryCollector,
    ): NovelGenerationEngine.Completion? {
        val snapshot = telemetry.completedSnapshot()
        return novelGenerationEngine.complete(active, body, telemetry = snapshot)
            ?.also { telemetry.sealCompleted(snapshot) }
    }

    /** Collect one model stream and attribute its timing/usage to an exact generation run. */
    private suspend fun collectGenerationStream(
        cfg: TextModelConfig,
        messages: List<ChatMessage>,
        telemetry: GenerationTelemetryCollector,
        purpose: String,
        visibleOutput: Boolean = true,
        onDelta: suspend (String) -> Unit,
        onFailure: suspend (String) -> Unit = {},
    ) {
        val preparedMessages = PromptRequestBudgeter.validate(cfg, messages).messages
        val trace = telemetry.beginRequest(purpose, preparedMessages, visibleOutput)
        try {
            ai.streamChat(cfg, preparedMessages, onUsage = trace::onUsage).collectCompleted(
                onDelta = { text ->
                    trace.onContent(text)
                    onDelta(text)
                },
                onFailure = onFailure,
            )
            trace.complete()
        } catch (cancelled: CancellationException) {
            trace.cancel()
            throw cancelled
        } catch (error: Throwable) {
            trace.fail(GenerationTelemetryCollector.failureCategory(error))
            throw error
        }
    }

    /** Collect one non-streaming model call without losing its per-request usage. */
    private suspend fun generationChat(
        cfg: TextModelConfig,
        messages: List<ChatMessage>,
        telemetry: GenerationTelemetryCollector,
        purpose: String,
    ): String {
        val preparedMessages = PromptRequestBudgeter.validate(cfg, messages).messages
        val trace = telemetry.beginRequest(purpose, preparedMessages, visibleOutput = false)
        return try {
            ai.chat(cfg, preparedMessages, onUsage = trace::onUsage).also {
                trace.complete()
            }
        } catch (cancelled: CancellationException) {
            trace.cancel()
            throw cancelled
        } catch (error: Throwable) {
            trace.fail(GenerationTelemetryCollector.failureCategory(error))
            throw error
        }
    }

    /** Collect a streaming completion, reporting the cumulative text via [onDelta]. */
    private suspend fun streamCollect(
        cfg: TextModelConfig,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        telemetry: GenerationTelemetryCollector? = null,
        purpose: String = "agent_stream",
    ): String {
        val sb = StringBuilder()
        val append: suspend (String) -> Unit = { text ->
            sb.append(text)
            onDelta(sb.toString())
        }
        if (telemetry == null) {
            ai.streamChat(cfg, messages).collectCompleted(onDelta = append)
        } else {
            collectGenerationStream(
                cfg = cfg,
                messages = messages,
                telemetry = telemetry,
                purpose = purpose,
                onDelta = append,
            )
        }
        return sb.toString().trim()
    }

    private fun contentConflict(target: String): Nothing =
        throw IllegalStateException("内容冲突：$target 在 AI 处理期间已被修改，已保留用户的新内容")

    suspend fun agentGenerateOutline(projectId: String, onDelta: (String) -> Unit = {}): String? {
        val cfg = repo.textModelForRole(projectId, "planning"); if (!cfg.isValid()) return null
        val promptSources = captureStablePromptSources(projectId, PromptSourceScope.OUTLINE) ?: return null
        val baselineOutline = promptSources.outline.orEmpty()
        val projectSource = promptSources.project
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang).ifBlank { null }
            val messages = listOf(
                ChatMessage("system", Prompts.outlineSystem(lang, isLong = true)),
                ChatMessage("user", Prompts.outlineUser(
                    projectSource.title, projectSource.genre, projectSource.description, null, null, lang,
                    isLong = true, realmContext = realmCtx,
                    existingWorld = repo.worldSetting(projectId).ifBlank { null },
                    existingTimeline = repo.timeline(projectId).ifBlank { null },
                    existingVolumes = null,
                    charactersInfo = buildCharactersInfo(projectId),
                )),
            )
            if (!promptSourcesStillCurrent(projectId, PromptSourceScope.OUTLINE, promptSources)) {
                contentConflict("项目大纲生成上下文")
            }
            val out = LongOutputRecovery.collect(
                config = cfg,
                initialMessages = messages,
                format = LongOutputFormat.MARKDOWN,
                taskLabel = if (lang == "en") "Agent outline generation" else "智能体大纲生成",
                language = lang,
                request = { requestMessages -> ai.streamChat(cfg, requestMessages) },
                onCumulative = onDelta,
            ).trim()
            currentCoroutineContext().ensureActive()
            if (!promptSourcesStillCurrent(projectId, PromptSourceScope.OUTLINE, promptSources) ||
                !repo.setOutlineIfUnchanged(projectId, baselineOutline, out)
            ) {
                contentConflict("项目大纲或生成上下文")
            }
            out
        }
    }

    suspend fun agentGenerateChapterText(
        projectId: String,
        chapterId: String,
        agentEngineMode: String = repo.agentEngine(),
        agentSessionId: String = "",
        agentActionId: String = "",
        onDelta: (String) -> Unit = {},
    ): String? {
        val liveChapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        return withContext(Dispatchers.IO) {
            val source = prepareChapterWriting(projectId, liveChapter)
            val mode = when (repo.writingWorkspace(projectId).mode) {
                "quick" -> WritingMode.FAST; "polish" -> WritingMode.POLISHED; else -> WritingMode.SCENES
            }
            val key = "$projectId/$chapterId"
            val owner = checkNotNull(currentCoroutineContext()[Job])
            check(writingJobs.putIfAbsent(key, owner) == null) { "本章已有任务正在运行" }
            var generationId: String? = null
            try {
                val previousRun = repo.latestGenerationRun(projectId, chapterId)
                val checkpoint = sceneWritingStore.load(projectId, chapterId)
                val resumeId = checkpoint?.runId?.takeIf {
                    agentActionId.isNotBlank() && previousRun?.initiator == GenerationRun.INITIATOR_AGENT &&
                        previousRun.agentActionId == agentActionId && previousRun.agentSessionId == agentSessionId &&
                        previousRun.status in setOf(GenerationRun.STATUS_FAILED, GenerationRun.STATUS_CANCELLED, GenerationRun.STATUS_RUNNING) &&
                        checkpoint.sourceFingerprint == source.sourceFingerprint && checkpoint.mode == mode &&
                        checkpoint.status != WritingStatus.COMPLETED
                }
                chapterWriter.write(source, _uiLanguage.value, mode, GenerationRun.INITIATOR_AGENT,
                    agentEngineMode, agentSessionId, agentActionId,
                    resumeRunId = resumeId,
                    onStarted = { active -> generationId = active.run.id; ChapterGenerationExecutions.replace(projectId, chapterId, active.run.id, owner) },
                    onProgress = { progress ->
                        _writingStages.update { it + (key to writingStageLabel(progress.stage)) }
                        onDelta(listOf(progress.checkpoint?.baselineText.orEmpty(), progress.checkpoint?.body.orEmpty(), progress.preview).filter { it.isNotBlank() }.joinToString("\n\n"))
                        if (progress.preview.isEmpty()) _writingRevision.update { it + 1 }
                    })
            } finally {
                generationId?.let { ChapterGenerationExecutions.unregister(projectId, chapterId, it) }
                if (writingJobs.remove(key, owner)) _writingStages.update { it - key }
                _writingRevision.update { it + 1 }
            }
        }
    }

    /** Scan a chapter's text for characters (esp. new ones not yet registered) and add the new
     *  ones to the character manager. Returns the list of newly-added names. */
    suspend fun agentExtractCharactersFromChapter(projectId: String, chapterId: String): List<String> {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) throw IllegalStateException("角色提取失败：请先配置可用的文本模型")
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId }
            ?: throw IllegalStateException("角色提取失败：未找到章节")
        val body = repo.chapterBody(chapterId)
        val text = body.final.ifBlank { body.draft }
        if (text.isBlank()) return emptyList()
        val lang = _uiLanguage.value
        val existing = repo.characters(projectId)
        val existingNames = existing.map { it.name }.toSet()
        return withContext(Dispatchers.IO) {
            val messages = listOf(
                ChatMessage("system", Prompts.charsFromOutlineSystem(lang)),
                ChatMessage("user", buildString {
                    appendLine(if (lang == "en") "Below is one chapter's prose. Extract the characters appearing in it — ESPECIALLY ones not yet registered — with a full profile each (role, personality, motivation, background, appearance)."
                    else "以下是小说某一章的正文。请提取其中出场的角色——尤其是尚未登记的新角色——为每个角色尽量填写完整档案（身份、性格、动机、背景、形象）。配角也要有血有肉，不要写成工具人。")
                    if (existingNames.isNotEmpty()) appendLine((if (lang == "en") "Already registered: " else "已登记角色：") + existingNames.joinToString("、"))
                    appendLine()
                    appendLine((if (lang == "en") "Chapter text:" else "章节正文：") + "\n" + text.take(6000))
                    appendLine()
                    append(
                        if (lang == "en") {
                            "Output only the complete JSON wrapper object required by the system wire contract."
                        } else {
                            "只能输出系统传输协议要求的完整 JSON 包装对象。"
                        },
                    )
                }),
            )
            val reply = runSuspendCatching {
                ai.chat(cfg, messages, responseFormat = ChatResponseFormat.JSON_OBJECT)
            }.getOrElse {
                throw IllegalStateException("角色提取失败：文本模型调用失败", it)
            }
            val parsed = runCatching { CharacterImportProtocol.parseCompleteJson(reply) }
                .getOrElse {
                    throw IllegalStateException(
                        "角色提取失败：模型没有返回符合协议的完整 JSON（${it.message}）",
                        it,
                    )
                }
                .let { CharacterImportProtocol.mergeByName(listOf(it), "char-chapter-${System.currentTimeMillis()}") }
                .filter { it.name.isNotBlank() && it.name !in existingNames }
            if (parsed.isEmpty()) return@withContext emptyList()
            currentCoroutineContext().ensureActive()
            val added = repo.withChapterTransaction {
                if (
                    repo.project(projectId) == null ||
                    repo.chapters(projectId).firstOrNull { it.id == chapterId } != chapter ||
                    repo.chapterBody(chapterId) != body ||
                    repo.characters(projectId) != existing
                ) {
                    contentConflict("章节正文、章节信息或角色列表")
                } else {
                    repo.mergeCharactersIfProjectExists(projectId, parsed)
                }
            }
            added.map { it.name }
        }
    }

    /** Refine a chapter's plan (goal + core conflict) before writing — batch-created blank chapters
     *  often have thin plans. Returns the refined "目标 / 冲突" summary, or null. */
    suspend fun agentRefineChapterPlan(projectId: String, chapterId: String): String? {
        val cfg = repo.textModelForRole(projectId, "planning"); if (!cfg.isValid()) return null
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val arc = chapterArcId(projectId, chapter)?.let { aid -> repo.plotArcs(projectId).firstOrNull { it.id == aid } }
            // Read the FULL planning context (outline/realm, volume+arc, characters+growth, prior
            // chapters, container knowledge base, settings-page KB index) so refinement is grounded.
            val query = listOf(chapter.title, chapter.outline_goal, chapter.conflict)
                .filterNot { it.isNullOrBlank() }.joinToString("\n")
            val ctx = buildPlanningContext(
                projectId = projectId, arc = arc, query = query, lang = lang,
                currentOrderIndex = chapter.order_index, excludeChapterId = chapter.id,
            )
            val messages = listOf(
                ChatMessage("system", "你是小说章节策划。请把给定章节的『本章目标』与『核心冲突』细化得更具体、可落笔（结合大纲/弧线/角色/前文，避免空泛）。只输出 JSON：{\"goal\":\"...\",\"conflict\":\"...\"}，不要任何其它文字。"),
                ChatMessage("user", buildString {
                    appendLine("第${chapter.order_index}章《${chapter.title}》")
                    appendLine("现有目标：${chapter.outline_goal.orEmpty().ifBlank { "(空)" }}")
                    appendLine("现有冲突：${chapter.conflict.orEmpty().ifBlank { "(空)" }}")
                    appendLine()
                    append(ctx)
                }),
            )
            val reply = runSuspendCatching { ai.chat(cfg, messages) }.getOrNull() ?: return@withContext null
            val s = reply.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
            val start = s.indexOf('{'); val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return@withContext null
            val o = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(s.substring(start, end + 1)).jsonObject }.getOrNull()
                ?: return@withContext null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            val goal = str("goal"); val conflict = str("conflict")
            if (goal.isNullOrBlank() && conflict.isNullOrBlank()) return@withContext null
            currentCoroutineContext().ensureActive()
            val committed = repo.withChapterTransaction {
                val latestChapter = repo.chapters(projectId).firstOrNull { it.id == chapterId }
                if (repo.project(projectId) == null || latestChapter != chapter) {
                    false
                } else {
                    repo.upsertChapter(projectId, latestChapter.copy(
                        outline_goal = goal ?: latestChapter.outline_goal,
                        conflict = conflict ?: latestChapter.conflict,
                        updated_at = nowIso(),
                    ))
                    true
                }
            }
            if (!committed) contentConflict("章节规划")
            "目标：${goal.orEmpty()}\n冲突：${conflict.orEmpty()}"
        }
    }

    suspend fun agentReviseChapter(
        projectId: String,
        chapterId: String,
        instruction: String,
        agentEngineMode: String = repo.agentEngine(),
        agentSessionId: String = "",
        agentActionId: String = "",
        onDelta: (String) -> Unit = {},
    ): String? {
        val cfg = repo.textModelForRole(projectId, "review"); if (!cfg.isValid()) return null
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        val body = repo.chapterBody(chapterId)
        val src = body.final.ifBlank { body.draft }
        if (src.isBlank()) return null
        val promptSources = captureStablePromptSources(
            projectId,
            PromptSourceScope.CHAPTER,
            chapter.order_index,
        ) ?: return null
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val arcs = promptSources.arcs.map { it.toPlotArc() }
            val realmContext = buildRealmSystemContext(promptSources.realms, lang)
            val ownerArc = chapter.arcId?.let { id -> arcs.firstOrNull { it.id == id } }
                ?: arcs.firstOrNull { (it.builtChapterIds ?: emptyList()).contains(chapter.id) }
            val volumeConstraint = ownerArc?.volumeId
                ?.let { id -> promptSources.volumes.firstOrNull { it.id == id } }
                ?.let { volume ->
                    buildVolumeRealmConstraint(
                        volume.realmPlan,
                        volume.name,
                        lang,
                        phase = "generate",
                    )
                }
                ?.takeIf { it.isNotBlank() }
            val worldRaw = promptSources.worldSetting.orEmpty()
                .ifBlank { promptSources.outline.orEmpty() }
            val worldContext = listOfNotNull(
                worldRaw.ifBlank { null },
                realmContext.ifBlank { null },
            ).joinToString("\n\n").ifBlank { null }
            val targetConstraints = volumeConstraint
            val chapterList = buildChapterList(projectId, chapter.id).ifBlank { null }
            val characters = buildCharactersInfo(projectId)
            val timeline = promptSources.timeline?.ifBlank { null }
            val kbAugmentation = buildStoryStateContext(promptSources, chapter, lang)
            val revisionSystem = if (lang == "en") {
                "You are a senior fiction editor. Rewrite the complete chapter to satisfy " +
                    "the instruction while preserving story continuity. Output prose only."
            } else {
                "你是资深小说编辑。请结合完整小说上下文按要求重写整章，保持人物、世界观与前文连续；只输出修改后的完整正文。"
            }
            val budgetedContext = budgetChapterContext(
                cfg = cfg,
                systemPrompt = revisionSystem,
                // Original prose and the author's revision instruction are never silently cut.
                requiredTaskText = listOf(
                    chapter.title,
                    chapter.outline_goal.orEmpty(),
                    chapter.conflict.orEmpty(),
                    src,
                    instruction,
                ).joinToString("\n"),
                worldSetting = worldContext,
                timeline = timeline,
                charactersInfo = characters,
                storyState = kbAugmentation,
                targetConstraints = targetConstraints,
                chapterList = chapterList,
                draftReference = null,
            )
            val messages = listOf(
                ChatMessage("system", revisionSystem),
                ChatMessage("user", buildString {
                    appendLine(if (lang == "en") "Chapter: " + chapter.title else "章节：" + chapter.title)
                    appendLine(if (lang == "en") "Goal: " + chapter.outline_goal.orEmpty() else "本章目标：" + chapter.outline_goal.orEmpty())
                    appendLine(if (lang == "en") "Conflict: " + chapter.conflict.orEmpty() else "核心冲突：" + chapter.conflict.orEmpty())
                    budgetedContext.worldSetting?.let { appendLine("\n【世界与约束】\n" + it) }
                    budgetedContext.timeline?.let { appendLine("\n【时间线】\n" + it) }
                    budgetedContext.charactersInfo?.let { appendLine("\n【角色】\n" + it) }
                    budgetedContext.storyState?.let {
                        appendLine(
                            if (lang == "en") {
                                "\n[Compiled Story State v2]\n$it"
                            } else {
                                "\n【编译小说状态 v2】\n$it"
                            },
                        )
                    }
                    budgetedContext.targetConstraints?.let { appendLine("\n【目标章节约束】\n" + it) }
                    budgetedContext.chapterList?.let { appendLine("\n【章节结构】\n" + it) }
                    appendLine("\n【原文】\n" + src)
                    appendLine("\n【修改要求】\n" + instruction)
                }),
            )
            if (!promptSourcesStillCurrent(
                    projectId,
                    PromptSourceScope.CHAPTER,
                    promptSources,
                    chapter.order_index,
                ) || repo.chapters(projectId).firstOrNull { it.id == chapterId } != chapter ||
                repo.chapterBody(chapterId) != body
            ) {
                contentConflict("章节正文或完整修订上下文")
            }
            val expectedSource = GenerationSourceFingerprint.capture(
                chapter,
                body.draft,
                body.final,
            )
            val active = novelGenerationEngine.startWithContextRecheck(
                NovelGenerationEngine.Request(
                    projectId = projectId,
                    chapter = chapter,
                    targetWords = countWords(src),
                    language = lang,
                    baselineText = src,
                    requireNetNewBody = false,
                    constraints = listOf(instruction),
                    contextManifest = promptContextManifest(PromptSourceScope.CHAPTER, promptSources),
                    expectedSourceHash = expectedSource.sourceHash,
                    initiator = GenerationRun.INITIATOR_AGENT,
                    agentEngine = agentEngineMode,
                    agentSessionId = agentSessionId.takeIf { it.isNotBlank() },
                    agentActionId = agentActionId.takeIf { it.isNotBlank() },
                    operation = GenerationRun.OPERATION_REVISE,
                ),
                currentContextManifest = {
                    capturePromptSources(
                        projectId,
                        PromptSourceScope.CHAPTER,
                        chapter.order_index,
                    )?.let { live ->
                        promptContextManifest(PromptSourceScope.CHAPTER, live)
                    }
                },
            ) ?: return@withContext null
            ChapterGenerationExecutions.replace(
                projectId,
                chapterId,
                active.run.id,
                checkNotNull(currentCoroutineContext()[Job]) { "Missing generation coroutine job" },
            )
            val generationTelemetry = GenerationTelemetryCollector.forModel(
                cfg,
                "chapter.agent_revise.v2",
            )
            try {
                if (!promptSourcesStillCurrent(
                        projectId,
                        PromptSourceScope.CHAPTER,
                        promptSources,
                        chapter.order_index,
                    ) || repo.chapters(projectId).firstOrNull { it.id == chapterId } != chapter ||
                    repo.chapterBody(chapterId) != body
                ) {
                    val category = GenerationTelemetry.FAILURE_SOURCE_CONFLICT
                    novelGenerationEngine.fail(
                        active,
                        GenerationTelemetryCollector.persistedFailureMessage(category),
                        generationTelemetry.finishFailed(category),
                    )
                    contentConflict("章节正文或完整修订上下文")
                }
                val out = streamCollect(
                    cfg,
                    messages,
                    onDelta,
                    telemetry = generationTelemetry,
                    purpose = "chapter_agent_revise",
                ).takeIf { it.isNotEmpty() }
                    ?: error("模型返回了空正文")
                currentCoroutineContext().ensureActive()
                if (
                    repo.project(projectId) == null ||
                    repo.chapters(projectId).firstOrNull { it.id == chapterId } != chapter ||
                    repo.chapterBody(chapterId) != body ||
                    !promptSourcesStillCurrent(
                        projectId,
                        PromptSourceScope.CHAPTER,
                        promptSources,
                        chapter.order_index,
                    )
                ) {
                    val category = GenerationTelemetry.FAILURE_SOURCE_CONFLICT
                    novelGenerationEngine.fail(
                        active,
                        GenerationTelemetryCollector.persistedFailureMessage(category),
                        generationTelemetry.finishFailed(category),
                    )
                    contentConflict("章节正文或完整修订上下文")
                }
                if (completeGenerationCandidate(active, out, generationTelemetry) == null) {
                    if (generationRunWasSuperseded(projectId, active.run.id)) {
                        throw CancellationException("Generation superseded")
                    }
                    error("修订候选稿完成状态写入失败")
                }
                out
            } catch (cancelled: CancellationException) {
                val category = generationCancellationCategory(projectId, active.run.id)
                val telemetry = generationTelemetry.finishCancelled(category)
                novelGenerationEngine.cancel(
                    active,
                    GenerationTelemetryCollector.persistedFailureMessage(category),
                    telemetry.takeIf { it.outcome == GenerationTelemetry.OUTCOME_CANCELLED },
                )
                throw cancelled
            } catch (error: Throwable) {
                val category = GenerationTelemetryCollector.failureCategory(error)
                val telemetry = generationTelemetry.finishFailed(category)
                novelGenerationEngine.fail(
                    active,
                    GenerationTelemetryCollector.persistedFailureMessage(category),
                    telemetry.takeIf { it.outcome == GenerationTelemetry.OUTCOME_FAILED },
                )
                throw error
            } finally {
                ChapterGenerationExecutions.unregister(projectId, chapterId, active.run.id)
            }
        }
    }

    suspend fun agentAnswerQuestion(projectId: String, question: String): String {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return "（未配置文本模型）"
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val ctx = runCatching { buildNovelQaContext(projectId, question, lang) }.getOrDefault("")
            val messages = listOf(
                ChatMessage("system", novelQaSystemPrompt(lang)),
                ChatMessage("user", "【小说资料】\n${ctx.ifBlank { "（暂无资料）" }}\n\n【问题】\n$question"),
            )
            runSuspendCatching { ai.chat(cfg, messages) }.getOrNull()?.trim() ?: "（检索失败）"
        }
    }

    /** Plan [count] chapters for an arc (AI → JSON), then create them via [addChaptersBatch]. */
    suspend fun agentPlanArcChapters(projectId: String, arcId: String, count: Int): Int {
        val cfg = repo.textModelForRole(projectId, "planning"); if (!cfg.isValid()) return 0
        val arc = repo.plotArcs(projectId).firstOrNull { it.id == arcId } ?: return 0
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val baselineChapters = repo.chapters(projectId)
            val nextOrder = (baselineChapters.maxOfOrNull { it.order_index } ?: 0) + 1
            val promptSources = captureStablePromptSources(
                projectId,
                PromptSourceScope.PLANNING,
                nextOrder,
            ) ?: contentConflict("弧线章节规划上下文")
            if (repo.chapters(projectId) != baselineChapters) {
                contentConflict("弧线章节规划上下文")
            }
            // Full planning context so arc chapters are planned against the whole project state.
            val ctx = buildPlanningContext(
                projectId = projectId, arc = arc, query = "${arc.title}\n${arc.summary}", lang = lang,
                currentOrderIndex = nextOrder,
            )
            val messages = listOf(
                ChatMessage("system", "你为某条剧情弧线规划具体章节。只输出 JSON 数组：[{\"title\":\"章节名\",\"goal\":\"本章目标(1-2句)\"}]，不要任何其它文字。"),
                ChatMessage("user", "弧线：${arc.title}\n${arc.summary}\n请规划 $count 个章节。\n参考资料：\n$ctx"),
            )
            if (!promptSourcesStillCurrent(
                    projectId,
                    PromptSourceScope.PLANNING,
                    promptSources,
                    nextOrder,
                ) || repo.plotArcs(projectId).firstOrNull { it.id == arcId } != arc ||
                repo.chapters(projectId) != baselineChapters
            ) {
                contentConflict("弧线章节规划上下文")
            }
            val reply = runSuspendCatching { ai.chat(cfg, messages) }.getOrNull() ?: return@withContext 0
            val items = parseChapterPlanArray(reply)
            if (items.isEmpty()) return@withContext 0
            currentCoroutineContext().ensureActive()
            if (!promptSourcesStillCurrent(
                    projectId,
                    PromptSourceScope.PLANNING,
                    promptSources,
                    nextOrder,
                ) || repo.plotArcs(projectId).firstOrNull { it.id == arcId } != arc ||
                repo.chapters(projectId) != baselineChapters
            ) {
                contentConflict("弧线章节规划上下文或章节集合")
            }
            val added = addChaptersBatchAgainst(
                projectId,
                arc,
                items,
                expectedChapters = baselineChapters,
            )
            if (added.isEmpty()) contentConflict("弧线章节规划上下文或章节集合")
            added.size
        }
    }

    /** Review the project's chapters for contradictions / logic errors (streaming). */
    suspend fun agentReviewConsistency(projectId: String, onDelta: (String) -> Unit = {}): String? {
        val cfg = repo.textModelForRole(projectId, "review"); if (!cfg.isValid()) return null
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val chapters = repo.chapters(projectId).sortedBy { it.order_index }
            if (chapters.isEmpty()) return@withContext "（暂无章节，无法审阅）"
            val sums = repo.summaries(projectId)
            val lines = chapters.joinToString("\n") { ch ->
                val sum = sums.firstOrNull { it.scopeType == "chapter" && it.scopeId == ch.id }?.summaryText?.takeIf { it.isNotBlank() }
                val body = sum ?: repo.chapterBody(ch.id).let { it.final.ifBlank { it.draft } }.take(180)
                "第${ch.order_index}章《${ch.title}》：${body.ifBlank { "(空)" }}"
            }
            val chars = buildCharactersInfo(projectId).orEmpty()
            val realm = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
            val messages = listOf(
                ChatMessage("system", "你是严谨的小说审校。请找出章节之间的前后矛盾、逻辑谬误、人物/设定/境界不一致之处。逐条列出，每条注明涉及章节与具体问题，按严重程度排序；若整体无明显问题也请说明。"),
                ChatMessage("user", buildString {
                    if (chars.isNotBlank()) appendLine("【角色】\n$chars\n")
                    if (realm.isNotBlank()) appendLine("【境界体系】\n$realm\n")
                    appendLine("【各章梗概/节选】\n$lines")
                }),
            )
            runSuspendCatching { streamCollect(cfg, messages, onDelta) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "（审阅失败）"
        }
    }

    private fun parseChapterPlanArray(raw: String): List<Pair<String, String?>> = runCatching {
        val s = raw.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = s.indexOf('['); val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        Json { ignoreUnknownKeys = true }.parseToJsonElement(s.substring(start, end + 1)).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            title to (o["goal"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        }
    }.getOrDefault(emptyList())

    // ── backup / restore ──────────────────────────────────────────────────

    fun generateCharactersFromOutline(
        projectId: String,
        onComplete: (List<Character>) -> Unit,
    ): Job? = generateCharactersFromOutlineWithSource(projectId) { parsed, _ -> onComplete(parsed) }

    fun generateCharactersFromOutlineWithSource(
        projectId: String,
        onComplete: (List<Character>, sourceOutline: String) -> Unit,
    ): Job? {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return null
        }
        val ticket = claimStreamingGeneration("")
        val promptSources = captureStablePromptSources(
            projectId,
            PromptSourceScope.CHARACTERS_FROM_OUTLINE,
        )
        if (promptSources == null) {
            abandonStreamingGeneration(ticket, "角色生成上下文正在变化或项目已删除，请稍后重试")
            return null
        }
        val outline = promptSources.outline.orEmpty()
        if (outline.isBlank()) {
            abandonStreamingGeneration(
                ticket,
                "尚无大纲，请先生成大纲 / No outline yet — generate one first.",
            )
            return null
        }
        val lang = _uiLanguage.value
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val systemMessage = ChatMessage("system", Prompts.charsFromOutlineSystem(lang))
        val stablePrefixMessages = buildList {
            add(systemMessage)
            realmCtx.takeIf { it.isNotBlank() }?.let {
                add(ChatMessage("system", Prompts.charsFromOutlineContext(it, lang)))
            }
        }
        val fixedMessages = stablePrefixMessages +
            ChatMessage(
                "user",
                Prompts.charsFromOutlineUser(
                    outline = "",
                    language = lang,
                    batchIndex = 9_999,
                    batchCount = 9_999,
                ),
            )
        return launchStreamingGeneration(ticket) { revision, buffer ->
            val batchTokenBudget = CharacterImportProtocol.outlineBatchTokenBudget(cfg, fixedMessages)
            val outlineBatches = CharacterImportProtocol.splitOutline(outline, batchTokenBudget)
            if (outlineBatches.isEmpty()) {
                throw IllegalStateException("角色导入失败：大纲分批后没有可处理内容，未导入任何角色。")
            }
            val parsedBatches = mutableListOf<List<Character>>()
            outlineBatches.forEachIndexed { index, outlineBatch ->
                if (!promptSourcesStillCurrent(
                        projectId,
                        PromptSourceScope.CHARACTERS_FROM_OUTLINE,
                        promptSources,
                    )
                ) {
                    contentConflict("大纲、境界体系或项目")
                }
                val batchNumber = index + 1
                val messages = stablePrefixMessages +
                    ChatMessage(
                        "user",
                        Prompts.charsFromOutlineUser(
                            outline = outlineBatch,
                            language = lang,
                            batchIndex = batchNumber,
                            batchCount = outlineBatches.size,
                        ),
                    )
                // LongOutputRecovery validates every initial/continuation request against the
                // normalized provider budget before any network call is allowed.
                val response = LongOutputRecovery.collect(
                    config = cfg,
                    initialMessages = messages,
                    format = LongOutputFormat.JSON_OBJECT,
                    taskLabel = if (lang == "en") {
                        "Character import batch $batchNumber/${outlineBatches.size}"
                    } else {
                        "角色导入第 $batchNumber/${outlineBatches.size} 批"
                    },
                    language = lang,
                    maxRequests = 3,
                    request = { requestMessages -> ai.streamChat(cfg, requestMessages) },
                    onCumulative = { cumulative ->
                        val progress = if (lang == "en") {
                            "Processing character batch $batchNumber/${outlineBatches.size}"
                        } else {
                            "正在处理角色分片 $batchNumber/${outlineBatches.size}"
                        }
                        replaceStreamingText(revision, buffer, "$progress\n\n$cumulative")
                    },
                )
                val parsed = try {
                    CharacterImportProtocol.parseCompleteJson(response)
                } catch (failure: CharacterImportProtocol.FormatException) {
                    throw IllegalStateException(
                        "角色导入失败：第 $batchNumber/${outlineBatches.size} 批没有返回符合协议的完整 JSON" +
                            "（${failure.message}）。所有批次均未导入；请重试，或更换支持 JSON 输出的模型。",
                        failure,
                    )
                }
                parsedBatches += parsed
            }
            ensureStreamingOwner(revision)
            val parsed = CharacterImportProtocol.mergeByName(
                batches = parsedBatches,
                idPrefix = "char-import-${System.currentTimeMillis()}",
            )
            val committed = withContext(Dispatchers.Main) {
                commitIfStreamingOwner(revision) {
                    if (!promptSourcesStillCurrent(
                            projectId,
                            PromptSourceScope.CHARACTERS_FROM_OUTLINE,
                            promptSources,
                        )
                    ) {
                        false
                    } else {
                        onComplete(parsed, outline)
                        true
                    }
                }
            }
            if (!committed) contentConflict("大纲、境界体系或项目")
        }
    }



    fun buildBackupJson(includeSecrets: Boolean = false): String {
        val bundle = repo.buildBackupBundle(includeSecrets)
        return AppRepository.JSON.encodeToString(BackupBundle.serializer(), bundle)
    }

    fun writeBackup(output: java.io.OutputStream, archive: Boolean = true, includeSecrets: Boolean = false) {
        val bundle = repo.buildBackupBundle(includeSecrets)
        if (archive) com.example.novelseek_ultra.data.backup.BackupArchiveCodec.write(output, bundle)
        else com.example.novelseek_ultra.data.backup.BackupArchiveCodec.writeLegacyJson(output, bundle)
    }

    private val backupImportPreviewRevision = java.util.concurrent.atomic.AtomicLong()

    fun stageImport(fileName: String, jsonText: String) {
        stageImport(fileName) { java.io.ByteArrayInputStream(jsonText.toByteArray(Charsets.UTF_8)) }
    }

    fun stageImport(fileName: String, openInput: () -> java.io.InputStream?) {
        val revision = backupImportPreviewRevision.incrementAndGet()
        _importPreview.value = null
        _statusMessage.value = "正在读取并校验备份… / Reading and verifying backup…"
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val input = openInput() ?: error("无法打开备份文件 / Unable to open backup")
                    val bundle = input.use { com.example.novelseek_ultra.data.backup.BackupArchiveCodec.read(it) }
                    bundle to repo.summarizeBackup(bundle)
                }
            }
            if (backupImportPreviewRevision.get() != revision) return@launch
            parsed
                .onSuccess { (bundle, summary) ->
                    _statusMessage.value = ""
                    _importPreview.value = ImportPreview(bundle, fileName, summary)
                }
                .onFailure { err ->
                    _importPreview.value = null
                    _statusMessage.value = "Import failed: ${err.message}"
                }
        }
    }

    fun cancelImport() { backupImportPreviewRevision.incrementAndGet(); _importPreview.value = null }

    fun confirmImport(includeAppSettings: Boolean) {
        val preview = _importPreview.value ?: return
        val revision = backupImportPreviewRevision.incrementAndGet()
        // Close the confirmation immediately so repeated taps cannot launch overlapping imports.
        // A failed import restores the preview, allowing the user to inspect/retry the same file.
        _importPreview.value = null
        viewModelScope.launch {
            _statusMessage.value = "正在校验并导入备份… / Validating and importing backup…"
            val result = try {
                agent.importBackupWhileIdle { repo.importBackup(preview.bundle, includeAppSettings) }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (backupImportPreviewRevision.get() != revision) return@launch
            result.onSuccess {
                val merged = preview.summary.projectIdsInBackup
                _statusMessage.value = "Import done: merged metadata for $merged projects." +
                    if (includeAppSettings) " App settings and any included API keys imported." else ""
            }.onFailure { error ->
                _importPreview.value = preview
                _statusMessage.value = error.message ?: "Import failed."
            }
        }
    }

    fun clearStatus() { _statusMessage.value = "" }

    fun showStatus(msg: String) { _statusMessage.value = msg }

    fun clearStreaming() { _streamingText.value = "" }

    // ── private helpers ───────────────────────────────────────────────────

    /** Capture only fields that can be rendered into the selected prompt, plus the target
     * collection for collection-producing calls. Repository lists are immutable data snapshots;
     * the caller performs a second identical capture before beginning provider I/O. */
    private fun capturePromptSources(
        projectId: String,
        scope: PromptSourceScope,
        currentOrderIndex: Int? = null,
    ): PromptSourceSnapshot? {
        val project = repo.project(projectId) ?: return null
        val fullProjectMetadata = scope == PromptSourceScope.OUTLINE ||
            scope == PromptSourceScope.PLANNING || scope == PromptSourceScope.PLOT_ARC

        val includeFullCharacters = scope == PromptSourceScope.OUTLINE ||
            scope == PromptSourceScope.CHAPTER || scope == PromptSourceScope.PLANNING ||
            scope == PromptSourceScope.PLOT_ARC
        val includeGrowth = scope == PromptSourceScope.CHAPTER || scope == PromptSourceScope.PLANNING
        val includeVolumes = scope == PromptSourceScope.CHAPTER ||
            scope == PromptSourceScope.PLANNING || scope == PromptSourceScope.VOLUME_COLLECTION ||
            scope == PromptSourceScope.ARC_COLLECTION
        val includeArcs = scope == PromptSourceScope.CHAPTER ||
            scope == PromptSourceScope.PLANNING || scope == PromptSourceScope.ARC_COLLECTION ||
            scope == PromptSourceScope.PLOT_ARC
        val includeDirectChapters = scope == PromptSourceScope.CHAPTER || scope == PromptSourceScope.PLANNING
        val includeKb = scope == PromptSourceScope.CHAPTER || scope == PromptSourceScope.PLANNING

        val selectedContainers = when (scope) {
            PromptSourceScope.CHAPTER -> repo.containers(projectId).filter { it.affectsGeneration }
            PromptSourceScope.PLANNING -> repo.containers(projectId)
            PromptSourceScope.VOLUME_COLLECTION -> repo.containers(projectId).filter { it.affectsVolumeGeneration }
            PromptSourceScope.ARC_COLLECTION -> repo.containers(projectId).filter { it.affectsArcGeneration }
            else -> emptyList()
        }
        val needsCharacterKeys = selectedContainers.any { it.type == Container.BY_CHARACTER }
        val needsChapterKeys = selectedContainers.any { it.type == Container.BY_CHAPTER }
        val rawCharacters = if (includeFullCharacters || needsCharacterKeys || includeGrowth) {
            repo.characters(projectId)
        } else {
            emptyList()
        }
        val rawChapters = if (includeDirectChapters || needsChapterKeys) repo.chapters(projectId) else emptyList()
        val rawArcs = if (includeArcs) repo.plotArcs(projectId) else emptyList()
        val temporalTargetOrder = currentOrderIndex.takeIf { scope == PromptSourceScope.CHAPTER }
        val chapterOrdersById = rawChapters.associate { it.id to it.order_index }
        fun sourceVisible(sourceChapterId: String?, sourceChapterOrder: Int?): Boolean =
            StoryStateVisibility.isVisibleBefore(
                targetChapterOrder = temporalTargetOrder,
                sourceChapterId = sourceChapterId,
                sourceChapterOrder = sourceChapterOrder,
                chapterOrdersById = chapterOrdersById,
            )

        // Global currentRealm has no chapter provenance. It is safe only for a genuinely blank
        // append target after every written chapter. Read effective bodies when legacy word_count is
        // missing so rewrites cannot accidentally receive an end-of-chapter/future realm.
        val hasWrittenTargetOrFutureChapter = if (temporalTargetOrder == null) {
            false
        } else {
            DerivedChapterMutation.hasWrittenAtOrAfter(
                temporalTargetOrder,
                rawChapters.asSequence()
                    .filter { it.order_index >= temporalTargetOrder }
                    .map { source ->
                        val effectiveText = if (source.word_count > 0) {
                            ""
                        } else {
                            repo.chapterBody(source.id).let { body ->
                                DerivedChapterMutation.effectiveText(body.draft, body.final)
                            }
                        }
                        DerivedChapterMutation.Presence(
                            orderIndex = source.order_index,
                            wordCount = source.word_count,
                            effectiveText = effectiveText,
                        )
                    }
                    .toList(),
            )
        }

        val characterSources = rawCharacters.map { character ->
            PromptCharacterSource(
                id = character.id,
                name = character.name,
                gender = character.gender.takeIf { includeFullCharacters }.orEmpty(),
                role = character.role.takeIf { includeFullCharacters }.orEmpty(),
                personality = character.personality.takeIf { includeFullCharacters }.orEmpty(),
                appearance = character.appearance.takeIf { includeFullCharacters }.orEmpty(),
                motivation = character.motivation.takeIf { includeFullCharacters }.orEmpty(),
                currentRealmId = character.currentRealmId.takeIf {
                    includeFullCharacters && !hasWrittenTargetOrFutureChapter
                },
                currentSubRealmId = character.currentSubRealmId.takeIf {
                    includeFullCharacters && !hasWrittenTargetOrFutureChapter
                },
            )
        }
        val chapterSources = rawChapters.map { chapter ->
            PromptChapterSource(
                id = chapter.id,
                title = chapter.title,
                orderIndex = chapter.order_index,
                outlineGoal = chapter.outline_goal.takeIf { includeDirectChapters },
                conflict = chapter.conflict.takeIf { includeDirectChapters },
                wordCount = chapter.word_count.takeIf { includeDirectChapters } ?: 0,
                arcId = chapter.arcId.takeIf { includeDirectChapters },
            )
        }
        val previousBodies = if (includeDirectChapters && currentOrderIndex != null) {
            rawChapters.asSequence()
                .filter { it.order_index < currentOrderIndex }
                .sortedBy { it.order_index }
                .map { it.id to repo.chapterBody(it.id) }
                .toList()
        } else {
            emptyList()
        }

        val recentContainerChapterCount = if (scope == PromptSourceScope.PLANNING) 5 else 3
        val eligibleContainerChapters = if (
            scope == PromptSourceScope.CHAPTER && currentOrderIndex != null
        ) {
            // Never leak state attached to planned/future chapters into the current chapter prompt.
            rawChapters.filter { it.order_index < currentOrderIndex }
        } else {
            rawChapters
        }
        val recentContainerChapters = eligibleContainerChapters
            .sortedBy { it.order_index }
            .takeLast(recentContainerChapterCount)
        val containerValues = selectedContainers.flatMap { container ->
            val blockKeys = when (container.type) {
                Container.BY_CHARACTER -> rawCharacters.map { it.id }
                Container.BY_CHAPTER -> recentContainerChapters.map { it.id }
                else -> listOf(Container.SINGLE_BLOCK_KEY)
            }
            blockKeys.map { blockKey ->
                val latestVisible = repo.containerEntries(projectId, container.id, blockKey)
                    .lastOrNull { sourceVisible(it.sourceChapterId, it.sourceChapterOrder) }
                PromptContainerValueSource(
                    containerId = container.id,
                    blockKey = blockKey,
                    latest = latestVisible,
                )
            }
        }

        val summariesEnabled = if (scope == PromptSourceScope.CHAPTER) repo.summariesEnabled() else null
        val relevantSummaries = if (summariesEnabled == true) {
            val activeArcIds = rawArcs.filter { it.status == "active" || it.status == "ending" }.map { it.id }.toSet()
            val recentChapterIds = previousBodies.takeLast(3).map { it.first }.toSet()
            repo.summaries(projectId).filter { summary ->
                !summary.isStale && when (summary.scopeType) {
                    // Roll-ups have no covered-through/source hash. They are safe only for a truly
                    // new blank append target; a rewrite could otherwise see its own old ending.
                    "book" -> !hasWrittenTargetOrFutureChapter && summary.scopeId == projectId
                    "arc" -> !hasWrittenTargetOrFutureChapter && summary.scopeId in activeArcIds
                    "chapter" -> summary.scopeId in recentChapterIds
                    else -> false
                }
            }
        } else {
            emptyList()
        }
        val entitiesEnabled = if (scope == PromptSourceScope.CHAPTER) repo.entitiesEnabled() else null
        val relevantEntities = if (entitiesEnabled == true) {
            repo.entities(projectId).mapNotNull { entity ->
                when (StoryStateVisibility.entityProjectionBefore(
                    targetChapterOrder = temporalTargetOrder,
                    historicalTarget = hasWrittenTargetOrFutureChapter,
                    currentStatus = entity.status,
                    firstSeenChapterId = entity.firstSeenChapterId,
                    lastSeenChapterId = entity.lastSeenChapterId,
                    chapterOrdersById = chapterOrdersById,
                )) {
                    StoryStateVisibility.EntityProjection.FULL -> entity
                    StoryStateVisibility.EntityProjection.IDENTITY_ONLY -> entity.copy(
                        aliases = emptyList(),
                        summary = "",
                        status = "open",
                        lastSeenChapterId = null,
                    )
                    StoryStateVisibility.EntityProjection.HIDDEN -> null
                }
            }
        } else {
            emptyList()
        }
        val knowledgeBaseEnabled = if (includeKb) repo.knowledgeBaseEnabled() else null
        val embeddingConfig = if (knowledgeBaseEnabled == true) repo.embeddingConfig() else null
        val kbStaleChapterIds = if (knowledgeBaseEnabled == true) {
            repo.kbStaleChapters(projectId).toSet()
        } else {
            emptySet()
        }
        val kbIndexHashes = if (knowledgeBaseEnabled == true) repo.kbIndexHashes(projectId) else emptyMap()
        val visibleBodyHashes = previousBodies.associate { (chapterId, body) ->
            chapterId to com.example.novelseek_ultra.data.SnapshotStore.sha1(
                com.example.novelseek_ultra.data.SnapshotStore.canonicalChapterText(
                    body.final,
                    body.draft,
                ),
            )
        }
        val chunks = if (knowledgeBaseEnabled == true && embeddingConfig != null) {
            repo.chunks(projectId).filter {
                it.embeddingModel == embeddingConfig.model &&
                    (it.sourceType != "chapter" || (
                        sourceVisible(it.sourceId, null) &&
                            it.sourceId !in kbStaleChapterIds &&
                            (
                                temporalTargetOrder == null ||
                                    kbIndexHashes[it.sourceId] == visibleBodyHashes[it.sourceId]
                                )
                        ))
            }
        } else {
            emptyList()
        }
        val relevantFactEvidence = if (entitiesEnabled == true) {
            val bodiesById = previousBodies.toMap()
            val characterSignature = repo.characterSourceSignature(rawCharacters)
            FactEvidenceLedger.visibleBefore(
                existing = repo.factEvidence(projectId),
                targetChapterOrder = temporalTargetOrder,
                chapterOrdersById = chapterOrdersById,
            ).filter { batch ->
                val source = chapterSources.firstOrNull { it.id == batch.chapterId }
                    ?: return@filter false
                val body = bodiesById[batch.chapterId] ?: return@filter false
                val effective = DerivedChapterMutation.effectiveText(body.draft, body.final)
                val sourceHash = DerivedSourceFingerprint.chapter(
                    chapterId = source.id,
                    title = source.title,
                    orderIndex = source.orderIndex,
                    effectiveText = effective,
                ).textHash
                FactEvidenceLedger.isFresh(
                    batch = batch,
                    chapterId = source.id,
                    chapterTitle = source.title,
                    sourceHash = sourceHash,
                    characterSignature = characterSignature,
                )
            }
        } else {
            emptyList()
        }

        val targetChapter = temporalTargetOrder?.let { targetOrder ->
            rawChapters.firstOrNull { it.order_index == targetOrder }
        }
        val targetArcOrder = targetChapter?.let { target ->
            rawArcs.firstOrNull { arc ->
                target.arcId == arc.id || target.id in arc.builtChapterIds.orEmpty()
            }?.order
        }
        val projectedArcs = rawArcs.map { arc ->
            val assignedOrders = rawChapters.asSequence()
                .filter { source ->
                    source.arcId == arc.id || source.id in arc.builtChapterIds.orEmpty()
                }
                .map { it.order_index }
                .toList()
            PromptArcSource(
                id = arc.id,
                title = arc.title,
                summary = arc.summary,
                order = arc.order,
                status = StoryStateVisibility.arcStatusBefore(
                    targetChapterOrder = temporalTargetOrder,
                    historicalTarget = hasWrittenTargetOrFutureChapter,
                    currentStatus = arc.status,
                    arcOrder = arc.order,
                    targetArcOrder = targetArcOrder,
                    assignedChapterOrders = assignedOrders,
                ),
                chapterCount = arc.chapterCount,
                volumeId = arc.volumeId,
                builtChapterIds = arc.builtChapterIds,
            )
        }

        return PromptSourceSnapshot(
            writingWorkspace = repo.writingWorkspace(projectId),
            project = PromptProjectSource(
                id = project.id,
                title = project.title.takeIf { fullProjectMetadata }.orEmpty(),
                genre = project.genre.orEmpty().takeIf { fullProjectMetadata }.orEmpty(),
                description = project.description.orEmpty().takeIf { fullProjectMetadata }.orEmpty(),
            ),
            outline = repo.outline(projectId),
            worldSetting = repo.worldSetting(projectId).takeIf {
                scope == PromptSourceScope.OUTLINE || scope == PromptSourceScope.CHAPTER
            },
            timeline = repo.timeline(projectId).takeIf {
                scope == PromptSourceScope.OUTLINE || scope == PromptSourceScope.CHAPTER
            },
            realms = repo.cultivationRealms(projectId),
            characters = characterSources,
            characterGrowth = if (includeGrowth) rawCharacters.map {
                PromptGrowthSource(
                    it.id,
                    repo.characterGrowth(projectId, it.id).lastOrNull { entry ->
                        sourceVisible(entry.chapterId, entry.chapterOrder)
                    },
                )
            } else emptyList(),
            volumes = if (includeVolumes) repo.volumes(projectId).map {
                PromptVolumeSource(it.id, it.name, it.description, it.order, it.realmPlan)
            } else emptyList(),
            arcs = projectedArcs,
            chapters = chapterSources,
            previousBodies = previousBodies,
            summariesEnabled = summariesEnabled,
            summaries = relevantSummaries,
            entitiesEnabled = entitiesEnabled,
            entities = relevantEntities,
            factEvidence = relevantFactEvidence,
            knowledgeBaseEnabled = knowledgeBaseEnabled,
            embeddingConfig = embeddingConfig,
            chunks = chunks,
            containers = selectedContainers.map {
                PromptContainerSource(
                    it.id, it.name, it.type, it.affectsGeneration,
                    it.affectsVolumeGeneration, it.affectsArcGeneration,
                )
            },
            containerValues = containerValues,
        )
    }

    /** Consecutive equal reads prove that prompt construction starts from one stable source state
     * without holding any repository lock during provider/network work. */
    private fun captureStablePromptSources(
        projectId: String,
        scope: PromptSourceScope,
        currentOrderIndex: Int? = null,
    ): PromptSourceSnapshot? {
        var previous = capturePromptSources(projectId, scope, currentOrderIndex) ?: return null
        repeat(2) {
            val current = capturePromptSources(projectId, scope, currentOrderIndex) ?: return null
            if (current == previous) return current
            previous = current
        }
        return null
    }

    private fun promptSourcesStillCurrent(
        projectId: String,
        scope: PromptSourceScope,
        expected: PromptSourceSnapshot,
        currentOrderIndex: Int? = null,
    ): Boolean = capturePromptSources(projectId, scope, currentOrderIndex) == expected

    /**
     * Convert the exact stable prompt snapshot into a small, persisted category manifest.
     * Length-prefix hashing happens in [GenerationContextFingerprint]; source text never enters
     * GenerationRun JSON.
     */
    private fun promptContextManifest(
        scope: PromptSourceScope,
        snapshot: PromptSourceSnapshot,
    ): GenerationContextManifest {
        fun nullable(value: Any?): List<String> =
            listOf(if (value == null) "0" else "1", value?.toString().orEmpty())

        fun listed(values: List<*>): List<String> =
            listOf(values.size.toString()) + values.map { it.toString() }

        val embeddingBasis = snapshot.embeddingConfig?.let { config ->
            listOf(
                "1",
                config.apiUrl,
                config.model,
                if (config.dimensions == null) "0" else "1",
                config.dimensions?.toString().orEmpty(),
            )
        } ?: listOf("0")

        return GenerationContextFingerprint.capture(
            scope = "prompt:${scope.name}",
            materials = listOf(
                GenerationContextMaterial(
                    "project",
                    listOf(
                        snapshot.project.id,
                        snapshot.project.title,
                        snapshot.project.genre,
                        snapshot.project.description,
                    ),
                ),
                GenerationContextMaterial("outline", nullable(snapshot.outline)),
                GenerationContextMaterial("writing_workspace", snapshot.writingWorkspace.contentFingerprintMaterial()),
                GenerationContextMaterial("world_setting", nullable(snapshot.worldSetting)),
                GenerationContextMaterial("timeline", nullable(snapshot.timeline)),
                GenerationContextMaterial("realms", listed(snapshot.realms)),
                GenerationContextMaterial("characters", listed(snapshot.characters)),
                GenerationContextMaterial("character_growth", listed(snapshot.characterGrowth)),
                GenerationContextMaterial("volumes", listed(snapshot.volumes)),
                GenerationContextMaterial("arcs", listed(snapshot.arcs)),
                GenerationContextMaterial("chapters", listed(snapshot.chapters)),
                GenerationContextMaterial(
                    "previous_bodies",
                    listOf(snapshot.previousBodies.size.toString()) +
                        snapshot.previousBodies.flatMap { (chapterId, body) ->
                            listOf(chapterId, body.draft, body.final)
                        },
                ),
                GenerationContextMaterial(
                    "summaries",
                    nullable(snapshot.summariesEnabled) + listed(snapshot.summaries),
                ),
                GenerationContextMaterial(
                    "entities",
                    nullable(snapshot.entitiesEnabled) + listed(snapshot.entities),
                ),
                GenerationContextMaterial("fact_evidence", listed(snapshot.factEvidence)),
                GenerationContextMaterial(
                    "knowledge_base",
                    nullable(snapshot.knowledgeBaseEnabled) +
                        embeddingBasis +
                        listed(snapshot.chunks),
                ),
                GenerationContextMaterial("containers", listed(snapshot.containers)),
                GenerationContextMaterial("container_values", listed(snapshot.containerValues)),
                GenerationContextMaterial(
                    "story_state_compiler",
                    listOf(
                        if (scope == PromptSourceScope.CHAPTER) {
                            StoryStateCompiler.CONTRACT
                        } else {
                            "none"
                        },
                    ),
                ),
            ),
        )
    }

    /** Last 3 written chapters before [currentOrderIndex], formatted as PC-style context snippets. */
    private fun buildPreviousChapterSummary(projectId: String, currentOrderIndex: Int): String {
        val prev = repo.chapters(projectId)
            .filter { it.order_index < currentOrderIndex }
            .filter { repo.chapterBody(it.id).let { b -> b.final.isNotEmpty() || b.draft.isNotEmpty() } }
            .sortedByDescending { it.order_index }
            .take(3)
            .reversed()
        if (prev.isEmpty()) return ""
        return prev.mapIndexed { idx, c ->
            val body = repo.chapterBody(c.id).let { if (it.final.isNotEmpty()) it.final else it.draft }
            val isLast = idx == prev.size - 1
            val label = when {
                idx == prev.size - 1 -> "紧邻上章"
                idx == prev.size - 2 -> "两章前"
                else -> "更早的章节"
            }
            val goalLine = if (!c.outline_goal.isNullOrBlank()) "（目标：${c.outline_goal}）" else ""
            "---- $label「${c.title}」$goalLine ----\n${body.takeLast(if (isLast) 1500 else 500)}"
        }.joinToString("\n\n")
    }

    private fun buildArcContext(arcs: List<PlotArc>, lang: String): String {
        if (arcs.isEmpty()) return ""
        val activeArc = arcs.find { it.status == "active" || it.status == "ending" }
        val completed = arcs.filter { it.status == "completed" }
        val upcoming = arcs.filter { it.status == "upcoming" }
        return if (lang == "en") buildString {
            appendLine("[Story Arc Progress]")
            if (completed.isNotEmpty()) appendLine("Completed arcs: ${completed.joinToString(" → ") { it.title }}")
            if (activeArc != null) {
                appendLine("Current arc: ${activeArc.title}")
                if (activeArc.summary.isNotBlank()) appendLine("Arc summary: ${activeArc.summary}")
                appendLine("This arc is actively unfolding. Maintain the arc's core conflict and keep plot threads alive.")
            }
            if (upcoming.isNotEmpty()) appendLine("Upcoming arcs: ${upcoming.joinToString(", ") { it.title }}")
        }.trimEnd()
        else buildString {
            appendLine("【剧情弧线进度】")
            if (completed.isNotEmpty()) appendLine("已完成弧线：${completed.joinToString(" → ") { it.title }}")
            if (activeArc != null) {
                appendLine("当前弧线：${activeArc.title}")
                if (activeArc.summary.isNotBlank()) appendLine("弧线概述：${activeArc.summary}")
                appendLine("弧线进行中：维持核心矛盾，推进主线剧情，为后续伏笔做铺垫。")
            }
            if (upcoming.isNotEmpty()) appendLine("后续弧线（暂不展开）：${upcoming.joinToString("、") { it.title }}")
        }.trimEnd()
    }

    private fun PromptArcSource.toPlotArc(): PlotArc = PlotArc(
        id = id,
        title = title,
        summary = summary,
        order = order,
        status = status,
        chapterCount = chapterCount,
        builtChapterIds = builtChapterIds,
        volumeId = volumeId,
    )

    private fun buildChapterList(projectId: String, currentChapterId: String): String {
        val chapters = repo.chapters(projectId).sortedBy { it.order_index }
        if (chapters.isEmpty()) return ""
        return chapters.joinToString("\n") { ch ->
            val isCurrent = ch.id == currentChapterId
            val flag = when {
                isCurrent -> " ←当前章节"
                ch.word_count == 0 -> " [待写]"
                else -> ""
            }
            val words = if (ch.word_count > 0) "（${ch.word_count}字）" else ""
            val goal = ch.outline_goal?.take(30)?.let { " — $it" }.orEmpty()
            "第${ch.order_index}章 ${ch.title}$words$goal$flag"
        }
    }

    private fun buildCharactersInfo(projectId: String): String? {
        val chars = repo.characters(projectId)
        if (chars.isEmpty()) return null
        return chars.joinToString("\n\n") { c ->
            buildString {
                appendLine("【${c.name}】")
                if (c.gender.isNotBlank()) appendLine("- 性别：${c.gender}")
                if (c.role.isNotBlank()) appendLine("- 身份：${c.role}")
                if (c.personality.isNotBlank()) appendLine("- 性格：${c.personality}")
                if (c.appearance.isNotBlank()) appendLine("- 形象：${c.appearance}")
                if (c.motivation.isNotBlank()) append("- 动机：${c.motivation}")
            }
        }
    }

    /**
     * Compile one bounded Story State from the exact prompt snapshot captured before provider I/O.
     * No repository read occurs here: RAG ranking, summaries, entities, growth and container values
     * all come from [snapshot], so the rendered prompt and persisted context manifest share one truth.
     */
    private suspend fun buildStoryStateContext(
        snapshot: PromptSourceSnapshot,
        chapter: Chapter,
        lang: String,
    ): String? {
        val query = listOf(chapter.title, chapter.outline_goal, chapter.conflict)
            .filterNot { it.isNullOrBlank() }
            .joinToString("\n")
        val embedding = snapshot.embeddingConfig
        val retrieved = if (
            snapshot.knowledgeBaseEnabled == true && embedding != null &&
            embedding.isUsableApiConfig() && snapshot.chunks.isNotEmpty() && query.isNotBlank()
        ) {
            try {
                kb.retrieveTopK(
                    query = query,
                    pool = snapshot.chunks,
                    topK = 4,
                    excludeSourceIds = setOf(chapter.id),
                    cfg = embedding,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val characterNames = snapshot.characters.associate { it.id to it.name }
        val chaptersById = snapshot.chapters.associateBy { it.id }
        val containersById = snapshot.containers.associateBy { it.id }
        val realmsById = snapshot.realms.associateBy { it.id }
        val subRealmsById = snapshot.realms
            .flatMap { it.subRealms.orEmpty() }
            .associateBy { it.id }
        val chapterSummaries = snapshot.summaries
            .filter { it.scopeType == "chapter" && !it.isStale }
            .associateBy { it.scopeId }
        val evidenceStates = snapshot.factEvidence.flatMap { batch ->
            val source = chaptersById[batch.chapterId] ?: return@flatMap emptyList()
            batch.facts.map { fact ->
                StoryStateCompiler.EvidenceState(
                    entityId = fact.entityId,
                    type = fact.factType,
                    subject = fact.subject,
                    claim = fact.claim,
                    statusAfter = fact.statusAfter,
                    sourceChapterId = batch.chapterId,
                    sourceChapterOrder = source.orderIndex,
                    sourceChapterTitle = source.title,
                    evidenceText = fact.evidenceText,
                )
            }
        }
        val evidenceByEntity = evidenceStates
            .filter { !it.entityId.isNullOrBlank() }
            .groupBy { checkNotNull(it.entityId) }
        val ledgerCommitments = evidenceByEntity.mapNotNull { (entityId, observations) ->
            val ordered = observations.sortedWith(
                compareBy<StoryStateCompiler.EvidenceState>(
                    { it.sourceChapterOrder },
                    { it.sourceChapterId },
                    { it.claim },
                ),
            )
            val earliest = ordered.first()
            val latest = ordered.last()
            if (latest.statusAfter != "open") return@mapNotNull null
            StoryStateCompiler.CommitmentState(
                id = entityId,
                type = latest.type,
                name = latest.subject,
                summary = latest.claim,
                firstSeenChapterId = earliest.sourceChapterId,
            )
        }

        val compilation = StoryStateCompiler.compile(
            StoryStateCompiler.Input(
                language = lang,
                arcs = snapshot.arcs.map {
                    StoryStateCompiler.ArcState(
                        id = it.id,
                        title = it.title,
                        summary = it.summary,
                        status = it.status,
                        order = it.order,
                    )
                },
                // Chapter summaries are represented in recentChapters below. Keeping them out of
                // the general summary section avoids paying twice for the same continuity fact.
                summaries = snapshot.summaries.filter { it.scopeType != "chapter" }.map {
                    StoryStateCompiler.SummaryState(
                        scope = it.scopeType,
                        scopeId = it.scopeId,
                        text = it.summaryText,
                        stale = it.isStale,
                    )
                },
                commitments = ledgerCommitments + snapshot.entities
                    // Old projects without evidence retain the conservative aggregate fallback.
                    .filter { it.id !in evidenceByEntity && it.status == "open" }
                    .map {
                        StoryStateCompiler.CommitmentState(
                            id = it.id,
                            type = it.entityType,
                            name = it.canonicalName,
                            summary = it.summary,
                            firstSeenChapterId = it.firstSeenChapterId,
                        )
                    },
                evidence = evidenceStates,
                characters = snapshot.characters.mapNotNull { character ->
                    val latest = snapshot.characterGrowth
                        .firstOrNull { it.characterId == character.id }
                        ?.latest
                    // Unsafe historical currentRealm values were removed while capturing the same
                    // immutable prompt snapshot used by the context manifest.
                    val realm = character.currentRealmId
                        ?.let(realmsById::get)
                        ?.let { realm ->
                            val subRealm = character.currentSubRealmId?.let(subRealmsById::get)
                            listOfNotNull(realm.name, subRealm?.name).joinToString(" · ")
                        }
                    val value = buildList {
                        realm?.takeIf { it.isNotBlank() }?.let {
                            add(if (lang == "en") "Current realm: $it" else "当前境界：$it")
                        }
                        latest?.value?.takeIf { it.isNotBlank() }?.let(::add)
                    }.joinToString("；")
                    if (character.name.isBlank() || value.isBlank()) return@mapNotNull null
                    StoryStateCompiler.CharacterState(
                        characterId = character.id,
                        name = character.name,
                        value = value,
                        chapterOrder = latest?.chapterOrder,
                    )
                },
                trackedState = snapshot.containerValues.mapNotNull { source ->
                    val container = containersById[source.containerId] ?: return@mapNotNull null
                    val latest = source.latest?.value?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val blockLabel = when (container.type) {
                        Container.BY_CHARACTER -> characterNames[source.blockKey]
                        Container.BY_CHAPTER -> chaptersById[source.blockKey]?.let {
                            if (lang == "en") "Chapter ${it.orderIndex}: ${it.title}"
                            else "第${it.orderIndex}章《${it.title}》"
                        }
                        else -> if (lang == "en") "Global" else "全局"
                    } ?: source.blockKey
                    StoryStateCompiler.TrackedState(
                        containerId = container.id,
                        containerName = container.name,
                        blockKey = source.blockKey,
                        blockLabel = blockLabel,
                        value = latest,
                    )
                },
                recentChapters = snapshot.previousBodies.mapNotNull { (chapterId, body) ->
                    val source = chaptersById[chapterId] ?: return@mapNotNull null
                    val effective = body.final.ifBlank { body.draft }
                    val summary = chapterSummaries[chapterId]?.summaryText?.takeIf { it.isNotBlank() }
                    if (effective.isBlank() && summary == null) return@mapNotNull null
                    StoryStateCompiler.RecentChapterState(
                        chapterId = chapterId,
                        order = source.orderIndex,
                        title = source.title,
                        goal = source.outlineGoal,
                        summary = summary,
                        bodyTail = effective.takeLast(1_500).takeIf { it.isNotBlank() },
                    )
                },
                retrievedMemory = retrieved.mapIndexed { index, chunk ->
                    StoryStateCompiler.MemoryState(
                        rank = index + 1,
                        sourceId = chunk.sourceId,
                        sourceTitle = chunk.sourceTitle,
                        chunkIndex = chunk.chunkIndex,
                        text = chunk.text.take(800),
                    )
                },
            ),
        )
        return compilation.prompt.takeIf { it.isNotBlank() }
    }

    /**
     * Fit semantic chapter-prompt blocks before rendering one combined user message. Fixed shares
     * keep the slow prefix independent from later chapter-specific block sizes. Author drafts and
     * the immediate task are required and therefore fail clearly instead of being truncated.
     */
    private fun budgetChapterContext(
        cfg: TextModelConfig,
        systemPrompt: String,
        requiredTaskText: String,
        worldSetting: String?,
        timeline: String?,
        charactersInfo: String?,
        storyState: String?,
        targetConstraints: String?,
        chapterList: String?,
        draftReference: String?,
    ): BudgetedChapterContext {
        val allocation = PromptRequestBudgeter.allocateSections(
            config = cfg,
            systemPrompt = systemPrompt,
            requiredTaskText = requiredTaskText,
            sections = listOf(
                PromptRequestBudgeter.Section("world", worldSetting, weight = 25),
                PromptRequestBudgeter.Section("timeline", timeline, weight = 10),
                PromptRequestBudgeter.Section("characters", charactersInfo, weight = 15),
                // Story State order is core -> recent -> RAG, so head clipping discards RAG first.
                PromptRequestBudgeter.Section("story_state", storyState, weight = 35),
                PromptRequestBudgeter.Section("target_constraints", targetConstraints, weight = 7),
                PromptRequestBudgeter.Section("chapter_list", chapterList, weight = 8),
                PromptRequestBudgeter.Section(
                    "author_draft",
                    draftReference,
                    required = true,
                    trimPolicy = PromptRequestBudgeter.TrimPolicy.MIDDLE,
                ),
            ),
        )
        fun value(key: String): String? = allocation.values[key]
        return BudgetedChapterContext(
            worldSetting = value("world"),
            timeline = value("timeline"),
            charactersInfo = value("characters"),
            storyState = value("story_state"),
            targetConstraints = value("target_constraints"),
            chapterList = value("chapter_list"),
            draftReference = value("author_draft"),
        )
    }

    /** Settings-page knowledge base (embedding index): retrieve the top-K chunks most relevant to
     *  [query]. Returns null when KB is disabled, unconfigured, empty, or nothing matches. */
    private suspend fun buildKbIndexRetrieval(
        projectId: String, query: String, lang: String, excludeSourceIds: Set<String> = emptySet(),
    ): String? {
        if (!repo.knowledgeBaseEnabled() || query.isBlank()) return null
        val cfg = repo.embeddingConfig()
        if (!cfg.isUsableApiConfig()) return null
        // Never compare a query vector against chunks from another embedding model/space.
        val pool = repo.chunks(projectId).filter { it.embeddingModel == cfg.model }
        if (pool.isEmpty()) return null
        return runCatching {
            kb.retrieveTopK(query = query, pool = pool, topK = 4, excludeSourceIds = excludeSourceIds, cfg = cfg)
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.toPromptContext(lang)
    }

    /**
     * Shared, full PLANNING context so neither the manual planner (generateArcMiniOutline) nor the
     * agent planners (agentRefineChapterPlan / agentPlanArcChapters) ever plan blind. Pulls together
     * every relevant source so the plan can't mislead generation:
     *   - 大纲 + 境界体系
     *   - 所属副本(volume) + 弧线 + 全局弧线进度
     *   - 角色档案 + 角色成长路线
     *   - 前文梗概（最近若干章正文节选）
     *   - 容器知识库（手动条目 + 自演化最新值，含全部容器）
     *   - 设置页知识库索引（按 [query] 语义检索 top-K）
     * [currentOrderIndex] bounds the 前文 window; [excludeChapterId] is dropped from KB retrieval.
     */
    private suspend fun buildPlanningContext(
        projectId: String,
        arc: PlotArc?,
        query: String,
        lang: String,
        currentOrderIndex: Int,
        excludeChapterId: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        repo.outline(projectId).takeIf { it.isNotBlank() }?.let { parts += "【大纲】\n${it.take(2000)}" }
        buildRealmSystemContext(repo.cultivationRealms(projectId), lang).takeIf { it.isNotBlank() }?.let { parts += it }
        arc?.volumeId?.let { vid -> repo.volumes(projectId).firstOrNull { it.id == vid } }?.let { vol ->
            parts += "【所属副本】${vol.name}" + (vol.description.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
            // Per-volume realm ceiling (hard limit) — prevents over-leveling / skips / drops while PLANNING.
            buildVolumeRealmConstraint(vol.realmPlan, vol.name, lang, phase = "plan").takeIf { it.isNotBlank() }?.let { parts += it }
        }
        arc?.let { parts += "【所属弧线】${it.title}：${it.summary}" }
        buildArcContext(repo.plotArcs(projectId), lang).takeIf { it.isNotBlank() }?.let { parts += it }
        buildCharactersInfo(projectId)?.let { parts += "【角色】\n$it" }
        buildCharacterGrowthGuidance(projectId, lang)?.let { parts += it }
        buildPreviousChapterSummary(projectId, currentOrderIndex).takeIf { it.isNotBlank() }?.let { parts += "【前文梗概】\n$it" }
        buildContainerKnowledgeForPlanning(projectId, lang)?.let { parts += it }
        buildKbIndexRetrieval(projectId, query, lang, excludeChapterId?.let { setOf(it) } ?: emptySet())?.let { parts += it }
        return parts.joinToString("\n\n")
    }

    /** Inject each character's latest growth-route entry as soft guidance for chapter generation. */
    private fun buildCharacterGrowthGuidance(projectId: String, lang: String): String? {
        val chars = repo.characters(projectId)
        val lines = chars.mapNotNull { c ->
            val latest = repo.characterGrowth(projectId, c.id).lastOrNull()?.value?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            "${c.name}：${latest.take(300)}"
        }
        if (lines.isEmpty()) return null
        val header = if (lang == "en") "[Character Growth — current state, evolve naturally from here]"
        else "【角色成长 — 当前状态，请在此基础上自然演进，保持前后一致】"
        return header + "\n" + lines.joinToString("\n")
    }

    /** Render each container's latest per-block values into prompt blocks (shared by generation
     *  guidance and planning context). [recentChapters] bounds how many tail chapters a
     *  BY_CHAPTER container contributes. */
    private fun renderContainerBlocks(projectId: String, containers: List<Container>, recentChapters: Int): List<String> {
        val blocks = mutableListOf<String>()
        containers.forEach { c ->
            when (c.type) {
                Container.BY_CHARACTER -> {
                    val lines = repo.characters(projectId).mapNotNull { ch ->
                        val v = repo.containerEntries(projectId, c.id, ch.id).lastOrNull()?.value?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        "${ch.name}：${v.take(300)}"
                    }
                    if (lines.isNotEmpty()) blocks += "《${c.name}》\n" + lines.joinToString("\n")
                }
                Container.BY_CHAPTER -> {
                    val recent = repo.chapters(projectId).sortedBy { it.order_index }.takeLast(recentChapters)
                    val lines = recent.mapNotNull { ch ->
                        val v = repo.containerEntries(projectId, c.id, ch.id).lastOrNull()?.value?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        "第${ch.order_index}章：${v.take(200)}"
                    }
                    if (lines.isNotEmpty()) blocks += "《${c.name}》\n" + lines.joinToString("\n")
                }
                else -> {
                    val v = repo.containerEntries(projectId, c.id, Container.SINGLE_BLOCK_KEY).lastOrNull()?.value
                    if (!v.isNullOrBlank()) blocks += "《${c.name}》：${v.take(400)}"
                }
            }
        }
        return blocks
    }

    /** For PLANNING: surface ALL container knowledge (manual entries + AI-evolved values), regardless
     *  of the affectsGeneration flag, so the planner sees tracked stats / relations / items / 伏笔. */
    private fun buildContainerKnowledgeForPlanning(projectId: String, lang: String): String? {
        val blocks = renderContainerBlocks(projectId, repo.containers(projectId), recentChapters = 5)
        if (blocks.isEmpty()) return null
        val header = if (lang == "en")
            "[Knowledge containers — manual + tracked state; use as a planning reference and keep consistent]"
        else
            "【资料容器 — 手动知识库与追踪状态，规划时作为依据，保持前后一致】"
        return header + "\n" + blocks.joinToString("\n\n")
    }

    private fun parseAppearanceJson(raw: String): Pair<String, String>? = runCatching {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = Json.parseToJsonElement(cleaned).let {
            it as? kotlinx.serialization.json.JsonObject
        } ?: return@runCatching null
        val appearance = (obj["appearance"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val imagePrompt = (obj["image_prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (appearance.isBlank() && imagePrompt.isBlank()) null else appearance to imagePrompt
    }.getOrNull()

    private data class ParsedArc(val title: String, val summary: String, val chapterCount: Int, val miniOutline: String?)

    private fun parsePlotArcJson(raw: String): ParsedArc? = runCatching {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = Json.parseToJsonElement(cleaned) as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        ParsedArc(
            title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            chapterCount = ((obj["chapter_count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()) ?: 10,
            miniOutline = (obj["mini_outline"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }.getOrNull()

    private fun TextModelConfig.isValid(): Boolean =
        isUsableApiConfig()

    /** Thinking tokens share the completion allowance, so only non-thinking stages use tight caps. */
    private fun TextModelConfig.withStageOutputLimit(limit: Int): TextModelConfig {
        val usesDeepSeekThinking = isDirectDeepSeek(this) &&
            TextModelThinkingModes.normalize(thinkingMode) != TextModelThinkingModes.DISABLED
        return if (usesDeepSeekThinking) this else copy(maxOutputTokens = minOf(maxOutputTokens, limit))
    }

    /** Reserve visible generated text conservatively; thinking models retain their full allowance. */
    private fun stepwiseGeneratedTextReserve(config: TextModelConfig, marker: String): String =
        marker.repeat(PromptRequestBudgeter.maxOutputTokens(config) * 3 / 2)

    private fun countWords(text: String): Int {
        // Mixed-script approximation: every CJK char counts as 1, latin words split on whitespace.
        var count = 0
        var inWord = false
        for (ch in text) {
            if (isCjk(ch)) {
                count += 1; inWord = false
            } else if (ch.isLetterOrDigit()) {
                if (!inWord) { count += 1; inWord = true }
            } else {
                inWord = false
            }
        }
        return count
    }

    private fun isCjk(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF

    // ── Knowledge Base ─────────────────────────────────────────────────────

    private val kb = KbService()

    fun knowledgeBaseEnabled(): Boolean = repo.knowledgeBaseEnabled()
    fun setKnowledgeBaseEnabled(b: Boolean) = repo.setKnowledgeBaseEnabled(b)
    fun summariesEnabled(): Boolean = repo.summariesEnabled()
    fun setSummariesEnabled(b: Boolean) = repo.setSummariesEnabled(b)
    fun entitiesEnabled(): Boolean = repo.entitiesEnabled()
    fun setEntitiesEnabled(b: Boolean) = repo.setEntitiesEnabled(b)

    // ── Chapter generation toggles (editor switches) ───────────────────────
    fun stepwiseChapterGen(): Boolean = repo.stepwiseChapterGen()
    fun setStepwiseChapterGen(b: Boolean) = repo.setStepwiseChapterGen(b)
    fun useDraftReference(): Boolean = repo.useDraftReference()
    fun setUseDraftReference(b: Boolean) = repo.setUseDraftReference(b)

    /** Bumps whenever the vector-chunk store changes — Settings KB stats observe this to refresh. */
    val kbRevision = repo.kbRevision

    fun kbStats(projectId: String) = repo.kbStats(projectId)
    fun summariesOf(projectId: String) = repo.summaries(projectId)
    fun entitiesOf(projectId: String) = repo.entities(projectId)
    fun factEvidenceOf(projectId: String) = repo.factEvidence(projectId)
    fun factEvidenceCoverageOf(projectId: String): FactEvidenceCoverage {
        val eligible = repo.captureProjectSource(projectId)
            ?.chapters
            .orEmpty()
            .filter { it.effectiveText.trim().length >= MIN_FACT_EXTRACTION_CHARS }
        val batchesByChapter = repo.factEvidence(projectId).groupBy { it.chapterId }
        val characterSignature = repo.characterSourceSignature(repo.characters(projectId))
        val matching = eligible.flatMap { input ->
            batchesByChapter[input.source.chapterId]
                .orEmpty()
                .filter { batch ->
                    FactEvidenceLedger.isFresh(
                        batch = batch,
                        chapterId = input.source.chapterId,
                        chapterTitle = input.source.title,
                        sourceHash = input.source.textHash,
                        characterSignature = characterSignature,
                    )
                }
        }
        return FactEvidenceCoverage(
            claims = matching.sumOf { it.facts.size },
            coveredChapters = eligible.count { input ->
                matching.any { it.chapterId == input.source.chapterId }
            },
            eligibleChapters = eligible.size,
        )
    }
    fun setEntityStatus(projectId: String, entityId: String, status: String) =
        repo.setEntityStatus(projectId, entityId, status)

    suspend fun testEmbeddingConnection(
        config: EmbeddingConfig = repo.embeddingConfig(),
    ): Boolean = withContext(Dispatchers.IO) {
        config.isUsableApiConfig() && kb.testConnection(config)
    }

    /** Bind an externally supplied save callback to the exact live title/effective prose. */
    private fun matchingChapterSource(
        projectId: String,
        chapterId: String,
        title: String,
        text: String,
    ): AppRepository.ChapterDerivedInput? = repo.captureChapterSource(projectId, chapterId)
        ?.takeIf { it.source.title == title && it.effectiveText == text }

    /** Re-embed every written chapter for [projectId] using the current EmbeddingConfig. */
    fun rebuildKnowledgeBase(
        projectId: String,
        onProgress: (current: Int, total: Int, chapterTitle: String) -> Unit = { _, _, _ -> },
        // `firstError` carries the real provider message from the very first failure so the user
        // can actually see WHY a rebuild failed (e.g. DashScope's "InvalidParameter.BatchSize"),
        // rather than just a tally of fails.
        onDone: (chapters: Int, chunks: Int, errors: Int, firstError: String?) -> Unit = { _, _, _, _ -> },
    ): Job? {
        val cfg = repo.embeddingConfig()
        if (!cfg.isUsableApiConfig()) {
            _statusMessage.value = "请先在「本地知识库」中填写 Embedding 配置 / Configure Embedding first."
            return null
        }
        val baseline = repo.captureProjectSource(projectId) ?: run {
            onDone(0, 0, 1, "项目已不存在，未开始重建")
            return null
        }
        val embeddingSignature = repo.embeddingSourceSignature(cfg)
        val eligible = baseline.chapters.filter { it.effectiveText.trim().length > 200 }
        return viewModelScope.launch(Dispatchers.IO) {
            if (eligible.isEmpty()) {
                _statusMessage.value = "无可索引章节（正文需 > 200 字） / No chapters > 200 chars to index."
                onDone(0, 0, 0, null)
                return@launch
            }
            val stagedChunks = mutableListOf<KbChunk>()
            val stagedHashes = mutableMapOf<String, String>()
            var errors = 0
            var firstError: String? = null
            eligible.forEachIndexed { idx, input ->
                val cid = input.source.chapterId
                val title = input.source.title
                val text = input.effectiveText
                currentCoroutineContext().ensureActive()
                onProgress(idx + 1, eligible.size, title)
                runSuspendCatching { kb.indexChapter(cid, title, text, cfg) }
                    .onSuccess { chunks ->
                        stagedChunks += chunks
                        stagedHashes[cid] = com.example.novelseek_ultra.data.SnapshotStore.sha1(text)
                    }
                    .onFailure { err ->
                        errors += 1
                        val msg = err.message ?: err::class.simpleName ?: "unknown"
                        if (firstError == null) firstError = "「$title」→ $msg"
                    }
            }
            currentCoroutineContext().ensureActive()
            if (errors > 0) {
                onDone(
                    eligible.size - errors,
                    0,
                    errors,
                    "重建未提交，已保留原索引；${firstError.orEmpty()}",
                )
                return@launch
            }
            // Commit only after every embedding succeeded. Cancellation/failure leaves the old KB
            // and hashes untouched. The repository re-checks every chapter source at one boundary.
            val committed = repo.commitRebuiltKbIfSourcesCurrent(
                projectId = projectId,
                expected = baseline.source,
                expectedEmbeddingSignature = embeddingSignature,
                expectedEmbeddingModel = cfg.model,
                rebuiltChunks = stagedChunks,
                rebuiltHashes = stagedHashes,
            )
            if (!committed) {
                onDone(0, 0, 1, "重建期间章节集合、标题或正文已变化，旧结果未提交")
                return@launch
            }
            onDone(eligible.size, stagedChunks.size, 0, null)
        }
    }

    /**
     * Fan out KB maintenance jobs after a successful chapter save:
     *   - re-embed the chapter into the vector store (if KB enabled)
     *   - regenerate the chapter summary (if summaries enabled)
     *   - extract entities from the chapter (if entities enabled)
     *
     * All three are fire-and-forget; failures surface via [statusMessage] but never block the save.
     */
    fun onChapterSaved(projectId: String, chapterId: String, title: String, text: String) {
        val textLength = text.trim().length
        if (textLength == 0) return
        if (textLength >= 200) {
            indexChapterIfEnabled(projectId, chapterId, title, text)
            if (repo.summariesEnabled()) generateChapterSummary(projectId, chapterId, title, text)
        }
        if (repo.entitiesEnabled() && textLength >= MIN_FACT_EXTRACTION_CHARS) {
            extractEntitiesForChapter(projectId, chapterId, title, text)
        }
        updateContainersForChapter(projectId, chapterId, title, text)
    }

    /** Index a single chapter (called after a successful chapter save when KB is enabled). */
    fun indexChapterIfEnabled(projectId: String, chapterId: String, title: String, text: String) {
        if (!repo.knowledgeBaseEnabled()) return
        val cfg = repo.embeddingConfig()
        if (!cfg.isUsableApiConfig()) return
        if (text.trim().length < 200) return
        val input = matchingChapterSource(projectId, chapterId, title, text) ?: return
        val embeddingSignature = repo.embeddingSourceSignature(cfg)
        viewModelScope.launch(Dispatchers.IO) {
            runSuspendCatching {
                val chunks = kb.indexChapter(chapterId, title, input.effectiveText, cfg)
                currentCoroutineContext().ensureActive()
                val committed = repo.commitChapterKbIfSourceCurrent(
                    projectId = projectId,
                    expected = input.source,
                    expectedEmbeddingSignature = embeddingSignature,
                    expectedEmbeddingModel = cfg.model,
                    newChunks = chunks,
                    indexHash = com.example.novelseek_ultra.data.SnapshotStore.sha1(input.effectiveText),
                )
                if (!committed) _statusMessage.value = "章节已更新，已丢弃过期 KB 索引结果"
            }.onFailure { _statusMessage.value = "KB 索引失败：${it.message}" }
        }
    }

    /** Generate / refresh a chapter summary. */
    fun generateChapterSummary(
        projectId: String,
        chapterId: String,
        chapterTitle: String,
        chapterText: String,
        onDone: (SummaryPayload?) -> Unit = {},
    ) {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; onDone(null); return }
        val input = matchingChapterSource(projectId, chapterId, chapterTitle, chapterText)
            ?: run { onDone(null); return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val text = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.chapterSummarySystem(lang)),
                    ChatMessage("user", Prompts.chapterSummaryUser(input.source.title, input.effectiveText, lang)),
                ))
            }.getOrNull()?.trim() ?: run { onDone(null); return@launch }
            currentCoroutineContext().ensureActive()
            val payload = SummaryPayload(
                id = "sum-ch-$chapterId",
                scopeType = "chapter",
                scopeId = chapterId,
                summaryText = text,
                isStale = false,
                wordCount = text.length,
            )
            val committed = repo.commitChapterSummaryIfSourceCurrent(projectId, input.source, payload)
            onDone(payload.takeIf { committed })
        }
    }

    fun generateChapterSummariesForAll(
        projectId: String,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDone: (ok: Int, errors: Int) -> Unit = { _, _ -> },
    ): Job? {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; return null }
        val lang = _uiLanguage.value
        val baseline = repo.captureProjectSource(projectId) ?: return null
        val eligible = baseline.chapters.filter { it.effectiveText.trim().length > 200 }
        return viewModelScope.launch(Dispatchers.IO) {
            var ok = 0; var errors = 0
            eligible.forEachIndexed { idx, input ->
                val cid = input.source.chapterId
                val title = input.source.title
                val text = input.effectiveText
                currentCoroutineContext().ensureActive()
                onProgress(idx + 1, eligible.size, title)
                val summaryResult = runSuspendCatching {
                    ai.chat(cfg, listOf(
                        ChatMessage("system", Prompts.chapterSummarySystem(lang)),
                        ChatMessage("user", Prompts.chapterSummaryUser(title, text, lang)),
                    ))
                }
                currentCoroutineContext().ensureActive()
                summaryResult.onSuccess { reply ->
                        val s = reply.trim()
                        if (s.isNotBlank()) {
                            val payload = SummaryPayload(
                                id = "sum-ch-$cid",
                                scopeType = "chapter",
                                scopeId = cid,
                                summaryText = s,
                                wordCount = s.length,
                            )
                            if (repo.commitChapterSummaryIfSourceCurrent(projectId, input.source, payload)) {
                                ok += 1
                            } else {
                                errors += 1
                            }
                        } else errors += 1
                    }
                    .onFailure { errors += 1 }
            }
            onDone(ok, errors)
        }
    }

    /** Generate / refresh the whole-book summary from chapter + arc summaries. */
    fun generateBookSummary(
        projectId: String,
        onDone: (SummaryPayload?) -> Unit = {},
    ): Job? {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; onDone(null); return null }
        val projectInput = repo.captureProjectSource(projectId)
            ?: run { onDone(null); return null }
        val all = repo.summaries(projectId)
        val summarySignature = repo.summarySourceSignature(all)
        val chapterSums = all.filter { it.scopeType == "chapter" }.map { it.summaryText }
        val arcSums = all.filter { it.scopeType == "arc" }.map { it.summaryText }
        val layers = (arcSums + chapterSums).filter { it.isNotBlank() }
        if (layers.isEmpty()) {
            _statusMessage.value = "尚未有任何章节摘要，先生成章节摘要 / Build chapter summaries first."
            onDone(null)
            return null
        }
        val lang = _uiLanguage.value
        return viewModelScope.launch(Dispatchers.IO) {
            val text = runSuspendCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.bookSummarySystem(lang)),
                    ChatMessage(
                        "user",
                        Prompts.bookSummaryUser(
                            projectInput.source.projectTitle,
                            projectInput.source.projectDescription,
                            layers,
                            lang,
                        ),
                    ),
                ))
            }.getOrNull()?.trim() ?: run { onDone(null); return@launch }
            currentCoroutineContext().ensureActive()
            val payload = SummaryPayload(
                id = "sum-book-$projectId",
                scopeType = "book",
                scopeId = projectId,
                summaryText = text,
                isStale = false,
                wordCount = text.length,
            )
            val committed = repo.commitBookSummaryIfSourceCurrent(
                projectId = projectId,
                expectedProject = projectInput.source,
                expectedSummarySignature = summarySignature,
                payload = payload,
            )
            onDone(payload.takeIf { committed })
        }
    }

    /** Extract entities from one chapter and merge into project's entity bag. */
    fun extractEntitiesForChapter(
        projectId: String,
        chapterId: String,
        chapterTitle: String,
        chapterText: String,
        onDone: (added: Int, updated: Int) -> Unit = { _, _ -> },
    ) {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; onDone(0, 0); return }
        val ticket = repo.captureFactExtractionTicket(projectId, chapterId)
            ?.takeIf { it.input.source.title == chapterTitle && it.input.effectiveText == chapterText }
            ?: run { onDone(0, 0); return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val result = runSuspendCatching {
                requestAndMergeChapterFacts(
                    cfg = cfg,
                    projectId = projectId,
                    input = ticket.input,
                    expectedCharacterSignature = ticket.characterSignature,
                    expectedFactEvidenceEpoch = ticket.evidenceEpoch,
                    knownNames = ticket.knownNames,
                    lang = lang,
                )
            }.getOrNull()
            onDone(result?.first ?: 0, result?.second ?: 0)
        }
    }

    fun rebuildFactEvidenceForAll(
        projectId: String,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDone: (ok: Int, errors: Int) -> Unit = { _, _ -> },
    ): Job? {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid()) {
            _statusMessage.value = "请先配置文本模型"
            onDone(0, 1)
            return null
        }
        val tickets = repo.captureProjectFactExtractionTickets(projectId)
        if (tickets == null) {
            onDone(0, 1)
            return null
        }
        val eligible = tickets
            .filter { it.input.effectiveText.trim().length >= MIN_FACT_EXTRACTION_CHARS }
        val lang = _uiLanguage.value
        return viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var errors = 0
            eligible.forEachIndexed { index, ticket ->
                currentCoroutineContext().ensureActive()
                onProgress(index + 1, eligible.size, ticket.input.source.title)
                val result = runSuspendCatching {
                    requestAndMergeChapterFacts(
                        cfg = cfg,
                        projectId = projectId,
                        input = ticket.input,
                        expectedCharacterSignature = ticket.characterSignature,
                        expectedFactEvidenceEpoch = ticket.evidenceEpoch,
                        knownNames = ticket.knownNames,
                        lang = lang,
                    )
                }.getOrNull()
                if (result != null) ok += 1 else errors += 1
            }
            onDone(ok, errors)
        }
    }

    private suspend fun requestAndMergeChapterFacts(
        cfg: TextModelConfig,
        projectId: String,
        input: AppRepository.ChapterDerivedInput,
        expectedCharacterSignature: String,
        expectedFactEvidenceEpoch: Long,
        knownNames: List<String>,
        lang: String,
    ): Pair<Int, Int>? {
        val reply = ai.chat(
            cfg.withStageOutputLimit(BLUEPRINT_MAX_OUTPUT_TOKENS),
            listOf(
                ChatMessage("system", Prompts.entityExtractionSystem(lang)),
                ChatMessage(
                    "user",
                    Prompts.entityExtractionUser(
                        input.source.title,
                        input.effectiveText,
                        knownNames,
                        lang,
                    ),
                ),
            ),
        )
        currentCoroutineContext().ensureActive()
        return mergeEntitiesFromJson(
            projectId,
            input.source,
            expectedCharacterSignature,
            expectedFactEvidenceEpoch,
            input.effectiveText,
            reply,
        )
    }

    private fun mergeEntitiesFromJson(
        projectId: String,
        source: ChapterSourceVersion,
        expectedCharacterSignature: String,
        expectedFactEvidenceEpoch: Long,
        sourceText: String,
        raw: String,
    ): Pair<Int, Int>? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(cleaned).jsonObject
        }.getOrNull() ?: return null

        return repo.updateEntitiesAndEvidenceIfSourceCurrent(
            projectId, source, expectedCharacterSignature, expectedFactEvidenceEpoch,
        ) { current, currentEvidence, chapterOrders ->
            var added = 0; var updated = 0
            val existing = current.toMutableList()
            val observations = mutableListOf<FactEvidenceLedger.Observation>()

            fun upsert(
                name: String,
                type: String,
                summary: String,
                aliases: List<String> = emptyList(),
                status: String = "open",
                evidenceText: String = "",
            ) {
                val canonical = name.trim().ifBlank { return }
                val normalizedStatus = status.takeIf {
                    it == "open" || it == "paid_off" || it == "archived"
                } ?: "open"
                val matchIdx = EntityReconciliation.matchIndex(
                    existing = existing,
                    entityType = type,
                    canonicalName = canonical,
                    sourceChapterId = source.chapterId,
                )
                if (matchIdx >= 0) {
                    val cur = existing[matchIdx]
                    existing[matchIdx] = EntityReconciliation.merge(
                        current = cur,
                        sourceChapterId = source.chapterId,
                        aliases = aliases,
                        summary = summary,
                        extractedStatus = normalizedStatus,
                    )
                    updated += 1
                } else {
                    existing += EntityPayload(
                        id = "ent-${type}-${System.currentTimeMillis()}-${added + updated}",
                        entityType = type,
                        canonicalName = canonical,
                        aliases = aliases.filter { it.isNotBlank() && it != canonical }.distinct(),
                        summary = summary,
                        status = normalizedStatus,
                        firstSeenChapterId = source.chapterId,
                        lastSeenChapterId = source.chapterId,
                    )
                    added += 1
                }
                val committedEntity = if (matchIdx >= 0) existing[matchIdx] else existing.last()
                val verifiedEvidence = FactEvidenceLedger.verifiedExcerpt(sourceText, evidenceText)
                if (verifiedEvidence.isNotBlank()) {
                    observations += FactEvidenceLedger.Observation(
                        entityId = committedEntity.id,
                        factType = type,
                        subject = committedEntity.canonicalName,
                        claim = summary,
                        statusAfter = normalizedStatus,
                        evidenceText = verifiedEvidence,
                    )
                }
            }

            fun processGroup(key: String, type: String) {
                val arr = root[key]?.jsonArray ?: return
                arr.forEach { e ->
                    val obj = e.jsonObject
                    val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    val summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    val status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "open"
                    val evidenceText = (obj["evidence"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content.orEmpty()
                    val aliases = obj["aliases"]?.jsonArray?.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: emptyList()
                    upsert(
                        name = name,
                        type = type,
                        summary = summary,
                        aliases = aliases,
                        status = status,
                        evidenceText = evidenceText,
                    )
                }
            }

            processGroup("characters", "character_ref")
            processGroup("foreshadowing", "foreshadowing")
            processGroup("locations", "location")
            processGroup("events", "event")
            processGroup("items", "item")

            val nextEvidence = FactEvidenceLedger.replaceChapter(
                existing = currentEvidence,
                chapterId = source.chapterId,
                sourceHash = source.textHash,
                extractionInputHash = FactEvidenceLedger.extractionInputHash(
                    chapterId = source.chapterId,
                    chapterTitle = source.title,
                    sourceHash = source.textHash,
                    characterSignature = expectedCharacterSignature,
                ),
                observations = observations,
            )
            val evidenceByEntity = nextEvidence.asSequence()
                .filterNot { it.isStale }
                .flatMap { batch ->
                    val order = chapterOrders[batch.chapterId] ?: return@flatMap emptySequence()
                    batch.facts.asSequence().mapNotNull { fact ->
                        fact.entityId?.let { entityId ->
                            entityId to EntityReconciliation.TimedEvidence(
                                chapterId = batch.chapterId,
                                chapterOrder = order,
                                summary = fact.claim,
                                statusAfter = fact.statusAfter,
                            )
                        }
                    }
                }
                .groupBy({ it.first }, { it.second })
            val reconciled = existing.map { entity ->
                EntityReconciliation.reconcileWithEvidence(
                    current = entity,
                    evidence = evidenceByEntity[entity.id].orEmpty(),
                    chapterOrdersById = chapterOrders,
                )
            }
            Triple(reconciled, nextEvidence, added to updated)
        }
    }

    fun forgetKbForChapter(projectId: String, chapterId: String) {
        repo.forgetKbSource(projectId, "chapter", chapterId)
        repo.forgetSummary(projectId, "chapter", chapterId)
        repo.forgetEntitiesForChapter(projectId, chapterId)
        repo.removeKbIndexHash(projectId, chapterId)
        repo.clearKbStaleChapter(projectId, chapterId)
    }

    // ── Project snapshots (version history) ──────────────────────────────────

    /** Observable so the version-history screen recomposes after create/delete/restore. */
    val snapshotRevision: StateFlow<Int> = repo.snapshotRevision

    fun listSnapshots(projectId: String): List<SnapshotMeta> = repo.listSnapshots(projectId)

    /** Number of chapters whose KB vectors are stale for [projectId] (drives the rebuild banner). */
    fun kbStaleCount(projectId: String): Int = repo.kbStaleChapters(projectId).size

    fun saveSnapshot(projectId: String, label: String, onDone: (Boolean) -> Unit = {}): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val meta = runCatching {
                repo.createSnapshot(projectId, label, SnapshotMeta.TRIGGER_MANUAL)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                _statusMessage.value = if (meta != null) "已保存版本" else "保存版本失败"
                onDone(meta != null)
            }
        }
    }

    fun renameSnapshot(projectId: String, snapshotId: String, label: String) =
        repo.renameSnapshot(projectId, snapshotId, label)

    fun deleteSnapshot(projectId: String, snapshotId: String) =
        repo.deleteSnapshot(projectId, snapshotId)

    fun restoreSnapshot(projectId: String, snapshotId: String, onDone: (RestoreResult) -> Unit = {}): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val result = runSuspendCatching { repo.restoreSnapshot(projectId, snapshotId) }
                .getOrElse { error ->
                    RestoreResult(
                        success = false,
                        errorMessage = error.message ?: error::class.simpleName ?: "未知错误",
                    )
                }
            withContext(Dispatchers.Main) {
                _statusMessage.value = if (result.success) {
                    buildString {
                        append("已回退到该版本")
                        if (result.staleChapterIds.isNotEmpty()) append("，知识库有 ${result.staleChapterIds.size} 章待重建")
                    }
                } else {
                    "版本回退失败：${result.errorMessage.orEmpty()}"
                }
                onDone(result)
            }
        }
    }

    /**
     * Targeted KB rebuild: re-embed only the chapters flagged stale (e.g. after a restore), then
     * clear the stale flags. Cost scales with how much actually changed, not the whole book.
     */
    fun rebuildStaleKb(projectId: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }, onDone: (Int) -> Unit = {}) {
        val cfg = repo.embeddingConfig()
        if (!cfg.isUsableApiConfig()) {
            _statusMessage.value = "请先在「本地知识库」中填写 Embedding 配置"
            onDone(0); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val stale = repo.kbStaleChapters(projectId)
            val embeddingSignature = repo.embeddingSourceSignature(cfg)
            var rebuilt = 0
            var skipped = 0
            stale.forEachIndexed { idx, cid ->
                currentCoroutineContext().ensureActive()
                onProgress(idx + 1, stale.size)
                val input = repo.captureChapterSource(projectId, cid) ?: run {
                    if (!repo.clearKbStaleIfChapterMissing(projectId, cid)) skipped += 1
                    return@forEachIndexed
                }
                val text = input.effectiveText
                val hash = com.example.novelseek_ultra.data.SnapshotStore.sha1(text)
                if (text.trim().length < 200) {
                    // Nothing worth indexing — drop any stale vectors and clear the flag.
                    if (!repo.commitChapterKbIfSourceCurrent(
                            projectId = projectId,
                            expected = input.source,
                            expectedEmbeddingSignature = embeddingSignature,
                            expectedEmbeddingModel = cfg.model,
                            newChunks = emptyList(),
                            indexHash = hash,
                        )
                    ) {
                        skipped += 1
                    }
                    return@forEachIndexed
                }
                runSuspendCatching { kb.indexChapter(cid, input.source.title, text, cfg) }
                    .onSuccess { chunks ->
                        if (repo.commitChapterKbIfSourceCurrent(
                                projectId = projectId,
                                expected = input.source,
                                expectedEmbeddingSignature = embeddingSignature,
                                expectedEmbeddingModel = cfg.model,
                                newChunks = chunks,
                                indexHash = hash,
                            )
                        ) {
                            rebuilt += 1
                        } else {
                            skipped += 1
                        }
                    }
                    .onFailure { _statusMessage.value = "KB 重建失败「${input.source.title}」：${it.message}" }
            }
            withContext(Dispatchers.Main) {
                _statusMessage.value = when {
                    repo.project(projectId) == null -> "项目已不存在，知识库重建结果未提交"
                    skipped > 0 -> "知识库重建完成（$rebuilt 章，跳过 $skipped 个已变化来源）"
                    else -> "知识库重建完成（$rebuilt 章）"
                }
                onDone(rebuilt)
            }
        }
    }

    // ── "Ask the novel" Q&A agent ────────────────────────────────────────────

    val novelChatRevision: StateFlow<Int> = repo.novelChatRevision

    fun novelChatHistory(projectId: String): List<NovelChatMessage> = repo.novelChatHistory(projectId)

    fun clearNovelChat(projectId: String) {
        stopAskNovel()
        repo.clearNovelChat(projectId)
    }

    fun stopAskNovel() {
        synchronized(qaGenerationLock) {
            qaGenerationRevision++
            qaJob?.cancel()
            qaJob = null
            _qaGenerating.value = false
            _qaStreamingText.value = ""
        }
    }

    /**
     * Answer a free-form question about the CURRENT version of the project. Hybrid retrieval:
     * structured data (characters/realms/relationships/events/entities/summaries/chapter list) is
     * always assembled; vector RAG over chapter chunks is added when embeddings are configured.
     * The answer streams into [qaStreamingText] and, on completion, is appended to the saved chat.
     */
    fun askNovel(projectId: String, question: String) {
        val q = question.trim()
        if (q.isEmpty() || repo.project(projectId) == null) return
        val cfg = repo.activeTextModelConfig()
        val lang = repo.uiLanguage()
        val job = synchronized(qaGenerationLock) {
            if (_qaGenerating.value) return
            val revision = ++qaGenerationRevision
            qaJob?.cancel()
            repo.appendNovelChat(
                projectId,
                NovelChatMessage(
                    id = "qa-${System.currentTimeMillis()}",
                    role = "user",
                    content = q,
                    createdAt = nowIso(),
                ),
            )
            _qaStreamingText.value = ""
            _qaGenerating.value = true
            val buffer = StringBuilder()
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val context = runCatching { buildNovelQaContext(projectId, q, lang) }.getOrDefault("")
            // Prior turns (drop the question we just appended) give the agent follow-up memory.
            val history = repo.novelChatHistory(projectId).dropLast(1).takeLast(8)
            val messages = buildList {
                add(ChatMessage("system", novelQaSystemPrompt(lang)))
                // Put the large, mostly stable novel material before changing chat history and the
                // current question. Prefix-caching providers can then reuse it across Q&A turns.
                add(ChatMessage("user", buildString {
                    appendLine(if (lang == "en") "[Novel Material]" else "【小说资料】")
                    append(context.ifBlank { if (lang == "en") "(no material available)" else "（暂无资料）" })
                }))
                history.forEach { add(ChatMessage(it.role, it.content)) }
                add(ChatMessage("user", buildString {
                    appendLine(if (lang == "en") "[Question]" else "【问题】")
                    append(q)
                }))
            }
            try {
                ai.streamChat(cfg, messages).collectCompleted(onDelta = { text ->
                    currentCoroutineContext().ensureActive()
                    synchronized(qaGenerationLock) {
                        if (qaGenerationRevision != revision) {
                            throw CancellationException("Novel Q&A request was superseded")
                        }
                        buffer.append(text)
                        _qaStreamingText.value = buffer.toString()
                    }
                })
                currentCoroutineContext().ensureActive()
                val answer = buffer.toString()
                synchronized(qaGenerationLock) {
                    if (
                        qaGenerationRevision == revision &&
                        repo.project(projectId) != null &&
                        answer.isNotBlank()
                    ) {
                        repo.appendNovelChat(
                            projectId,
                            NovelChatMessage(
                                id = "qa-${System.currentTimeMillis()}",
                                role = "assistant",
                                content = answer,
                                createdAt = nowIso(),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                synchronized(qaGenerationLock) {
                    if (qaGenerationRevision == revision) {
                        _statusMessage.value = "问答失败：${error.message}"
                    }
                }
            } finally {
                synchronized(qaGenerationLock) {
                    if (qaGenerationRevision == revision) {
                        _qaGenerating.value = false
                        _qaStreamingText.value = ""
                        qaJob = null
                    }
                }
            }
            }.also { qaJob = it }
        }
        job.start()
    }

    private fun novelQaSystemPrompt(lang: String): String =
        if (lang == "en")
            "You are a knowledgeable assistant for THIS specific novel. Answer the user's question using ONLY the provided novel material (characters, cultivation realms, relationships, events, entities, summaries, and retrieved passages). Be specific and concise; cite chapter numbers when relevant. If the material does not contain the answer, say you cannot find it in the current version — never invent facts. Reply in English."
        else
            "你是这部小说的资料助手。只能依据提供的【小说资料】（角色、修炼境界、角色关系、事件、知识条目、摘要、检索到的正文片段）来回答用户的问题。回答要具体、简洁，涉及情节时尽量标注章节号。如果资料中没有相关信息，请直接说明“当前版本资料中未找到”，绝不要编造。请用中文回答。"

    /** Assemble the hybrid retrieval context for one Q&A question. */
    private suspend fun buildNovelQaContext(projectId: String, question: String, lang: String): String {
        val en = lang == "en"
        val parts = mutableListOf<String>()

        repo.project(projectId)?.let { p ->
            parts += buildString {
                append(if (en) "[Novel]" else "【小说】")
                append("\n"); append(if (en) "Title: " else "书名："); append(p.title)
                p.genre?.takeIf { it.isNotBlank() }?.let { append("\n"); append(if (en) "Genre: " else "题材："); append(it) }
                p.description?.takeIf { it.isNotBlank() }?.let { append("\n"); append(if (en) "Synopsis: " else "简介："); append(it) }
            }
        }

        val realms = repo.cultivationRealms(projectId)
        if (realms.isNotEmpty()) parts += buildRealmSystemContext(realms, lang)

        val chars = repo.characters(projectId)
        val nameById = chars.associate { it.id to it.name }
        if (chars.isNotEmpty()) {
            val realmById = realms.associateBy { it.id }
            val subById = realms.flatMap { it.subRealms ?: emptyList() }.associateBy { it.id }
            parts += (if (en) "[Characters]" else "【角色】") + "\n" + chars.joinToString("\n\n") { c ->
                buildString {
                    append("【${c.name}】")
                    if (c.isProtagonist) append(if (en) " (protagonist)" else "（主角）")
                    if (c.role.isNotBlank()) { append("\n"); append(if (en) "Role: " else "身份："); append(c.role) }
                    if (c.gender.isNotBlank()) { append("\n"); append(if (en) "Gender: " else "性别："); append(c.gender) }
                    val realmName = c.currentRealmId?.let { realmById[it]?.name }
                    if (realmName != null) {
                        val subName = c.currentSubRealmId?.let { subById[it]?.name }
                        append("\n"); append(if (en) "Current realm: " else "当前境界：")
                        append(realmName); subName?.let { append(" · "); append(it) }
                    }
                    if (c.personality.isNotBlank()) { append("\n"); append(if (en) "Personality: " else "性格："); append(c.personality) }
                    if (c.background.isNotBlank()) { append("\n"); append(if (en) "Background: " else "背景："); append(c.background) }
                    if (c.motivation.isNotBlank()) { append("\n"); append(if (en) "Motivation: " else "动机："); append(c.motivation) }
                }
            }
        }

        repo.characterRelationships(projectId).takeIf { it.isNotEmpty() }?.let { rels ->
            parts += (if (en) "[Relationships]" else "【角色关系】") + "\n" + rels.joinToString("\n") { r ->
                val from = nameById[r.fromCharId] ?: r.fromCharId
                val to = nameById[r.toCharId] ?: r.toCharId
                "- $from → $to（${r.type}）：${r.description}"
            }
        }

        repo.characterEvents(projectId).takeIf { it.isNotEmpty() }?.let { events ->
            parts += (if (en) "[Character Events]" else "【角色事件】") + "\n" +
                events.sortedBy { it.chapterIndex }.joinToString("\n") { e ->
                    val who = nameById[e.characterId] ?: e.characterId
                    "- 第${e.chapterIndex}章《${e.chapterTitle}》$who：${e.title} — ${e.description}"
                }
        }

        repo.characterRealmEvents(projectId).takeIf { it.isNotEmpty() }?.let { revs ->
            val realmById = realms.associateBy { it.id }
            parts += (if (en) "[Realm Progression]" else "【境界变化】") + "\n" +
                revs.sortedBy { it.chapterOrderIndex }.joinToString("\n") { ev ->
                    val who = nameById[ev.characterId] ?: ev.characterId
                    val realmName = realmById[ev.realmId]?.name ?: ev.realmId
                    "- 第${ev.chapterOrderIndex}章 $who → $realmName${ev.note?.let { "（$it）" } ?: ""}"
                }
        }

        repo.entities(projectId).takeIf { it.isNotEmpty() }?.let { entities ->
            parts += (if (en) "[Knowledge Entities]" else "【知识条目】") + "\n" + entities.joinToString("\n") { e ->
                val alias = if (e.aliases.isNotEmpty()) "（${e.aliases.joinToString("、")}）" else ""
                "- [${e.entityType}] ${e.canonicalName}$alias：${e.summary}"
            }
        }

        val summaries = repo.summaries(projectId)
        summaries.firstOrNull { it.scopeType == "book" && it.scopeId == projectId }
            ?.takeIf { it.summaryText.isNotBlank() }
            ?.let { parts += (if (en) "[Book Synopsis]" else "【全书梗概】") + "\n" + it.summaryText }

        repo.plotArcs(projectId).takeIf { it.isNotEmpty() }?.let { arcs ->
            parts += (if (en) "[Plot Arcs]" else "【剧情弧线】") + "\n" +
                arcs.sortedBy { it.order }.joinToString("\n") { arc ->
                    val arcSum = summaries.firstOrNull { it.scopeType == "arc" && it.scopeId == arc.id }
                        ?.summaryText?.takeIf { it.isNotBlank() } ?: arc.summary
                    "- ${arc.title}（${arc.status}）：$arcSum"
                }
        }

        repo.chapters(projectId).sortedBy { it.order_index }.takeIf { it.isNotEmpty() }?.let { chapters ->
            parts += (if (en) "[Chapters]" else "【章节列表】") + "\n" +
                chapters.joinToString("\n") { "第${it.order_index}章 ${it.title}" }
        }

        // Vector RAG over chapter passages (only when embeddings are configured + indexed).
        if (repo.knowledgeBaseEnabled()) {
            val cfg = repo.embeddingConfig()
            if (cfg.isUsableApiConfig()) {
                val pool = repo.chunks(projectId)
                if (pool.isNotEmpty()) {
                    runCatching {
                        kb.retrieveTopK(query = question, pool = pool, topK = 6, excludeSourceIds = emptySet(), cfg = cfg)
                    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { hits ->
                        parts += hits.toPromptContext(lang)
                    }
                }
            }
        }

        return parts.joinToString("\n\n")
    }

    // ── Containers (容器) ─────────────────────────────────────────────────────

    fun containers(projectId: String): List<Container> = repo.containers(projectId)
    fun container(projectId: String, containerId: String): Container? = repo.container(projectId, containerId)
    fun createContainer(projectId: String, container: Container) = repo.createContainer(projectId, container)
    fun updateContainerMeta(
        projectId: String, containerId: String, name: String,
        autoUpdatePerChapter: Boolean, affectsGeneration: Boolean,
        affectsVolumeGeneration: Boolean, affectsArcGeneration: Boolean,
    ) = repo.updateContainerMeta(projectId, containerId, name, autoUpdatePerChapter, affectsGeneration, affectsVolumeGeneration, affectsArcGeneration)
    fun deleteContainer(projectId: String, containerId: String) = repo.deleteContainer(projectId, containerId)
    fun containerEntries(projectId: String, containerId: String, blockKey: String): List<ContainerEntry> =
        repo.containerEntries(projectId, containerId, blockKey)

    /** Manually overwrite the newest value in a block (latest value is user-editable). */
    fun editLatestContainerEntry(projectId: String, containerId: String, blockKey: String, value: String) =
        repo.replaceLatestContainerEntry(projectId, containerId, blockKey, value)

    /** Manually append a value to a block (e.g. a scratch entry). */
    fun addContainerEntry(projectId: String, containerId: String, blockKey: String, value: String) {
        repo.appendContainerEntry(
            projectId, containerId, blockKey,
            ContainerEntry(id = "ce-${System.currentTimeMillis()}", value = value, createdAt = nowIso(), manual = true),
        )
    }

    /** Blocks of a container as (blockKey, label), derived live from current characters/chapters. */
    fun containerBlocks(projectId: String, container: Container): List<Pair<String, String>> = when (container.type) {
        Container.BY_CHARACTER -> repo.characters(projectId).map { it.id to it.name }
        Container.BY_CHAPTER -> repo.chapters(projectId).sortedBy { it.order_index }
            .map { it.id to "第${it.order_index}章 ${it.title}" }
        else -> listOf(Container.SINGLE_BLOCK_KEY to "主块")
    }

    /** Re-run the AI update for one container against the latest chapter (manual "立即更新" button). */
    fun updateContainerNow(projectId: String, containerId: String, onDone: (Boolean) -> Unit = {}) {
        val c = repo.container(projectId, containerId) ?: return onDone(false)
        val ch = repo.chapters(projectId).maxByOrNull { it.order_index }
        if (ch == null) { _statusMessage.value = "暂无章节可供更新"; return onDone(false) }
        val input = repo.captureChapterSource(projectId, ch.id)
            ?: run { _statusMessage.value = "章节已不存在"; return onDone(false) }
        if (input.effectiveText.isBlank()) { _statusMessage.value = "最新章节暂无正文"; return onDone(false) }
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; return onDone(false) }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runSuspendCatching {
                updateOneContainer(projectId, c, input.source, input.effectiveText, cfg, _uiLanguage.value)
            }
            val ok = outcome.getOrDefault(false)
            withContext(Dispatchers.Main) {
                _statusMessage.value = when {
                    outcome.isFailure -> "容器更新失败：${outcome.exceptionOrNull()?.message.orEmpty()}"
                    ok -> "容器《${c.name}》已更新"
                    else -> "章节或容器已变化，旧结果未写入"
                }
                onDone(ok)
            }
        }
    }

    /** Fan out per-chapter AI updates for every auto-update container (called from onChapterSaved). */
    private fun updateContainersForChapter(projectId: String, chapterId: String, title: String, text: String) {
        val cfg = repo.textModelForRole(projectId, "extraction")
        if (!cfg.isValid() || text.trim().length < 50) return
        val input = matchingChapterSource(projectId, chapterId, title, text) ?: return
        val containers = repo.containers(projectId).filter { it.autoUpdatePerChapter }
        if (containers.isEmpty()) return
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            containers.forEach { c ->
                runSuspendCatching { updateOneContainer(projectId, c, input.source, input.effectiveText, cfg, lang) }
                    .onFailure { _statusMessage.value = "容器《${c.name}》更新失败：${it.message}" }
            }
        }
    }

    private suspend fun updateOneContainer(
        projectId: String,
        container: Container,
        source: ChapterSourceVersion,
        text: String,
        cfg: TextModelConfig,
        lang: String,
    ): Boolean {
        fun entryFrom(value: String) = ContainerEntry(
            id = "ce-${System.currentTimeMillis()}-${(0..999).random()}",
            value = value.trim(),
            sourceChapterId = source.chapterId,
            sourceChapterOrder = source.orderIndex,
            sourceChapterTitle = source.title,
            createdAt = nowIso(),
        )
        return when (container.type) {
            Container.BY_CHARACTER -> {
                val chars = repo.characters(projectId)
                if (chars.isEmpty()) return false
                val characterSignature = repo.characterSourceSignature(chars)
                val latest = chars.associate { ch ->
                    ch.id to repo.containerEntries(projectId, container.id, ch.id).lastOrNull()
                }
                val perChar = chars.joinToString("\n") { ch ->
                    val v = latest[ch.id]?.value.orEmpty()
                    "【${ch.name}】${if (lang == "en") "current: " else "当前值："}${v.ifBlank { if (lang == "en") "(none)" else "（暂无）" }}"
                }
                val reply = ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.containerByCharacterSystem(container.name, lang)),
                    ChatMessage("user", Prompts.containerByCharacterUser(container.name, perChar, source.orderIndex, source.title, text, lang)),
                ))
                currentCoroutineContext().ensureActive()
                val updates = parseStringMap(reply)
                val additions = updates.mapNotNull { (name, value) ->
                    if (value.isBlank()) return@mapNotNull null
                    val ch = chars.firstOrNull { it.name == name } ?: return@mapNotNull null
                    ch.id to entryFrom(value)
                }
                if (additions.isEmpty()) {
                    repo.containerInputsAreCurrent(
                        projectId, source, container, latest,
                        expectedCharacterSignature = characterSignature,
                    )
                } else {
                    repo.appendContainerEntriesIfSourceCurrent(
                        projectId = projectId,
                        expectedSource = source,
                        expectedContainer = container,
                        expectedLatestEntries = latest,
                        expectedCharacterSignature = characterSignature,
                        additions = additions,
                    )
                }
            }
            Container.BY_CHAPTER -> {
                val latest = mapOf(
                    source.chapterId to repo.containerEntries(projectId, container.id, source.chapterId).lastOrNull(),
                )
                val reply = ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.containerByChapterSystem(container.name, lang)),
                    ChatMessage("user", Prompts.containerByChapterUser(container.name, source.orderIndex, source.title, text, lang)),
                )).trim()
                currentCoroutineContext().ensureActive()
                if (isNoChange(reply)) {
                    repo.containerInputsAreCurrent(projectId, source, container, latest)
                } else {
                    repo.appendContainerEntriesIfSourceCurrent(
                        projectId = projectId,
                        expectedSource = source,
                        expectedContainer = container,
                        expectedLatestEntries = latest,
                        additions = listOf(source.chapterId to entryFrom(reply)),
                    )
                }
            }
            else -> {
                val latestEntry = repo.containerEntries(projectId, container.id, Container.SINGLE_BLOCK_KEY).lastOrNull()
                val latest = mapOf(Container.SINGLE_BLOCK_KEY to latestEntry)
                val cur = latestEntry?.value.orEmpty()
                val reply = ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.containerSingleSystem(container.name, lang)),
                    ChatMessage("user", Prompts.containerSingleUser(container.name, cur, source.orderIndex, source.title, text, lang)),
                )).trim()
                currentCoroutineContext().ensureActive()
                if (isNoChange(reply)) {
                    repo.containerInputsAreCurrent(projectId, source, container, latest)
                } else {
                    repo.appendContainerEntriesIfSourceCurrent(
                        projectId = projectId,
                        expectedSource = source,
                        expectedContainer = container,
                        expectedLatestEntries = latest,
                        additions = listOf(Container.SINGLE_BLOCK_KEY to entryFrom(reply)),
                    )
                }
            }
        }
    }

    private fun isNoChange(reply: String): Boolean {
        val r = reply.trim().trim('"', '“', '”', '。', '.').uppercase()
        return r.isEmpty() || r == "NO_CHANGE" || r == "NOCHANGE"
    }

    private fun parseStringMap(text: String): Map<String, String> = runCatching {
        val stripped = text.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
        val start = stripped.indexOf('{'); val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        Json { ignoreUnknownKeys = true }.parseToJsonElement(stripped.substring(start, end + 1)).jsonObject
            .mapNotNull { (k, v) ->
                val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: v.toString().trim('"').takeIf { it.isNotBlank() && it != "null" }
                if (s.isNullOrBlank()) null else k to s
            }.toMap()
    }.getOrDefault(emptyMap())

    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    data class ImportPreview(
        val bundle: BackupBundle,
        val fileName: String,
        val summary: BackupSummary,
    )

    companion object {
        private const val TARGET_WORDS = 2500
        private const val MAX_CHAPTER_BEATS = 7
        private const val BLUEPRINT_MAX_OUTPUT_TOKENS = 2_000
        /** Two beats produce the largest per-beat output allowance: 1250 * 2 + 512. */
        private const val STEPWISE_PREFLIGHT_OUTPUT_TOKENS = 3_012
        private const val MIN_FACT_EXTRACTION_CHARS = 20
        private val STEPWISE_WRITTEN_TAIL_RESERVE = "文".repeat(1_500)
    }
}
