package com.example.novelseek_ultra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.ai.AiService
import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.KbService
import com.example.novelseek_ultra.data.ai.Prompts
import com.example.novelseek_ultra.data.ai.toPromptContext
import com.example.novelseek_ultra.data.model.BackupBundle
import com.example.novelseek_ultra.data.model.BackupSummary
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterPromo
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
import com.example.novelseek_ultra.data.model.Illustration
import com.example.novelseek_ultra.data.model.NovelChatMessage
import com.example.novelseek_ultra.data.model.PlotArc
import com.example.novelseek_ultra.data.model.RestoreResult
import com.example.novelseek_ultra.data.model.SnapshotMeta
import com.example.novelseek_ultra.data.model.SummaryPayload
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.data.model.Volume
import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.nowIso
import com.example.novelseek_ultra.util.buildRealmSystemContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo: AppRepository = AppRepository.get(application)
    private val ai = AiService()

    /** Audiobook (听书) playback engine — observed directly by the listen screen. */
    val audiobook = com.example.novelseek_ultra.data.audio.AudiobookController(application, repo, viewModelScope)

    /** Autonomous agent (智能体) — drives the app's operations via tool calls. */
    val agent = com.example.novelseek_ultra.agent.AgentController(this, repo, viewModelScope, application)

    // ── observable state ───────────────────────────────────────────────────

    val projects: StateFlow<List<Project>> = repo.projects
    val state = repo.state

    private val _uiLanguage = MutableStateFlow(repo.uiLanguage())
    val uiLanguage: StateFlow<String> = _uiLanguage.asStateFlow()

    /** "light" | "dark" — observed by AppRoot to choose Material color scheme. */
    private val _theme = MutableStateFlow(repo.themePref())
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview: StateFlow<ImportPreview?> = _importPreview.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** Live preview of the currently streaming AI generation, if any. */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var streamingJob: Job? = null

    /** True only while a chapter body is streaming — guards EditorScreen from mini-outline bleed. */
    private val _isChapterGenerating = MutableStateFlow(false)
    val isChapterGenerating: StateFlow<Boolean> = _isChapterGenerating.asStateFlow()

    /** Streaming result for the AI-fill (title/goal/conflict) dialog in EditorScreen. */
    private val _aiFillText = MutableStateFlow("")
    val aiFillText: StateFlow<String> = _aiFillText.asStateFlow()
    private val _isAiFilling = MutableStateFlow(false)
    val isAiFilling: StateFlow<Boolean> = _isAiFilling.asStateFlow()
    private var aiFillJob: Job? = null

    /** Live streaming answer for the "ask the novel" Q&A agent (per-project chat). */
    private val _qaStreamingText = MutableStateFlow("")
    val qaStreamingText: StateFlow<String> = _qaStreamingText.asStateFlow()
    private val _qaGenerating = MutableStateFlow(false)
    val qaGenerating: StateFlow<Boolean> = _qaGenerating.asStateFlow()
    private var qaJob: Job? = null

    init {
        viewModelScope.launch {
            repo.state.collectLatest {
                _uiLanguage.value = repo.uiLanguage()
                _theme.value = repo.themePref()
            }
        }
    }

    override fun onCleared() {
        audiobook.release()
        agent.stop()
        super.onCleared()
    }

    // ── settings ───────────────────────────────────────────────────────────

    fun setUiLanguage(lang: String) = repo.setUiLanguage(lang)
    fun setTheme(theme: String) = repo.setTheme(theme)
    fun currentTheme(): String = repo.themePref()

    fun textModelProfiles(): List<TextModelProfile> = repo.textModelProfiles()
    fun saveTextModelProfile(profile: TextModelProfile) = repo.saveTextModelProfile(profile)
    fun deleteTextModelProfile(profileId: String) = repo.deleteTextModelProfile(profileId)
    fun setActiveProfile(profileId: String) = repo.setActiveProfile(profileId)
    fun activeTextModelConfig(): TextModelConfig = repo.activeTextModelConfig()
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
    fun deleteChapter(projectId: String, chapterId: String) {
        repo.deleteChapter(projectId, chapterId)
        renumberChapters(projectId)   // close the序号 gap left by deletion
    }

    /** Renumber chapters to a contiguous 1..n by current order (only display order_index changes;
     *  the agent's chapter index is by id/arcId so it is NOT affected). */
    fun renumberChapters(projectId: String): Boolean {
        val sorted = repo.chapters(projectId).sortedBy { it.order_index }
        if (sorted.isEmpty()) return false
        val needs = sorted.withIndex().any { (i, c) -> c.order_index != i + 1 }
        if (needs) repo.setChapters(projectId, sorted.mapIndexed { i, c -> c.copy(order_index = i + 1) })
        return needs
    }

    /** Delete a chapter and clean up its references (arc.builtChapterIds + KB/summary/entities),
     *  then renumber so chapter numbers stay contiguous. */
    fun deleteChapterFully(projectId: String, chapterId: String) {
        repo.setPlotArcs(projectId, repo.plotArcs(projectId).map { a ->
            if (a.builtChapterIds?.contains(chapterId) == true)
                a.copy(builtChapterIds = a.builtChapterIds.filterNot { it == chapterId }) else a
        })
        forgetKbForChapter(projectId, chapterId)
        repo.deleteChapter(projectId, chapterId)
        renumberChapters(projectId)
    }

    /** Resolve a chapter's arc id (explicit, else inferred from any arc's builtChapterIds). */
    fun chapterArcId(projectId: String, chapter: Chapter): String? =
        chapter.arcId ?: repo.plotArcs(projectId).firstOrNull { it.builtChapterIds?.contains(chapter.id) == true }?.id

    /** Apply edited final text to a chapter (save + refresh word count + KB reindex). For the
     *  agent's local edits. Returns false if the chapter doesn't exist. */
    fun agentApplyChapterText(projectId: String, chapterId: String, text: String): Boolean {
        val ch = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return false
        repo.saveChapterBody(chapterId, repo.chapterBody(chapterId).copy(final = text))
        repo.upsertChapter(projectId, ch.copy(word_count = countWords(text), updated_at = nowIso()))
        onChapterSaved(projectId, chapterId, ch.title, text)
        return true
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
        val chain = repo.characterGrowth(projectId, characterId).toMutableList()
        if (chain.isEmpty()) return
        chain[chain.lastIndex] = chain.last().copy(value = value, manual = true)
        repo.setCharacterGrowth(projectId, characterId, chain)
    }

    fun deleteCharacterGrowth(projectId: String, characterId: String, entryId: String) =
        repo.setCharacterGrowth(projectId, characterId, repo.characterGrowth(projectId, characterId).filterNot { it.id == entryId })

    fun plotArcs(projectId: String): List<PlotArc> = repo.plotArcs(projectId)
    fun setPlotArcs(projectId: String, arcs: List<PlotArc>) = repo.setPlotArcs(projectId, arcs)

    // ── 副本 (Volumes) ────────────────────────────────────────────────────────
    fun volumes(projectId: String): List<Volume> = repo.volumes(projectId).sortedBy { it.order }
    fun setVolumes(projectId: String, volumes: List<Volume>) = repo.setVolumes(projectId, volumes)
    fun ensureVolumes(projectId: String) = repo.ensureVolumes(projectId)

    fun arcsForVolume(projectId: String, volumeId: String): List<PlotArc> =
        repo.plotArcs(projectId).filter { it.volumeId == volumeId }.sortedBy { it.order }

    fun createVolume(projectId: String, name: String, description: String): Volume {
        val order = (repo.volumes(projectId).maxOfOrNull { it.order } ?: -1) + 1
        val v = Volume(id = "vol-${System.currentTimeMillis()}", name = name, description = description, order = order, createdAt = nowIso())
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
        streamingJob?.cancel()
        streamingJob = null
        _isGenerating.value = false
        _isChapterGenerating.value = false
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
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
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
        val currentOutline = repo.outline(projectId)
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
        streamingJob?.cancel()
        // In continuation mode the model is told to emit ONLY the next chunk, so we prefix the
        // streaming buffer with everything already written. `appendToExisting` keeps the same
        // behaviour for legacy callers that just want a prefix without changing the prompt.
        val prefix = when {
            continueFromExisting && currentOutline.isNotBlank() -> "$currentOutline\n\n"
            appendToExisting -> currentOutline.let { if (it.isEmpty()) "" else "$it\n\n" }
            else -> ""
        }
        _streamingText.value = prefix
        _isGenerating.value = true
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ai.streamChat(cfg, messages).collect { ev ->
                    when (ev) {
                        is AiService.StreamEvent.Delta -> {
                            _streamingText.value = _streamingText.value + ev.text
                        }
                        AiService.StreamEvent.Done -> { /* handled below */ }
                        is AiService.StreamEvent.Error -> _statusMessage.value = "生成失败：${ev.message}"
                    }
                }
                val final = _streamingText.value.trim()
                if (final.isNotEmpty()) {
                    repo.setOutline(projectId, final)
                    onComplete(final)
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun generateChapter(
        projectId: String,
        chapter: Chapter,
        currentContent: String? = null,  // full existing final text; null → fresh generation
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
        val lang = _uiLanguage.value
        val isContinuation = !currentContent.isNullOrBlank()

        // Build all context
        val arcs = repo.plotArcs(projectId)
        val arcContext = buildArcContext(arcs, lang)
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val worldSettingRaw = repo.worldSetting(projectId).ifBlank { repo.outline(projectId) }
        val worldParts = listOfNotNull(
            arcContext.takeIf { it.isNotBlank() },
            realmCtx.takeIf { it.isNotBlank() },
            worldSettingRaw.takeIf { it.isNotBlank() },
        )
        val worldSetting = worldParts.joinToString("\n\n").takeIf { it.isNotBlank() }
        val timeline = repo.timeline(projectId).takeIf { it.isNotBlank() }
        val charactersInfo = buildCharactersInfo(projectId)
        val prevSummary = buildPreviousChapterSummary(projectId, chapter.order_index)
        val chapterList = buildChapterList(projectId, chapter.id)

        streamingJob?.cancel()
        _streamingText.value = currentContent ?: ""
        _isGenerating.value = true
        _isChapterGenerating.value = true
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            // KB augmentation: book summary + arc summary + open foreshadowing + RAG chunks.
            // Each layer respects its own enable flag and silently no-ops if config is missing.
            val kbAugmentation = buildKbAugmentation(projectId, chapter, lang)

            val messages = listOf(
                ChatMessage("system", Prompts.chapterSystem(lang)),
                ChatMessage("user", Prompts.chapterUser(
                    chapterTitle = chapter.title,
                    outlineGoal = chapter.outline_goal.orEmpty(),
                    conflict = chapter.conflict,
                    prevSummary = prevSummary.takeIf { it.isNotBlank() },
                    currentContent = if (isContinuation) currentContent!!.takeLast(2000) else null,
                    chapterList = chapterList.takeIf { it.isNotBlank() },
                    charactersInfo = charactersInfo,
                    worldSetting = worldSetting,
                    timeline = timeline,
                    targetWords = TARGET_WORDS,
                    isContinuation = isContinuation,
                    language = lang,
                    kbAugmentation = kbAugmentation,
                )),
            )
            try {
                ai.streamChat(cfg, messages).collect { ev ->
                    when (ev) {
                        is AiService.StreamEvent.Delta -> _streamingText.value = _streamingText.value + ev.text
                        AiService.StreamEvent.Done -> {}
                        is AiService.StreamEvent.Error -> _statusMessage.value = "生成失败：${ev.message}"
                    }
                }
                val final = _streamingText.value
                if (final.isNotEmpty()) {
                    val prior = repo.chapterBody(chapter.id)
                    // Safety snapshot before AI overwrites real existing work (skip empty chapters).
                    if (prior.final.isNotBlank() || prior.draft.isNotBlank()) {
                        runCatching {
                            repo.createSnapshot(projectId, "AI写作前：${chapter.title}", SnapshotMeta.TRIGGER_PRE_AI)
                        }
                    }
                    val body = prior.copy(final = final)
                    repo.saveChapterBody(chapter.id, body)
                    repo.upsertChapter(
                        projectId,
                        chapter.copy(word_count = countWords(final), updated_at = nowIso(), status = "review"),
                    )
                    // KB fan-out: index this freshly-generated chapter + regenerate its summary +
                    // extract entities (each respects its own enable flag). Previously only the
                    // manual save button did this, so AI-generated / continued chapters were never
                    // auto-indexed or summarized.
                    onChapterSaved(projectId, chapter.id, chapter.title, final)
                }
            } finally {
                _isGenerating.value = false
                _isChapterGenerating.value = false
            }
        }
    }

    /** AI fill: generates a 3-line title/goal/conflict suggestion for the given chapter index. */
    fun generateChapterOutline(
        projectId: String,
        chapterOrderIndex: Int,
        userRequirements: String,
    ) {
        val cfg = repo.activeTextModelConfig()
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
        aiFillJob?.cancel()
        _aiFillText.value = ""
        _isAiFilling.value = true
        aiFillJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ai.streamChat(cfg, messages).collect { ev ->
                    when (ev) {
                        is AiService.StreamEvent.Delta -> _aiFillText.value = _aiFillText.value + ev.text
                        AiService.StreamEvent.Done -> {}
                        is AiService.StreamEvent.Error -> _statusMessage.value = "AI助填失败：${ev.message}"
                    }
                }
            } finally {
                _isAiFilling.value = false
            }
        }
    }

    fun stopAiFill() { aiFillJob?.cancel(); aiFillJob = null; _isAiFilling.value = false }
    fun clearAiFill() { _aiFillText.value = "" }

    /** Generates a chapter-by-chapter plan for [arcId] and saves it as arc.miniOutline. */
    fun generateArcMiniOutline(projectId: String, arcId: String) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
        val lang = _uiLanguage.value
        val project = repo.project(projectId) ?: return
        val arc = repo.plotArcs(projectId).find { it.id == arcId } ?: return
        val chapters = repo.chapters(projectId)
        val outline = repo.outline(projectId)
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val projectOutline = if (realmCtx.isNotBlank()) "$outline\n\n$realmCtx" else outline
        val charactersInfo = buildCharactersInfo(projectId)
        val startChapterNumber = (chapters.maxOfOrNull { it.order_index } ?: 0) + 1
        val prevCtx = chapters.sortedByDescending { it.order_index }.take(5).reversed()
            .joinToString("\n") { c -> "第${c.order_index}章《${c.title}》：${c.outline_goal ?: "(无目标)"}" }
        val messages = listOf(
            ChatMessage("system", Prompts.arcMiniOutlineSystem(lang)),
            ChatMessage("user", Prompts.arcMiniOutlineUser(
                projectTitle = project.title,
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
        streamingJob?.cancel()
        _streamingText.value = ""
        _isGenerating.value = true
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ai.streamChat(cfg, messages).collect { ev ->
                    when (ev) {
                        is AiService.StreamEvent.Delta -> _streamingText.value = _streamingText.value + ev.text
                        AiService.StreamEvent.Done -> {}
                        is AiService.StreamEvent.Error -> _statusMessage.value = "弧线计划生成失败：${ev.message}"
                    }
                }
                val result = _streamingText.value
                if (result.isNotEmpty()) {
                    repo.setPlotArcs(projectId, repo.plotArcs(projectId).map { a ->
                        if (a.id == arcId) a.copy(miniOutline = result, builtChapterIds = emptyList()) else a
                    })
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    /** Creates chapters from parsed arc mini-outline rows and updates arc.builtChapterIds. */
    fun addChaptersBatch(
        projectId: String,
        arcId: String?,
        items: List<Pair<String, String?>>,   // (title, goal?)
    ): List<Chapter> {
        val ts = System.currentTimeMillis()
        val maxOrder = repo.chapters(projectId).maxOfOrNull { it.order_index } ?: 0
        val now = nowIso()
        val newChapters = items.mapIndexed { i, (title, goal) ->
            Chapter(
                id = "c-$ts-$i",
                project_id = projectId,
                title = title,
                order_index = maxOrder + i + 1,
                outline_goal = goal,
                created_at = now,
                updated_at = now,
                arcId = arcId,
            )
        }
        newChapters.forEach { ch -> repo.upsertChapter(projectId, ch) }
        if (arcId != null) {
            val builtIds = newChapters.map { it.id }
            repo.setPlotArcs(projectId, repo.plotArcs(projectId).map { a ->
                if (a.id == arcId) a.copy(builtChapterIds = builtIds) else a
            })
        }
        return newChapters
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
            runCatching {
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
            runCatching {
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
            runCatching {
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
            runCatching {
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
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val prompt = runCatching {
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
            val bytes = runCatching {
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
            repo.upsertIllustration(chapterId, illustration)
            onDone(illustration, null)
        }
    }

    // ── Chapter promo (PC parity — "推文" / chapter banner) ──────────────────────────────────

    fun getChapterPromo(chapterId: String): ChapterPromo? = repo.getChapterPromo(chapterId)

    fun generateChapterPromo(
        chapterId: String,
        chapterTitle: String,
        chapterContent: String,
        style: String?,
        model: String,
        width: Int,
        height: Int,
        onDone: (ChapterPromo?, errorMessage: String?) -> Unit,
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            onDone(null, "请先在「设置」中配置可用的文本模型 / Configure a text model first.")
            return
        }
        if (chapterContent.length < 100) {
            onDone(null, "章节内容太少（至少需要100字）/ Chapter content too short (need 100+ chars).")
            return
        }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val json = runCatching {
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
            val bytes = runCatching {
                generateImageBytes(prompt = imagePrompt, width = width, height = height, model = model)
            }.getOrElse { onDone(null, "图像生成失败：${it.message}"); return@launch }
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val promo = ChapterPromo(imagePrompt = imagePrompt, summary = summary, imageBase64 = b64)
            repo.setChapterPromo(chapterId, promo)
            onDone(promo, null)
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
            val prompt = runCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.projectCoverSystem(lang)),
                    ChatMessage("user", Prompts.projectCoverUser(
                        proj.title, proj.description, outline.ifBlank { null }, style, lang,
                        charactersInfo)),
                ))
            }.getOrElse { onDone(null, "生成封面提示词失败：${it.message}"); return@launch }
                .trim().lines().firstOrNull { it.isNotBlank() }.orEmpty()
            if (prompt.isBlank()) { onDone(null, "模型未返回可用的图像提示词。"); return@launch }
            val bytes = runCatching {
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
            val existing = repo.getCoverImages(projectId)
            val newList = existing + item
            val defaultId = repo.project(projectId)?.default_cover_id ?: item.id
            repo.setCoverImages(projectId, newList, defaultId)
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
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) return null
        val lang = _uiLanguage.value
        val project = repo.project(projectId) ?: return null
        val existingArcs = repo.plotArcs(projectId).joinToString("\n") { "- ${it.title} (${it.status})" }
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val charSummary = repo.characters(projectId).joinToString("\n") { "- ${it.name}: ${it.role}" }
        val reply = withContext(Dispatchers.IO) {
            runCatching {
                ai.chat(
                    cfg,
                    listOf(
                        ChatMessage("system", Prompts.plotArcSystem(lang)),
                        ChatMessage("user", Prompts.plotArcUser(
                            userIdea = userIdea,
                            bookTitle = project.title,
                            bookDescription = project.description,
                            bookOutline = repo.outline(projectId).ifBlank { null },
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
        val order = (repo.plotArcs(projectId).maxOfOrNull { it.order } ?: -1) + 1
        val arc = PlotArc(
            id = "arc-${System.currentTimeMillis()}",
            title = parsed.title,
            summary = parsed.summary,
            order = order,
            status = "upcoming",
            chapterCount = parsed.chapterCount,
            miniOutline = parsed.miniOutline,
        )
        repo.setPlotArcs(projectId, repo.plotArcs(projectId) + arc)
        return arc
    }

    // ── 副本 / 弧线 AI generation ─────────────────────────────────────────────

    /** Generate [count] volumes from the outline + realm system + influencing containers (no arcs).
     *  [requirements] is the user's free-form instruction (e.g. what the first/later volumes cover). */
    fun generateVolumes(projectId: String, count: Int, requirements: String? = null, onDone: (Int) -> Unit = {}) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先在「设置」中配置可用的文本模型"; onDone(0); return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
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
            val existing = repo.volumes(projectId).sortedBy { it.order }.joinToString("\n") { "- ${it.name}" }.ifBlank { null }
            val reply = runCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.volumePlanSystem(lang)),
                    ChatMessage("user", Prompts.volumePlanUser(count, context, existing, requirements?.trim()?.ifBlank { null }, lang)),
                ))
            }.getOrNull()
            val parsed = reply?.let { parseVolumeArray(it) } ?: emptyList()
            if (parsed.isEmpty()) { withContext(Dispatchers.Main) { _statusMessage.value = "副本生成失败"; onDone(0) }; return@launch }
            var order = (repo.volumes(projectId).maxOfOrNull { it.order } ?: -1) + 1
            val ts = System.currentTimeMillis()
            val newVols = parsed.mapIndexed { i, (name, desc) ->
                Volume(id = "vol-$ts-$i", name = name, description = desc, order = order++, createdAt = nowIso())
            }
            repo.setVolumes(projectId, repo.volumes(projectId) + newVols)
            withContext(Dispatchers.Main) { _statusMessage.value = "已生成 ${newVols.size} 个副本"; onDone(newVols.size) }
        }
    }

    /** Generate [count] plot arcs inside [volumeId] (no chapter planning). [requirements] is the
     *  user's free-form instruction (e.g. what specific arcs should cover). */
    fun generateArcsForVolume(projectId: String, volumeId: String, count: Int, requirements: String? = null, onDone: (Int) -> Unit = {}) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先在「设置」中配置可用的文本模型"; onDone(0); return }
        val lang = _uiLanguage.value
        val volume = repo.volumes(projectId).firstOrNull { it.id == volumeId } ?: run { onDone(0); return }
        viewModelScope.launch(Dispatchers.IO) {
            val context = buildString {
                repo.outline(projectId).takeIf { it.isNotBlank() }?.let {
                    append(if (lang == "en") "[Outline]\n" else "【大纲】\n"); append(it.take(3000))
                }
                buildRealmSystemContext(repo.cultivationRealms(projectId), lang).takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
                containerGuidanceFor(projectId, lang) { it.affectsArcGeneration }.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n"); append(it)
                }
            }
            val existingArcs = repo.plotArcs(projectId).filter { it.volumeId == volumeId }
                .joinToString("\n") { "- ${it.title}" }.ifBlank { null }
            val reply = runCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.arcsForVolumeSystem(lang)),
                    ChatMessage("user", Prompts.arcsForVolumeUser(count, volume.name, volume.description.ifBlank { null }, context, existingArcs, requirements?.trim()?.ifBlank { null }, lang)),
                ))
            }.getOrNull()
            val parsed = reply?.let { parseArcArray(it) } ?: emptyList()
            if (parsed.isEmpty()) { withContext(Dispatchers.Main) { _statusMessage.value = "弧线生成失败"; onDone(0) }; return@launch }
            var order = (repo.plotArcs(projectId).maxOfOrNull { it.order } ?: -1) + 1
            val ts = System.currentTimeMillis()
            val newArcs = parsed.mapIndexed { i, p ->
                PlotArc(id = "arc-$ts-$i", title = p.title, summary = p.summary, order = order++,
                    status = "upcoming", chapterCount = p.chapterCount, volumeId = volumeId)
            }
            repo.setPlotArcs(projectId, repo.plotArcs(projectId) + newArcs)
            withContext(Dispatchers.Main) { _statusMessage.value = "已生成 ${newArcs.size} 条弧线"; onDone(newArcs.size) }
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
    suspend fun agentChat(messages: List<ChatMessage>): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
        return withContext(Dispatchers.IO) { ai.chat(cfg, messages) }
    }

    fun agentTextModelReady(): Boolean = repo.activeTextModelConfig().isValid()

    fun agentName(): String = repo.agentName()
    fun setAgentName(name: String) = repo.setAgentName(name)
    fun activeTextModelProfileId(): String? = repo.activeTextModelProfileId()

    /** Collect a streaming completion, reporting the cumulative text via [onDelta]. */
    private suspend fun streamCollect(
        cfg: TextModelConfig,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ): String {
        val sb = StringBuilder()
        ai.streamChat(cfg, messages).collect { ev ->
            when (ev) {
                is AiService.StreamEvent.Delta -> { sb.append(ev.text); onDelta(sb.toString()) }
                else -> {}
            }
        }
        return sb.toString().trim()
    }

    suspend fun agentGenerateOutline(projectId: String, onDelta: (String) -> Unit = {}): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
        val p = repo.project(projectId) ?: return null
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang).ifBlank { null }
            val messages = listOf(
                ChatMessage("system", Prompts.outlineSystem(lang, isLong = true)),
                ChatMessage("user", Prompts.outlineUser(
                    p.title, p.genre.orEmpty(), p.description.orEmpty(), null, null, lang,
                    isLong = true, realmContext = realmCtx,
                    existingWorld = repo.worldSetting(projectId).ifBlank { null },
                    existingTimeline = repo.timeline(projectId).ifBlank { null },
                    existingVolumes = null,
                    charactersInfo = buildCharactersInfo(projectId),
                )),
            )
            val out = runCatching { streamCollect(cfg, messages, onDelta) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (out != null) repo.setOutline(projectId, out)
            out
        }
    }

    suspend fun agentGenerateChapterText(projectId: String, chapterId: String, onDelta: (String) -> Unit = {}): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val arcContext = buildArcContext(repo.plotArcs(projectId), lang)
            val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
            val worldRaw = repo.worldSetting(projectId).ifBlank { repo.outline(projectId) }
            val worldSetting = listOfNotNull(arcContext.ifBlank { null }, realmCtx.ifBlank { null }, worldRaw.ifBlank { null })
                .joinToString("\n\n").ifBlank { null }
            val kbAug = buildKbAugmentation(projectId, chapter, lang)
            val messages = listOf(
                ChatMessage("system", Prompts.chapterSystem(lang)),
                ChatMessage("user", Prompts.chapterUser(
                    chapterTitle = chapter.title,
                    outlineGoal = chapter.outline_goal.orEmpty(),
                    conflict = chapter.conflict,
                    prevSummary = buildPreviousChapterSummary(projectId, chapter.order_index).ifBlank { null },
                    currentContent = null,
                    chapterList = buildChapterList(projectId, chapter.id).ifBlank { null },
                    charactersInfo = buildCharactersInfo(projectId),
                    worldSetting = worldSetting,
                    timeline = repo.timeline(projectId).ifBlank { null },
                    targetWords = TARGET_WORDS,
                    isContinuation = false,
                    language = lang,
                    kbAugmentation = kbAug,
                )),
            )
            val text = runCatching { streamCollect(cfg, messages, onDelta) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (text != null) {
                repo.saveChapterBody(chapterId, repo.chapterBody(chapterId).copy(final = text))
                repo.upsertChapter(projectId, chapter.copy(word_count = countWords(text), updated_at = nowIso(), status = "review"))
                onChapterSaved(projectId, chapterId, chapter.title, text)
            }
            text
        }
    }

    /** Scan a chapter's text for characters (esp. new ones not yet registered) and add the new
     *  ones to the character manager. Returns the list of newly-added names. */
    suspend fun agentExtractCharactersFromChapter(projectId: String, chapterId: String): List<String> {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return emptyList()
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
                    append(if (lang == "en") "Output the JSON character array." else "请输出 JSON 角色数组。")
                }),
            )
            val reply = runCatching { ai.chat(cfg, messages) }.getOrNull() ?: return@withContext emptyList()
            val parsed = parseCharsFromAiText(reply).filter { it.name.isNotBlank() && it.name !in existingNames }
            if (parsed.isNotEmpty()) repo.setCharacters(projectId, existing + parsed)
            parsed.map { it.name }
        }
    }

    /** Refine a chapter's plan (goal + core conflict) before writing — batch-created blank chapters
     *  often have thin plans. Returns the refined "目标 / 冲突" summary, or null. */
    suspend fun agentRefineChapterPlan(projectId: String, chapterId: String): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val arc = chapterArcId(projectId, chapter)?.let { aid -> repo.plotArcs(projectId).firstOrNull { it.id == aid } }
            val ctx = buildString {
                repo.outline(projectId).takeIf { it.isNotBlank() }?.let { append("【大纲】\n"); appendLine(it.take(1500)) }
                arc?.let { appendLine("【所属弧线】${it.title}：${it.summary}") }
                buildCharactersInfo(projectId)?.let { appendLine("【角色】\n$it") }
                buildRealmSystemContext(repo.cultivationRealms(projectId), lang).takeIf { it.isNotBlank() }?.let { appendLine(it) }
                buildPreviousChapterSummary(projectId, chapter.order_index).takeIf { it.isNotBlank() }?.let { appendLine("【前文梗概】\n$it") }
            }
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
            val reply = runCatching { ai.chat(cfg, messages) }.getOrNull() ?: return@withContext null
            val s = reply.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()
            val start = s.indexOf('{'); val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return@withContext null
            val o = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(s.substring(start, end + 1)).jsonObject }.getOrNull()
                ?: return@withContext null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            val goal = str("goal"); val conflict = str("conflict")
            if (goal.isNullOrBlank() && conflict.isNullOrBlank()) return@withContext null
            repo.upsertChapter(projectId, chapter.copy(
                outline_goal = goal ?: chapter.outline_goal,
                conflict = conflict ?: chapter.conflict,
                updated_at = nowIso(),
            ))
            "目标：${goal.orEmpty()}\n冲突：${conflict.orEmpty()}"
        }
    }

    suspend fun agentReviseChapter(projectId: String, chapterId: String, instruction: String, onDelta: (String) -> Unit = {}): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
        val chapter = repo.chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        val body = repo.chapterBody(chapterId)
        val src = body.final.ifBlank { body.draft }
        if (src.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val messages = listOf(
                ChatMessage("system", "你是资深小说编辑。请按用户要求修改给定章节正文，只输出修改后的完整正文，不要任何解释或标记。"),
                ChatMessage("user", "原文：\n$src\n\n修改要求：$instruction"),
            )
            val out = runCatching { streamCollect(cfg, messages, onDelta) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (out != null) {
                repo.saveChapterBody(chapterId, body.copy(final = out))
                repo.upsertChapter(projectId, chapter.copy(word_count = countWords(out), updated_at = nowIso()))
                onChapterSaved(projectId, chapterId, chapter.title, out)
            }
            out
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
            runCatching { ai.chat(cfg, messages) }.getOrNull()?.trim() ?: "（检索失败）"
        }
    }

    /** Plan [count] chapters for an arc (AI → JSON), then create them via [addChaptersBatch]. */
    suspend fun agentPlanArcChapters(projectId: String, arcId: String, count: Int): Int {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return 0
        val arc = repo.plotArcs(projectId).firstOrNull { it.id == arcId } ?: return 0
        val lang = _uiLanguage.value
        return withContext(Dispatchers.IO) {
            val ctx = buildString {
                repo.outline(projectId).takeIf { it.isNotBlank() }?.let { append("【大纲】\n"); append(it.take(2000)) }
                buildRealmSystemContext(repo.cultivationRealms(projectId), lang).takeIf { it.isNotBlank() }
                    ?.let { if (isNotEmpty()) append("\n\n"); append(it) }
            }
            val messages = listOf(
                ChatMessage("system", "你为某条剧情弧线规划具体章节。只输出 JSON 数组：[{\"title\":\"章节名\",\"goal\":\"本章目标(1-2句)\"}]，不要任何其它文字。"),
                ChatMessage("user", "弧线：${arc.title}\n${arc.summary}\n请规划 $count 个章节。\n参考资料：\n$ctx"),
            )
            val reply = runCatching { ai.chat(cfg, messages) }.getOrNull() ?: return@withContext 0
            val items = parseChapterPlanArray(reply)
            if (items.isNotEmpty()) withContext(Dispatchers.Main) { addChaptersBatch(projectId, arcId, items) }
            items.size
        }
    }

    /** Review the project's chapters for contradictions / logic errors (streaming). */
    suspend fun agentReviewConsistency(projectId: String, onDelta: (String) -> Unit = {}): String? {
        val cfg = repo.activeTextModelConfig(); if (!cfg.isValid()) return null
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
            runCatching { streamCollect(cfg, messages, onDelta) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "（审阅失败）"
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
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) {
            _statusMessage.value = "请先在「设置」中配置可用的文本模型 / Configure a text model first."
            return
        }
        val outline = repo.outline(projectId)
        if (outline.isBlank()) {
            _statusMessage.value = "尚无大纲，请先生成大纲 / No outline yet — generate one first."
            return
        }
        val lang = _uiLanguage.value
        val realmCtx = buildRealmSystemContext(repo.cultivationRealms(projectId), lang)
        val outlineWithCtx = if (realmCtx.isNotEmpty()) "$outline\n\n$realmCtx" else outline
        val messages = listOf(
            ChatMessage("system", Prompts.charsFromOutlineSystem(lang)),
            ChatMessage("user", Prompts.charsFromOutlineUser(outlineWithCtx, lang)),
        )
        streamingJob?.cancel()
        _streamingText.value = ""
        _isGenerating.value = true
        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ai.streamChat(cfg, messages).collect { ev ->
                    when (ev) {
                        is AiService.StreamEvent.Delta -> _streamingText.value = _streamingText.value + ev.text
                        AiService.StreamEvent.Done -> {}
                        is AiService.StreamEvent.Error -> _statusMessage.value = "生成失败：${ev.message}"
                    }
                }
                val parsed = parseCharsFromAiText(_streamingText.value)
                withContext(Dispatchers.Main) { onComplete(parsed) }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun parseCharsFromAiText(text: String): List<Character> {
        val ts = System.currentTimeMillis()
        // Try JSON array first
        val jsonResult = runCatching {
            // Strip optional markdown code fences and find the JSON array
            val stripped = text
                .replace(Regex("```(?:json)?\\s*"), "")
                .replace("```", "")
                .trim()
            val start = stripped.indexOf('[')
            val end = stripped.lastIndexOf(']')
            if (start < 0 || end <= start) error("no array")
            val jsonArray = stripped.substring(start, end + 1)
            val arr = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonArray)
                .jsonArray
            arr.mapIndexed { i, el ->
                val obj = el.jsonObject
                fun str(key: String) = obj[key]?.let {
                    it.toString().trim('"')
                }?.takeIf { it.isNotBlank() && it != "null" } ?: ""
                val isProta = obj["isProtagonist"]?.toString()?.trim('"')
                    ?.equals("true", ignoreCase = true) ?: false
                Character(
                    id = "char-import-$ts-$i",
                    name = str("name").ifBlank { return@mapIndexed null },
                    gender = str("gender"),
                    role = str("role"),
                    personality = str("personality"),
                    motivation = str("motivation"),
                    background = str("background"),
                    appearance = str("appearance"),
                    isProtagonist = isProta,
                )
            }.filterNotNull()
        }
        if (jsonResult.isSuccess) {
            val list = jsonResult.getOrDefault(emptyList())
            if (list.isNotEmpty()) return list
        }
        // Fallback: pipe-delimited format
        return text.lines()
            .mapIndexedNotNull { i, line ->
                val raw = line.trimStart('-', '*', ' ', '\t')
                    .replace(Regex("^\\d+[.)、]\\s*"), "")
                    .trim()
                if (!raw.contains('|')) return@mapIndexedNotNull null
                val parts = raw.split('|').map { it.trim() }
                val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Character(
                    id = "char-import-$ts-$i",
                    name = name,
                    gender = parts.getOrElse(1) { "" },
                    role = parts.getOrElse(2) { "" },
                    personality = parts.getOrElse(3) { "" },
                    motivation = parts.getOrElse(4) { "" },
                    background = parts.getOrElse(5) { "" },
                )
            }
    }



    fun buildBackupJson(): String {
        val bundle = repo.buildBackupBundle()
        return AppRepository.JSON.encodeToString(BackupBundle.serializer(), bundle)
    }

    fun stageImport(fileName: String, jsonText: String) {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString(BackupBundle.serializer(), jsonText)
                }
            }
            parsed
                .onSuccess { bundle ->
                    _statusMessage.value =
                        if (bundle.version != BackupBundle.BACKUP_VERSION)
                            "⚠ Backup version ${bundle.version} differs from expected ${BackupBundle.BACKUP_VERSION} — proceeding."
                        else ""
                    val summary = repo.summarizeBackup(bundle)
                    _importPreview.value = ImportPreview(bundle, fileName, summary)
                }
                .onFailure { err ->
                    _importPreview.value = null
                    _statusMessage.value = "Import failed: ${err.message}"
                }
        }
    }

    fun cancelImport() { _importPreview.value = null }

    fun confirmImport(includeAppSettings: Boolean) {
        val preview = _importPreview.value ?: return
        repo.importBackup(preview.bundle, includeAppSettings)
        val merged = preview.summary.projectIdsInBackup
        _importPreview.value = null
        _statusMessage.value = "Import done: merged metadata for $merged projects." +
            if (includeAppSettings) " App settings (incl. API keys) overwritten." else ""
    }

    fun clearStatus() { _statusMessage.value = "" }

    fun showStatus(msg: String) { _statusMessage.value = msg }

    fun clearStreaming() { _streamingText.value = "" }

    // ── private helpers ───────────────────────────────────────────────────

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
     * Assemble the KB augmentation block. Each layer is independent:
     *   - summariesEnabled → book + active-arc summaries
     *   - entitiesEnabled  → open foreshadowing list (helps long arcs land payoffs)
     *   - knowledgeBaseEnabled + valid embedding cfg → cosine-retrieve top-K chunks vs this
     *     chapter's title/goal/conflict, excluding chunks from the current chapter itself
     *
     * Returns null when no layer contributes — Prompts.chapterUser will then skip the block.
     */
    private suspend fun buildKbAugmentation(projectId: String, chapter: Chapter, lang: String): String? {
        val parts = mutableListOf<String>()

        if (repo.summariesEnabled()) {
            val all = repo.summaries(projectId)
            val book = all.firstOrNull { it.scopeType == "book" && it.scopeId == projectId }
            if (book != null && book.summaryText.isNotBlank()) {
                val header = if (lang == "en") "[Book Synopsis]" else "【全书梗概】"
                val staleTag = if (book.isStale) (if (lang == "en") " (may be stale)" else "（可能已过时）") else ""
                parts += "$header$staleTag\n${book.summaryText}"
            }
            val activeArc = repo.plotArcs(projectId).firstOrNull { it.status == "active" || it.status == "ending" }
            if (activeArc != null) {
                val arcSum = all.firstOrNull { it.scopeType == "arc" && it.scopeId == activeArc.id }
                val summaryText = arcSum?.summaryText?.takeIf { it.isNotBlank() } ?: activeArc.summary
                if (summaryText.isNotBlank()) {
                    val header = if (lang == "en") "[Current Arc Progress]" else "【当前弧线进度】"
                    parts += "$header\n${activeArc.title}\n$summaryText"
                }
            }
        }

        if (repo.entitiesEnabled()) {
            val open = repo.entities(projectId)
                .filter { it.entityType == "foreshadowing" && it.status == "open" }
            if (open.isNotEmpty()) {
                val header = if (lang == "en") "[Open Foreshadowing — pay these off when natural]"
                             else "【未回收伏笔 — 自然时机请回收】"
                parts += "$header\n" + open.joinToString("\n") {
                    "- ${it.canonicalName}：${it.summary}"
                }
            }
        }

        if (repo.knowledgeBaseEnabled()) {
            val cfg = repo.embeddingConfig()
            if (cfg.apiKey.isNotBlank() && cfg.apiUrl.isNotBlank() && cfg.model.isNotBlank()) {
                val pool = repo.chunks(projectId)
                if (pool.isNotEmpty()) {
                    val query = listOf(chapter.title, chapter.outline_goal, chapter.conflict)
                        .filterNot { it.isNullOrBlank() }.joinToString("\n")
                    if (query.isNotBlank()) {
                        runCatching {
                            kb.retrieveTopK(
                                query = query,
                                pool = pool,
                                topK = 4,
                                excludeSourceIds = setOf(chapter.id),
                                cfg = cfg,
                            )
                        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { hits ->
                            parts += hits.toPromptContext(lang)
                        }
                    }
                }
            }
        }

        // Container guidance — soft, "evolve from here" reference state for affectsGeneration containers.
        buildContainerGuidance(projectId, lang)?.let { parts += it }

        // Character growth routes — latest dev state per character (soft guidance for consistency).
        buildCharacterGrowthGuidance(projectId, lang)?.let { parts += it }

        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
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

    /** Inject the latest values of every affectsGeneration container as soft (non-forced) guidance. */
    private fun buildContainerGuidance(projectId: String, lang: String): String? {
        val containers = repo.containers(projectId).filter { it.affectsGeneration }
        if (containers.isEmpty()) return null
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
                    val recent = repo.chapters(projectId).sortedBy { it.order_index }.takeLast(3)
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
        if (blocks.isEmpty()) return null
        val header = if (lang == "en")
            "[Containers — reference state. Evolve naturally from here; do not contradict or regress these without an in-story reason.]"
        else
            "【资料容器 — 参考状态。请在此基础上自然演进，勿无理由跳变或倒退。】"
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
        apiKey.isNotBlank() && apiUrl.isNotBlank() && model.isNotBlank()

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

    /** Bumps whenever the vector-chunk store changes — Settings KB stats observe this to refresh. */
    val kbRevision = repo.kbRevision

    fun kbStats(projectId: String) = repo.kbStats(projectId)
    fun summariesOf(projectId: String) = repo.summaries(projectId)
    fun entitiesOf(projectId: String) = repo.entities(projectId)
    fun setEntityStatus(projectId: String, entityId: String, status: String) =
        repo.setEntityStatus(projectId, entityId, status)

    suspend fun testEmbeddingConnection(): Boolean = withContext(Dispatchers.IO) {
        kb.testConnection(repo.embeddingConfig())
    }

    /** Re-embed every written chapter for [projectId] using the current EmbeddingConfig. */
    fun rebuildKnowledgeBase(
        projectId: String,
        onProgress: (current: Int, total: Int, chapterTitle: String) -> Unit = { _, _, _ -> },
        // `firstError` carries the real provider message from the very first failure so the user
        // can actually see WHY a rebuild failed (e.g. DashScope's "InvalidParameter.BatchSize"),
        // rather than just a tally of fails.
        onDone: (chapters: Int, chunks: Int, errors: Int, firstError: String?) -> Unit = { _, _, _, _ -> },
    ) {
        val cfg = repo.embeddingConfig()
        if (cfg.apiKey.isBlank() || cfg.apiUrl.isBlank() || cfg.model.isBlank()) {
            _statusMessage.value = "请先在「本地知识库」中填写 Embedding 配置 / Configure Embedding first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val eligible = repo.chapters(projectId).mapNotNull { ch ->
                val body = repo.chapterBody(ch.id)
                val text = body.final.ifBlank { body.draft }
                if (text.trim().length > 200) Triple(ch.id, ch.title, text) else null
            }
            if (eligible.isEmpty()) {
                _statusMessage.value = "无可索引章节（正文需 > 200 字） / No chapters > 200 chars to index."
                onDone(0, 0, 0, null)
                return@launch
            }
            var totalChunks = 0
            var errors = 0
            var firstError: String? = null
            // Replace the whole KB to avoid stale chunks from removed chapters.
            repo.setChunks(projectId, emptyList())
            eligible.forEachIndexed { idx, (cid, title, text) ->
                onProgress(idx + 1, eligible.size, title)
                runCatching { kb.indexChapter(cid, title, text, cfg) }
                    .onSuccess { chunks ->
                        repo.appendChunks(projectId, chunks)
                        repo.setKbIndexHash(projectId, cid, com.example.novelseek_ultra.data.SnapshotStore.sha1(text))
                        repo.clearKbStaleChapter(projectId, cid)
                        totalChunks += chunks.size
                    }
                    .onFailure { err ->
                        errors += 1
                        val msg = err.message ?: err::class.simpleName ?: "unknown"
                        if (firstError == null) firstError = "「$title」→ $msg"
                    }
            }
            onDone(eligible.size, totalChunks, errors, firstError)
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
        if (text.trim().length < 200) return
        indexChapterIfEnabled(projectId, chapterId, title, text)
        if (repo.summariesEnabled()) generateChapterSummary(projectId, chapterId, title, text)
        if (repo.entitiesEnabled()) extractEntitiesForChapter(projectId, chapterId, title, text)
        updateContainersForChapter(projectId, chapterId, title, text)
    }

    /** Index a single chapter (called after a successful chapter save when KB is enabled). */
    fun indexChapterIfEnabled(projectId: String, chapterId: String, title: String, text: String) {
        if (!repo.knowledgeBaseEnabled()) return
        val cfg = repo.embeddingConfig()
        if (cfg.apiKey.isBlank() || cfg.apiUrl.isBlank() || cfg.model.isBlank()) return
        if (text.trim().length < 200) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val chunks = kb.indexChapter(chapterId, title, text, cfg)
                repo.appendChunks(projectId, chunks)
                repo.setKbIndexHash(projectId, chapterId, com.example.novelseek_ultra.data.SnapshotStore.sha1(text))
                repo.clearKbStaleChapter(projectId, chapterId)
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
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; onDone(null); return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.chapterSummarySystem(lang)),
                    ChatMessage("user", Prompts.chapterSummaryUser(chapterTitle, chapterText, lang)),
                ))
            }.getOrNull()?.trim() ?: run { onDone(null); return@launch }
            val payload = SummaryPayload(
                id = "sum-ch-$chapterId",
                scopeType = "chapter",
                scopeId = chapterId,
                summaryText = text,
                isStale = false,
                wordCount = text.length,
            )
            repo.upsertSummary(projectId, payload)
            // Mark book / arc roll-ups stale so the user knows to refresh them.
            repo.setSummaries(projectId, repo.summaries(projectId).map {
                if (it.scopeType != "chapter") it.copy(isStale = true) else it
            })
            onDone(payload)
        }
    }

    fun generateChapterSummariesForAll(
        projectId: String,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDone: (ok: Int, errors: Int) -> Unit = { _, _ -> },
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val eligible = repo.chapters(projectId).mapNotNull { ch ->
                val body = repo.chapterBody(ch.id)
                val t = body.final.ifBlank { body.draft }
                if (t.trim().length > 200) Triple(ch.id, ch.title, t) else null
            }
            var ok = 0; var errors = 0
            eligible.forEachIndexed { idx, (cid, title, text) ->
                onProgress(idx + 1, eligible.size, title)
                runCatching {
                    ai.chat(cfg, listOf(
                        ChatMessage("system", Prompts.chapterSummarySystem(lang)),
                        ChatMessage("user", Prompts.chapterSummaryUser(title, text, lang)),
                    ))
                }
                    .onSuccess { reply ->
                        val s = reply.trim()
                        if (s.isNotBlank()) {
                            repo.upsertSummary(projectId, SummaryPayload(
                                id = "sum-ch-$cid",
                                scopeType = "chapter",
                                scopeId = cid,
                                summaryText = s,
                                wordCount = s.length,
                            ))
                            ok += 1
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
    ) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; onDone(null); return }
        val project = repo.project(projectId) ?: run { onDone(null); return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val all = repo.summaries(projectId)
            val chapterSums = all.filter { it.scopeType == "chapter" }.map { it.summaryText }
            val arcSums = all.filter { it.scopeType == "arc" }.map { it.summaryText }
            val layers = (arcSums + chapterSums).filter { it.isNotBlank() }
            if (layers.isEmpty()) {
                _statusMessage.value = "尚未有任何章节摘要，先生成章节摘要 / Build chapter summaries first."
                onDone(null); return@launch
            }
            val text = runCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.bookSummarySystem(lang)),
                    ChatMessage("user", Prompts.bookSummaryUser(project.title, project.description, layers, lang)),
                ))
            }.getOrNull()?.trim() ?: run { onDone(null); return@launch }
            val payload = SummaryPayload(
                id = "sum-book-$projectId",
                scopeType = "book",
                scopeId = projectId,
                summaryText = text,
                isStale = false,
                wordCount = text.length,
            )
            repo.upsertSummary(projectId, payload)
            onDone(payload)
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
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; onDone(0, 0); return }
        val lang = _uiLanguage.value
        viewModelScope.launch(Dispatchers.IO) {
            val knownNames = repo.characters(projectId).map { it.name }
            val reply = runCatching {
                ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.entityExtractionSystem(lang)),
                    ChatMessage("user", Prompts.entityExtractionUser(chapterTitle, chapterText, knownNames, lang)),
                ))
            }.getOrNull() ?: run { onDone(0, 0); return@launch }
            val (added, updated) = mergeEntitiesFromJson(projectId, chapterId, reply)
            onDone(added, updated)
        }
    }

    private fun mergeEntitiesFromJson(projectId: String, chapterId: String, raw: String): Pair<Int, Int> {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(cleaned).jsonObject
        }.getOrNull() ?: return 0 to 0

        var added = 0; var updated = 0
        val existing = repo.entities(projectId).toMutableList()

        fun upsert(name: String, type: String, summary: String, aliases: List<String> = emptyList(), status: String = "open") {
            val canonical = name.trim().ifBlank { return }
            val matchIdx = existing.indexOfFirst {
                it.entityType == type && (it.canonicalName == canonical || canonical in it.aliases)
            }
            if (matchIdx >= 0) {
                val cur = existing[matchIdx]
                existing[matchIdx] = cur.copy(
                    aliases = (cur.aliases + aliases).distinct(),
                    summary = if (summary.isNotBlank()) summary else cur.summary,
                    status = if (status == "paid_off" && cur.status == "open") "paid_off" else cur.status,
                    lastSeenChapterId = chapterId,
                )
                updated += 1
            } else {
                existing += EntityPayload(
                    id = "ent-${type}-${System.currentTimeMillis()}-${added + updated}",
                    entityType = type,
                    canonicalName = canonical,
                    aliases = aliases.filter { it.isNotBlank() && it != canonical }.distinct(),
                    summary = summary,
                    status = status,
                    firstSeenChapterId = chapterId,
                    lastSeenChapterId = chapterId,
                )
                added += 1
            }
        }

        fun processGroup(key: String, type: String) {
            val arr = root[key]?.jsonArray ?: return
            arr.forEach { e ->
                val obj = e.jsonObject
                val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val summary = (obj["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "open"
                val aliases = obj["aliases"]?.jsonArray?.mapNotNull {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: emptyList()
                upsert(name = name, type = type, summary = summary, aliases = aliases, status = status)
            }
        }

        processGroup("characters", "character_ref")
        processGroup("foreshadowing", "foreshadowing")
        processGroup("locations", "location")
        processGroup("events", "event")
        processGroup("items", "item")

        repo.setEntities(projectId, existing)
        return added to updated
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

    fun saveSnapshot(projectId: String, label: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
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

    fun restoreSnapshot(projectId: String, snapshotId: String, onDone: (RestoreResult) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repo.restoreSnapshot(projectId, snapshotId) }
                .getOrDefault(RestoreResult())
            withContext(Dispatchers.Main) {
                _statusMessage.value = buildString {
                    append("已回退到该版本")
                    if (result.staleChapterIds.isNotEmpty()) append("，知识库有 ${result.staleChapterIds.size} 章待重建")
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
        if (cfg.apiKey.isBlank() || cfg.apiUrl.isBlank() || cfg.model.isBlank()) {
            _statusMessage.value = "请先在「本地知识库」中填写 Embedding 配置"
            onDone(0); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val stale = repo.kbStaleChapters(projectId)
            val chapters = repo.chapters(projectId).associateBy { it.id }
            var rebuilt = 0
            stale.forEachIndexed { idx, cid ->
                onProgress(idx + 1, stale.size)
                val ch = chapters[cid] ?: run { repo.clearKbStaleChapter(projectId, cid); return@forEachIndexed }
                val body = repo.chapterBody(cid)
                val text = body.final.ifBlank { body.draft }
                if (text.trim().length < 200) {
                    // Nothing worth indexing — drop any stale vectors and clear the flag.
                    repo.forgetKbSource(projectId, "chapter", cid)
                    repo.setKbIndexHash(projectId, cid, com.example.novelseek_ultra.data.SnapshotStore.sha1(text))
                    repo.clearKbStaleChapter(projectId, cid)
                    return@forEachIndexed
                }
                runCatching { kb.indexChapter(cid, ch.title, text, cfg) }
                    .onSuccess { chunks ->
                        repo.appendChunks(projectId, chunks)
                        repo.setKbIndexHash(projectId, cid, com.example.novelseek_ultra.data.SnapshotStore.sha1(text))
                        repo.clearKbStaleChapter(projectId, cid)
                        rebuilt += 1
                    }
                    .onFailure { _statusMessage.value = "KB 重建失败「${ch.title}」：${it.message}" }
            }
            withContext(Dispatchers.Main) {
                _statusMessage.value = "知识库重建完成（$rebuilt 章）"
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
        qaJob?.cancel()
        _qaGenerating.value = false
        _qaStreamingText.value = ""
    }

    /**
     * Answer a free-form question about the CURRENT version of the project. Hybrid retrieval:
     * structured data (characters/realms/relationships/events/entities/summaries/chapter list) is
     * always assembled; vector RAG over chapter chunks is added when embeddings are configured.
     * The answer streams into [qaStreamingText] and, on completion, is appended to the saved chat.
     */
    fun askNovel(projectId: String, question: String) {
        val q = question.trim()
        if (q.isEmpty() || _qaGenerating.value) return
        val cfg = repo.activeTextModelConfig()
        val lang = repo.uiLanguage()

        repo.appendNovelChat(
            projectId,
            NovelChatMessage(id = "qa-${System.currentTimeMillis()}", role = "user", content = q, createdAt = nowIso()),
        )
        _qaStreamingText.value = ""
        _qaGenerating.value = true
        qaJob?.cancel()
        qaJob = viewModelScope.launch(Dispatchers.IO) {
            val context = runCatching { buildNovelQaContext(projectId, q, lang) }.getOrDefault("")
            // Prior turns (drop the question we just appended) give the agent follow-up memory.
            val history = repo.novelChatHistory(projectId).dropLast(1).takeLast(8)
            val messages = buildList {
                add(ChatMessage("system", novelQaSystemPrompt(lang)))
                history.forEach { add(ChatMessage(it.role, it.content)) }
                add(ChatMessage("user", buildString {
                    appendLine(if (lang == "en") "[Novel Material]" else "【小说资料】")
                    appendLine(context.ifBlank { if (lang == "en") "(no material available)" else "（暂无资料）" })
                    appendLine()
                    appendLine(if (lang == "en") "[Question]" else "【问题】")
                    append(q)
                }))
            }
            try {
                ai.streamChat(cfg, messages).collect { ev ->
                    when (ev) {
                        is AiService.StreamEvent.Delta -> _qaStreamingText.value = _qaStreamingText.value + ev.text
                        AiService.StreamEvent.Done -> {}
                        is AiService.StreamEvent.Error -> _statusMessage.value = "问答失败：${ev.message}"
                    }
                }
                val answer = _qaStreamingText.value
                if (answer.isNotBlank()) {
                    repo.appendNovelChat(
                        projectId,
                        NovelChatMessage(id = "qa-${System.currentTimeMillis()}", role = "assistant", content = answer, createdAt = nowIso()),
                    )
                }
            } catch (e: Exception) {
                _statusMessage.value = "问答失败：${e.message}"
            } finally {
                _qaGenerating.value = false
                _qaStreamingText.value = ""
            }
        }
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
            if (cfg.apiKey.isNotBlank() && cfg.apiUrl.isNotBlank() && cfg.model.isNotBlank()) {
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

    private fun latestContainerValue(projectId: String, containerId: String, blockKey: String): String =
        repo.containerEntries(projectId, containerId, blockKey).lastOrNull()?.value.orEmpty()

    /** Re-run the AI update for one container against the latest chapter (manual "立即更新" button). */
    fun updateContainerNow(projectId: String, containerId: String, onDone: (Boolean) -> Unit = {}) {
        val c = repo.container(projectId, containerId) ?: return onDone(false)
        val ch = repo.chapters(projectId).maxByOrNull { it.order_index }
        if (ch == null) { _statusMessage.value = "暂无章节可供更新"; return onDone(false) }
        val body = repo.chapterBody(ch.id)
        val text = body.final.ifBlank { body.draft }
        if (text.isBlank()) { _statusMessage.value = "最新章节暂无正文"; return onDone(false) }
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid()) { _statusMessage.value = "请先配置文本模型"; return onDone(false) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                updateOneContainer(projectId, c, ch.id, ch.order_index, ch.title, text, cfg, _uiLanguage.value)
            }.isSuccess
            withContext(Dispatchers.Main) {
                _statusMessage.value = if (ok) "容器《${c.name}》已更新" else "容器更新失败"
                onDone(ok)
            }
        }
    }

    /** Fan out per-chapter AI updates for every auto-update container (called from onChapterSaved). */
    private fun updateContainersForChapter(projectId: String, chapterId: String, title: String, text: String) {
        val cfg = repo.activeTextModelConfig()
        if (!cfg.isValid() || text.trim().length < 50) return
        val containers = repo.containers(projectId).filter { it.autoUpdatePerChapter }
        if (containers.isEmpty()) return
        val lang = _uiLanguage.value
        val order = repo.chapters(projectId).firstOrNull { it.id == chapterId }?.order_index ?: 0
        viewModelScope.launch(Dispatchers.IO) {
            containers.forEach { c ->
                runCatching { updateOneContainer(projectId, c, chapterId, order, title, text, cfg, lang) }
                    .onFailure { _statusMessage.value = "容器《${c.name}》更新失败：${it.message}" }
            }
        }
    }

    private suspend fun updateOneContainer(
        projectId: String, container: Container, chapterId: String,
        order: Int, title: String, text: String, cfg: TextModelConfig, lang: String,
    ) {
        fun entryFrom(value: String) = ContainerEntry(
            id = "ce-${System.currentTimeMillis()}-${(0..999).random()}",
            value = value.trim(),
            sourceChapterId = chapterId, sourceChapterOrder = order, sourceChapterTitle = title,
            createdAt = nowIso(),
        )
        when (container.type) {
            Container.BY_CHARACTER -> {
                val chars = repo.characters(projectId)
                if (chars.isEmpty()) return
                val perChar = chars.joinToString("\n") { ch ->
                    val v = latestContainerValue(projectId, container.id, ch.id)
                    "【${ch.name}】${if (lang == "en") "current: " else "当前值："}${v.ifBlank { if (lang == "en") "(none)" else "（暂无）" }}"
                }
                val reply = ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.containerByCharacterSystem(container.name, lang)),
                    ChatMessage("user", Prompts.containerByCharacterUser(container.name, perChar, order, title, text, lang)),
                ))
                val updates = parseStringMap(reply)
                updates.forEach { (name, value) ->
                    if (value.isBlank()) return@forEach
                    val ch = chars.firstOrNull { it.name == name } ?: return@forEach
                    repo.appendContainerEntry(projectId, container.id, ch.id, entryFrom(value))
                }
            }
            Container.BY_CHAPTER -> {
                val reply = ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.containerByChapterSystem(container.name, lang)),
                    ChatMessage("user", Prompts.containerByChapterUser(container.name, order, title, text, lang)),
                )).trim()
                if (!isNoChange(reply)) repo.appendContainerEntry(projectId, container.id, chapterId, entryFrom(reply))
            }
            else -> {
                val cur = latestContainerValue(projectId, container.id, Container.SINGLE_BLOCK_KEY)
                val reply = ai.chat(cfg, listOf(
                    ChatMessage("system", Prompts.containerSingleSystem(container.name, lang)),
                    ChatMessage("user", Prompts.containerSingleUser(container.name, cur, order, title, text, lang)),
                )).trim()
                if (!isNoChange(reply)) repo.appendContainerEntry(projectId, container.id, Container.SINGLE_BLOCK_KEY, entryFrom(reply))
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

    data class ImportPreview(
        val bundle: BackupBundle,
        val fileName: String,
        val summary: BackupSummary,
    )

    companion object {
        private const val TARGET_WORDS = 2500
    }
}
