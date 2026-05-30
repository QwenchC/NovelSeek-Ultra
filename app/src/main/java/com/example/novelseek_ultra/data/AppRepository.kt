package com.example.novelseek_ultra.data

import android.content.Context
import com.example.novelseek_ultra.data.model.APP_SETTINGS_FIELDS
import com.example.novelseek_ultra.data.model.BackupBundle
import com.example.novelseek_ultra.data.model.BackupSummary
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterPromo
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.CharacterEvent
import com.example.novelseek_ultra.data.model.CharacterRealmEvent
import com.example.novelseek_ultra.data.model.CharacterRelationship
import com.example.novelseek_ultra.data.model.Container
import com.example.novelseek_ultra.data.model.ContainerEntry
import com.example.novelseek_ultra.data.model.ContainerStore
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.data.model.CultivationRealm
import com.example.novelseek_ultra.data.model.EmbeddingConfig
import com.example.novelseek_ultra.data.model.EntityPayload
import com.example.novelseek_ultra.data.model.Illustration
import com.example.novelseek_ultra.data.model.NovelChatMessage
import com.example.novelseek_ultra.data.model.KbChunk
import com.example.novelseek_ultra.data.model.KbStats
import com.example.novelseek_ultra.data.model.PROJECT_MAP_FIELDS
import com.example.novelseek_ultra.data.model.PlotArc
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.data.model.ProjectSnapshot
import com.example.novelseek_ultra.data.model.RestoreResult
import com.example.novelseek_ultra.data.model.SnapshotMeta
import com.example.novelseek_ultra.data.model.SummaryPayload
import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.model.collectProjectIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the in-memory application state as a [JsonObject] whose schema matches the PC zustand
 * store. The same shape is persisted to `<filesDir>/app_state.json`. Sensitive keys are never
 * written here — they live in [SecureStore].
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val stateFile: File = File(appContext.filesDir, STATE_FILE_NAME)
    val secureStore = SecureStore(appContext)

    private val _state = MutableStateFlow(seedIfNeeded(loadStateFromDisk()))
    val state: StateFlow<JsonObject> = _state.asStateFlow()

    private val _projects = MutableStateFlow(readProjects(_state.value))
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // Bumped whenever the vector-chunk store changes. KB chunks live in their own per-project
    // file (NOT in `_state`), so a chunk write doesn't emit on `state` — UI that wants to react
    // to indexing (e.g. the Settings KB stats) observes this counter instead.
    private val _kbRevision = MutableStateFlow(0)
    val kbRevision: StateFlow<Int> = _kbRevision.asStateFlow()

    // Bumped whenever a project's snapshot index changes (create / delete / restore). Snapshots
    // live in their own files (NOT in `_state`), so the version-history UI observes this counter.
    private val _snapshotRevision = MutableStateFlow(0)
    val snapshotRevision: StateFlow<Int> = _snapshotRevision.asStateFlow()

    // Bumped whenever a project's "ask the novel" chat history changes. The history lives in its
    // own per-project file (NOT in `_state`), so the Q&A UI observes this counter.
    private val _novelChatRevision = MutableStateFlow(0)
    val novelChatRevision: StateFlow<Int> = _novelChatRevision.asStateFlow()

    /** On first launch (no profiles yet), seed the 4 built-in profiles PC ships with. */
    private fun seedIfNeeded(state: JsonObject): JsonObject {
        if (state["textModelProfiles"] is JsonArray) return state
        val seeds = listOf(
            TextModelProfile("deepseek", "DeepSeek", "deepseek", "", "https://api.deepseek.com/v1", "deepseek-chat", 0.7, true, "https://platform.deepseek.com/api_keys"),
            TextModelProfile("openai", "OpenAI", "openai", "", "https://api.openai.com/v1", "gpt-4o-mini", 0.7, true, "https://platform.openai.com/api-keys"),
            TextModelProfile("openrouter", "OpenRouter", "openrouter", "", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", 0.7, true, "https://openrouter.ai/keys"),
            TextModelProfile("gemini", "Gemini(OpenAI兼容)", "gemini", "", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", 0.7, true, "https://aistudio.google.com/app/apikey"),
        )
        val seeded = JsonObject(
            state.toMutableMap().apply {
                put("textModelProfiles", JSON.encodeToJsonElement(ListSerializer(TextModelProfile.serializer()), seeds) as JsonArray)
                put("activeTextModelProfileId", JsonPrimitive("deepseek"))
                put("textModelConfig", JSON.encodeToJsonElement(
                    TextModelConfig.serializer(),
                    TextModelConfig(provider = "deepseek", apiUrl = "https://api.deepseek.com/v1", model = "deepseek-chat", temperature = 0.7),
                ) as JsonObject)
            }
        )
        saveStateToDisk(seeded)
        return seeded
    }

    // ── disk persistence ──────────────────────────────────────────────────

    private fun loadStateFromDisk(): JsonObject {
        if (!stateFile.exists()) return JsonObject(emptyMap())
        return try {
            JSON.parseToJsonElement(stateFile.readText()).jsonObject
        } catch (_: SerializationException) {
            JsonObject(emptyMap())
        } catch (_: IllegalStateException) {
            JsonObject(emptyMap())
        }
    }

    private fun saveStateToDisk(state: JsonObject) {
        stateFile.writeText(JSON.encodeToString(JsonObject.serializer(), state))
    }

    private fun mutateState(transform: (JsonObject) -> JsonObject) {
        _state.update { current ->
            val next = transform(current)
            saveStateToDisk(next)
            _projects.value = readProjects(next)
            next
        }
    }

    // ── projects ──────────────────────────────────────────────────────────

    private fun readProjects(state: JsonObject): List<Project> {
        val arr = state["projects"] as? JsonArray ?: return emptyList()
        return runCatching {
            JSON.decodeFromJsonElement(ListSerializer(Project.serializer()), arr)
        }.getOrDefault(emptyList())
    }

    fun project(projectId: String): Project? = _projects.value.firstOrNull { it.id == projectId }

    fun createProject(input: Project) {
        mutateState { current ->
            val list = readProjects(current).toMutableList()
            list.add(input)
            current.with("projects", JSON.encodeToJsonElement(ListSerializer(Project.serializer()), list) as JsonArray)
        }
    }

    fun updateProject(projectId: String, patch: (Project) -> Project) {
        mutateState { current ->
            val list = readProjects(current).map { if (it.id == projectId) patch(it) else it }
            current.with("projects", JSON.encodeToJsonElement(ListSerializer(Project.serializer()), list) as JsonArray)
        }
    }

    fun deleteProject(projectId: String) {
        mutateState { current ->
            val list = readProjects(current).filterNot { it.id == projectId }
            val next = current.with("projects", JSON.encodeToJsonElement(ListSerializer(Project.serializer()), list) as JsonArray)
            // Drop the project's KB caches so they don't linger in app_state.json.
            val withoutHashes = (next["kbIndexHashByProject"] as? JsonObject)?.let { next.with("kbIndexHashByProject", JsonObject(it.minus(projectId))) } ?: next
            (withoutHashes["kbStaleByProject"] as? JsonObject)?.let { withoutHashes.with("kbStaleByProject", JsonObject(it.minus(projectId))) } ?: withoutHashes
        }
        deleteSnapshotsForProject(projectId)
        clearNovelChat(projectId)
    }

    // ── chapters (Android-only, stored as chaptersByProject in JSON) ──────

    fun chapters(projectId: String): List<Chapter> {
        val map = _state.value["chaptersByProject"] as? JsonObject ?: return emptyList()
        val arr = map[projectId] as? JsonArray ?: return emptyList()
        return runCatching {
            JSON.decodeFromJsonElement(ListSerializer(Chapter.serializer()), arr)
        }.getOrDefault(emptyList())
    }

    fun setChapters(projectId: String, chapters: List<Chapter>) {
        mutateState { current ->
            val map = (current["chaptersByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            val newMap = map.toMutableMap()
            newMap[projectId] = JSON.encodeToJsonElement(ListSerializer(Chapter.serializer()), chapters) as JsonArray
            val totalWords = chapters.sumOf { it.word_count }
            val withChapters = current.with("chaptersByProject", JsonObject(newMap))
            // Also refresh `current_word_count` on the project.
            val projList = readProjects(withChapters).map { p ->
                if (p.id == projectId) p.copy(current_word_count = totalWords, updated_at = nowIso()) else p
            }
            withChapters.with(
                "projects",
                JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projList) as JsonArray,
            )
        }
    }

    fun upsertChapter(projectId: String, chapter: Chapter) {
        val list = chapters(projectId).toMutableList()
        val idx = list.indexOfFirst { it.id == chapter.id }
        if (idx >= 0) list[idx] = chapter else list.add(chapter)
        setChapters(projectId, list.sortedBy { it.order_index })
    }

    fun deleteChapter(projectId: String, chapterId: String) {
        setChapters(projectId, chapters(projectId).filterNot { it.id == chapterId })
    }

    // ── chapter body storage (separate JSON to avoid bloating main state) ─

    fun chapterBody(chapterId: String): ChapterBody {
        val file = File(appContext.filesDir, "chapters/${chapterId}.json")
        if (!file.exists()) return ChapterBody(draft = "", final = "")
        return runCatching {
            JSON.decodeFromString(ChapterBody.serializer(), file.readText())
        }.getOrDefault(ChapterBody("", ""))
    }

    fun saveChapterBody(chapterId: String, body: ChapterBody) {
        val dir = File(appContext.filesDir, "chapters")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "${chapterId}.json").writeText(
            JSON.encodeToString(ChapterBody.serializer(), body)
        )
    }

    // ── Chapter illustrations (PC parity — kept in their own file per chapter to avoid
    //    bloating app_state.json with megabytes of base64 image data) ─────────────────────────

    fun chapterIllustrations(chapterId: String): List<Illustration> {
        val file = File(appContext.filesDir, "illustrations/${chapterId}.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            JSON.decodeFromString(ListSerializer(Illustration.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    fun setChapterIllustrations(chapterId: String, list: List<Illustration>) {
        val dir = File(appContext.filesDir, "illustrations")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "${chapterId}.json").writeText(
            JSON.encodeToString(ListSerializer(Illustration.serializer()), list)
        )
    }

    fun upsertIllustration(chapterId: String, illustration: Illustration) {
        val existing = chapterIllustrations(chapterId).toMutableList()
        val idx = existing.indexOfFirst { it.id == illustration.id }
        if (idx >= 0) existing[idx] = illustration else existing.add(illustration)
        setChapterIllustrations(chapterId, existing)
    }

    fun deleteIllustration(chapterId: String, illustrationId: String) {
        setChapterIllustrations(chapterId, chapterIllustrations(chapterId).filterNot { it.id == illustrationId })
    }

    // ── per-project metadata maps (typed accessors) ───────────────────────

    fun worldSetting(projectId: String): String =
        ((_state.value["worldSettingByProject"] as? JsonObject)?.get(projectId) as? JsonPrimitive)
            ?.contentOrNull.orEmpty()

    fun setWorldSetting(projectId: String, value: String) {
        mutateState { current ->
            val map = (current["worldSettingByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with("worldSettingByProject", map.with(projectId, JsonPrimitive(value)))
        }
    }

    fun timeline(projectId: String): String =
        ((_state.value["timelineByProject"] as? JsonObject)?.get(projectId) as? JsonPrimitive)
            ?.contentOrNull.orEmpty()

    fun setTimeline(projectId: String, value: String) {
        mutateState { current ->
            val map = (current["timelineByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with("timelineByProject", map.with(projectId, JsonPrimitive(value)))
        }
    }

    fun outline(projectId: String): String =
        ((_state.value["longNovelOutlineByProject"] as? JsonObject)?.get(projectId) as? JsonPrimitive)
            ?.contentOrNull.orEmpty()

    fun setOutline(projectId: String, value: String) {
        mutateState { current ->
            val map = (current["longNovelOutlineByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with("longNovelOutlineByProject", map.with(projectId, JsonPrimitive(value)))
        }
    }

    fun characters(projectId: String): List<Character> =
        readListMap("charactersByProject", projectId, Character.serializer())

    fun setCharacters(projectId: String, characters: List<Character>) =
        writeListMap("charactersByProject", projectId, characters, Character.serializer())

    fun plotArcs(projectId: String): List<PlotArc> =
        readListMap("plotArcsByProject", projectId, PlotArc.serializer())

    fun setPlotArcs(projectId: String, arcs: List<PlotArc>) =
        writeListMap("plotArcsByProject", projectId, arcs.sortedBy { it.order }, PlotArc.serializer())

    fun cultivationRealms(projectId: String): List<CultivationRealm> =
        readListMap("cultivationRealmsByProject", projectId, CultivationRealm.serializer())

    fun setCultivationRealms(projectId: String, realms: List<CultivationRealm>) =
        writeListMap("cultivationRealmsByProject", projectId, realms.sortedBy { it.order }, CultivationRealm.serializer())

    fun characterRelationships(projectId: String): List<CharacterRelationship> =
        readListMap("characterRelationshipsByProject", projectId, CharacterRelationship.serializer())

    fun characterEvents(projectId: String): List<CharacterEvent> =
        readListMap("characterEventsByProject", projectId, CharacterEvent.serializer())

    fun characterRealmEvents(projectId: String): List<CharacterRealmEvent> =
        readListMap("characterRealmEventsByProject", projectId, CharacterRealmEvent.serializer())

    // ── KB summaries (chapter / arc / book) ────────────────────────────────

    fun summaries(projectId: String): List<SummaryPayload> =
        readListMap("summariesByProject", projectId, SummaryPayload.serializer())

    fun setSummaries(projectId: String, list: List<SummaryPayload>) =
        writeListMap("summariesByProject", projectId, list, SummaryPayload.serializer())

    fun upsertSummary(projectId: String, payload: SummaryPayload) {
        val existing = summaries(projectId).toMutableList()
        val idx = existing.indexOfFirst { it.scopeType == payload.scopeType && it.scopeId == payload.scopeId }
        if (idx >= 0) existing[idx] = payload else existing.add(payload)
        setSummaries(projectId, existing)
    }

    fun forgetSummary(projectId: String, scopeType: String, scopeId: String) {
        setSummaries(projectId, summaries(projectId).filterNot { it.scopeType == scopeType && it.scopeId == scopeId })
    }

    // ── KB entities (characters / foreshadowing / locations / events / items) ─

    fun entities(projectId: String): List<EntityPayload> =
        readListMap("entitiesByProject", projectId, EntityPayload.serializer())

    fun setEntities(projectId: String, list: List<EntityPayload>) =
        writeListMap("entitiesByProject", projectId, list, EntityPayload.serializer())

    fun upsertEntity(projectId: String, entity: EntityPayload) {
        val existing = entities(projectId).toMutableList()
        val idx = existing.indexOfFirst { it.id == entity.id }
        if (idx >= 0) existing[idx] = entity else existing.add(entity)
        setEntities(projectId, existing)
    }

    fun setEntityStatus(projectId: String, entityId: String, status: String) {
        setEntities(projectId, entities(projectId).map {
            if (it.id == entityId) it.copy(status = status) else it
        })
    }

    /** Drop every entity whose first/last seen chapter is the deleted one (best-effort cleanup). */
    fun forgetEntitiesForChapter(projectId: String, chapterId: String) {
        setEntities(projectId, entities(projectId).filterNot {
            it.firstSeenChapterId == chapterId || it.lastSeenChapterId == chapterId
        })
    }

    // ── KB vector chunks (per-project, stored in separate file to keep state JSON small) ─

    fun chunks(projectId: String): List<KbChunk> {
        val file = File(appContext.filesDir, "kb/${projectId}.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            JSON.decodeFromString(ListSerializer(KbChunk.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    fun setChunks(projectId: String, chunks: List<KbChunk>) {
        val dir = File(appContext.filesDir, "kb")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "${projectId}.json").writeText(
            JSON.encodeToString(ListSerializer(KbChunk.serializer()), chunks)
        )
        _kbRevision.value += 1  // notify observers (Settings KB stats) that chunks changed
    }

    fun appendChunks(projectId: String, newChunks: List<KbChunk>) {
        if (newChunks.isEmpty()) return
        val existing = chunks(projectId).toMutableList()
        // Drop any old chunks for the same (sourceType, sourceId) so re-indexing replaces cleanly.
        val sources = newChunks.map { it.sourceType to it.sourceId }.toSet()
        existing.removeAll { (it.sourceType to it.sourceId) in sources }
        existing.addAll(newChunks)
        setChunks(projectId, existing)
    }

    fun forgetKbSource(projectId: String, sourceType: String, sourceId: String) {
        setChunks(projectId, chunks(projectId).filterNot { it.sourceType == sourceType && it.sourceId == sourceId })
    }

    fun kbStats(projectId: String): KbStats {
        val all = chunks(projectId)
        val sources = all.map { it.sourceType to it.sourceId }.toSet().size
        val models = all.map { it.embeddingModel }.distinct()
        return KbStats(totalChunks = all.size, totalSources = sources, embeddingModels = models)
    }

    // ── KB feature toggles ─────────────────────────────────────────────────

    fun knowledgeBaseEnabled(): Boolean =
        (_state.value["knowledgeBaseEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false

    fun setKnowledgeBaseEnabled(enabled: Boolean) {
        mutateState { it.with("knowledgeBaseEnabled", JsonPrimitive(enabled)) }
    }

    fun summariesEnabled(): Boolean =
        (_state.value["summariesEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false

    fun setSummariesEnabled(enabled: Boolean) {
        mutateState { it.with("summariesEnabled", JsonPrimitive(enabled)) }
    }

    fun entitiesEnabled(): Boolean =
        (_state.value["entitiesEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false

    fun setEntitiesEnabled(enabled: Boolean) {
        mutateState { it.with("entitiesEnabled", JsonPrimitive(enabled)) }
    }

    // ── Chapter promo (stored in promoByChapter map in state) ────────────────────────────────

    fun getChapterPromo(chapterId: String): ChapterPromo? {
        val map = _state.value["promoByChapter"] as? JsonObject ?: return null
        val obj = map[chapterId] as? JsonObject ?: return null
        return runCatching { JSON.decodeFromJsonElement(ChapterPromo.serializer(), obj) }.getOrNull()
    }

    fun setChapterPromo(chapterId: String, promo: ChapterPromo) {
        mutateState { current ->
            val map = (current["promoByChapter"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with("promoByChapter", map.with(chapterId, JSON.encodeToJsonElement(ChapterPromo.serializer(), promo) as JsonObject))
        }
    }

    // ── Project cover images (stored as JSON string in Project.cover_images) ─────────────────

    fun getCoverImages(projectId: String): List<CoverImageItem> {
        val json = project(projectId)?.cover_images ?: return emptyList()
        return runCatching { JSON.decodeFromString(ListSerializer(CoverImageItem.serializer()), json) }.getOrDefault(emptyList())
    }

    fun setCoverImages(projectId: String, images: List<CoverImageItem>, defaultId: String?) {
        updateProject(projectId) { p ->
            p.copy(
                cover_images = JSON.encodeToString(ListSerializer(CoverImageItem.serializer()), images),
                default_cover_id = defaultId,
            )
        }
    }

    fun novelType(projectId: String): String =
        ((_state.value["novelTypeByProject"] as? JsonObject)?.get(projectId) as? JsonPrimitive)
            ?.contentOrNull ?: "short"

    fun setNovelType(projectId: String, type: String) {
        mutateState { current ->
            val map = (current["novelTypeByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with("novelTypeByProject", map.with(projectId, JsonPrimitive(type)))
        }
    }

    private fun <T> readListMap(field: String, projectId: String, ser: KSerializer<T>): List<T> {
        val arr = (_state.value[field] as? JsonObject)?.get(projectId) as? JsonArray ?: return emptyList()
        return runCatching { JSON.decodeFromJsonElement(ListSerializer(ser), arr) }
            .getOrDefault(emptyList())
    }

    private fun <T> writeListMap(field: String, projectId: String, value: List<T>, ser: KSerializer<T>) {
        mutateState { current ->
            val map = (current[field] as? JsonObject) ?: JsonObject(emptyMap())
            val encoded = JSON.encodeToJsonElement(ListSerializer(ser), value) as JsonArray
            current.with(field, map.with(projectId, encoded))
        }
    }

    // ── settings convenience accessors ────────────────────────────────────

    fun uiLanguage(): String = _state.value["uiLanguage"]?.jsonPrimitive?.contentOrNull ?: "zh"
    fun themePref(): String = _state.value["theme"]?.jsonPrimitive?.contentOrNull ?: "light"

    fun setUiLanguage(lang: String) = mutateState { it.with("uiLanguage", JsonPrimitive(lang)) }
    fun setTheme(theme: String) = mutateState { it.with("theme", JsonPrimitive(theme)) }

    /** Active text-model config — apiKey is pulled from SecureStore. */
    fun activeTextModelConfig(): TextModelConfig {
        val state = _state.value
        val cfg = (state["textModelConfig"] as? JsonObject)?.let {
            runCatching { JSON.decodeFromJsonElement(TextModelConfig.serializer(), it) }.getOrNull()
        } ?: TextModelConfig()
        val apiKey = secureStore.get(SecureStore.TEXT_MODEL_CONFIG_KEY).ifEmpty {
            // Fallback: look up via active profile id.
            val activeId = state["activeTextModelProfileId"]?.jsonPrimitive?.contentOrNull
            if (activeId != null) secureStore.get(SecureStore.profileKey(activeId)) else ""
        }
        return cfg.copy(apiKey = apiKey)
    }

    fun textModelProfiles(): List<TextModelProfile> {
        val arr = _state.value["textModelProfiles"] as? JsonArray ?: return emptyList()
        return runCatching { JSON.decodeFromJsonElement(ListSerializer(TextModelProfile.serializer()), arr) }
            .getOrDefault(emptyList())
            .map { it.copy(apiKey = secureStore.get(SecureStore.profileKey(it.id))) }
    }

    fun setActiveProfile(profileId: String) {
        mutateState { it.with("activeTextModelProfileId", JsonPrimitive(profileId)) }
        val profile = textModelProfiles().firstOrNull { it.id == profileId } ?: return
        // Sync `textModelConfig` (minus key) into state for UI bindings.
        val cfgWithoutKey = TextModelConfig(
            provider = profile.provider,
            apiKey = "",
            apiUrl = profile.apiUrl,
            model = profile.model,
            temperature = profile.temperature,
        )
        mutateState {
            it.with(
                "textModelConfig",
                JSON.encodeToJsonElement(TextModelConfig.serializer(), cfgWithoutKey) as JsonObject,
            )
        }
        secureStore.put(SecureStore.TEXT_MODEL_CONFIG_KEY, profile.apiKey)
    }

    fun saveTextModelProfile(profile: TextModelProfile) {
        secureStore.put(SecureStore.profileKey(profile.id), profile.apiKey)
        val sanitized = profile.copy(apiKey = "")
        val current = textModelProfiles().map { it.copy(apiKey = "") }
        val updated = if (current.any { it.id == profile.id })
            current.map { if (it.id == profile.id) sanitized else it }
        else current + sanitized
        mutateState {
            it.with(
                "textModelProfiles",
                JSON.encodeToJsonElement(ListSerializer(TextModelProfile.serializer()), updated) as JsonArray,
            )
        }
    }

    fun deleteTextModelProfile(profileId: String) {
        secureStore.remove(SecureStore.profileKey(profileId))
        val updated = textModelProfiles().map { it.copy(apiKey = "") }.filterNot { it.id == profileId }
        mutateState {
            it.with(
                "textModelProfiles",
                JSON.encodeToJsonElement(ListSerializer(TextModelProfile.serializer()), updated) as JsonArray,
            )
        }
    }

    fun pollinationsKey(): String = secureStore.get(SecureStore.POLLINATIONS_KEY)
    fun setPollinationsKey(key: String) = secureStore.put(SecureStore.POLLINATIONS_KEY, key)

    // Image generation engine: "pollinations" (default) or "comfyui". Stored in plain state (no
    // secret) so it round-trips with PC backups — mirrors PC store `imageEngine` / `comfyUIUrl`.
    fun imageEngine(): String =
        _state.value["imageEngine"]?.jsonPrimitive?.contentOrNull ?: "pollinations"
    fun setImageEngine(engine: String) = mutateState { it.with("imageEngine", JsonPrimitive(engine)) }

    fun comfyUIUrl(): String =
        _state.value["comfyUIUrl"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            ?: "http://localhost:8188"
    fun setComfyUIUrl(url: String) = mutateState { it.with("comfyUIUrl", JsonPrimitive(url)) }

    fun embeddingConfig(): EmbeddingConfig {
        val state = _state.value
        val cfg = (state["embeddingConfig"] as? JsonObject)?.let {
            runCatching { JSON.decodeFromJsonElement(EmbeddingConfig.serializer(), it) }.getOrNull()
        } ?: EmbeddingConfig()
        return cfg.copy(apiKey = secureStore.get(SecureStore.EMBEDDING_CONFIG_KEY))
    }

    fun saveEmbeddingConfig(cfg: EmbeddingConfig) {
        secureStore.put(SecureStore.EMBEDDING_CONFIG_KEY, cfg.apiKey)
        val sanitized = cfg.copy(apiKey = "")
        mutateState {
            it.with(
                "embeddingConfig",
                JSON.encodeToJsonElement(EmbeddingConfig.serializer(), sanitized) as JsonObject,
            )
        }
    }

    // ── export / import (PC-compatible) ────────────────────────────────────

    fun buildBackupBundle(): BackupBundle {
        val merged = mergeSensitivesIntoState(_state.value)
        return BackupBundle(
            version = BackupBundle.BACKUP_VERSION,
            exportedAt = nowIso(),
            appVersion = ANDROID_APP_VERSION,
            data = merged,
        )
    }

    private fun mergeSensitivesIntoState(base: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in base) put(k, v)
        (base["textModelProfiles"] as? JsonArray)?.let { arr ->
            val rebuilt = arr.map { entry ->
                val obj = entry.jsonObject.toMutableMap()
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                if (id != null) obj["apiKey"] = JsonPrimitive(secureStore.get(SecureStore.profileKey(id)))
                JsonObject(obj)
            }
            put("textModelProfiles", JsonArray(rebuilt))
        }
        (base["textModelConfig"] as? JsonObject)?.let { cfg ->
            val obj = cfg.toMutableMap()
            obj["apiKey"] = JsonPrimitive(secureStore.get(SecureStore.TEXT_MODEL_CONFIG_KEY))
            put("textModelConfig", JsonObject(obj))
        }
        (base["embeddingConfig"] as? JsonObject)?.let { cfg ->
            val obj = cfg.toMutableMap()
            obj["apiKey"] = JsonPrimitive(secureStore.get(SecureStore.EMBEDDING_CONFIG_KEY))
            put("embeddingConfig", JsonObject(obj))
        }
        put("pollinationsKey", JsonPrimitive(secureStore.get(SecureStore.POLLINATIONS_KEY)))
    }

    fun summarizeBackup(bundle: BackupBundle): BackupSummary {
        val inMaps = PROJECT_MAP_FIELDS.filter { it != "promoByChapter" }.map { bundle.data[it] }
        val curMaps = PROJECT_MAP_FIELDS.filter { it != "promoByChapter" }.map { _state.value[it] }
        val inIds = collectProjectIds(inMaps)
        val curIds = collectProjectIds(curMaps)
        val overlap = inIds.count { it in curIds }
        val promosIn = (bundle.data["promoByChapter"] as? JsonObject)?.size ?: 0
        val hasAppSettings = APP_SETTINGS_FIELDS.any { it in bundle.data }
        return BackupSummary(inIds.size, curIds.size, overlap, promosIn, hasAppSettings)
    }

    fun importBackup(bundle: BackupBundle, includeAppSettings: Boolean) {
        val incoming = bundle.data
        val (sanitizedIncoming, sensitives) = if (includeAppSettings) splitSensitives(incoming)
        else JsonObject(incoming.filterKeys { it in PROJECT_MAP_FIELDS || it == "folders" }) to emptyMap()

        mutateState { current ->
            val next = current.toMutableMap()

            for (k in PROJECT_MAP_FIELDS) {
                val inc = sanitizedIncoming[k] as? JsonObject ?: continue
                val cur = current[k] as? JsonObject ?: JsonObject(emptyMap())
                next[k] = JsonObject(cur + inc)
            }
            (sanitizedIncoming["folders"] as? JsonArray)?.let { incArr ->
                val map = LinkedHashMap<String, JsonObject>()
                (current["folders"] as? JsonArray)?.forEach { e ->
                    (e as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = e }
                }
                incArr.forEach { e ->
                    (e as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = e }
                }
                next["folders"] = JsonArray(map.values.toList())
            }
            if (includeAppSettings) {
                (sanitizedIncoming["textModelProfiles"] as? JsonArray)?.let { incArr ->
                    val map = LinkedHashMap<String, JsonObject>()
                    (current["textModelProfiles"] as? JsonArray)?.forEach { e ->
                        (e as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = e }
                    }
                    incArr.forEach { e ->
                        (e as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = e }
                    }
                    next["textModelProfiles"] = JsonArray(map.values.toList())
                }
                for (k in APP_SETTINGS_FIELDS) {
                    if (k == "textModelProfiles") continue
                    sanitizedIncoming[k]?.let { next[k] = it }
                }
            }
            JsonObject(next)
        }
        if (sensitives.isNotEmpty()) secureStore.putAll(sensitives)
    }

    private fun splitSensitives(incoming: JsonObject): Pair<JsonObject, Map<String, String>> {
        val sanitized = incoming.toMutableMap()
        val sensitives = mutableMapOf<String, String>()
        (incoming["textModelProfiles"] as? JsonArray)?.let { arr ->
            val stripped = arr.map { entry ->
                val obj = entry.jsonObject.toMutableMap()
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                val key = obj["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id != null && key.isNotEmpty()) sensitives[SecureStore.profileKey(id)] = key
                obj["apiKey"] = JsonPrimitive("")
                JsonObject(obj)
            }
            sanitized["textModelProfiles"] = JsonArray(stripped)
        }
        (incoming["textModelConfig"] as? JsonObject)?.let { cfg ->
            val key = cfg["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (key.isNotEmpty()) sensitives[SecureStore.TEXT_MODEL_CONFIG_KEY] = key
            val obj = cfg.toMutableMap()
            obj["apiKey"] = JsonPrimitive("")
            sanitized["textModelConfig"] = JsonObject(obj)
        }
        (incoming["embeddingConfig"] as? JsonObject)?.let { cfg ->
            val key = cfg["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (key.isNotEmpty()) sensitives[SecureStore.EMBEDDING_CONFIG_KEY] = key
            val obj = cfg.toMutableMap()
            obj["apiKey"] = JsonPrimitive("")
            sanitized["embeddingConfig"] = JsonObject(obj)
        }
        (incoming["pollinationsKey"] as? JsonPrimitive)?.contentOrNull?.let { key ->
            if (key.isNotEmpty()) sensitives[SecureStore.POLLINATIONS_KEY] = key
            sanitized["pollinationsKey"] = JsonPrimitive("")
        }
        return JsonObject(sanitized) to sensitives
    }

    // ── KB index hashes & stale tracking (support for snapshot restore) ──────
    //
    // `kbIndexHashByProject[pid][chapterId]` = sha1 of the chapter text that is CURRENTLY embedded
    // in the vector store. `kbStaleByProject[pid]` lists chapters whose embedded vectors no longer
    // match the live content (e.g. after a snapshot restore) and need re-indexing. Both are local
    // caches kept out of the backup/PROJECT_MAP_FIELDS plumbing on purpose.

    private fun nestedPut(field: String, outerKey: String, innerKey: String, value: kotlinx.serialization.json.JsonElement?) {
        mutateState { current ->
            val outer = (current[field] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val inner = (outer[outerKey] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            if (value == null) inner.remove(innerKey) else inner[innerKey] = value
            outer[outerKey] = JsonObject(inner)
            current.with(field, JsonObject(outer))
        }
    }

    fun kbIndexHashes(projectId: String): Map<String, String> {
        val inner = (_state.value["kbIndexHashByProject"] as? JsonObject)?.get(projectId) as? JsonObject
            ?: return emptyMap()
        return inner.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    fun setKbIndexHash(projectId: String, chapterId: String, hash: String) =
        nestedPut("kbIndexHashByProject", projectId, chapterId, JsonPrimitive(hash))

    fun removeKbIndexHash(projectId: String, chapterId: String) =
        nestedPut("kbIndexHashByProject", projectId, chapterId, null)

    /** Drop hash entries for chapters no longer present (keepChapterIds). */
    fun pruneKbIndexHashes(projectId: String, keepChapterIds: Set<String>) {
        mutateState { current ->
            val outer = (current["kbIndexHashByProject"] as? JsonObject) ?: return@mutateState current
            val inner = (outer[projectId] as? JsonObject) ?: return@mutateState current
            val filtered = inner.filterKeys { it in keepChapterIds }
            current.with("kbIndexHashByProject", outer.with(projectId, JsonObject(filtered)))
        }
    }

    fun kbStaleChapters(projectId: String): List<String> {
        val arr = (_state.value["kbStaleByProject"] as? JsonObject)?.get(projectId) as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    fun setKbStaleChapters(projectId: String, chapterIds: List<String>) {
        mutateState { current ->
            val map = (current["kbStaleByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with("kbStaleByProject", map.with(projectId, JsonArray(chapterIds.distinct().map { JsonPrimitive(it) })))
        }
    }

    fun clearKbStaleChapter(projectId: String, chapterId: String) {
        setKbStaleChapters(projectId, kbStaleChapters(projectId).filterNot { it == chapterId })
    }

    // ── "Ask the novel" Q&A chat history (per-project, own file) ─────────────

    private fun novelChatFile(projectId: String): File =
        File(appContext.filesDir, "novel_chat/${projectId}.json")

    fun novelChatHistory(projectId: String): List<NovelChatMessage> {
        val f = novelChatFile(projectId)
        if (!f.exists()) return emptyList()
        return runCatching {
            JSON.decodeFromString(ListSerializer(NovelChatMessage.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    fun setNovelChatHistory(projectId: String, messages: List<NovelChatMessage>) {
        val dir = File(appContext.filesDir, "novel_chat")
        if (!dir.exists()) dir.mkdirs()
        novelChatFile(projectId).writeText(
            JSON.encodeToString(ListSerializer(NovelChatMessage.serializer()), messages)
        )
        _novelChatRevision.value += 1
    }

    fun appendNovelChat(projectId: String, message: NovelChatMessage) {
        setNovelChatHistory(projectId, novelChatHistory(projectId) + message)
    }

    fun clearNovelChat(projectId: String) {
        novelChatFile(projectId).delete()
        _novelChatRevision.value += 1
    }

    // ── Audiobook (听书) reading progress + voice prefs ───────────────────────

    /** (chapterId, segmentIndex) the user last listened to in [projectId], or null. */
    fun listenProgress(projectId: String): Pair<String, Int>? {
        val obj = (_state.value["listenProgressByProject"] as? JsonObject)?.get(projectId) as? JsonObject
            ?: return null
        val chapterId = (obj["chapterId"] as? JsonPrimitive)?.contentOrNull ?: return null
        val seg = (obj["segment"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return chapterId to seg
    }

    fun setListenProgress(projectId: String, chapterId: String, segment: Int) {
        mutateState { current ->
            val map = (current["listenProgressByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            val entry = JsonObject(mapOf("chapterId" to JsonPrimitive(chapterId), "segment" to JsonPrimitive(segment)))
            current.with("listenProgressByProject", map.with(projectId, entry))
        }
    }

    fun lastListenProjectId(): String? =
        (_state.value["lastListenProjectId"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

    fun setLastListenProjectId(projectId: String) =
        mutateState { it.with("lastListenProjectId", JsonPrimitive(projectId)) }

    fun listenVoice(): String =
        (_state.value["listenVoice"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            ?: "zh-CN-XiaoxiaoNeural"

    fun setListenVoice(voice: String) = mutateState { it.with("listenVoice", JsonPrimitive(voice)) }

    fun listenRate(): Int =
        (_state.value["listenRate"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    fun setListenRate(rate: Int) = mutateState { it.with("listenRate", JsonPrimitive(rate)) }

    // ── Containers (容器) — flexible AI-evolved knowledge stores per project ──

    fun containerStore(projectId: String): ContainerStore {
        val el = (_state.value["containersByProject"] as? JsonObject)?.get(projectId) ?: return ContainerStore()
        return runCatching { JSON.decodeFromJsonElement(ContainerStore.serializer(), el) }.getOrDefault(ContainerStore())
    }

    private fun setContainerStore(projectId: String, store: ContainerStore) {
        mutateState { current ->
            val map = (current["containersByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            current.with(
                "containersByProject",
                map.with(projectId, JSON.encodeToJsonElement(ContainerStore.serializer(), store) as JsonObject),
            )
        }
    }

    fun containers(projectId: String): List<Container> = containerStore(projectId).containers

    fun container(projectId: String, containerId: String): Container? =
        containerStore(projectId).containers.firstOrNull { it.id == containerId }

    fun createContainer(projectId: String, container: Container) {
        val s = containerStore(projectId)
        setContainerStore(projectId, s.copy(containers = s.containers + container))
    }

    /** Update a container's editable metadata (name + toggles). Type and id are never changed. */
    fun updateContainerMeta(projectId: String, containerId: String, name: String, autoUpdatePerChapter: Boolean, affectsGeneration: Boolean) {
        val s = containerStore(projectId)
        setContainerStore(projectId, s.copy(
            containers = s.containers.map {
                if (it.id == containerId) it.copy(
                    name = name,
                    autoUpdatePerChapter = autoUpdatePerChapter,
                    affectsGeneration = affectsGeneration,
                ) else it
            },
        ))
    }

    fun deleteContainer(projectId: String, containerId: String) {
        val s = containerStore(projectId)
        setContainerStore(projectId, s.copy(
            containers = s.containers.filterNot { it.id == containerId },
            entries = s.entries - containerId,
        ))
    }

    fun containerEntries(projectId: String, containerId: String, blockKey: String): List<ContainerEntry> =
        containerStore(projectId).entries[containerId]?.get(blockKey) ?: emptyList()

    fun appendContainerEntry(projectId: String, containerId: String, blockKey: String, entry: ContainerEntry) {
        val s = containerStore(projectId)
        val byContainer = (s.entries[containerId] ?: emptyMap()).toMutableMap()
        byContainer[blockKey] = (byContainer[blockKey] ?: emptyList()) + entry
        setContainerStore(projectId, s.copy(entries = s.entries + (containerId to byContainer)))
    }

    /** Overwrite the value of the newest entry in a block (the user manually editing the latest value). */
    fun replaceLatestContainerEntry(projectId: String, containerId: String, blockKey: String, value: String) {
        val s = containerStore(projectId)
        val byContainer = (s.entries[containerId] ?: return).toMutableMap()
        val chain = byContainer[blockKey]?.toMutableList() ?: return
        if (chain.isEmpty()) return
        chain[chain.lastIndex] = chain.last().copy(value = value, manual = true)
        byContainer[blockKey] = chain
        setContainerStore(projectId, s.copy(entries = s.entries + (containerId to byContainer)))
    }

    // ── Project snapshots (version history) ──────────────────────────────────

    private fun versionsDir(projectId: String): File = File(appContext.filesDir, "versions/$projectId")
    private fun snapshotIndexFile(projectId: String): File = File(versionsDir(projectId), "index.json")
    private fun snapshotFile(projectId: String, snapshotId: String): File =
        File(versionsDir(projectId), "$snapshotId.json")

    fun listSnapshots(projectId: String): List<SnapshotMeta> {
        val f = snapshotIndexFile(projectId)
        if (!f.exists()) return emptyList()
        return runCatching {
            JSON.decodeFromString(ListSerializer(SnapshotMeta.serializer()), f.readText())
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    private fun writeSnapshotIndex(projectId: String, metas: List<SnapshotMeta>) {
        val dir = versionsDir(projectId)
        if (!dir.exists()) dir.mkdirs()
        snapshotIndexFile(projectId).writeText(
            JSON.encodeToString(ListSerializer(SnapshotMeta.serializer()), metas.sortedByDescending { it.createdAt })
        )
        _snapshotRevision.value += 1
    }

    private fun readSnapshot(projectId: String, snapshotId: String): ProjectSnapshot? {
        val f = snapshotFile(projectId, snapshotId)
        if (!f.exists()) return null
        return runCatching { JSON.decodeFromString(ProjectSnapshot.serializer(), f.readText()) }.getOrNull()
    }

    /**
     * Capture the whole project into a new snapshot. Returns null if the project does not exist, or
     * if [trigger] is non-manual and the content is identical to the most recent snapshot (dedup).
     */
    fun createSnapshot(projectId: String, label: String, trigger: String): SnapshotMeta? {
        val project = project(projectId) ?: return null
        val chapterList = chapters(projectId)

        val bodies = LinkedHashMap<String, JsonObject>()
        val illus = LinkedHashMap<String, JsonArray>()
        val promos = LinkedHashMap<String, JsonObject>()
        val chapterHashes = LinkedHashMap<String, String>()
        for (ch in chapterList) {
            val body = chapterBody(ch.id)
            bodies[ch.id] = JSON.encodeToJsonElement(ChapterBody.serializer(), body) as JsonObject
            chapterHashes[ch.id] = SnapshotStore.sha1(SnapshotStore.canonicalChapterText(body.final, body.draft))
            chapterIllustrations(ch.id).takeIf { it.isNotEmpty() }?.let {
                illus[ch.id] = JSON.encodeToJsonElement(ListSerializer(Illustration.serializer()), it) as JsonArray
            }
            getChapterPromo(ch.id)?.let {
                promos[ch.id] = JSON.encodeToJsonElement(ChapterPromo.serializer(), it) as JsonObject
            }
        }

        val state = _state.value
        val projectMaps = buildJsonObject {
            for (field in SnapshotStore.PROJECT_KEYED_FIELDS) {
                (state[field] as? JsonObject)?.get(projectId)?.let { put(field, it) }
            }
        }
        val projectJson = JSON.encodeToJsonElement(Project.serializer(), project) as JsonObject
        val chaptersJson = JSON.encodeToJsonElement(ListSerializer(Chapter.serializer()), chapterList) as JsonArray

        val contentSig = SnapshotStore.sha1(
            chaptersJson.toString() +
                chapterHashes.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" } +
                projectMaps.toString()
        )

        val existing = listSnapshots(projectId)
        if (trigger != SnapshotMeta.TRIGGER_MANUAL && existing.firstOrNull()?.contentSig == contentSig) {
            return null  // dedup: nothing changed since the last snapshot
        }

        val id = "snap-${System.currentTimeMillis()}-${(0..99999).random()}"
        val snapshot = ProjectSnapshot(
            meta = SnapshotMeta(
                id = id, projectId = projectId, createdAt = nowIso(), label = label, trigger = trigger,
                wordCount = project.current_word_count, chapterCount = chapterList.size, contentSig = contentSig,
            ),
            project = projectJson,
            chapters = chaptersJson,
            chapterBodies = bodies,
            illustrations = illus,
            promos = promos,
            projectMaps = projectMaps,
            chapterHashes = chapterHashes,
        )

        val dir = versionsDir(projectId)
        if (!dir.exists()) dir.mkdirs()
        val file = snapshotFile(projectId, id)
        file.writeText(JSON.encodeToString(ProjectSnapshot.serializer(), snapshot))
        val meta = snapshot.meta.copy(sizeBytes = file.length())

        writeSnapshotIndex(projectId, applyRetention(projectId, existing + meta))
        return meta
    }

    /** Keep all manual snapshots; cap auto/pre_ai at [SnapshotStore.DEFAULT_RETENTION], deleting files. */
    private fun applyRetention(projectId: String, all: List<SnapshotMeta>): List<SnapshotMeta> {
        val sorted = all.sortedByDescending { it.createdAt }
        val manual = sorted.filter { it.trigger == SnapshotMeta.TRIGGER_MANUAL }
        val auto = sorted.filter { it.trigger != SnapshotMeta.TRIGGER_MANUAL }
        auto.drop(SnapshotStore.DEFAULT_RETENTION).forEach { snapshotFile(projectId, it.id).delete() }
        return manual + auto.take(SnapshotStore.DEFAULT_RETENTION)
    }

    fun renameSnapshot(projectId: String, snapshotId: String, label: String) {
        val updated = listSnapshots(projectId).map { if (it.id == snapshotId) it.copy(label = label) else it }
        writeSnapshotIndex(projectId, updated)
    }

    fun deleteSnapshot(projectId: String, snapshotId: String) {
        snapshotFile(projectId, snapshotId).delete()
        writeSnapshotIndex(projectId, listSnapshots(projectId).filterNot { it.id == snapshotId })
    }

    fun deleteSnapshotsForProject(projectId: String) {
        versionsDir(projectId).deleteRecursively()
        _snapshotRevision.value += 1
    }

    /**
     * Restore the project to [snapshotId]. The current state is auto-snapshotted first so the
     * restore itself is undoable. Returns which chapters' KB vectors went stale (for the rebuild
     * banner) and how many orphan chunks were pruned.
     */
    fun restoreSnapshot(projectId: String, snapshotId: String): RestoreResult {
        val snap = readSnapshot(projectId, snapshotId) ?: return RestoreResult()

        // Safety net: capture current state before overwriting it.
        createSnapshot(projectId, "回退前自动备份", SnapshotMeta.TRIGGER_AUTO)

        val restoredChapterIds = snap.chapters.mapNotNull {
            (it as? JsonObject)?.get("id")?.let { idEl -> (idEl as? JsonPrimitive)?.contentOrNull }
        }.toSet()

        // 1) per-chapter files (bodies / illustrations).
        snap.chapterBodies.forEach { (cid, obj) ->
            saveChapterBody(cid, JSON.decodeFromJsonElement(ChapterBody.serializer(), obj))
        }
        restoredChapterIds.forEach { cid ->
            val arr = snap.illustrations[cid]
            val list = if (arr != null) JSON.decodeFromJsonElement(ListSerializer(Illustration.serializer()), arr) else emptyList()
            setChapterIllustrations(cid, list)
        }

        // 2) state: project record, chapter list, per-project maps, promos.
        mutateState { current ->
            val next = current.toMutableMap()

            val proj = JSON.decodeFromJsonElement(Project.serializer(), snap.project)
            val projList = readProjects(current).toMutableList()
            val idx = projList.indexOfFirst { it.id == projectId }
            if (idx >= 0) projList[idx] = proj else projList.add(proj)
            next["projects"] = JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projList) as JsonArray

            val cbp = (current["chaptersByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            cbp[projectId] = snap.chapters
            next["chaptersByProject"] = JsonObject(cbp)

            for (field in SnapshotStore.PROJECT_KEYED_FIELDS) {
                val map = (current[field] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                val slice = snap.projectMaps[field]
                if (slice != null) map[projectId] = slice else map.remove(projectId)
                next[field] = JsonObject(map)
            }

            val promoMap = (current["promoByChapter"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            restoredChapterIds.forEach { promoMap.remove(it) }
            snap.promos.forEach { (cid, obj) -> promoMap[cid] = obj }
            next["promoByChapter"] = JsonObject(promoMap)

            JsonObject(next)
        }

        return reconcileKbAfterRestore(projectId, restoredChapterIds, snap.chapterHashes)
    }

    /**
     * After a restore: prune KB chunks/hashes for chapters that no longer exist (kills "ghost from
     * the future" + orphan citations for free), then flag chapters whose embedded vectors no longer
     * match the restored content as stale (so the UI can offer a targeted, opt-in rebuild).
     */
    private fun reconcileKbAfterRestore(
        projectId: String,
        restoredChapterIds: Set<String>,
        snapHashes: Map<String, String>,
    ): RestoreResult {
        val before = chunks(projectId)
        val kept = before.filter { it.sourceId in restoredChapterIds }
        val pruned = before.size - kept.size
        if (pruned > 0) setChunks(projectId, kept)
        pruneKbIndexHashes(projectId, restoredChapterIds)

        if (!knowledgeBaseEnabled()) {
            setKbStaleChapters(projectId, emptyList())
            return RestoreResult(emptyList(), pruned)
        }

        val live = kbIndexHashes(projectId)
        // Stale = chapters whose desired (restored) content hash differs from what is embedded now.
        val stale = restoredChapterIds.filter { cid ->
            val want = snapHashes[cid] ?: return@filter false
            live[cid] != want
        }
        setKbStaleChapters(projectId, stale)
        return RestoreResult(stale, pruned)
    }

    @kotlinx.serialization.Serializable
    data class ChapterBody(val draft: String = "", val final: String = "")

    companion object {
        private const val STATE_FILE_NAME = "app_state.json"
        private const val ANDROID_APP_VERSION = "1.0.0-android"

        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile private var INSTANCE: AppRepository? = null
        private val LOCK = AtomicReference<Any>(Any())

        fun get(context: Context): AppRepository {
            INSTANCE?.let { return it }
            synchronized(LOCK) {
                INSTANCE?.let { return it }
                val created = AppRepository(context.applicationContext)
                INSTANCE = created
                return created
            }
        }
    }
}

// ── tiny JsonObject helpers ───────────────────────────────────────────────

internal fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
    JsonObject(this.toMutableMap().apply { put(key, value) })

internal fun nowIso(): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date())
}
