package com.example.novelseek_ultra.data

import android.content.Context
import com.example.novelseek_ultra.data.ai.EntityReconciliation
import com.example.novelseek_ultra.data.model.APP_SETTINGS_FIELDS
import com.example.novelseek_ultra.data.model.AgentIndex
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentSessionMeta
import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.data.model.BackupBundle
import com.example.novelseek_ultra.data.model.BackupSummary
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterPromo
import com.example.novelseek_ultra.data.model.ChapterQualityReport
import com.example.novelseek_ultra.data.model.ChapterSpec
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.CharacterGrowthEntry
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
import com.example.novelseek_ultra.data.model.ChapterFactEvidenceBatch
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.GenerationContextFingerprint
import com.example.novelseek_ultra.data.model.GenerationContextManifest
import com.example.novelseek_ultra.data.model.GenerationSourceFingerprint
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
import com.example.novelseek_ultra.data.model.Volume
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.model.collectProjectIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the in-memory application state as a [JsonObject] whose schema matches the PC zustand
 * store. The same shape is persisted to `<filesDir>/app_state.json`. Sensitive keys are never
 * written here — they live in [SecureStore].
 */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val stateFile: File = File(appContext.filesDir, STATE_FILE_NAME)
    private val secureStore = SecureStore(appContext)
    private val generationRunStore = GenerationRunStore(
        File(appContext.filesDir, "generation_runs"),
        JSON,
    )

    /** True when this process repaired an import that was interrupted before commit. */
    val recoveredInterruptedBackupImport: Boolean =
        BackupImportJournal.recoverIfNeeded(appContext.filesDir, stateFile, secureStore)
    private val initialState = seedIfNeeded(loadStateFromDisk())
    private val stateStore = AtomicJsonStateStore(stateFile, initialState, JSON)
    private val stateMutationLock = Any()
    private val chapterMutationLock = Any()
    /** In-memory tombstone is sufficient: process death also cancels every in-flight request. */
    private val factEvidenceEpochs = mutableMapOf<Pair<String, String>, Long>()
    private val generationRunMutationLock = Any()
    private val illustrationMutationLock = Any()
    private val kbMutationLock = Any()
    private val novelChatMutationLock = Any()
    private val agentMutationLock = Any()
    private val secureMutationLock = Any()
    @Volatile
    private var writesBlockedByBackupRecovery = false
    private val _state = MutableStateFlow(initialState)
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

    // Candidate bodies are stored outside app_state, so review UIs observe this revision instead
    // of forcing every long candidate through the application's main JSON state flow.
    private val _generationRunRevision = MutableStateFlow(0)
    val generationRunRevision: StateFlow<Int> = _generationRunRevision.asStateFlow()

    /** Seed built-ins and narrowly migrate the retired official DeepSeek model alias. */
    private fun seedIfNeeded(state: JsonObject): JsonObject {
        val seeds = listOf(
            TextModelProfile(
                id = "deepseek", name = "DeepSeek", provider = "deepseek",
                apiUrl = "https://api.deepseek.com/v1",
                model = TextModelMigration.CURRENT_MODEL,
                thinkingMode = com.example.novelseek_ultra.data.model.TextModelThinkingModes.DISABLED,
                builtIn = true,
                keyUrl = "https://platform.deepseek.com/api_keys",
            ),
            TextModelProfile(
                id = "openai", name = "OpenAI", provider = "openai",
                apiUrl = "https://api.openai.com/v1", model = "gpt-4o-mini", builtIn = true,
                keyUrl = "https://platform.openai.com/api-keys",
            ),
            TextModelProfile(
                id = "openrouter", name = "OpenRouter", provider = "openrouter",
                apiUrl = "https://openrouter.ai/api/v1", model = "openai/gpt-4o-mini", builtIn = true,
                keyUrl = "https://openrouter.ai/keys",
            ),
            TextModelProfile(
                id = "gemini", name = "Gemini(OpenAI兼容)", provider = "gemini",
                apiUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                model = "gemini-2.0-flash", builtIn = true,
                keyUrl = "https://aistudio.google.com/app/apikey",
            ),
        )
        var next = if (state["textModelProfiles"] !is JsonArray) {
            JsonObject(state.toMutableMap().apply {
                put("textModelProfiles", JSON.encodeToJsonElement(ListSerializer(TextModelProfile.serializer()), seeds) as JsonArray)
                put("activeTextModelProfileId", JsonPrimitive("deepseek"))
                put("textModelConfig", JSON.encodeToJsonElement(
                    TextModelConfig.serializer(),
                    TextModelConfig(
                        provider = "deepseek",
                        apiUrl = "https://api.deepseek.com/v1",
                        model = TextModelMigration.CURRENT_MODEL,
                        temperature = 0.7,
                        thinkingMode = com.example.novelseek_ultra.data.model.TextModelThinkingModes.DISABLED,
                    ),
                ) as JsonObject)
            })
        } else {
            state
        }
        next = migrateTextModelState(next)
        if (next != state) saveStateToDisk(next)
        return next
    }

    /** Apply the same narrow model migration to startup state and imported application settings. */
    private fun migrateTextModelState(state: JsonObject): JsonObject {
        val profiles = (state["textModelProfiles"] as? JsonArray)?.let { encoded ->
            runCatching {
                JSON.decodeFromJsonElement(ListSerializer(TextModelProfile.serializer()), encoded)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val config = (state["textModelConfig"] as? JsonObject)?.let { encoded ->
            runCatching { JSON.decodeFromJsonElement(TextModelConfig.serializer(), encoded) }.getOrNull()
        }
        val migrated = TextModelMigration.migrate(
            profiles = profiles,
            activeProfileId = state["activeTextModelProfileId"]?.jsonPrimitive?.contentOrNull,
            config = config,
        )
        if (migrated.profiles == profiles && migrated.config == config) return state

        return JsonObject(state.toMutableMap().apply {
            put(
                "textModelProfiles",
                JSON.encodeToJsonElement(
                    ListSerializer(TextModelProfile.serializer()),
                    migrated.profiles,
                ) as JsonArray,
            )
            migrated.config?.let { current ->
                put(
                    "textModelConfig",
                    JSON.encodeToJsonElement(TextModelConfig.serializer(), current) as JsonObject,
                )
            }
        })
    }

    // ── disk persistence ──────────────────────────────────────────────────

    private fun loadStateFromDisk(): JsonObject {
        return AtomicJsonStateStore.loadOrEmpty(stateFile, JSON)
    }

    private fun saveStateToDisk(state: JsonObject) {
        AtomicTextFile.writeText(stateFile, JSON.encodeToString(JsonObject.serializer(), state))
    }

    private fun mutateState(transform: (JsonObject) -> JsonObject) {
        synchronized(stateMutationLock) {
            ensureWritesAllowed()
            stateStore.update(transform) { next ->
                _projects.value = readProjects(next)
                _state.value = next
            }
        }
    }

    private fun ensureWritesAllowed() {
        check(!writesBlockedByBackupRecovery) {
            "上次备份导入回滚尚未完成；为保护新数据，本进程已停止写入，请立即重启应用完成恢复"
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

    fun updateProject(projectId: String, patch: (Project) -> Project) = synchronized(chapterMutationLock) {
        mutateState { current ->
            val list = readProjects(current).map { if (it.id == projectId) patch(it) else it }
            current.with("projects", JSON.encodeToJsonElement(ListSerializer(Project.serializer()), list) as JsonArray)
        }
    }

    fun deleteProject(projectId: String) {
        synchronized(chapterMutationLock) {
            synchronized(illustrationMutationLock) {
                synchronized(kbMutationLock) {
                    synchronized(novelChatMutationLock) {
                        var chapterIds: Set<String> = emptySet()
                        mutateState { current ->
                            chapterIds = ((current["chaptersByProject"] as? JsonObject)?.get(projectId) as? JsonArray)
                                ?.mapNotNull { item ->
                                    runCatching { JSON.decodeFromJsonElement(Chapter.serializer(), item).id }.getOrNull()
                                }
                                .orEmpty()
                                .toSet()
                            val next = current.toMutableMap()
                            val projects = readProjects(current).filterNot { it.id == projectId }
                            next["projects"] = JSON.encodeToJsonElement(
                                ListSerializer(Project.serializer()),
                                projects,
                            ) as JsonArray

                            // Every *ByProject value is a project-id keyed map. Removing every slice prevents
                            // a delayed AI completion from finding stale chapters/outline/KB state after the
                            // project itself has gone away.
                            current.forEach { (key, value) ->
                                if (key.endsWith("ByProject") && value is JsonObject) {
                                    next[key] = JsonObject(value.minus(projectId))
                                }
                            }
                            if (chapterIds.isNotEmpty()) {
                                val promos = current["promoByChapter"] as? JsonObject
                                if (promos != null) {
                                    next["promoByChapter"] = JsonObject(promos.filterKeys { it !in chapterIds })
                                }
                            }
                            JsonObject(next)
                        }

                        // Keep all external deletion inside the same lock boundary as the state
                        // removal. Queued AI completions resume afterwards, observe that their
                        // chapter/project is gone, and cannot recreate any of these files.
                        chapterIds.forEach { chapterId ->
                            AtomicTextFile.delete(File(appContext.filesDir, "chapters/${chapterId}.json"))
                            AtomicTextFile.delete(File(appContext.filesDir, "illustrations/${chapterId}.json"))
                        }
                        AtomicTextFile.delete(chunksFile(projectId))
                        AtomicTextFile.delete(novelChatFile(projectId))
                        _kbRevision.value += 1
                        _novelChatRevision.value += 1
                    }
                }
            }
        }
        synchronized(generationRunMutationLock) {
            if (generationRunStore.deleteProject(projectId)) {
                _generationRunRevision.value += 1
            }
        }
        deleteSnapshotsForProject(projectId)
    }

    // ── chapters (Android-only, stored as chaptersByProject in JSON) ──────

    fun chapters(projectId: String): List<Chapter> {
        val map = _state.value["chaptersByProject"] as? JsonObject ?: return emptyList()
        val arr = map[projectId] as? JsonArray ?: return emptyList()
        return runCatching {
            JSON.decodeFromJsonElement(ListSerializer(Chapter.serializer()), arr)
        }.getOrDefault(emptyList())
    }

    fun setChapters(projectId: String, chapters: List<Chapter>) = synchronized(chapterMutationLock) {
        mutateState { current ->
            val previous = readListMap(
                current,
                "chaptersByProject",
                projectId,
                Chapter.serializer(),
            )
            val changedSourceIds = DerivedChapterMutation.changedMetadataIds(
                previous.map { DerivedChapterMutation.Metadata(it.id, it.title, it.order_index) },
                chapters.map { DerivedChapterMutation.Metadata(it.id, it.title, it.order_index) },
            )
            // Metadata and its derived stale markers publish in the same state transaction.
            val sourceState = invalidateChapterDerivedState(
                current,
                projectId,
                changedSourceIds,
                includeExtractedState = false,
            )
            val map = (sourceState["chaptersByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            val newMap = map.toMutableMap()
            newMap[projectId] = JSON.encodeToJsonElement(ListSerializer(Chapter.serializer()), chapters) as JsonArray
            val totalWords = chapters.sumOf { it.word_count }
            val withChapters = sourceState.with("chaptersByProject", JsonObject(newMap))
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

    fun upsertChapter(projectId: String, chapter: Chapter) = synchronized(chapterMutationLock) {
        val list = chapters(projectId).toMutableList()
        val idx = list.indexOfFirst { it.id == chapter.id }
        if (idx >= 0) list[idx] = chapter else list.add(chapter)
        setChapters(projectId, list.sortedBy { it.order_index })
    }

    /** Replace an exact chapter snapshot without overwriting a concurrent editor/agent change. */
    internal fun updateChapterIfUnchanged(
        projectId: String,
        expected: Chapter,
        transform: (Chapter) -> Chapter,
    ): Boolean = synchronized(chapterMutationLock) {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val chapters = readListMap(
                current,
                "chaptersByProject",
                projectId,
                Chapter.serializer(),
            )
            val index = chapters.indexOfFirst { it.id == expected.id }
            if (index < 0 || chapters[index] != expected) return@mutateState current

            val transformed = transform(chapters[index])
            val sourceState = if (
                transformed.title != chapters[index].title ||
                transformed.order_index != chapters[index].order_index
            ) {
                invalidateChapterDerivedState(
                    current,
                    projectId,
                    setOf(expected.id),
                    includeExtractedState = false,
                )
            } else {
                current
            }
            val updated = chapters.toMutableList()
            updated[index] = transformed
            val sorted = updated.sortedBy { it.order_index }
            val withChapters = withListMap(
                sourceState,
                "chaptersByProject",
                projectId,
                sorted,
                Chapter.serializer(),
            )
            val totalWords = sorted.sumOf { it.word_count }
            val projects = readProjects(withChapters).map { project ->
                if (project.id == projectId) {
                    project.copy(current_word_count = totalWords, updated_at = nowIso())
                } else {
                    project
                }
            }
            committed = true
            withChapters.with(
                "projects",
                JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projects) as JsonArray,
            )
        }
        committed
    }

    /**
     * Assign a chapter and update the arc index in one transaction. Both source objects must still
     * be byte-for-byte equal to the values the caller inspected before asking for confirmation.
     */
    internal fun assignChapterToArcIfUnchanged(
        projectId: String,
        expectedChapter: Chapter,
        expectedArc: PlotArc,
    ): Boolean = synchronized(chapterMutationLock) {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val chapters = readListMap(
                current,
                "chaptersByProject",
                projectId,
                Chapter.serializer(),
            )
            val chapterIndex = chapters.indexOfFirst { it.id == expectedChapter.id }
            if (chapterIndex < 0 || chapters[chapterIndex] != expectedChapter) {
                return@mutateState current
            }

            val arcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            val arcIndex = arcs.indexOfFirst { it.id == expectedArc.id }
            if (arcIndex < 0 || arcs[arcIndex] != expectedArc) return@mutateState current

            val updatedChapters = chapters.toMutableList().apply {
                this[chapterIndex] = this[chapterIndex].copy(arcId = expectedArc.id)
            }
            val updatedArcs = arcs.map { arc ->
                val indexedChapterIds = arc.builtChapterIds.orEmpty()
                when (arc.id) {
                    expectedArc.id -> arc.copy(
                        builtChapterIds = (indexedChapterIds + expectedChapter.id).distinct(),
                    )
                    else -> if (expectedChapter.id in indexedChapterIds) {
                        arc.copy(builtChapterIds = indexedChapterIds.filterNot { it == expectedChapter.id })
                    } else {
                        arc
                    }
                }
            }

            var next = withListMap(
                current,
                "chaptersByProject",
                projectId,
                updatedChapters.sortedBy { it.order_index },
                Chapter.serializer(),
            )
            next = withListMap(
                next,
                "plotArcsByProject",
                projectId,
                updatedArcs.sortedBy { it.order },
                PlotArc.serializer(),
            )
            val projects = readProjects(next).map { project ->
                if (project.id == projectId) project.copy(updated_at = nowIso()) else project
            }
            committed = true
            next.with(
                "projects",
                JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projects) as JsonArray,
            )
        }
        committed
    }

    /**
     * Delete one exact chapter snapshot and every directly-derived reference without exposing an
     * intermediate state. External files are removed under the same chapter lock, followed by the
     * illustration and KB locks in the repository-wide lock order.
     */
    internal fun deleteChapterFullyIfUnchanged(
        projectId: String,
        expected: Chapter,
    ): Boolean = synchronized(chapterMutationLock) {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val chapters = readListMap(
                current,
                "chaptersByProject",
                projectId,
                Chapter.serializer(),
            )
            val chapterIndex = chapters.indexOfFirst { it.id == expected.id }
            if (chapterIndex < 0 || chapters[chapterIndex] != expected) return@mutateState current

            val remainingChapters = chapters
                .filterIndexed { index, _ -> index != chapterIndex }
                .sortedBy { it.order_index }
                .mapIndexed { index, chapter -> chapter.copy(order_index = index + 1) }
            val invalidated = invalidateChapterDerivedState(
                current,
                projectId,
                setOf(expected.id),
                includeExtractedState = true,
            )
            var next = withListMap(
                invalidated,
                "chaptersByProject",
                projectId,
                remainingChapters,
                Chapter.serializer(),
            )

            val arcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            next = withListMap(
                next,
                "plotArcsByProject",
                projectId,
                arcs.map { arc ->
                    val chapterIds = arc.builtChapterIds
                    if (chapterIds?.contains(expected.id) == true) {
                        arc.copy(builtChapterIds = chapterIds.filterNot { it == expected.id })
                    } else {
                        arc
                    }
                },
                PlotArc.serializer(),
            )

            val summaries = readListMap(next, "summariesByProject", projectId, SummaryPayload.serializer())
            next = withListMap(
                next,
                "summariesByProject",
                projectId,
                summaries.filterNot { it.scopeType == "chapter" && it.scopeId == expected.id },
                SummaryPayload.serializer(),
            )
            val evidence = readListMap(
                next,
                "factEvidenceByProject",
                projectId,
                ChapterFactEvidenceBatch.serializer(),
            )
            val remainingEvidence = FactEvidenceLedger.removeChapter(evidence, expected.id)
            next = withListMap(
                next,
                "factEvidenceByProject",
                projectId,
                remainingEvidence,
                ChapterFactEvidenceBatch.serializer(),
            )
            val chapterOrders = remainingChapters.associate { it.id to it.order_index }
            val timedEvidenceByEntity = remainingEvidence.asSequence()
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
            val entities = readListMap(next, "entitiesByProject", projectId, EntityPayload.serializer())
            next = withListMap(
                next,
                "entitiesByProject",
                projectId,
                entities.mapNotNull { entity ->
                    EntityReconciliation.afterChapterRemoval(
                        current = entity,
                        removedChapterId = expected.id,
                        remainingEvidence = timedEvidenceByEntity[entity.id].orEmpty(),
                        chapterOrdersById = chapterOrders,
                    )
                },
                EntityPayload.serializer(),
            )

            val promos = next["promoByChapter"] as? JsonObject
            if (promos != null && expected.id in promos) {
                next = next.with("promoByChapter", JsonObject(promos.minus(expected.id)))
            }

            val hashOuter = (next["kbIndexHashByProject"] as? JsonObject)?.toMutableMap()
            if (hashOuter != null) {
                val hashes = (hashOuter[projectId] as? JsonObject)?.minus(expected.id).orEmpty()
                hashOuter[projectId] = JsonObject(hashes)
                next = next.with("kbIndexHashByProject", JsonObject(hashOuter))
            }
            val staleOuter = (next["kbStaleByProject"] as? JsonObject)?.toMutableMap()
            if (staleOuter != null) {
                val staleChapterIds = (staleOuter[projectId] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filterNot { it == expected.id }
                staleOuter[projectId] = JsonArray(
                    staleChapterIds.distinct().map { JsonPrimitive(it) },
                )
                next = next.with("kbStaleByProject", JsonObject(staleOuter))
            }

            val projects = readProjects(next).map { project ->
                if (project.id == projectId) {
                    project.copy(
                        current_word_count = remainingChapters.sumOf { it.word_count },
                        updated_at = nowIso(),
                    )
                } else {
                    project
                }
            }
            committed = true
            next.with(
                "projects",
                JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projects) as JsonArray,
            )
        }

        if (committed) {
            synchronized(generationRunMutationLock) {
                if (generationRunStore.deleteChapter(projectId, expected.id)) {
                    _generationRunRevision.value += 1
                }
            }
            AtomicTextFile.delete(File(appContext.filesDir, "chapters/${expected.id}.json"))
            synchronized(illustrationMutationLock) {
                AtomicTextFile.delete(File(appContext.filesDir, "illustrations/${expected.id}.json"))
                synchronized(kbMutationLock) {
                    val chunks = readChunksLocked(projectId)
                    val remaining = chunks.filterNot {
                        it.sourceType == "chapter" && it.sourceId == expected.id
                    }
                    if (remaining != chunks) {
                        if (remaining.isEmpty()) {
                            AtomicTextFile.delete(chunksFile(projectId))
                        } else {
                            writeChunksLocked(projectId, remaining)
                        }
                        _kbRevision.value += 1
                    }
                }
            }
        }
        committed
    }

    fun deleteChapter(projectId: String, chapterId: String): Boolean {
        val expected = chapters(projectId).firstOrNull { it.id == chapterId } ?: return false
        return deleteChapterFullyIfUnchanged(projectId, expected)
    }

    // ── chapter body storage (separate JSON to avoid bloating main state) ─

    private fun chapterProjectId(chapterId: String, state: JsonObject = _state.value): String? {
        val chaptersByProject = state["chaptersByProject"] as? JsonObject ?: return null
        return readProjects(state).firstOrNull { project ->
            (chaptersByProject[project.id] as? JsonArray).orEmpty().any { chapter ->
                (chapter as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == chapterId
            }
        }?.id
    }

    private fun chapterExists(chapterId: String, state: JsonObject = _state.value): Boolean =
        chapterProjectId(chapterId, state) != null

    fun chapterBody(chapterId: String): ChapterBody = synchronized(chapterMutationLock) {
        val file = File(appContext.filesDir, "chapters/${chapterId}.json")
        if (!AtomicTextFile.exists(file)) return ChapterBody(draft = "", final = "")
        runCatching {
            JSON.decodeFromString(ChapterBody.serializer(), AtomicTextFile.readText(file))
        }.getOrDefault(ChapterBody("", ""))
    }

    fun saveChapterBody(chapterId: String, body: ChapterBody) = synchronized(chapterMutationLock) {
        val projectId = chapterProjectId(chapterId) ?: return@synchronized
        val previous = chapterBody(chapterId)
        if (previous == body) return@synchronized
        if (DerivedChapterMutation.effectiveTextChanged(
                oldDraft = previous.draft,
                oldFinal = previous.final,
                newDraft = body.draft,
                newFinal = body.final,
            )
        ) {
            // Invalidate first. A process death between the two writes leaves conservative stale
            // markers, never fresh-looking derived data for prose that may already have changed.
            invalidateChapterDerivedStateLocked(projectId, chapterId)
        }
        writeChapterBodyUncheckedLocked(chapterId, body)
    }

    /** Must run while [chapterMutationLock] is held. */
    private fun invalidateChapterDerivedStateLocked(projectId: String, chapterId: String) {
        mutateState { current ->
            invalidateChapterDerivedState(
                current,
                projectId,
                setOf(chapterId),
                includeExtractedState = true,
            )
        }
    }

    /** Non-destructive state transform shared by body, title and order mutation boundaries. */
    private fun invalidateChapterDerivedState(
        current: JsonObject,
        projectId: String,
        chapterIds: Set<String>,
        includeExtractedState: Boolean,
    ): JsonObject {
        if (chapterIds.isEmpty() || !hasProject(current, projectId)) return current
        val orderedIds = chapterIds.sorted()
        val staleSummaries = orderedIds.fold(
            readListMap(current, "summariesByProject", projectId, SummaryPayload.serializer()),
        ) { entries, chapterId ->
            DerivedStateInvalidation.summariesAfterChapterChange(entries, chapterId)
        }
        val currentEntities = readListMap(
            current,
            "entitiesByProject",
            projectId,
            EntityPayload.serializer(),
        )
        val staleEntities = if (includeExtractedState) {
            orderedIds.fold(currentEntities) { entries, chapterId ->
                DerivedStateInvalidation.entitiesAfterChapterChange(entries, chapterId)
            }
        } else {
            currentEntities
        }
        val currentEvidence = readListMap(
            current,
            "factEvidenceByProject",
            projectId,
            ChapterFactEvidenceBatch.serializer(),
        )
        val staleEvidence = if (includeExtractedState) {
            orderedIds.fold(currentEvidence) { entries, chapterId ->
                FactEvidenceLedger.afterChapterChange(entries, chapterId)
            }
        } else {
            currentEvidence
        }
        val staleOuter = (current["kbStaleByProject"] as? JsonObject ?: JsonObject(emptyMap()))
            .toMutableMap()
        val staleChapterIds = orderedIds.fold(
            (staleOuter[projectId] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        ) { entries, chapterId ->
            DerivedStateInvalidation.staleChapterIdsAfterChange(entries, chapterId)
        }
        staleOuter[projectId] = JsonArray(staleChapterIds.map(::JsonPrimitive))

        var next = withListMap(
            current,
            "summariesByProject",
            projectId,
            staleSummaries,
            SummaryPayload.serializer(),
        )
        next = withListMap(
            next,
            "entitiesByProject",
            projectId,
            staleEntities,
            EntityPayload.serializer(),
        )
        next = withListMap(
            next,
            "factEvidenceByProject",
            projectId,
            staleEvidence,
            ChapterFactEvidenceBatch.serializer(),
        )

        val growthOuter = if (includeExtractedState) {
            (current["characterGrowthByProject"] as? JsonObject)?.toMutableMap()
        } else {
            null
        }
        val growthInner = growthOuter?.get(projectId) as? JsonObject
        if (growthOuter != null && growthInner != null) {
            val staleGrowth = growthInner.mapValues { (_, value) ->
                val entries = runCatching {
                    JSON.decodeFromJsonElement(
                        ListSerializer(CharacterGrowthEntry.serializer()),
                        value,
                    )
                }.getOrDefault(emptyList())
                JSON.encodeToJsonElement(
                    ListSerializer(CharacterGrowthEntry.serializer()),
                    orderedIds.fold(entries) { currentEntries, chapterId ->
                        DerivedStateInvalidation.growthAfterChapterChange(currentEntries, chapterId)
                    },
                )
            }
            growthOuter[projectId] = JsonObject(staleGrowth)
            next = next.with("characterGrowthByProject", JsonObject(growthOuter))
        }

        val containerProjects = current["containersByProject"].takeIf { includeExtractedState } as? JsonObject
        if (containerProjects?.containsKey(projectId) == true) {
            val staleStore = orderedIds.fold(containerStore(current, projectId)) { store, chapterId ->
                DerivedStateInvalidation.containersAfterChapterChange(store, chapterId)
            }
            next = withContainerStore(next, projectId, staleStore)
        }
        return next.with("kbStaleByProject", JsonObject(staleOuter))
    }

    /** Snapshot restore validates its payload first and publishes chapter metadata last. */
    private fun writeChapterBodyUncheckedLocked(chapterId: String, body: ChapterBody) {
        ensureWritesAllowed()
        val dir = File(appContext.filesDir, "chapters")
        if (!dir.exists()) dir.mkdirs()
        AtomicTextFile.writeText(File(dir, "${chapterId}.json"),
            JSON.encodeToString(ChapterBody.serializer(), body)
        )
    }

    /** One linearizable read-check-write boundary shared by editor and agent chapter mutations. */
    internal fun <T> withChapterTransaction(block: () -> T): T =
        synchronized(chapterMutationLock, block)

    // ── reviewable chapter-generation runs ───────────────────────────────

    fun listGenerationRuns(projectId: String, chapterId: String? = null): List<GenerationRun> =
        generationRunStore.list(projectId).let { runs ->
            if (chapterId == null) runs else runs.filter { it.chapterId == chapterId }
        }

    fun latestGenerationRun(projectId: String, chapterId: String): GenerationRun? =
        generationRunStore.latest(projectId, chapterId)

    fun getGenerationRun(projectId: String, runId: String): GenerationRun? =
        generationRunStore.get(projectId, runId)

    /**
     * Capture the persisted source under the chapter lock while retaining the editor's requested
     * planning fields separately in [ChapterSpec]. Candidate slots contain no streaming text.
     */
    internal fun createGenerationRun(
        projectId: String,
        requestedChapter: Chapter,
        candidateCount: Int = 1,
        targetWords: Int = 0,
        language: String = "zh",
        mode: String = ChapterSpec.MODE_ONE_SHOT,
        beats: List<String> = emptyList(),
        constraints: List<String> = emptyList(),
        contextManifest: GenerationContextManifest? = null,
        initiator: String = GenerationRun.INITIATOR_EDITOR,
        agentEngine: String? = null,
        agentSessionId: String? = null,
        agentActionId: String? = null,
        operation: String = GenerationRun.OPERATION_GENERATE,
        expectedSourceHash: String,
        currentContextManifest: (() -> GenerationContextManifest?)? = null,
    ): GenerationRun? = synchronized(chapterMutationLock) chapterLock@{
        synchronized(generationRunMutationLock) generationLock@{
            ensureWritesAllowed()
            // The unified engine currently owns exactly one execution/telemetry stream per run.
            // Candidate slots remain in the persisted schema for forward compatibility, but a
            // multi-candidate run needs candidate-level telemetry and must not be created yet.
            require(candidateCount == 1) { "Generation runs currently require exactly one candidate" }
            if (requestedChapter.project_id != projectId) return@generationLock null
            val baselineChapter = chapters(projectId).firstOrNull { it.id == requestedChapter.id }
                ?: return@generationLock null
            val baselineBody = chapterBody(requestedChapter.id)
            val hashes = GenerationSourceFingerprint.capture(
                baselineChapter,
                baselineBody.draft,
                baselineBody.final,
            )
            if (hashes.sourceHash != expectedSourceHash) {
                return@generationLock null
            }

            synchronized(kbMutationLock) kbLock@{
            synchronized(stateMutationLock) stateLock@{
            if (contextManifest != null && !contextManifest.scope.startsWith("agent:")) {
                val lockedContext = currentContextManifest
                    ?.let { provider -> runCatching(provider).getOrNull() }
                    ?: return@generationLock null
                if (
                    lockedContext.version != contextManifest.version ||
                    lockedContext.scope != contextManifest.scope ||
                    lockedContext.fingerprint != contextManifest.fingerprint ||
                    !lockedContext.isSelfConsistent()
                ) {
                    return@generationLock null
                }
            }
            val existingRuns = generationRunStore.list(projectId).filter {
                it.chapterId == baselineChapter.id
            }
            // A completed run represents an unresolved user decision and must remain reachable.
            if (existingRuns.any { it.status == GenerationRun.STATUS_COMPLETED }) {
                return@generationLock null
            }
            // Source validation above and superseding the old active run happen under the same
            // chapter+generation lock. A stale request therefore cannot cancel a valid newer run.
            existingRuns.firstOrNull { !it.isTerminal() }?.let { stale ->
                val cancelled = cancelGenerationRun(
                    projectId,
                    stale.id,
                    GenerationRun.CANCEL_REASON_SUPERSEDED,
                )
                if (cancelled?.status != GenerationRun.STATUS_CANCELLED) {
                    return@generationLock null
                }
            }
            val sourceHashVersion = if (contextManifest == null) {
                GenerationSourceFingerprint.LEGACY_VERSION
            } else {
                GenerationSourceFingerprint.CONTEXT_BOUND_VERSION
            }
            val boundSourceHash = if (contextManifest == null) {
                hashes.sourceHash
            } else {
                GenerationSourceFingerprint.combineWithContext(
                    hashes.planHash,
                    hashes.bodyHash,
                    contextManifest.fingerprint,
                )
            }
            val timestamp = nowIso()
            val run = GenerationRun(
                id = "run-${System.currentTimeMillis()}-${UUID.randomUUID()}",
                projectId = projectId,
                chapterId = baselineChapter.id,
                spec = ChapterSpec.fromChapter(
                    projectId = projectId,
                    chapter = requestedChapter,
                    targetWords = targetWords,
                    language = language,
                    mode = mode,
                    beats = beats,
                    constraints = constraints,
                ),
                sourceHash = boundSourceHash,
                sourceHashVersion = sourceHashVersion,
                baselinePlanHash = hashes.planHash,
                baselineBodyHash = hashes.bodyHash,
                contextManifest = contextManifest,
                initiator = initiator,
                agentEngine = agentEngine,
                agentSessionId = agentSessionId,
                agentActionId = agentActionId,
                operation = operation,
                candidates = (1..candidateCount).map { slot ->
                    CandidateChapter(
                        id = "candidate-$slot-${UUID.randomUUID()}",
                        slot = slot,
                    )
                },
                createdAt = timestamp,
                updatedAt = timestamp,
            )
            if (!generationRunStore.create(run)) return@generationLock null
            _generationRunRevision.value += 1
            run
            }
            }
        }
    }

    /** Atomic read-transform-write for run metadata; callers must never persist streaming deltas. */
    private fun updateGenerationRun(
        projectId: String,
        runId: String,
        transform: (GenerationRun) -> GenerationRun,
    ): GenerationRun? = synchronized(generationRunMutationLock) {
        ensureWritesAllowed()
        val before = generationRunStore.get(projectId, runId) ?: return@synchronized null
        val updated = generationRunStore.update(projectId, runId, transform)
        if (updated != null && updated != before) _generationRunRevision.value += 1
        updated
    }

    /** Mark one reserved slot active without exposing the store lock to an arbitrary callback. */
    internal fun markCandidateRunning(
        projectId: String,
        runId: String,
        candidateId: String,
    ): GenerationRun? = updateGenerationRun(projectId, runId) { run ->
        if (run.status != GenerationRun.STATUS_RUNNING) return@updateGenerationRun run
        val index = run.candidates.indexOfFirst { it.id == candidateId }
        if (index < 0 || run.candidates[index].status != CandidateChapter.STATUS_PENDING) {
            return@updateGenerationRun run
        }
        val timestamp = nowIso()
        run.copy(
            candidates = run.candidates.toMutableList().apply {
                this[index] = this[index].copy(status = CandidateChapter.STATUS_RUNNING)
            },
            updatedAt = timestamp,
        )
    }

    /** Persist one candidate only after its stream and deterministic quality checks have ended. */
    internal fun completeCandidate(
        projectId: String,
        runId: String,
        candidateId: String,
        body: String,
        report: ChapterQualityReport,
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun? = updateGenerationRun(projectId, runId) { run ->
        if (run.status != GenerationRun.STATUS_RUNNING) return@updateGenerationRun run
        val index = run.candidates.indexOfFirst { it.id == candidateId }
        if (index < 0 || run.candidates[index].status != CandidateChapter.STATUS_RUNNING) {
            return@updateGenerationRun run
        }
        val timestamp = nowIso()
        val candidates = run.candidates.toMutableList().apply {
            this[index] = this[index].copy(
                status = CandidateChapter.STATUS_COMPLETED,
                body = body,
                wordCount = report.wordCount,
                qualityReport = report,
                completedAt = timestamp,
                error = null,
            )
        }
        finishRunIfCandidatesTerminal(run, candidates, timestamp, telemetry)
    }

    internal fun failCandidate(
        projectId: String,
        runId: String,
        candidateId: String,
        error: String,
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun? = updateGenerationRun(projectId, runId) { run ->
        if (run.status != GenerationRun.STATUS_RUNNING) return@updateGenerationRun run
        val index = run.candidates.indexOfFirst { it.id == candidateId }
        if (index < 0 || run.candidates[index].isTerminal()) return@updateGenerationRun run
        val timestamp = nowIso()
        val candidates = run.candidates.toMutableList().apply {
            this[index] = this[index].copy(
                status = CandidateChapter.STATUS_FAILED,
                body = "",
                wordCount = 0,
                qualityReport = null,
                completedAt = timestamp,
                error = error,
            )
        }
        finishRunIfCandidatesTerminal(run, candidates, timestamp, telemetry)
    }

    internal fun failGenerationRun(
        projectId: String,
        runId: String,
        error: String,
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun? =
        updateGenerationRun(projectId, runId) { run ->
            if (run.isTerminal()) {
                return@updateGenerationRun if (
                    run.status == GenerationRun.STATUS_FAILED &&
                    run.telemetry == null &&
                    telemetry?.outcome == GenerationTelemetry.OUTCOME_FAILED
                ) {
                    run.copy(telemetry = telemetry, updatedAt = nowIso())
                } else {
                    run
                }
            }
            val timestamp = nowIso()
            run.copy(
                status = GenerationRun.STATUS_FAILED,
                candidates = run.candidates.map { candidate ->
                    if (candidate.isTerminal()) candidate else candidate.copy(
                        status = CandidateChapter.STATUS_FAILED,
                        body = "",
                        wordCount = 0,
                        qualityReport = null,
                        completedAt = timestamp,
                        error = error,
                    )
                },
                updatedAt = timestamp,
                completedAt = timestamp,
                error = error,
                telemetry = telemetry,
            )
        }

    internal fun cancelGenerationRun(
        projectId: String,
        runId: String,
        reason: String = "",
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun? = updateGenerationRun(projectId, runId) { run ->
        if (run.isTerminal()) {
            return@updateGenerationRun if (
                run.status == GenerationRun.STATUS_CANCELLED &&
                run.telemetry == null &&
                telemetry?.outcome == GenerationTelemetry.OUTCOME_CANCELLED
            ) {
                run.copy(telemetry = telemetry, updatedAt = nowIso())
            } else {
                run
            }
        }
        val timestamp = nowIso()
        run.copy(
            status = GenerationRun.STATUS_CANCELLED,
            candidates = run.candidates.map { candidate ->
                if (candidate.isTerminal()) candidate else candidate.copy(
                    status = CandidateChapter.STATUS_CANCELLED,
                    body = "",
                    wordCount = 0,
                    qualityReport = null,
                    completedAt = timestamp,
                    error = reason.takeIf { it.isNotBlank() },
                )
            },
            updatedAt = timestamp,
            completedAt = timestamp,
            error = reason.takeIf { it.isNotBlank() },
            telemetry = telemetry,
        )
    }

    /** Rejecting review work never changes chapter metadata or its official body. */
    internal fun rejectGenerationRun(projectId: String, runId: String): GenerationRun? =
        updateGenerationRun(projectId, runId) { run ->
            if (run.status == GenerationRun.STATUS_ACCEPTED ||
                run.status == GenerationRun.STATUS_REJECTED
            ) return@updateGenerationRun run
            val timestamp = nowIso()
            run.copy(
                status = GenerationRun.STATUS_REJECTED,
                candidates = run.candidates.map { candidate ->
                    if (candidate.isTerminal()) candidate else candidate.copy(
                        status = CandidateChapter.STATUS_CANCELLED,
                        completedAt = timestamp,
                    )
                },
                updatedAt = timestamp,
                completedAt = run.completedAt ?: timestamp,
            )
        }

    /**
     * The sole publication edge from candidate storage to official chapter state. The persisted
     * baseline must still match before the requested plan and candidate prose are committed.
     */
    internal fun adoptCandidate(
        projectId: String,
        runId: String,
        candidateId: String,
        currentContextManifest: () -> GenerationContextManifest?,
    ): CandidateAdoptionResult = synchronized(chapterMutationLock) chapterLock@{
        synchronized(generationRunMutationLock) generationLock@{
            ensureWritesAllowed()
            val run = generationRunStore.get(projectId, runId)
                ?: return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.RUN_NOT_FOUND,
                )
            if (run.status == GenerationRun.STATUS_ACCEPTED) {
                return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.ALREADY_ACCEPTED,
                )
            }
            if (run.status != GenerationRun.STATUS_COMPLETED) {
                return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.RUN_NOT_READY,
                )
            }
            val candidate = run.candidates.firstOrNull { it.id == candidateId }
                ?: return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.CANDIDATE_NOT_FOUND,
                )
            if (candidate.status != CandidateChapter.STATUS_COMPLETED) {
                return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.CANDIDATE_NOT_READY,
                )
            }
            val quality = candidate.qualityReport
            if (quality?.completedStream != true) {
                return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.STREAM_INCOMPLETE,
                )
            }
            if (quality.blocking) {
                return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.QUALITY_BLOCKED,
                )
            }
            if (candidate.body.isBlank()) {
                return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.EMPTY_BODY,
                )
            }

            val liveChapter = chapters(projectId).firstOrNull { it.id == run.chapterId }
                ?: return@generationLock CandidateAdoptionResult.Unavailable(
                    CandidateAdoptionResult.CHAPTER_NOT_FOUND,
                )
            val liveBody = chapterBody(run.chapterId)
            val liveHashes = GenerationSourceFingerprint.capture(
                liveChapter,
                liveBody.draft,
                liveBody.final,
            )
            val planChanged = liveHashes.planHash != run.baselinePlanHash
            val bodyChanged = liveHashes.bodyHash != run.baselineBodyHash
            val unsupportedHash = run.sourceHashVersion !in setOf(
                GenerationSourceFingerprint.LEGACY_VERSION,
                GenerationSourceFingerprint.CONTEXT_BOUND_VERSION,
            )
            val expectedCombinedHash = when (run.sourceHashVersion) {
                GenerationSourceFingerprint.LEGACY_VERSION -> liveHashes.sourceHash
                GenerationSourceFingerprint.CONTEXT_BOUND_VERSION -> {
                    val manifest = run.contextManifest
                    if (manifest == null) {
                        ""
                    } else {
                        GenerationSourceFingerprint.combineWithContext(
                            liveHashes.planHash,
                            liveHashes.bodyHash,
                            manifest.fingerprint,
                        )
                    }
                }
                else -> ""
            }
            val combinedChanged = expectedCombinedHash != run.sourceHash
            val expectedContext = run.contextManifest
            if (planChanged || bodyChanged || unsupportedHash || combinedChanged) {
                return@generationLock CandidateAdoptionResult.SourceChanged(
                    planChanged = planChanged || unsupportedHash || (combinedChanged && !bodyChanged),
                    bodyChanged = bodyChanged,
                    contextChanged = false,
                )
            }

            if (liveBody.final.isNotBlank() || liveBody.draft.isNotBlank()) {
                runCatching {
                    createSnapshot(
                        projectId,
                        "AI写作前：${liveChapter.title}",
                        SnapshotMeta.TRIGGER_PRE_AI,
                    )
                }
            }

            // Context is recomputed only after acquiring every lock protecting prompt inputs. The
            // same locks remain held through journal commit, closing the former VM-check/write gap.
            synchronized(kbMutationLock) kbLock@{
            synchronized(stateMutationLock) stateLock@{
            val lockedContextManifest = if (expectedContext == null) {
                null
            } else {
                runCatching(currentContextManifest).getOrNull()
            }
            val contextChanged = expectedContext != null && (
                lockedContextManifest == null ||
                    lockedContextManifest.version != expectedContext.version ||
                    lockedContextManifest.scope != expectedContext.scope ||
                    lockedContextManifest.fingerprint != expectedContext.fingerprint ||
                    !lockedContextManifest.isSelfConsistent()
                )
            if (contextChanged) {
                return@stateLock CandidateAdoptionResult.SourceChanged(
                    planChanged = false,
                    bodyChanged = false,
                    contextChanged = true,
                    contextKeys = GenerationContextFingerprint.changedKeys(
                        expectedContext,
                        lockedContextManifest,
                    ),
                )
            }

            val previousState = _state.value
            val bodyFile = File(appContext.filesDir, "chapters/${run.chapterId}.json")
            val runFile = generationRunStore.targetFile(projectId, runId)
            val fileSnapshots = listOf(
                snapshotImportFile(bodyFile),
                snapshotImportFile(runFile),
            )
            val journal = BackupImportJournal.prepare(
                filesDir = appContext.filesDir,
                previousStateJson = JSON.encodeToString(JsonObject.serializer(), previousState),
                snapshots = fileSnapshots.map { snapshot ->
                    BackupImportJournal.SnapshotInput(
                        target = snapshot.file,
                        existed = snapshot.existed,
                        text = snapshot.text,
                    )
                },
                secretKeys = emptyList(),
                secrets = secureStore,
            )
            val timestamp = nowIso()
            val adoptedChapter = run.spec.applyPlanningTo(liveChapter).copy(
                word_count = candidate.wordCount,
                status = "review",
                updated_at = timestamp,
            )
            val adoptedBody = liveBody.copy(final = candidate.body)
            val adoptedRun: GenerationRun
            try {
                if (DerivedChapterMutation.effectiveTextChanged(
                        oldDraft = liveBody.draft,
                        oldFinal = liveBody.final,
                        newDraft = adoptedBody.draft,
                        newFinal = adoptedBody.final,
                    )
                ) {
                    invalidateChapterDerivedStateLocked(projectId, run.chapterId)
                }
                writeChapterBodyUncheckedLocked(run.chapterId, adoptedBody)
                check(updateChapterIfUnchanged(projectId, liveChapter) { adoptedChapter }) {
                    "Chapter source changed while the adoption lock was held"
                }
                adoptedRun = generationRunStore.update(projectId, runId) { current ->
                    current.copy(
                        status = GenerationRun.STATUS_ACCEPTED,
                        selectedCandidateId = candidateId,
                        updatedAt = timestamp,
                        completedAt = current.completedAt ?: timestamp,
                        error = null,
                    )
                } ?: error("Generation run disappeared during adoption")
                // Removing the durable marker is the commit point. Until this succeeds, startup
                // restores state/body/run to the exact pre-adoption snapshots above.
                BackupImportJournal.commit(journal, secureStore)
            } catch (error: Throwable) {
                val rollbackErrors = mutableListOf<Throwable>()
                fileSnapshots.asReversed().forEach { snapshot ->
                    runCatching { restoreImportFile(snapshot) }.onFailure(rollbackErrors::add)
                }
                runCatching { mutateState { previousState } }.onFailure(rollbackErrors::add)
                if (rollbackErrors.isEmpty()) {
                    runCatching {
                        BackupImportJournal.resolveAfterRollback(journal, secureStore)
                    }.onFailure(rollbackErrors::add)
                }
                if (rollbackErrors.isNotEmpty()) writesBlockedByBackupRecovery = true
                val message = if (rollbackErrors.isEmpty()) {
                    "候选采用失败，已恢复采用前状态：${error.message ?: error::class.simpleName}"
                } else {
                    "候选采用失败，且部分自动回滚失败；为保护数据已停止本进程写入，请立即重启应用：" +
                        (error.message ?: error::class.simpleName)
                }
                val wrapped = IllegalStateException(message, error)
                rollbackErrors.forEach(wrapped::addSuppressed)
                throw wrapped
            }
            _generationRunRevision.value += 1
            CandidateAdoptionResult.Adopted(adoptedRun, adoptedChapter)
        }
        }
        }
    }

    private fun finishRunIfCandidatesTerminal(
        run: GenerationRun,
        candidates: List<CandidateChapter>,
        timestamp: String,
        telemetry: GenerationTelemetry? = null,
    ): GenerationRun {
        if (!candidates.all { it.isTerminal() }) {
            return run.copy(candidates = candidates, updatedAt = timestamp)
        }
        val hasCompleted = candidates.any { it.status == CandidateChapter.STATUS_COMPLETED }
        return run.copy(
            status = if (hasCompleted) GenerationRun.STATUS_COMPLETED else GenerationRun.STATUS_FAILED,
            candidates = candidates,
            updatedAt = timestamp,
            completedAt = timestamp,
            error = if (hasCompleted) null else run.error ?: "No candidate completed successfully",
            telemetry = telemetry,
        )
    }

    internal data class ChapterDerivedInput(
        val source: ChapterSourceVersion,
        val effectiveText: String,
    )

    internal data class FactExtractionTicket(
        val input: ChapterDerivedInput,
        val characterSignature: String,
        val knownNames: List<String>,
        val evidenceEpoch: Long,
    )

    internal data class ProjectDerivedInput(
        val source: ProjectSourceVersion,
        val chapters: List<ChapterDerivedInput>,
    )

    internal data class ChapterMediaInput(
        val chapter: ChapterDerivedInput,
        val promo: ChapterPromo?,
    )

    /** Capture title/order/effective prose together, under the same lock used by editor writes. */
    internal fun captureChapterSource(projectId: String, chapterId: String): ChapterDerivedInput? =
        synchronized(chapterMutationLock) { captureChapterSourceLocked(projectId, chapterId) }

    /** Capture every fact-extractor dependency and its forget epoch at one linearization point. */
    internal fun captureFactExtractionTicket(
        projectId: String,
        chapterId: String,
    ): FactExtractionTicket? = synchronized(chapterMutationLock) {
        val input = captureChapterSourceLocked(projectId, chapterId) ?: return@synchronized null
        val characters = characters(projectId)
        FactExtractionTicket(
            input = input,
            characterSignature = characterSourceSignature(characters),
            knownNames = characters.map { it.name },
            evidenceEpoch = factEvidenceEpochLocked(projectId, chapterId),
        )
    }

    /** A bulk rebuild captures all epochs up-front so a later explicit forget always wins. */
    internal fun captureProjectFactExtractionTickets(
        projectId: String,
    ): List<FactExtractionTicket>? = synchronized(chapterMutationLock) {
        val project = captureProjectSourceLocked(projectId) ?: return@synchronized null
        val characters = characters(projectId)
        val signature = characterSourceSignature(characters)
        val names = characters.map { it.name }
        project.chapters.map { input ->
            FactExtractionTicket(
                input = input,
                characterSignature = signature,
                knownNames = names,
                evidenceEpoch = factEvidenceEpochLocked(projectId, input.source.chapterId),
            )
        }
    }

    private fun factEvidenceEpochLocked(projectId: String, chapterId: String): Long =
        factEvidenceEpochs[projectId to chapterId] ?: 0L

    private fun advanceFactEvidenceEpochLocked(projectId: String, chapterId: String) {
        val key = projectId to chapterId
        factEvidenceEpochs[key] = factEvidenceEpochLocked(projectId, chapterId) + 1L
    }

    /** Capture every chapter so a full-book result cannot commit across add/delete/edit/reorder. */
    internal fun captureProjectSource(projectId: String): ProjectDerivedInput? =
        synchronized(chapterMutationLock) { captureProjectSourceLocked(projectId) }

    internal fun captureChapterMediaSource(projectId: String, chapterId: String): ChapterMediaInput? =
        synchronized(chapterMutationLock) {
            val chapter = captureChapterSourceLocked(projectId, chapterId) ?: return@synchronized null
            ChapterMediaInput(chapter = chapter, promo = getChapterPromo(chapterId))
        }

    /** Commit media only while the exact chapter title/order/body used to generate it is current. */
    internal fun upsertIllustrationIfChapterSourceCurrent(
        projectId: String,
        expected: ChapterSourceVersion,
        illustration: Illustration,
    ): Boolean = synchronized(chapterMutationLock) {
        if (!chapterSourceIsCurrentLocked(projectId, expected)) return@synchronized false
        upsertIllustration(expected.chapterId, illustration)
        true
    }

    internal fun setChapterPromoIfSourceCurrent(
        projectId: String,
        expected: ChapterSourceVersion,
        expectedPromo: ChapterPromo?,
        promo: ChapterPromo,
    ): Boolean = synchronized(chapterMutationLock) {
        if (!chapterSourceIsCurrentLocked(projectId, expected)) return@synchronized false
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val map = current["promoByChapter"] as? JsonObject ?: JsonObject(emptyMap())
            val currentPromo = (map[expected.chapterId] as? JsonObject)?.let { encoded ->
                runCatching {
                    JSON.decodeFromJsonElement(ChapterPromo.serializer(), encoded)
                }.getOrNull()
            }
            if (currentPromo != expectedPromo) return@mutateState current
            committed = true
            current.with(
                "promoByChapter",
                map.with(
                    expected.chapterId,
                    JSON.encodeToJsonElement(ChapterPromo.serializer(), promo) as JsonObject,
                ),
            )
        }
        committed
    }

    private fun captureChapterSourceLocked(projectId: String, chapterId: String): ChapterDerivedInput? {
        if (project(projectId) == null) return null
        val chapter = chapters(projectId).firstOrNull { it.id == chapterId } ?: return null
        val body = chapterBody(chapterId)
        val effectiveText = body.final.ifBlank { body.draft }
        return ChapterDerivedInput(
            source = DerivedSourceFingerprint.chapter(
                chapterId = chapter.id,
                title = chapter.title,
                orderIndex = chapter.order_index,
                effectiveText = effectiveText,
            ),
            effectiveText = effectiveText,
        )
    }

    private fun captureProjectSourceLocked(projectId: String): ProjectDerivedInput? {
        val project = project(projectId) ?: return null
        val chapterInputs = chapters(projectId)
            .sortedWith(compareBy<Chapter> { it.order_index }.thenBy { it.id })
            .map { chapter ->
                val body = chapterBody(chapter.id)
                val effectiveText = body.final.ifBlank { body.draft }
                ChapterDerivedInput(
                    source = DerivedSourceFingerprint.chapter(
                        chapterId = chapter.id,
                        title = chapter.title,
                        orderIndex = chapter.order_index,
                        effectiveText = effectiveText,
                    ),
                    effectiveText = effectiveText,
                )
            }
        return ProjectDerivedInput(
            source = DerivedSourceFingerprint.project(
                projectId = projectId,
                projectTitle = project.title,
                projectDescription = project.description.orEmpty(),
                chapters = chapterInputs.map { it.source },
            ),
            chapters = chapterInputs,
        )
    }

    private fun chapterSourceIsCurrentLocked(projectId: String, expected: ChapterSourceVersion): Boolean =
        captureChapterSourceLocked(projectId, expected.chapterId)?.source == expected

    private fun projectSourceIsCurrentLocked(projectId: String, expected: ProjectSourceVersion): Boolean =
        captureProjectSourceLocked(projectId)?.source == expected

    internal fun characterSourceSignature(characters: List<Character>): String =
        DerivedSourceFingerprint.characterIdentities(characters.map { it.id to it.name })

    internal fun embeddingSourceSignature(config: EmbeddingConfig): String {
        val encoded = JSON.encodeToJsonElement(
            EmbeddingConfig.serializer(),
            config.copy(apiKey = ""),
        ).jsonObject
        val vectorSpaceFields = listOf(
            "provider", "apiUrl", "api_url", "model",
            "dimensions", "dimension", "outputDimensions", "output_dimensions",
        )
        return DerivedSourceFingerprint.collection(
            vectorSpaceFields.flatMap { field -> listOf(field, encoded[field]?.toString().orEmpty()) },
        )
    }

    private fun embeddingSourceSignature(state: JsonObject): String {
        val config = (state["embeddingConfig"] as? JsonObject)?.let {
            runCatching { JSON.decodeFromJsonElement(EmbeddingConfig.serializer(), it) }.getOrNull()
        } ?: EmbeddingConfig()
        return embeddingSourceSignature(config)
    }

    private fun characterSourceSignature(state: JsonObject, projectId: String): String =
        characterSourceSignature(
            readListMap(state, "charactersByProject", projectId, Character.serializer()),
        )

    /** Atomically append chapters and update their target arc against the exact arc AI planned. */
    internal fun appendChaptersToArcIfUnchanged(
        projectId: String,
        expectedArc: PlotArc?,
        candidates: List<Chapter>,
        expectedChapters: List<Chapter>? = null,
    ): List<Chapter> = synchronized(chapterMutationLock) {
        if (candidates.isEmpty()) return@synchronized emptyList()
        var added = emptyList<Chapter>()
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val arcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            val latestArc = expectedArc?.let { expected -> arcs.firstOrNull { it.id == expected.id } }
            if (expectedArc != null && latestArc != expectedArc) return@mutateState current

            val chapterMap = current["chaptersByProject"] as? JsonObject ?: JsonObject(emptyMap())
            val existing = (chapterMap[projectId] as? JsonArray)?.let { array ->
                runCatching {
                    JSON.decodeFromJsonElement(ListSerializer(Chapter.serializer()), array)
                }.getOrDefault(emptyList())
            }.orEmpty()
            if (expectedChapters != null && existing != expectedChapters) return@mutateState current
            val nextOrder = (existing.maxOfOrNull { it.order_index } ?: 0) + 1
            added = candidates.mapIndexed { index, chapter ->
                chapter.copy(project_id = projectId, order_index = nextOrder + index)
            }
            val allChapters = (existing + added).sortedBy { it.order_index }

            var next = current.with(
                "chaptersByProject",
                chapterMap.with(
                    projectId,
                    JSON.encodeToJsonElement(
                        ListSerializer(Chapter.serializer()),
                        allChapters,
                    ) as JsonArray,
                ),
            )
            val projects = readProjects(next).map { project ->
                if (project.id == projectId) {
                    project.copy(
                        current_word_count = allChapters.sumOf { it.word_count },
                        updated_at = nowIso(),
                    )
                } else {
                    project
                }
            }
            next = next.with(
                "projects",
                JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projects) as JsonArray,
            )
            if (latestArc != null) {
                val newIds = (latestArc.builtChapterIds.orEmpty() + added.map { it.id }).distinct()
                val updatedArcs = arcs.map { arc ->
                    if (arc.id == latestArc.id) arc.copy(builtChapterIds = newIds) else arc
                }
                next = withListMap(next, "plotArcsByProject", projectId, updatedArcs, PlotArc.serializer())
            }
            next
        }
        added
    }

    // ── Chapter illustrations (PC parity — kept in their own file per chapter to avoid
    //    bloating app_state.json with megabytes of base64 image data) ─────────────────────────

    fun chapterIllustrations(chapterId: String): List<Illustration> = synchronized(illustrationMutationLock) {
        val file = File(appContext.filesDir, "illustrations/${chapterId}.json")
        if (!AtomicTextFile.exists(file)) return@synchronized emptyList()
        runCatching {
            JSON.decodeFromString(
                ListSerializer(Illustration.serializer()),
                AtomicTextFile.readText(file),
            )
        }.getOrDefault(emptyList())
    }

    fun setChapterIllustrations(chapterId: String, list: List<Illustration>) = synchronized(illustrationMutationLock) {
        if (!chapterExists(chapterId)) return@synchronized
        writeChapterIllustrationsUncheckedLocked(chapterId, list)
    }

    /** Snapshot restore writes these before atomically publishing its restored chapter list. */
    private fun writeChapterIllustrationsUncheckedLocked(chapterId: String, list: List<Illustration>) {
        ensureWritesAllowed()
        val dir = File(appContext.filesDir, "illustrations")
        if (!dir.exists()) dir.mkdirs()
        AtomicTextFile.writeText(File(dir, "${chapterId}.json"),
            JSON.encodeToString(ListSerializer(Illustration.serializer()), list)
        )
    }

    fun upsertIllustration(chapterId: String, illustration: Illustration) = synchronized(illustrationMutationLock) {
        val existing = chapterIllustrations(chapterId).toMutableList()
        val idx = existing.indexOfFirst { it.id == illustration.id }
        if (idx >= 0) existing[idx] = illustration else existing.add(illustration)
        setChapterIllustrations(chapterId, existing)
    }

    fun deleteIllustration(chapterId: String, illustrationId: String) = synchronized(illustrationMutationLock) {
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

    /** Atomically replace an outline only when the value used to start AI work is still current. */
    internal fun setOutlineIfUnchanged(projectId: String, expected: String, value: String): Boolean {
        var committed = false
        mutateState { current ->
            val map = (current["longNovelOutlineByProject"] as? JsonObject) ?: JsonObject(emptyMap())
            val currentValue = (map[projectId] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val projectStillExists = readProjects(current).any { it.id == projectId }
            if (!projectStillExists || currentValue != expected) {
                current
            } else {
                committed = true
                current.with("longNovelOutlineByProject", map.with(projectId, JsonPrimitive(value)))
            }
        }
        return committed
    }

    fun characters(projectId: String): List<Character> =
        readListMap("charactersByProject", projectId, Character.serializer())

    fun setCharacters(projectId: String, characters: List<Character>) =
        writeListMap("charactersByProject", projectId, characters, Character.serializer())

    /** Commit an AI character update only if the project and exact source character still exist. */
    internal fun updateCharacterIfUnchanged(
        projectId: String,
        expected: Character,
        update: (Character) -> Character,
    ): Boolean {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val characters = readListMap(
                current,
                "charactersByProject",
                projectId,
                Character.serializer(),
            ).toMutableList()
            val index = characters.indexOfFirst { it.id == expected.id }
            if (index < 0 || characters[index] != expected) return@mutateState current

            characters[index] = update(characters[index])
            committed = true
            withListMap(
                current,
                "charactersByProject",
                projectId,
                characters,
                Character.serializer(),
            )
        }
        return committed
    }

    internal fun deleteCharacterIfUnchanged(projectId: String, expected: Character): Boolean {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val characters = readListMap(
                current,
                "charactersByProject",
                projectId,
                Character.serializer(),
            )
            val index = characters.indexOfFirst { it.id == expected.id }
            if (index < 0 || characters[index] != expected) return@mutateState current

            committed = true
            withListMap(
                current,
                "charactersByProject",
                projectId,
                characters.filterIndexed { itemIndex, _ -> itemIndex != index },
                Character.serializer(),
            )
        }
        return committed
    }

    /** Merge AI-discovered characters into the latest list without losing concurrent user edits. */
    internal fun mergeCharactersIfProjectExists(
        projectId: String,
        candidates: List<Character>,
    ): List<Character> = mergeCharacters(projectId, candidates, expectedOutline = null)

    internal fun mergeCharactersIfOutlineUnchanged(
        projectId: String,
        expectedOutline: String,
        candidates: List<Character>,
    ): List<Character> = mergeCharacters(projectId, candidates, expectedOutline)

    private fun mergeCharacters(
        projectId: String,
        candidates: List<Character>,
        expectedOutline: String?,
    ): List<Character> {
        var added = emptyList<Character>()
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            if (expectedOutline != null) {
                val outlines = current["longNovelOutlineByProject"] as? JsonObject
                val currentOutline = (outlines?.get(projectId) as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (currentOutline != expectedOutline) return@mutateState current
            }
            val existing = readListMap(current, "charactersByProject", projectId, Character.serializer())
            val names = existing.mapTo(mutableSetOf()) { it.name }
            added = candidates.filter { candidate ->
                candidate.name.isNotBlank() && names.add(candidate.name)
            }
            if (added.isEmpty()) current else withListMap(
                current,
                "charactersByProject",
                projectId,
                existing + added,
                Character.serializer(),
            )
        }
        return added
    }

    // ── Character growth route (角色成长) — per-character, per-chapter dev chain ──

    fun characterGrowth(projectId: String, characterId: String): List<CharacterGrowthEntry> {
        val inner = (_state.value["characterGrowthByProject"] as? JsonObject)?.get(projectId) as? JsonObject
            ?: return emptyList()
        val arr = inner[characterId] as? JsonArray ?: return emptyList()
        return runCatching { JSON.decodeFromJsonElement(ListSerializer(CharacterGrowthEntry.serializer()), arr) }
            .getOrDefault(emptyList())
            .filterNot { it.isStale }
    }

    fun setCharacterGrowth(projectId: String, characterId: String, entries: List<CharacterGrowthEntry>) {
        mutateState { current ->
            val outer = (current["characterGrowthByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val inner = (outer[projectId] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            inner[characterId] = JSON.encodeToJsonElement(ListSerializer(CharacterGrowthEntry.serializer()), entries) as JsonArray
            outer[projectId] = JsonObject(inner)
            current.with("characterGrowthByProject", JsonObject(outer))
        }
    }

    fun appendCharacterGrowth(projectId: String, characterId: String, entry: CharacterGrowthEntry) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val outer = (current["characterGrowthByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val inner = (outer[projectId] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val existing = runCatching {
                JSON.decodeFromJsonElement(
                    ListSerializer(CharacterGrowthEntry.serializer()),
                    inner[characterId] ?: JsonArray(emptyList()),
                )
            }.getOrDefault(emptyList())
            inner[characterId] = JSON.encodeToJsonElement(
                ListSerializer(CharacterGrowthEntry.serializer()),
                existing + entry.copy(isStale = false),
            ) as JsonArray
            outer[projectId] = JsonObject(inner)
            current.with("characterGrowthByProject", JsonObject(outer))
        }
    }

    fun updateLatestCharacterGrowth(projectId: String, characterId: String, value: String) {
        mutateState { current ->
            val outer = (current["characterGrowthByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val inner = (outer[projectId] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val entries = runCatching {
                JSON.decodeFromJsonElement(
                    ListSerializer(CharacterGrowthEntry.serializer()),
                    inner[characterId] ?: JsonArray(emptyList()),
                )
            }.getOrDefault(emptyList())
            val updated = DerivedStateInvalidation.updateLatestActiveGrowth(entries, value)
            if (updated == entries) return@mutateState current
            inner[characterId] = JSON.encodeToJsonElement(
                ListSerializer(CharacterGrowthEntry.serializer()),
                updated,
            ) as JsonArray
            outer[projectId] = JsonObject(inner)
            current.with("characterGrowthByProject", JsonObject(outer))
        }
    }

    fun deleteCharacterGrowthEntry(projectId: String, characterId: String, entryId: String) {
        mutateState { current ->
            val outer = (current["characterGrowthByProject"] as? JsonObject ?: return@mutateState current).toMutableMap()
            val inner = (outer[projectId] as? JsonObject ?: return@mutateState current).toMutableMap()
            val entries = runCatching {
                JSON.decodeFromJsonElement(
                    ListSerializer(CharacterGrowthEntry.serializer()),
                    inner[characterId] ?: JsonArray(emptyList()),
                )
            }.getOrDefault(emptyList())
            val updated = DerivedStateInvalidation.deleteGrowthEntry(entries, entryId)
            if (updated == entries) return@mutateState current
            inner[characterId] = JSON.encodeToJsonElement(
                ListSerializer(CharacterGrowthEntry.serializer()),
                updated,
            ) as JsonArray
            outer[projectId] = JsonObject(inner)
            current.with("characterGrowthByProject", JsonObject(outer))
        }
    }

    fun plotArcs(projectId: String): List<PlotArc> =
        readListMap("plotArcsByProject", projectId, PlotArc.serializer())

    fun setPlotArcs(projectId: String, arcs: List<PlotArc>) =
        writeListMap("plotArcsByProject", projectId, arcs.sortedBy { it.order }, PlotArc.serializer())

    internal fun updatePlotArcIfUnchanged(
        projectId: String,
        expected: PlotArc,
        transform: (PlotArc) -> PlotArc,
    ): Boolean {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val arcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            val index = arcs.indexOfFirst { it.id == expected.id }
            if (index < 0 || arcs[index] != expected) return@mutateState current
            val updated = arcs.toMutableList()
            updated[index] = transform(arcs[index])
            committed = true
            withListMap(
                current,
                "plotArcsByProject",
                projectId,
                updated.sortedBy { it.order },
                PlotArc.serializer(),
            )
        }
        return committed
    }

    /** Reorder one arc only while that arc and its volume-local sibling list are unchanged. */
    internal fun reorderPlotArcIfUnchanged(
        projectId: String,
        expected: PlotArc,
        expectedSiblings: List<PlotArc>,
        newPosition1Based: Int,
    ): Boolean {
        var accepted = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val allArcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            if (allArcs.firstOrNull { it.id == expected.id } != expected) return@mutateState current
            val siblings = allArcs.filter { it.volumeId == expected.volumeId }.sortedBy { it.order }
            if (siblings != expectedSiblings) return@mutateState current

            val currentIndex = siblings.indexOfFirst { it.id == expected.id }
            if (currentIndex < 0) return@mutateState current
            val targetIndex = newPosition1Based.coerceIn(1, siblings.size) - 1
            accepted = true
            if (targetIndex == currentIndex) return@mutateState current

            val orderSlots = siblings.map { it.order }
            val reordered = siblings.toMutableList()
            val moved = reordered.removeAt(currentIndex)
            reordered.add(targetIndex, moved)
            val replacements = reordered.mapIndexed { index, arc ->
                arc.id to arc.copy(order = orderSlots[index])
            }.toMap()
            withListMap(
                current,
                "plotArcsByProject",
                projectId,
                allArcs.map { replacements[it.id] ?: it }.sortedBy { it.order },
                PlotArc.serializer(),
            )
        }
        return accepted
    }

    internal fun deletePlotArcIfUnchanged(projectId: String, expected: PlotArc): Boolean {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val arcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            val index = arcs.indexOfFirst { it.id == expected.id }
            if (index < 0 || arcs[index] != expected) return@mutateState current

            committed = true
            withListMap(
                current,
                "plotArcsByProject",
                projectId,
                arcs.filterIndexed { itemIndex, _ -> itemIndex != index },
                PlotArc.serializer(),
            )
        }
        return committed
    }

    /** Append arcs to the latest collection, optionally requiring an unchanged target volume. */
    internal fun appendPlotArcsIfTargetExists(
        projectId: String,
        candidates: List<PlotArc>,
        expectedVolume: Volume? = null,
        expectedArcs: List<PlotArc>? = null,
    ): List<PlotArc> {
        var added = emptyList<PlotArc>()
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            if (expectedVolume != null) {
                val volumes = readListMap(current, "volumesByProject", projectId, Volume.serializer())
                if (volumes.firstOrNull { it.id == expectedVolume.id } != expectedVolume) {
                    return@mutateState current
                }
            }
            val existing = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            if (expectedArcs != null && existing != expectedArcs) return@mutateState current
            val nextOrder = (existing.maxOfOrNull { it.order } ?: -1) + 1
            added = candidates.mapIndexed { index, arc -> arc.copy(order = nextOrder + index) }
            withListMap(
                current,
                "plotArcsByProject",
                projectId,
                (existing + added).sortedBy { it.order },
                PlotArc.serializer(),
            )
        }
        return added
    }

    // ── 副本 (Volumes) — long-novel containers for plot arcs ──────────────────

    fun volumes(projectId: String): List<Volume> =
        readListMap("volumesByProject", projectId, Volume.serializer())

    fun setVolumes(projectId: String, volumes: List<Volume>) =
        writeListMap("volumesByProject", projectId, volumes.sortedBy { it.order }, Volume.serializer())

    internal fun updateVolumeIfUnchanged(
        projectId: String,
        expected: Volume,
        transform: (Volume) -> Volume,
    ): Boolean {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val volumes = readListMap(current, "volumesByProject", projectId, Volume.serializer())
            val index = volumes.indexOfFirst { it.id == expected.id }
            if (index < 0 || volumes[index] != expected) return@mutateState current

            val updated = volumes.toMutableList()
            updated[index] = transform(volumes[index])
            committed = true
            withListMap(
                current,
                "volumesByProject",
                projectId,
                updated.sortedBy { it.order },
                Volume.serializer(),
            )
        }
        return committed
    }

    /** Reorder volumes only while the exact collection inspected by the caller is still current. */
    internal fun reorderVolumeIfUnchanged(
        projectId: String,
        expected: Volume,
        expectedVolumes: List<Volume>,
        newPosition1Based: Int,
    ): Boolean {
        var accepted = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val volumes = readListMap(current, "volumesByProject", projectId, Volume.serializer())
                .sortedBy { it.order }
            if (volumes != expectedVolumes || volumes.firstOrNull { it.id == expected.id } != expected) {
                return@mutateState current
            }

            val currentIndex = volumes.indexOfFirst { it.id == expected.id }
            if (currentIndex < 0) return@mutateState current
            val targetIndex = newPosition1Based.coerceIn(1, volumes.size) - 1
            accepted = true
            if (targetIndex == currentIndex) return@mutateState current

            val orderSlots = volumes.map { it.order }
            val reordered = volumes.toMutableList()
            val moved = reordered.removeAt(currentIndex)
            reordered.add(targetIndex, moved)
            withListMap(
                current,
                "volumesByProject",
                projectId,
                reordered.mapIndexed { index, volume -> volume.copy(order = orderSlots[index]) },
                Volume.serializer(),
            )
        }
        return accepted
    }

    /** Delete a volume only while both it and the complete set of owned arcs remain unchanged. */
    internal fun deleteVolumeIfUnchanged(
        projectId: String,
        expected: Volume,
        expectedOwnedArcs: List<PlotArc>,
    ): Boolean {
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val volumes = readListMap(current, "volumesByProject", projectId, Volume.serializer())
            val index = volumes.indexOfFirst { it.id == expected.id }
            if (index < 0 || volumes[index] != expected) return@mutateState current

            val arcs = readListMap(current, "plotArcsByProject", projectId, PlotArc.serializer())
            val ownedArcs = arcs.filter { it.volumeId == expected.id }.sortedBy { it.order }
            if (ownedArcs != expectedOwnedArcs) return@mutateState current
            var next = withListMap(
                current,
                "volumesByProject",
                projectId,
                volumes.filterIndexed { itemIndex, _ -> itemIndex != index },
                Volume.serializer(),
            )
            next = withListMap(
                next,
                "plotArcsByProject",
                projectId,
                arcs.filterNot { it.volumeId == expected.id },
                PlotArc.serializer(),
            )
            committed = true
            next
        }
        return committed
    }

    internal fun appendVolumesIfProjectExists(
        projectId: String,
        candidates: List<Volume>,
        expectedVolumes: List<Volume>? = null,
    ): List<Volume> {
        var added = emptyList<Volume>()
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "volumesByProject", projectId, Volume.serializer())
            if (expectedVolumes != null && existing != expectedVolumes) return@mutateState current
            val nextOrder = (existing.maxOfOrNull { it.order } ?: -1) + 1
            added = candidates.mapIndexed { index, volume -> volume.copy(order = nextOrder + index) }
            withListMap(
                current,
                "volumesByProject",
                projectId,
                (existing + added).sortedBy { it.order },
                Volume.serializer(),
            )
        }
        return added
    }

    /**
     * Backward-compat migration: a long-novel project with arcs but no volumes gets all its arcs
     * wrapped into a single volume "副本1". Also re-homes any orphan arcs (volumeId null/dangling)
     * into the earliest volume. Idempotent — safe to call on every project open.
     */
    fun ensureVolumes(projectId: String) {
        val arcs = plotArcs(projectId)
        val vols = volumes(projectId)
        if (vols.isNotEmpty()) {
            val ids = vols.map { it.id }.toSet()
            val firstId = vols.minByOrNull { it.order }?.id ?: return
            if (arcs.any { it.volumeId == null || it.volumeId !in ids }) {
                setPlotArcs(projectId, arcs.map {
                    if (it.volumeId == null || it.volumeId !in ids) it.copy(volumeId = firstId) else it
                })
            }
            return
        }
        if (arcs.isEmpty()) return  // no volumes & no arcs: nothing to migrate (volumes created on demand)
        val name = if (uiLanguage() == "en") "Volume 1" else "副本1"
        val vol = Volume(id = "vol-${System.currentTimeMillis()}", name = name, order = 0, createdAt = nowIso())
        setVolumes(projectId, listOf(vol))
        setPlotArcs(projectId, arcs.map { it.copy(volumeId = vol.id) })
    }

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
        mutateState { current ->
            if (!hasProject(current, projectId)) current
            else withListMap(current, "summariesByProject", projectId, list, SummaryPayload.serializer())
        }

    fun upsertSummary(projectId: String, payload: SummaryPayload) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "summariesByProject", projectId, SummaryPayload.serializer()).toMutableList()
            val idx = existing.indexOfFirst { it.scopeType == payload.scopeType && it.scopeId == payload.scopeId }
            if (idx >= 0) existing[idx] = payload else existing.add(payload)
            withListMap(current, "summariesByProject", projectId, existing, SummaryPayload.serializer())
        }
    }

    internal fun summarySourceSignature(projectId: String): String =
        summarySignature(summaries(projectId))

    internal fun summarySourceSignature(list: List<SummaryPayload>): String =
        summarySignature(list)

    /** Commit a chapter summary and stale its roll-ups only while its prompt source still matches. */
    internal fun commitChapterSummaryIfSourceCurrent(
        projectId: String,
        expected: ChapterSourceVersion,
        payload: SummaryPayload,
    ): Boolean = synchronized(chapterMutationLock) {
        if (!chapterSourceIsCurrentLocked(projectId, expected)) return@synchronized false
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "summariesByProject", projectId, SummaryPayload.serializer()).toMutableList()
            val idx = existing.indexOfFirst { it.scopeType == payload.scopeType && it.scopeId == payload.scopeId }
            if (idx >= 0) existing[idx] = payload else existing.add(payload)
            val next = existing.map { item ->
                if (item.scopeType != "chapter") item.copy(isStale = true) else item
            }
            committed = true
            withListMap(current, "summariesByProject", projectId, next, SummaryPayload.serializer())
        }
        committed
    }

    /** Whole-book summaries bind both live prose and the exact summary layers sent to the model. */
    internal fun commitBookSummaryIfSourceCurrent(
        projectId: String,
        expectedProject: ProjectSourceVersion,
        expectedSummarySignature: String,
        payload: SummaryPayload,
    ): Boolean = synchronized(chapterMutationLock) {
        if (!projectSourceIsCurrentLocked(projectId, expectedProject)) return@synchronized false
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "summariesByProject", projectId, SummaryPayload.serializer()).toMutableList()
            if (summarySignature(existing) != expectedSummarySignature) return@mutateState current
            val idx = existing.indexOfFirst { it.scopeType == payload.scopeType && it.scopeId == payload.scopeId }
            if (idx >= 0) existing[idx] = payload else existing.add(payload)
            committed = true
            withListMap(current, "summariesByProject", projectId, existing, SummaryPayload.serializer())
        }
        committed
    }

    private fun summarySignature(list: List<SummaryPayload>): String =
        DerivedSourceFingerprint.collection(buildList {
            list.forEach { item ->
                add(item.id)
                add(item.scopeType)
                add(item.scopeId)
                add(item.summaryText)
                add(item.isStale.toString())
                add(item.wordCount.toString())
            }
        })

    fun forgetSummary(projectId: String, scopeType: String, scopeId: String) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "summariesByProject", projectId, SummaryPayload.serializer())
            withListMap(
                current,
                "summariesByProject",
                projectId,
                existing.filterNot { it.scopeType == scopeType && it.scopeId == scopeId },
                SummaryPayload.serializer(),
            )
        }
    }

    // ── KB entities (characters / foreshadowing / locations / events / items) ─

    fun entities(projectId: String): List<EntityPayload> =
        readListMap("entitiesByProject", projectId, EntityPayload.serializer()).filterNot { it.isStale }

    fun factEvidence(projectId: String): List<ChapterFactEvidenceBatch> =
        readListMap("factEvidenceByProject", projectId, ChapterFactEvidenceBatch.serializer())
            .filterNot { it.isStale }

    fun setEntities(projectId: String, list: List<EntityPayload>) =
        mutateState { current ->
            if (!hasProject(current, projectId)) current
            else withListMap(current, "entitiesByProject", projectId, list, EntityPayload.serializer())
        }

    fun upsertEntity(projectId: String, entity: EntityPayload) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "entitiesByProject", projectId, EntityPayload.serializer()).toMutableList()
            val idx = existing.indexOfFirst { it.id == entity.id }
            if (idx >= 0) existing[idx] = entity else existing.add(entity)
            withListMap(current, "entitiesByProject", projectId, existing, EntityPayload.serializer())
        }
    }

    /** Merge against the latest entity bag; stale model output never enters the transform. */
    internal fun <T : Any> updateEntitiesIfSourceCurrent(
        projectId: String,
        expected: ChapterSourceVersion,
        expectedCharacterSignature: String,
        transform: (List<EntityPayload>) -> Pair<List<EntityPayload>, T>,
    ): T? = synchronized(chapterMutationLock) {
        if (!chapterSourceIsCurrentLocked(projectId, expected)) return@synchronized null
        var result: T? = null
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            if (characterSourceSignature(current, projectId) != expectedCharacterSignature) {
                return@mutateState current
            }
            val existing = readListMap(current, "entitiesByProject", projectId, EntityPayload.serializer())
            val (next, transformedResult) = transform(existing)
            result = transformedResult
            withListMap(current, "entitiesByProject", projectId, next, EntityPayload.serializer())
        }
        result
    }

    /** Atomically publish the aggregate entity bag and its chapter evidence observation batch. */
    internal fun <T : Any> updateEntitiesAndEvidenceIfSourceCurrent(
        projectId: String,
        expected: ChapterSourceVersion,
        expectedCharacterSignature: String,
        expectedFactEvidenceEpoch: Long,
        transform: (
            List<EntityPayload>,
            List<ChapterFactEvidenceBatch>,
            Map<String, Int>,
        ) -> Triple<List<EntityPayload>, List<ChapterFactEvidenceBatch>, T>,
    ): T? = synchronized(chapterMutationLock) {
        if (!chapterSourceIsCurrentLocked(projectId, expected)) return@synchronized null
        if (factEvidenceEpochLocked(projectId, expected.chapterId) != expectedFactEvidenceEpoch) {
            return@synchronized null
        }
        var result: T? = null
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            if (characterSourceSignature(current, projectId) != expectedCharacterSignature) {
                return@mutateState current
            }
            val entities = readListMap(
                current, "entitiesByProject", projectId, EntityPayload.serializer(),
            )
            val evidence = readListMap(
                current, "factEvidenceByProject", projectId, ChapterFactEvidenceBatch.serializer(),
            )
            val chapterOrders = readListMap(
                current, "chaptersByProject", projectId, Chapter.serializer(),
            ).associate { it.id to it.order_index }
            val (nextEntities, nextEvidence, transformedResult) =
                transform(entities, evidence, chapterOrders)
            result = transformedResult
            withListMap(
                withListMap(
                    current,
                    "entitiesByProject",
                    projectId,
                    nextEntities,
                    EntityPayload.serializer(),
                ),
                "factEvidenceByProject",
                projectId,
                nextEvidence,
                ChapterFactEvidenceBatch.serializer(),
            )
        }
        result
    }

    fun setEntityStatus(projectId: String, entityId: String, status: String) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val existing = readListMap(current, "entitiesByProject", projectId, EntityPayload.serializer())
            withListMap(
                current,
                "entitiesByProject",
                projectId,
                existing.map { if (it.id == entityId) it.copy(status = status) else it },
                EntityPayload.serializer(),
            )
        }
    }

    /** Drop every entity whose first/last seen chapter is the deleted one (best-effort cleanup). */
    fun forgetEntitiesForChapter(projectId: String, chapterId: String) =
        synchronized(chapterMutationLock) {
        if (project(projectId) == null) return@synchronized
        // Advance even when there is no stored batch: a first-ever extraction may still be in flight.
        advanceFactEvidenceEpochLocked(projectId, chapterId)
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val evidence = readListMap(
                current, "factEvidenceByProject", projectId, ChapterFactEvidenceBatch.serializer(),
            )
            val remainingEvidence = FactEvidenceLedger.removeChapter(evidence, chapterId)
            val chapterOrders = readListMap(
                current, "chaptersByProject", projectId, Chapter.serializer(),
            ).associate { it.id to it.order_index }
            val timedEvidenceByEntity = remainingEvidence.asSequence()
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
            val existing = readListMap(current, "entitiesByProject", projectId, EntityPayload.serializer())
            val withEntities = withListMap(
                current,
                "entitiesByProject",
                projectId,
                existing.mapNotNull { entity ->
                    EntityReconciliation.afterChapterRemoval(
                        current = entity,
                        removedChapterId = chapterId,
                        remainingEvidence = timedEvidenceByEntity[entity.id].orEmpty(),
                        chapterOrdersById = chapterOrders,
                    )
                },
                EntityPayload.serializer(),
            )
            withListMap(
                withEntities,
                "factEvidenceByProject",
                projectId,
                remainingEvidence,
                ChapterFactEvidenceBatch.serializer(),
            )
        }
    }

    // ── KB vector chunks (per-project, stored in separate file to keep state JSON small) ─

    private fun chunksFile(projectId: String): File = File(appContext.filesDir, "kb/${projectId}.json")

    private fun readChunksLocked(projectId: String): List<KbChunk> {
        val file = chunksFile(projectId)
        if (!AtomicTextFile.exists(file)) return emptyList()
        return runCatching {
            JSON.decodeFromString(ListSerializer(KbChunk.serializer()), AtomicTextFile.readText(file))
        }.getOrDefault(emptyList())
    }

    private fun writeChunksLocked(projectId: String, chunks: List<KbChunk>) {
        ensureWritesAllowed()
        val dir = File(appContext.filesDir, "kb")
        if (!dir.exists()) dir.mkdirs()
        AtomicTextFile.writeText(
            chunksFile(projectId),
            JSON.encodeToString(ListSerializer(KbChunk.serializer()), chunks),
        )
    }

    fun chunks(projectId: String): List<KbChunk> = synchronized(kbMutationLock) {
        readChunksLocked(projectId)
    }

    fun setChunks(projectId: String, chunks: List<KbChunk>) = synchronized(kbMutationLock) {
        if (project(projectId) == null) return@synchronized
        writeChunksLocked(projectId, chunks)
        _kbRevision.value += 1  // notify observers (Settings KB stats) that chunks changed
    }

    fun appendChunks(projectId: String, newChunks: List<KbChunk>) = synchronized(kbMutationLock) {
        if (newChunks.isEmpty() || project(projectId) == null) return@synchronized
        val existing = readChunksLocked(projectId).toMutableList()
        // Drop any old chunks for the same (sourceType, sourceId) so re-indexing replaces cleanly.
        val sources = newChunks.map { it.sourceType to it.sourceId }.toSet()
        existing.removeAll { (it.sourceType to it.sourceId) in sources }
        existing.addAll(newChunks)
        writeChunksLocked(projectId, existing)
        _kbRevision.value += 1
    }

    fun forgetKbSource(projectId: String, sourceType: String, sourceId: String) = synchronized(kbMutationLock) {
        if (project(projectId) == null) return@synchronized
        val current = readChunksLocked(projectId)
        val next = current.filterNot { it.sourceType == sourceType && it.sourceId == sourceId }
        if (next == current) return@synchronized
        writeChunksLocked(projectId, next)
        _kbRevision.value += 1
    }

    /** Replace one chapter's vectors/hash/stale flag only for the exact title + prose version. */
    internal fun commitChapterKbIfSourceCurrent(
        projectId: String,
        expected: ChapterSourceVersion,
        expectedEmbeddingSignature: String,
        expectedEmbeddingModel: String,
        newChunks: List<KbChunk>,
        indexHash: String,
    ): Boolean = synchronized(chapterMutationLock) chapterLock@{
        if (!chapterSourceIsCurrentLocked(projectId, expected)) return@chapterLock false
        synchronized(kbMutationLock) kbLock@{
            if (project(projectId) == null) return@kbLock false
            if (newChunks.any {
                    it.sourceType != "chapter" ||
                        it.sourceId != expected.chapterId ||
                        it.embeddingModel != expectedEmbeddingModel
                }
            ) {
                return@kbLock false
            }
            val before = readChunksLocked(projectId)
            val next = before.filterNot {
                it.sourceType == "chapter" && it.sourceId == expected.chapterId
            } + newChunks
            var stateCommitted = false
            try {
                writeChunksLocked(projectId, next)
                mutateState { current ->
                    if (!hasProject(current, projectId)) return@mutateState current
                    if (embeddingSourceSignature(current) != expectedEmbeddingSignature) {
                        return@mutateState current
                    }
                    val hashOuter = (current["kbIndexHashByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                    val hashInner = (hashOuter[projectId] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                    hashInner[expected.chapterId] = JsonPrimitive(indexHash)
                    hashOuter[projectId] = JsonObject(hashInner)

                    val staleOuter = (current["kbStaleByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                    val stale = (staleOuter[projectId] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .filterNot { it == expected.chapterId }
                    staleOuter[projectId] = JsonArray(stale.distinct().map { JsonPrimitive(it) })
                    stateCommitted = true
                    current
                        .with("kbIndexHashByProject", JsonObject(hashOuter))
                        .with("kbStaleByProject", JsonObject(staleOuter))
                }
            } catch (error: Throwable) {
                runCatching { writeChunksLocked(projectId, before) }
                throw error
            }
            if (!stateCommitted) {
                runCatching { writeChunksLocked(projectId, before) }
                return@kbLock false
            }
            _kbRevision.value += 1
            true
        }
    }

    /** All-or-nothing full rebuild commit, guarded by the complete live project source signature. */
    internal fun commitRebuiltKbIfSourcesCurrent(
        projectId: String,
        expected: ProjectSourceVersion,
        expectedEmbeddingSignature: String,
        expectedEmbeddingModel: String,
        rebuiltChunks: List<KbChunk>,
        rebuiltHashes: Map<String, String>,
    ): Boolean = synchronized(chapterMutationLock) chapterLock@{
        if (!projectSourceIsCurrentLocked(projectId, expected)) return@chapterLock false
        synchronized(kbMutationLock) kbLock@{
            if (project(projectId) == null) return@kbLock false
            if (rebuiltChunks.any {
                    it.sourceType != "chapter" ||
                        it.sourceId !in rebuiltHashes ||
                        it.embeddingModel != expectedEmbeddingModel
                }
            ) {
                return@kbLock false
            }
            val before = readChunksLocked(projectId)
            var stateCommitted = false
            try {
                writeChunksLocked(projectId, rebuiltChunks)
                mutateState { current ->
                    if (!hasProject(current, projectId)) return@mutateState current
                    if (embeddingSourceSignature(current) != expectedEmbeddingSignature) {
                        return@mutateState current
                    }
                    val hashOuter = (current["kbIndexHashByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                    hashOuter[projectId] = JsonObject(rebuiltHashes.mapValues { JsonPrimitive(it.value) })

                    val rebuiltIds = rebuiltHashes.keys
                    val staleOuter = (current["kbStaleByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                    val stale = (staleOuter[projectId] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .filterNot { it in rebuiltIds }
                    staleOuter[projectId] = JsonArray(stale.distinct().map { JsonPrimitive(it) })
                    stateCommitted = true
                    current
                        .with("kbIndexHashByProject", JsonObject(hashOuter))
                        .with("kbStaleByProject", JsonObject(staleOuter))
                }
            } catch (error: Throwable) {
                runCatching { writeChunksLocked(projectId, before) }
                throw error
            }
            if (!stateCommitted) {
                runCatching { writeChunksLocked(projectId, before) }
                return@kbLock false
            }
            _kbRevision.value += 1
            true
        }
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

    // ── Chapter generation toggles ──────────────────────────────────────────
    // Stepwise (logic-chain) chapter generation: instead of one giant 3000+ word call, the AI first
    // drafts a beat-by-beat blueprint (bound to this chapter's plan / realm system / containers /
    // prior context), then writes each beat in sequence. Reduces long-form logic errors. Off by
    // default — the legacy one-shot path is preserved and the user can switch back any time.
    fun stepwiseChapterGen(): Boolean =
        (_state.value["stepwiseChapterGen"] as? JsonPrimitive)?.booleanOrNull ?: false

    fun setStepwiseChapterGen(enabled: Boolean) {
        mutateState { it.with("stepwiseChapterGen", JsonPrimitive(enabled)) }
    }

    // When on AND the chapter draft is non-empty, the user's draft is injected into generation as a
    // strong reference. Off by default.
    fun useDraftReference(): Boolean =
        (_state.value["useDraftReference"] as? JsonPrimitive)?.booleanOrNull ?: false

    fun setUseDraftReference(enabled: Boolean) {
        mutateState { it.with("useDraftReference", JsonPrimitive(enabled)) }
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

    /** Atomically merges one generated cover into the latest persisted project state. */
    fun upsertCoverImage(projectId: String, image: CoverImageItem, makeDefault: Boolean): Boolean {
        var committed = false
        mutateState { current ->
            val projects = readProjects(current).toMutableList()
            val projectIndex = projects.indexOfFirst { it.id == projectId }
            if (projectIndex < 0) return@mutateState current

            val project = projects[projectIndex]
            val images = runCatching {
                JSON.decodeFromString(
                    ListSerializer(CoverImageItem.serializer()),
                    project.cover_images.orEmpty(),
                )
            }.getOrDefault(emptyList()).toMutableList()
            val imageIndex = images.indexOfFirst { it.id == image.id }
            if (imageIndex >= 0) images[imageIndex] = image else images.add(image)

            projects[projectIndex] = project.copy(
                cover_images = JSON.encodeToString(ListSerializer(CoverImageItem.serializer()), images),
                default_cover_id = if (makeDefault) image.id else project.default_cover_id ?: image.id,
            )
            committed = true
            current.with(
                "projects",
                JSON.encodeToJsonElement(ListSerializer(Project.serializer()), projects) as JsonArray,
            )
        }
        return committed
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

    private fun hasProject(state: JsonObject, projectId: String): Boolean =
        readProjects(state).any { it.id == projectId }

    private fun <T> readListMap(field: String, projectId: String, ser: KSerializer<T>): List<T> =
        readListMap(_state.value, field, projectId, ser)

    private fun <T> readListMap(
        state: JsonObject,
        field: String,
        projectId: String,
        ser: KSerializer<T>,
    ): List<T> {
        val arr = (state[field] as? JsonObject)?.get(projectId) as? JsonArray ?: return emptyList()
        return runCatching { JSON.decodeFromJsonElement(ListSerializer(ser), arr) }
            .getOrDefault(emptyList())
    }

    private fun <T> withListMap(
        state: JsonObject,
        field: String,
        projectId: String,
        value: List<T>,
        ser: KSerializer<T>,
    ): JsonObject {
        val map = state[field] as? JsonObject ?: JsonObject(emptyMap())
        val encoded = JSON.encodeToJsonElement(ListSerializer(ser), value) as JsonArray
        return state.with(field, map.with(projectId, encoded))
    }

    private fun <T> writeListMap(field: String, projectId: String, value: List<T>, ser: KSerializer<T>) {
        mutateState { current -> withListMap(current, field, projectId, value, ser) }
    }

    // ── settings convenience accessors ────────────────────────────────────

    fun uiLanguage(): String = _state.value["uiLanguage"]?.jsonPrimitive?.contentOrNull ?: "zh"
    fun themePref(): String = _state.value["theme"]?.jsonPrimitive?.contentOrNull ?: "light"

    /** Embedded-agent runtime: the original single-agent ReAct loop or planner + executor. */
    fun agentEngine(): String = when (
        _state.value["agentEngine"]?.jsonPrimitive?.contentOrNull
    ) {
        "dual" -> "dual"
        else -> "classic"
    }

    fun setUiLanguage(lang: String) = mutateState { it.with("uiLanguage", JsonPrimitive(lang)) }
    fun setTheme(theme: String) = mutateState { it.with("theme", JsonPrimitive(theme)) }
    fun setAgentEngine(engine: String) {
        val sanitized = if (engine == "dual") "dual" else "classic"
        mutateState { it.with("agentEngine", JsonPrimitive(sanitized)) }
    }

    fun dualAgentReasoningLevel(): String = AgentReasoningLevels.normalize(
        _state.value["dualAgentReasoningLevel"]?.jsonPrimitive?.contentOrNull,
    )

    fun setDualAgentReasoningLevel(level: String) {
        val sanitized = AgentReasoningLevels.normalize(level)
        mutateState { it.with("dualAgentReasoningLevel", JsonPrimitive(sanitized)) }
    }

    /** Active text-model config — apiKey is pulled from SecureStore. */
    fun activeTextModelConfig(): TextModelConfig = synchronized(secureMutationLock) {
        val state = _state.value
        val cfg = (state["textModelConfig"] as? JsonObject)?.let {
            runCatching { JSON.decodeFromJsonElement(TextModelConfig.serializer(), it) }.getOrNull()
        } ?: TextModelConfig()
        val apiKey = secureStore.get(SecureStore.TEXT_MODEL_CONFIG_KEY).ifEmpty {
            // Fallback: look up via active profile id.
            val activeId = state["activeTextModelProfileId"]?.jsonPrimitive?.contentOrNull
            if (activeId != null) secureStore.get(SecureStore.profileKey(activeId)) else ""
        }
        cfg.copy(apiKey = apiKey)
    }

    fun textModelProfiles(): List<TextModelProfile> = synchronized(secureMutationLock) {
        val arr = _state.value["textModelProfiles"] as? JsonArray ?: return@synchronized emptyList()
        runCatching { JSON.decodeFromJsonElement(ListSerializer(TextModelProfile.serializer()), arr) }
            .getOrDefault(emptyList())
            .map { it.copy(apiKey = secureStore.get(SecureStore.profileKey(it.id))) }
    }

    fun setActiveProfile(profileId: String) = synchronized(secureMutationLock) {
        val profile = textModelProfiles().firstOrNull { it.id == profileId }
            ?: return@synchronized
        // Sync `textModelConfig` (minus key) into state for UI bindings.
        val cfgWithoutKey = TextModelConfig(
            provider = profile.provider,
            apiKey = "",
            apiUrl = profile.apiUrl,
            model = profile.model,
            temperature = profile.temperature,
            thinkingMode = profile.thinkingMode,
            contextWindowTokens = profile.contextWindowTokens,
            maxOutputTokens = profile.maxOutputTokens,
        )
        mutateState { current ->
            current
                .with("activeTextModelProfileId", JsonPrimitive(profileId))
                .with(
                    "textModelConfig",
                    JSON.encodeToJsonElement(TextModelConfig.serializer(), cfgWithoutKey) as JsonObject,
                )
        }
        secureStore.put(SecureStore.TEXT_MODEL_CONFIG_KEY, profile.apiKey)
    }

    fun saveTextModelProfile(profile: TextModelProfile) = synchronized(secureMutationLock) {
        ensureWritesAllowed()
        val isActive = _state.value["activeTextModelProfileId"]
            ?.jsonPrimitive?.contentOrNull == profile.id
        secureStore.put(SecureStore.profileKey(profile.id), profile.apiKey)
        if (isActive) secureStore.put(SecureStore.TEXT_MODEL_CONFIG_KEY, profile.apiKey)
        val sanitized = profile.copy(apiKey = "")
        val current = textModelProfiles().map { it.copy(apiKey = "") }
        val updated = if (current.any { it.id == profile.id })
            current.map { if (it.id == profile.id) sanitized else it }
        else current + sanitized
        mutateState { state ->
            val withProfiles = state.with(
                "textModelProfiles",
                JSON.encodeToJsonElement(ListSerializer(TextModelProfile.serializer()), updated) as JsonArray,
            )
            if (!isActive) {
                withProfiles
            } else {
                withProfiles.with(
                    "textModelConfig",
                    JSON.encodeToJsonElement(
                        TextModelConfig.serializer(),
                        TextModelConfig(
                            provider = profile.provider,
                            apiKey = "",
                            apiUrl = profile.apiUrl,
                            model = profile.model,
                            temperature = profile.temperature,
                            thinkingMode = profile.thinkingMode,
                            contextWindowTokens = profile.contextWindowTokens,
                            maxOutputTokens = profile.maxOutputTokens,
                        ),
                    ) as JsonObject,
                )
            }
        }
    }

    fun deleteTextModelProfile(profileId: String) = synchronized(secureMutationLock) {
        ensureWritesAllowed()
        secureStore.remove(SecureStore.profileKey(profileId))
        val updated = textModelProfiles().map { it.copy(apiKey = "") }.filterNot { it.id == profileId }
        mutateState {
            it.with(
                "textModelProfiles",
                JSON.encodeToJsonElement(ListSerializer(TextModelProfile.serializer()), updated) as JsonArray,
            )
        }
    }

    fun pollinationsKey(): String = synchronized(secureMutationLock) {
        secureStore.get(SecureStore.POLLINATIONS_KEY)
    }
    fun setPollinationsKey(key: String) = synchronized(secureMutationLock) {
        ensureWritesAllowed()
        secureStore.put(SecureStore.POLLINATIONS_KEY, key)
    }

    // Image generation engine: "pollinations" (default) or "comfyui". Stored in plain state (no
    // secret) so it round-trips with PC backups — mirrors PC store `imageEngine` / `comfyUIUrl`.
    fun imageEngine(): String =
        _state.value["imageEngine"]?.jsonPrimitive?.contentOrNull ?: "pollinations"
    fun setImageEngine(engine: String) = mutateState { it.with("imageEngine", JsonPrimitive(engine)) }

    fun comfyUIUrl(): String =
        _state.value["comfyUIUrl"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            ?: "http://localhost:8188"
    fun setComfyUIUrl(url: String) = mutateState { it.with("comfyUIUrl", JsonPrimitive(url)) }

    fun embeddingConfig(): EmbeddingConfig = synchronized(secureMutationLock) {
        val state = _state.value
        val cfg = (state["embeddingConfig"] as? JsonObject)?.let {
            runCatching { JSON.decodeFromJsonElement(EmbeddingConfig.serializer(), it) }.getOrNull()
        } ?: EmbeddingConfig()
        cfg.copy(apiKey = secureStore.get(SecureStore.EMBEDDING_CONFIG_KEY))
    }

    fun saveEmbeddingConfig(cfg: EmbeddingConfig) = synchronized(kbMutationLock) {
        synchronized(secureMutationLock) {
            val previousKey = secureStore.get(SecureStore.EMBEDDING_CONFIG_KEY)
            val sanitized = cfg.copy(apiKey = "")
            val vectorSpaceChanged =
                embeddingSourceSignature(_state.value) != embeddingSourceSignature(sanitized)
            val previousChunks = if (vectorSpaceChanged) {
                projects.value.associate { project ->
                    val file = chunksFile(project.id)
                    project.id to (AtomicTextFile.exists(file) to readChunksLocked(project.id))
                }
            } else {
                emptyMap()
            }
            fun restorePreviousChunks() {
                previousChunks.forEach { (projectId, previous) ->
                    runCatching {
                        if (previous.first) writeChunksLocked(projectId, previous.second)
                        else AtomicTextFile.delete(chunksFile(projectId))
                    }
                }
            }

            try {
                if (vectorSpaceChanged) {
                    // Clear every live project's vectors before publishing the new vector space.
                    // Each replacement is atomic; the in-memory copies provide rollback on errors.
                    previousChunks.keys.forEach { projectId -> writeChunksLocked(projectId, emptyList()) }
                }
                mutateState { current ->
                    var next = current.with(
                        "embeddingConfig",
                        JSON.encodeToJsonElement(EmbeddingConfig.serializer(), sanitized) as JsonObject,
                    )
                    if (vectorSpaceChanged) {
                        val chaptersByProject =
                            current["chaptersByProject"] as? JsonObject ?: JsonObject(emptyMap())
                        val staleByProject = chaptersByProject.mapValues { (_, value) ->
                            val ids = (value as? JsonArray).orEmpty().mapNotNull { chapter ->
                                (chapter as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                            }
                            JsonArray(ids.distinct().map { JsonPrimitive(it) })
                        }
                        next = next.with("kbStaleByProject", JsonObject(staleByProject))
                    }
                    next
                }
                // Publish the credential only after vector files and non-secret configuration
                // have committed. This avoids pairing a new key with a rolled-back endpoint.
                secureStore.put(SecureStore.EMBEDDING_CONFIG_KEY, cfg.apiKey)
            } catch (error: Throwable) {
                restorePreviousChunks()
                secureStore.put(SecureStore.EMBEDDING_CONFIG_KEY, previousKey)
                throw error
            }
            if (vectorSpaceChanged) _kbRevision.value += 1
        }
    }

    // ── export / import (PC-compatible) ────────────────────────────────────

    fun buildBackupBundle(includeSecrets: Boolean = false): BackupBundle =
        synchronized(chapterMutationLock) {
            synchronized(illustrationMutationLock) {
                synchronized(novelChatMutationLock) {
                    synchronized(agentMutationLock) {
                        synchronized(secureMutationLock) {
                            synchronized(stateMutationLock) {
                                buildBackupBundleLocked(includeSecrets)
                            }
                        }
                    }
                }
            }
        }

    private fun buildBackupBundleLocked(includeSecrets: Boolean): BackupBundle {
        val stateSnapshot = _state.value
        val base = if (includeSecrets) {
            mergeSensitivesIntoState(stateSnapshot)
        } else {
            // State is already designed to be secret-free, but strip the fields explicitly so an
            // old/migrated state file can never accidentally leak credentials into the default
            // export path.
            BackupSecretSanitizer.redact(stateSnapshot)
        }
        // app_state holds metadata only — chapter bodies / illustrations / novel-chat live in their
        // own files. Embed them under Android-specific keys so a backup fully restores on a fresh
        // device (PC import ignores unknown keys).
        val merged = buildJsonObject {
            for ((k, v) in base) put(k, v)
            val chapterIds = allChapterIds(stateSnapshot)
            buildJsonObject {
                chapterIds.forEach { cid ->
                    val f = File(appContext.filesDir, "chapters/$cid.json")
                    if (AtomicTextFile.exists(f)) {
                        put(cid, readBackupJson(f, "章节正文 $cid"))
                    }
                }
            }.takeIf { it.isNotEmpty() }?.let { put("chapterBodies", it) }
            buildJsonObject {
                chapterIds.forEach { cid ->
                    val f = File(appContext.filesDir, "illustrations/$cid.json")
                    if (AtomicTextFile.exists(f)) {
                        put(cid, readBackupJson(f, "章节配图 $cid"))
                    }
                }
            }.takeIf { it.isNotEmpty() }?.let { put("chapterIllustrations", it) }
            buildJsonObject {
                readProjects(stateSnapshot).forEach { p ->
                    val f = File(appContext.filesDir, "novel_chat/${p.id}.json")
                    if (AtomicTextFile.exists(f)) {
                        put(p.id, readBackupJson(f, "小说问答 ${p.id}"))
                    }
                }
            }.takeIf { it.isNotEmpty() }?.let { put("novelChats", it) }
            if (AtomicTextFile.exists(agentIndexFile)) {
                put("agentIndex", readBackupJson(agentIndexFile, "智能体索引"))
            }
            buildJsonObject {
                File(agentDir, "sessions").listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.extension == "json" }
                    ?.sortedBy { it.name }
                    ?.forEach { file ->
                        put(
                            file.nameWithoutExtension,
                            readBackupJson(file, "智能体会话 ${file.nameWithoutExtension}"),
                        )
                    }
            }.takeIf { it.isNotEmpty() }?.let { put("agentSessions", it) }
        }
        return BackupBundle(
            version = BackupBundle.BACKUP_VERSION,
            exportedAt = nowIso(),
            appVersion = ANDROID_APP_VERSION,
            data = merged,
        )
    }

    /** All chapter ids across every project (keys for the body/illustration files). */
    private fun allChapterIds(state: JsonObject): List<String> {
        val map = state["chaptersByProject"] as? JsonObject ?: return emptyList()
        return map.values.filterIsInstance<JsonArray>().flatMap { arr ->
            arr.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        }
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
        fun projectArrayIds(obj: JsonObject): Set<String> =
            (obj["projects"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }?.toSet() ?: emptySet()
        val inMaps = PROJECT_MAP_FIELDS.filter { it != "promoByChapter" }.map { bundle.data[it] }
        val curMaps = PROJECT_MAP_FIELDS.filter { it != "promoByChapter" }.map { _state.value[it] }
        val inIds = collectProjectIds(inMaps) + projectArrayIds(bundle.data)
        val curIds = collectProjectIds(curMaps) + projectArrayIds(_state.value)
        val overlap = inIds.count { it in curIds }
        val promosIn = (bundle.data["promoByChapter"] as? JsonObject)?.size ?: 0
        val hasAppSettings = APP_SETTINGS_FIELDS.any { it in bundle.data } ||
            "agentIndex" in bundle.data || "agentSessions" in bundle.data
        return BackupSummary(inIds.size, curIds.size, overlap, promosIn, hasAppSettings)
    }

    private data class PreparedBackupImport(
        val nextState: JsonObject,
        val sensitives: Map<String, String>,
        val chapterBodies: Map<String, ChapterBody>,
        val chapterIllustrations: Map<String, List<Illustration>>,
        val novelChats: Map<String, List<NovelChatMessage>>,
        val agentSessions: Map<String, AgentSession>,
        val agentIndex: AgentIndex?,
        val chunkProjectIdsToClear: Set<String>,
    )

    private data class ImportFileSnapshot(
        val file: File,
        val existed: Boolean,
        val text: String?,
    )

    /**
     * Import is a staged transaction for all ordinary failures: decode and cross-reference checks
     * finish before the first write; every touched external file and secret is snapshotted; state
     * is published last. If any write fails, touched data is restored before the error is surfaced.
     */
    fun importBackup(bundle: BackupBundle, includeAppSettings: Boolean) {
        synchronized(chapterMutationLock) {
            synchronized(illustrationMutationLock) {
                synchronized(kbMutationLock) {
                    synchronized(novelChatMutationLock) {
                        synchronized(agentMutationLock) {
                            synchronized(secureMutationLock) {
                                synchronized(stateMutationLock) {
                                    ensureWritesAllowed()
                                    val previousState = _state.value
                                    val prepared = prepareBackupImport(
                                        incoming = bundle.data,
                                        current = previousState,
                                        includeAppSettings = includeAppSettings,
                                    )
                                    applyPreparedBackupImport(prepared, previousState)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun prepareBackupImport(
        incoming: JsonObject,
        current: JsonObject,
        includeAppSettings: Boolean,
    ): PreparedBackupImport {
        // The bundle is untrusted input. Validate all path-influencing IDs and typed metadata
        // before splitting secrets or constructing any destination path.
        BackupImportIdValidator.validate(incoming, includeAppSettings)
        validateTypedBackupMetadata(incoming, includeAppSettings)
        val (sanitizedIncoming, sensitives) = if (includeAppSettings) splitSensitives(incoming)
        else incoming to emptyMap()

        var nextState = mergeBackupState(current, sanitizedIncoming, includeAppSettings)
        if (includeAppSettings) nextState = migrateTextModelState(nextState)
        val vectorSpaceChanged = includeAppSettings &&
            embeddingSourceSignature(current) != embeddingSourceSignature(nextState)
        if (vectorSpaceChanged) nextState = invalidateAllKbState(nextState)

        val projectIds = readProjects(nextState).mapTo(linkedSetOf()) { it.id }
        val chapterIdsByProject = chapterIdsByProject(nextState, projectIds)
        val chapterIds = chapterIdsByProject.values.flatten().toSet()
        BackupImportPayloadPreflight.validate(
            data = incoming,
            knownProjectIds = projectIds,
            knownChapterIds = chapterIds,
            includeAppSettings = includeAppSettings,
            knownChapterIdsByProject = chapterIdsByProject,
        )

        val chapterBodies = decodeImportMap(
            incoming,
            "chapterBodies",
            ChapterBody.serializer(),
        )
        val chapterIllustrations = decodeImportMap(
            incoming,
            "chapterIllustrations",
            ListSerializer(Illustration.serializer()),
        )
        val novelChats = decodeImportMap(
            incoming,
            "novelChats",
            ListSerializer(NovelChatMessage.serializer()),
        )
        val agentSessions = if (includeAppSettings) {
            decodeImportMap(incoming, "agentSessions", AgentSession.serializer())
                .also { sessions ->
                    sessions.forEach { (id, session) ->
                        if (session.id != id) rejectImport("data.agentSessions[$id].id 与对象键不一致")
                    }
                }
        } else {
            emptyMap()
        }
        val agentIndex = if (includeAppSettings) {
            incoming["agentIndex"]?.let {
                decodeImportValue(AgentIndex.serializer(), it, "data.agentIndex")
            }
        } else {
            null
        }
        if (includeAppSettings) validateAgentImportReferences(agentSessions, agentIndex)

        return PreparedBackupImport(
            nextState = nextState,
            sensitives = sensitives,
            chapterBodies = chapterBodies,
            chapterIllustrations = chapterIllustrations,
            novelChats = novelChats,
            agentSessions = agentSessions,
            agentIndex = agentIndex,
            chunkProjectIdsToClear = if (vectorSpaceChanged) projectIds else emptySet(),
        )
    }

    private fun readBackupJson(file: File, label: String): kotlinx.serialization.json.JsonElement =
        try {
            JSON.parseToJsonElement(AtomicTextFile.readText(file))
        } catch (error: Throwable) {
            throw IllegalStateException(
                "备份导出中止：无法读取$label；原数据未被修改：" +
                    (error.message ?: error::class.simpleName),
                error,
            )
        }

    private fun validateTypedBackupMetadata(incoming: JsonObject, includeAppSettings: Boolean) {
        incoming["projects"]?.let { element ->
            decodeImportValue(
                ListSerializer(Project.serializer()),
                element,
                "data.projects",
            )
        }
        incoming["chaptersByProject"]?.let { element ->
            val map = element as? JsonObject ?: rejectImport("data.chaptersByProject 必须是对象")
            map.forEach { (projectId, chapters) ->
                decodeImportValue(
                    ListSerializer(Chapter.serializer()),
                    chapters,
                    "data.chaptersByProject[$projectId]",
                )
            }
        }
        incoming["factEvidenceByProject"]?.let { element ->
            val map = element as? JsonObject
                ?: rejectImport("data.factEvidenceByProject 必须是对象")
            map.forEach { (projectId, batches) ->
                decodeImportValue(
                    ListSerializer(ChapterFactEvidenceBatch.serializer()),
                    batches,
                    "data.factEvidenceByProject[$projectId]",
                )
            }
        }
        if (!includeAppSettings) return
        incoming["textModelProfiles"]?.let {
            decodeImportValue(ListSerializer(TextModelProfile.serializer()), it, "data.textModelProfiles")
        }
        incoming["textModelConfig"]?.let {
            decodeImportValue(TextModelConfig.serializer(), it, "data.textModelConfig")
        }
        incoming["embeddingConfig"]?.let {
            decodeImportValue(EmbeddingConfig.serializer(), it, "data.embeddingConfig")
        }
    }

    private fun mergeBackupState(
        current: JsonObject,
        sanitizedIncoming: JsonObject,
        includeAppSettings: Boolean,
    ): JsonObject {
        val next = current.toMutableMap()
        (sanitizedIncoming["projects"] as? JsonArray)?.let { incArr ->
            val map = LinkedHashMap<String, JsonObject>()
            (current["projects"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.let { project ->
                    project["id"]?.jsonPrimitive?.contentOrNull?.let { map[it] = project }
                }
            }
            incArr.forEach { element ->
                (element as? JsonObject)?.let { project ->
                    project["id"]?.jsonPrimitive?.contentOrNull?.let { map[it] = project }
                }
            }
            next["projects"] = JsonArray(map.values.toList())
        }

        // Every future `*ByProject` field is merged automatically. Derived KB caches are always
        // local and re-indexable, so an imported backup can never overwrite them directly.
        val kbCacheKeys = setOf("kbIndexHashByProject", "kbStaleByProject")
        val projectMapKeys = (
            sanitizedIncoming.keys.filter { it.endsWith("ByProject") && it !in kbCacheKeys } +
                "promoByChapter"
            ).distinct()
        for (key in projectMapKeys) {
            val imported = sanitizedIncoming[key] as? JsonObject ?: continue
            val existing = current[key] as? JsonObject ?: JsonObject(emptyMap())
            next[key] = JsonObject(existing + imported)
        }

        (sanitizedIncoming["folders"] as? JsonArray)?.let { incArr ->
            val map = LinkedHashMap<String, JsonObject>()
            (current["folders"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = element }
            }
            incArr.forEach { element ->
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = element }
            }
            next["folders"] = JsonArray(map.values.toList())
        }

        if (includeAppSettings) {
            (sanitizedIncoming["textModelProfiles"] as? JsonArray)?.let { incArr ->
                val map = LinkedHashMap<String, JsonObject>()
                (current["textModelProfiles"] as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = element }
                }
                incArr.forEach { element ->
                    (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { map[it] = element }
                }
                next["textModelProfiles"] = JsonArray(map.values.toList())
            }
            for (key in APP_SETTINGS_FIELDS) {
                if (key == "textModelProfiles") continue
                sanitizedIncoming[key]?.let { next[key] = it }
            }
        }
        return JsonObject(next)
    }

    private fun invalidateAllKbState(state: JsonObject): JsonObject {
        val chaptersByProject = state["chaptersByProject"] as? JsonObject ?: JsonObject(emptyMap())
        val staleByProject = chaptersByProject.mapValues { (_, value) ->
            val chapterIds = (value as? JsonArray).orEmpty().mapNotNull { chapter ->
                (chapter as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }
            JsonArray(chapterIds.distinct().map(::JsonPrimitive))
        }
        return state
            .with("kbIndexHashByProject", JsonObject(emptyMap()))
            .with("kbStaleByProject", JsonObject(staleByProject))
    }

    private fun chapterIdsByProject(
        state: JsonObject,
        projectIds: Set<String>,
    ): Map<String, Set<String>> {
        val map = state["chaptersByProject"] as? JsonObject ?: return emptyMap()
        val owners = linkedMapOf<String, String>()
        val result = linkedMapOf<String, Set<String>>()
        projectIds.forEach { projectId ->
            val ids = linkedSetOf<String>()
            (map[projectId] as? JsonArray).orEmpty().forEach chapterLoop@ { chapter ->
                val chapterId = (chapter as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: return@chapterLoop
                val previousOwner = owners.putIfAbsent(chapterId, projectId)
                if (previousOwner != null) {
                    rejectImport(
                        "合并后的章节 ID $chapterId 同时出现在项目 $previousOwner 与 $projectId 中；" +
                        "它们会指向同一个正文文件",
                    )
                }
                ids += chapterId
            }
            result[projectId] = ids
        }
        return result
    }

    private fun <T> decodeImportMap(
        incoming: JsonObject,
        field: String,
        serializer: KSerializer<T>,
    ): Map<String, T> {
        val map = incoming[field] as? JsonObject ?: return emptyMap()
        return map.mapValues { (id, element) ->
            decodeImportValue(serializer, element, "data.$field[$id]")
        }
    }

    private fun <T> decodeImportValue(
        serializer: KSerializer<T>,
        element: kotlinx.serialization.json.JsonElement,
        path: String,
    ): T = try {
        JSON.decodeFromJsonElement(serializer, element)
    } catch (error: Throwable) {
        throw BackupImportValidationException(
            "备份导入已拒绝：$path 数据格式无效：${error.message ?: error::class.simpleName}",
            error,
        )
    }

    private fun rejectImport(reason: String): Nothing =
        throw BackupImportValidationException("备份导入已拒绝：$reason")

    private fun validateAgentImportReferences(
        sessions: Map<String, AgentSession>,
        index: AgentIndex?,
    ) {
        if (sessions.isNotEmpty() && index == null) {
            rejectImport("data.agentSessions 存在，但缺少 data.agentIndex，导入后会成为不可见会话")
        }
        val suppliedOrExisting = sessions.keys +
            (File(agentDir, "sessions").listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                .orEmpty())
        index ?: return
        val itemIds = index.items.map { it.id }.toSet()
        index.currentId?.let { currentId ->
            if (currentId !in itemIds) rejectImport("data.agentIndex.currentId 不在 items 中")
        }
        index.items.forEachIndexed { itemIndex, item ->
            if (item.id !in suppliedOrExisting) {
                rejectImport("data.agentIndex.items[$itemIndex] 指向缺失的会话文件 ${item.id}")
            }
        }
    }

    private fun applyPreparedBackupImport(
        prepared: PreparedBackupImport,
        previousState: JsonObject,
    ) {
        val targetFiles = buildList {
            prepared.chapterBodies.keys.forEach { add(File(appContext.filesDir, "chapters/$it.json")) }
            prepared.chapterIllustrations.keys.forEach { add(File(appContext.filesDir, "illustrations/$it.json")) }
            prepared.novelChats.keys.forEach { add(novelChatFile(it)) }
            prepared.chunkProjectIdsToClear.forEach { add(chunksFile(it)) }
            prepared.agentSessions.keys.forEach { add(agentSessionFile(it)) }
            if (prepared.agentIndex != null) add(agentIndexFile)
        }.distinctBy { it.absolutePath }
        val snapshots = targetFiles.associate { file -> file.absolutePath to snapshotImportFile(file) }
        val secretKeys = prepared.sensitives.keys.sorted()
        val journal = BackupImportJournal.prepare(
            filesDir = appContext.filesDir,
            previousStateJson = JSON.encodeToString(JsonObject.serializer(), previousState),
            snapshots = snapshots.values.map { snapshot ->
                BackupImportJournal.SnapshotInput(
                    target = snapshot.file,
                    existed = snapshot.existed,
                    text = snapshot.text,
                )
            },
            secretKeys = secretKeys,
            secrets = secureStore,
        )
        val touchedFiles = linkedSetOf<String>()
        var secretsTouched = false
        var stateCommitted = false

        fun write(file: File, text: String) {
            touchedFiles += file.absolutePath
            AtomicTextFile.writeText(file, text)
        }

        try {
            prepared.chapterBodies.forEach { (chapterId, body) ->
                write(
                    File(appContext.filesDir, "chapters/$chapterId.json"),
                    JSON.encodeToString(ChapterBody.serializer(), body),
                )
            }
            prepared.chapterIllustrations.forEach { (chapterId, illustrations) ->
                write(
                    File(appContext.filesDir, "illustrations/$chapterId.json"),
                    JSON.encodeToString(ListSerializer(Illustration.serializer()), illustrations),
                )
            }
            prepared.novelChats.forEach { (projectId, messages) ->
                write(
                    novelChatFile(projectId),
                    JSON.encodeToString(ListSerializer(NovelChatMessage.serializer()), messages),
                )
            }
            prepared.chunkProjectIdsToClear.forEach { projectId ->
                write(
                    chunksFile(projectId),
                    JSON.encodeToString(ListSerializer(KbChunk.serializer()), emptyList()),
                )
            }
            // Sessions precede the index so an index is never committed before its supplied files.
            prepared.agentSessions.forEach { (id, session) ->
                write(agentSessionFile(id), JSON.encodeToString(AgentSession.serializer(), session))
            }
            prepared.agentIndex?.let { index ->
                write(agentIndexFile, JSON.encodeToString(AgentIndex.serializer(), index))
            }
            if (prepared.sensitives.isNotEmpty()) {
                secretsTouched = true
                secureStore.putAllDurably(prepared.sensitives)
            }

            // Publish metadata only after every external payload is durable.
            mutateState { prepared.nextState }
            stateCommitted = true
            BackupImportJournal.commit(journal, secureStore)
            if (prepared.novelChats.isNotEmpty()) _novelChatRevision.value += 1
            if (prepared.chunkProjectIdsToClear.isNotEmpty()) _kbRevision.value += 1
        } catch (error: Throwable) {
            val rollbackErrors = mutableListOf<Throwable>()
            touchedFiles.toList().asReversed().forEach { path ->
                snapshots[path]?.let { snapshot ->
                    runCatching { restoreImportFile(snapshot) }
                        .onFailure(rollbackErrors::add)
                }
            }
            if (secretsTouched) {
                runCatching { secureStore.restoreRollbackValues(secretKeys) }
                    .onFailure(rollbackErrors::add)
            }
            if (stateCommitted) {
                runCatching { mutateState { previousState } }
                    .onFailure(rollbackErrors::add)
            }
            if (rollbackErrors.isEmpty()) {
                runCatching { BackupImportJournal.resolveAfterRollback(journal, secureStore) }
                    .onFailure(rollbackErrors::add)
            }
            if (rollbackErrors.isNotEmpty()) writesBlockedByBackupRecovery = true
            val message = if (rollbackErrors.isEmpty()) {
                "备份导入失败，已恢复导入前状态：${error.message ?: error::class.simpleName}"
            } else {
                "备份导入失败，且部分自动回滚失败；为保护数据已停止本进程写入，请立即重启应用：" +
                    (error.message ?: error::class.simpleName)
            }
            val wrapped = IllegalStateException(message, error)
            rollbackErrors.forEach(wrapped::addSuppressed)
            throw wrapped
        }
    }

    private fun snapshotImportFile(file: File): ImportFileSnapshot {
        val existed = AtomicTextFile.exists(file)
        return ImportFileSnapshot(
            file = file,
            existed = existed,
            text = if (existed) AtomicTextFile.readText(file) else null,
        )
    }

    private fun restoreImportFile(snapshot: ImportFileSnapshot) {
        if (snapshot.existed) {
            AtomicTextFile.writeText(snapshot.file, checkNotNull(snapshot.text))
        } else if (!AtomicTextFile.delete(snapshot.file)) {
            error("无法移除导入过程中创建的文件：${snapshot.file.absolutePath}")
        }
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

    /** Clear an orphan stale marker only if that chapter is still absent at the atomic check. */
    internal fun clearKbStaleIfChapterMissing(projectId: String, chapterId: String): Boolean =
        synchronized(chapterMutationLock) {
            if (project(projectId) == null || chapters(projectId).any { it.id == chapterId }) {
                return@synchronized false
            }
            var committed = false
            mutateState { current ->
                if (!hasProject(current, projectId)) return@mutateState current
                val staleOuter = (current["kbStaleByProject"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                val stale = (staleOuter[projectId] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filterNot { it == chapterId }
                staleOuter[projectId] = JsonArray(stale.distinct().map { JsonPrimitive(it) })
                committed = true
                current.with("kbStaleByProject", JsonObject(staleOuter))
            }
            committed
        }

    // ── "Ask the novel" Q&A chat history (per-project, own file) ─────────────

    private fun novelChatFile(projectId: String): File =
        File(appContext.filesDir, "novel_chat/${projectId}.json")

    private fun readNovelChatHistoryLocked(projectId: String): List<NovelChatMessage> {
        val f = novelChatFile(projectId)
        if (!AtomicTextFile.exists(f)) return emptyList()
        return runCatching {
            JSON.decodeFromString(
                ListSerializer(NovelChatMessage.serializer()),
                AtomicTextFile.readText(f),
            )
        }.getOrDefault(emptyList())
    }

    fun novelChatHistory(projectId: String): List<NovelChatMessage> =
        synchronized(novelChatMutationLock) { readNovelChatHistoryLocked(projectId) }

    private fun writeNovelChatHistoryLocked(projectId: String, messages: List<NovelChatMessage>) {
        ensureWritesAllowed()
        val dir = File(appContext.filesDir, "novel_chat")
        if (!dir.exists()) dir.mkdirs()
        AtomicTextFile.writeText(novelChatFile(projectId),
            JSON.encodeToString(ListSerializer(NovelChatMessage.serializer()), messages)
        )
        _novelChatRevision.value += 1
    }

    fun setNovelChatHistory(projectId: String, messages: List<NovelChatMessage>) =
        synchronized(novelChatMutationLock) {
            if (project(projectId) == null) return@synchronized
            writeNovelChatHistoryLocked(projectId, messages)
        }

    fun appendNovelChat(projectId: String, message: NovelChatMessage) =
        synchronized(novelChatMutationLock) {
            if (project(projectId) == null) return@synchronized
            writeNovelChatHistoryLocked(projectId, readNovelChatHistoryLocked(projectId) + message)
        }

    fun clearNovelChat(projectId: String) = synchronized(novelChatMutationLock) {
        ensureWritesAllowed()
        AtomicTextFile.delete(novelChatFile(projectId))
        _novelChatRevision.value += 1
    }

    // ── Agent (智能体) name ───────────────────────────────────────────────────

    fun activeTextModelProfileId(): String? =
        (_state.value["activeTextModelProfileId"] as? JsonPrimitive)?.contentOrNull

    fun agentName(): String = (_state.value["agentName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun setAgentName(name: String) = mutateState { it.with("agentName", JsonPrimitive(name)) }

    // ── Agent multi-session persistence (index + per-session files) ───────────

    private val agentDir: File get() = File(appContext.filesDir, "agent")
    private val agentIndexFile: File get() = File(agentDir, "index.json")
    private fun agentSessionFile(id: String): File = File(agentDir, "sessions/$id.json")

    fun loadAgentIndex(): AgentIndex = synchronized(agentMutationLock) {
        migrateLegacyAgentSession()
        val f = agentIndexFile
        runCatching {
            JSON.decodeFromString(AgentIndex.serializer(), AtomicTextFile.readText(f))
        }.getOrDefault(AgentIndex())
    }

    fun saveAgentIndex(index: AgentIndex) = synchronized(agentMutationLock) {
        ensureWritesAllowed()
        AtomicTextFile.writeText(
            agentIndexFile,
            JSON.encodeToString(AgentIndex.serializer(), index),
        )
    }

    fun loadAgentSessionById(id: String): AgentSession? = synchronized(agentMutationLock) {
        if (!isSafeAgentSessionId(id)) return@synchronized null
        val f = agentSessionFile(id)
        runCatching {
            JSON.decodeFromString(AgentSession.serializer(), AtomicTextFile.readText(f))
        }.getOrNull()
    }

    fun saveAgentSessionById(session: AgentSession) = synchronized(agentMutationLock) {
        ensureWritesAllowed()
        require(isSafeAgentSessionId(session.id)) { "Invalid agent session id" }
        AtomicTextFile.writeText(
            agentSessionFile(session.id),
            JSON.encodeToString(AgentSession.serializer(), session),
        )
    }

    fun deleteAgentSessionById(id: String) = synchronized(agentMutationLock) {
        ensureWritesAllowed()
        if (isSafeAgentSessionId(id)) AtomicTextFile.delete(agentSessionFile(id))
    }

    private fun isSafeAgentSessionId(id: String): Boolean =
        id.isNotBlank() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** One-time migration of the old single `agent/session.json` into the new multi-session store. */
    private fun migrateLegacyAgentSession() {
        val old = File(agentDir, "session.json")
        if (!AtomicTextFile.exists(old) || AtomicTextFile.exists(agentIndexFile)) return
        val obj = runCatching {
            JSON.parseToJsonElement(AtomicTextFile.readText(old)).jsonObject
        }.getOrNull()
        val steps = (obj?.get("steps"))?.let {
            runCatching { JSON.decodeFromJsonElement(ListSerializer(AgentStep.serializer()), it) }.getOrNull()
        } ?: emptyList()
        val locked = (obj?.get("activeProjectId") as? JsonPrimitive)?.contentOrNull
        val s = AgentSession(id = "sess-${System.currentTimeMillis()}", title = "会话 1", createdAt = nowIso(),
            steps = steps, lockedProjectId = locked)
        saveAgentSessionById(s)
        saveAgentIndex(AgentIndex(currentId = s.id, items = listOf(AgentSessionMeta(s.id, s.title, s.createdAt))))
        AtomicTextFile.delete(old)
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

    fun containerStore(projectId: String): ContainerStore = containerStore(_state.value, projectId)

    private fun containerStore(state: JsonObject, projectId: String): ContainerStore {
        val el = (state["containersByProject"] as? JsonObject)?.get(projectId) ?: return ContainerStore()
        return runCatching { JSON.decodeFromJsonElement(ContainerStore.serializer(), el) }.getOrDefault(ContainerStore())
    }

    private fun withContainerStore(current: JsonObject, projectId: String, store: ContainerStore): JsonObject {
        val map = (current["containersByProject"] as? JsonObject) ?: JsonObject(emptyMap())
        return current.with(
            "containersByProject",
            map.with(projectId, JSON.encodeToJsonElement(ContainerStore.serializer(), store) as JsonObject),
        )
    }

    fun containers(projectId: String): List<Container> = containerStore(projectId).containers

    fun container(projectId: String, containerId: String): Container? =
        containerStore(projectId).containers.firstOrNull { it.id == containerId }

    fun createContainer(projectId: String, container: Container) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val store = containerStore(current, projectId)
            if (store.containers.any { it.id == container.id }) return@mutateState current
            withContainerStore(current, projectId, store.copy(containers = store.containers + container))
        }
    }

    /** Update a container's editable metadata (name + toggles). Type and id are never changed. */
    fun updateContainerMeta(
        projectId: String, containerId: String, name: String,
        autoUpdatePerChapter: Boolean, affectsGeneration: Boolean,
        affectsVolumeGeneration: Boolean, affectsArcGeneration: Boolean,
    ) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val store = containerStore(current, projectId)
            withContainerStore(current, projectId, store.copy(
            containers = store.containers.map {
                if (it.id == containerId) it.copy(
                    name = name,
                    autoUpdatePerChapter = autoUpdatePerChapter,
                    affectsGeneration = affectsGeneration,
                    affectsVolumeGeneration = affectsVolumeGeneration,
                    affectsArcGeneration = affectsArcGeneration,
                ) else it
            },
            ))
        }
    }

    fun deleteContainer(projectId: String, containerId: String) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val store = containerStore(current, projectId)
            withContainerStore(current, projectId, store.copy(
                containers = store.containers.filterNot { it.id == containerId },
                entries = store.entries - containerId,
            ))
        }
    }

    fun containerEntries(projectId: String, containerId: String, blockKey: String): List<ContainerEntry> =
        containerStore(projectId).entries[containerId]?.get(blockKey)
            ?.filterNot { it.isStale }
            ?: emptyList()

    fun appendContainerEntry(projectId: String, containerId: String, blockKey: String, entry: ContainerEntry) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val store = containerStore(current, projectId)
            if (store.containers.none { it.id == containerId }) return@mutateState current
            val byContainer = (store.entries[containerId] ?: emptyMap()).toMutableMap()
            byContainer[blockKey] = (byContainer[blockKey] ?: emptyList()) + entry
            withContainerStore(current, projectId, store.copy(entries = store.entries + (containerId to byContainer)))
        }
    }

    /**
     * Append AI-derived entries only while the chapter, container metadata, and prompt-time latest
     * block values all still match. The transform merges into the newest store in one state update.
     */
    internal fun appendContainerEntriesIfSourceCurrent(
        projectId: String,
        expectedSource: ChapterSourceVersion,
        expectedContainer: Container,
        expectedLatestEntries: Map<String, ContainerEntry?>,
        expectedCharacterSignature: String? = null,
        additions: List<Pair<String, ContainerEntry>>,
    ): Boolean = synchronized(chapterMutationLock) {
        if (additions.isEmpty() || !chapterSourceIsCurrentLocked(projectId, expectedSource)) {
            return@synchronized false
        }
        var committed = false
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            if (expectedCharacterSignature != null &&
                characterSourceSignature(current, projectId) != expectedCharacterSignature
            ) {
                return@mutateState current
            }
            val store = containerStore(current, projectId)
            if (store.containers.firstOrNull { it.id == expectedContainer.id } != expectedContainer) {
                return@mutateState current
            }
            val liveEntries = store.entries[expectedContainer.id].orEmpty()
            if (expectedLatestEntries.any { (blockKey, expected) ->
                    liveEntries[blockKey]?.lastOrNull { !it.isStale } != expected
                }
            ) {
                return@mutateState current
            }
            val byContainer = liveEntries.toMutableMap()
            additions.forEach { (blockKey, entry) ->
                byContainer[blockKey] = byContainer[blockKey].orEmpty() + entry
            }
            committed = true
            withContainerStore(
                current,
                projectId,
                store.copy(entries = store.entries + (expectedContainer.id to byContainer)),
            )
        }
        committed
    }

    internal fun containerInputsAreCurrent(
        projectId: String,
        expectedSource: ChapterSourceVersion,
        expectedContainer: Container,
        expectedLatestEntries: Map<String, ContainerEntry?>,
        expectedCharacterSignature: String? = null,
    ): Boolean = synchronized(chapterMutationLock) {
        if (!chapterSourceIsCurrentLocked(projectId, expectedSource)) return@synchronized false
        val current = _state.value
        if (!hasProject(current, projectId)) return@synchronized false
        if (expectedCharacterSignature != null &&
            characterSourceSignature(current, projectId) != expectedCharacterSignature
        ) {
            return@synchronized false
        }
        val store = containerStore(current, projectId)
        if (store.containers.firstOrNull { it.id == expectedContainer.id } != expectedContainer) {
            return@synchronized false
        }
        val liveEntries = store.entries[expectedContainer.id].orEmpty()
        expectedLatestEntries.all { (blockKey, expected) ->
            liveEntries[blockKey]?.lastOrNull { !it.isStale } == expected
        }
    }

    /** Overwrite the value of the newest entry in a block (the user manually editing the latest value). */
    fun replaceLatestContainerEntry(projectId: String, containerId: String, blockKey: String, value: String) {
        mutateState { current ->
            if (!hasProject(current, projectId)) return@mutateState current
            val store = containerStore(current, projectId)
            if (store.containers.none { it.id == containerId }) return@mutateState current
            val byContainer = (store.entries[containerId] ?: return@mutateState current).toMutableMap()
            val chain = byContainer[blockKey]?.toMutableList() ?: return@mutateState current
            val activeIndex = chain.indexOfLast { !it.isStale }
            if (activeIndex < 0) return@mutateState current
            chain[activeIndex] = chain[activeIndex].copy(value = value, manual = true, isStale = false)
            byContainer[blockKey] = chain
            withContainerStore(current, projectId, store.copy(entries = store.entries + (containerId to byContainer)))
        }
    }

    // ── Project snapshots (version history) ──────────────────────────────────

    private fun versionsDir(projectId: String): File = File(appContext.filesDir, "versions/$projectId")
    private fun snapshotIndexFile(projectId: String): File = File(versionsDir(projectId), "index.json")
    private fun snapshotFile(projectId: String, snapshotId: String): File =
        File(versionsDir(projectId), "$snapshotId.json")

    fun listSnapshots(projectId: String): List<SnapshotMeta> {
        val f = snapshotIndexFile(projectId)
        if (!AtomicTextFile.exists(f)) return emptyList()
        return runCatching {
            JSON.decodeFromString(
                ListSerializer(SnapshotMeta.serializer()),
                AtomicTextFile.readText(f),
            )
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    private fun writeSnapshotIndex(projectId: String, metas: List<SnapshotMeta>) {
        val dir = versionsDir(projectId)
        if (!dir.exists()) dir.mkdirs()
        AtomicTextFile.writeText(snapshotIndexFile(projectId),
            JSON.encodeToString(ListSerializer(SnapshotMeta.serializer()), metas.sortedByDescending { it.createdAt })
        )
        _snapshotRevision.value += 1
    }

    private fun readSnapshot(projectId: String, snapshotId: String): ProjectSnapshot? {
        val f = snapshotFile(projectId, snapshotId)
        if (!AtomicTextFile.exists(f)) return null
        return runCatching {
            JSON.decodeFromString(ProjectSnapshot.serializer(), AtomicTextFile.readText(f))
        }.getOrNull()
    }

    /**
     * Capture the whole project into a new snapshot. Returns null if the project does not exist, or
     * if [trigger] is non-manual and the content is identical to the most recent snapshot (dedup).
     * [force] is reserved for safety snapshots that must represent the exact pre-mutation state.
     */
    fun createSnapshot(projectId: String, label: String, trigger: String, force: Boolean = false): SnapshotMeta? {
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

        val contentSig = SnapshotStore.contentSignature(
            project = projectJson,
            chapters = chaptersJson,
            chapterBodies = bodies,
            illustrations = illus,
            promos = promos,
            projectMaps = projectMaps,
            chapterHashes = chapterHashes,
        )

        val existing = listSnapshots(projectId)
        if (!force && trigger != SnapshotMeta.TRIGGER_MANUAL && existing.firstOrNull()?.contentSig == contentSig) {
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
        AtomicTextFile.writeText(file, JSON.encodeToString(ProjectSnapshot.serializer(), snapshot))
        val meta = snapshot.meta.copy(sizeBytes = file.length())

        writeSnapshotIndex(projectId, applyRetention(projectId, existing + meta))
        return meta
    }

    /** Keep all manual snapshots; cap auto/pre_ai at [SnapshotStore.DEFAULT_RETENTION], deleting files. */
    private fun applyRetention(projectId: String, all: List<SnapshotMeta>): List<SnapshotMeta> {
        val sorted = all.sortedByDescending { it.createdAt }
        val manual = sorted.filter { it.trigger == SnapshotMeta.TRIGGER_MANUAL }
        val auto = sorted.filter { it.trigger != SnapshotMeta.TRIGGER_MANUAL }
        auto.drop(SnapshotStore.DEFAULT_RETENTION).forEach {
            AtomicTextFile.delete(snapshotFile(projectId, it.id))
        }
        return manual + auto.take(SnapshotStore.DEFAULT_RETENTION)
    }

    fun renameSnapshot(projectId: String, snapshotId: String, label: String) {
        val updated = listSnapshots(projectId).map { if (it.id == snapshotId) it.copy(label = label) else it }
        writeSnapshotIndex(projectId, updated)
    }

    fun deleteSnapshot(projectId: String, snapshotId: String) {
        AtomicTextFile.delete(snapshotFile(projectId, snapshotId))
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
        val snap = readSnapshot(projectId, snapshotId)
            ?: return RestoreResult(success = false, errorMessage = "快照不存在或无法读取")
        if (project(projectId) == null) {
            return RestoreResult(success = false, errorMessage = "项目不存在")
        }

        // Safety net: force an independent snapshot of the exact current state. Restore must never
        // reuse an older snapshot as rollback material, even if regular auto-dedup would skip it.
        val backupMeta = runCatching {
            createSnapshot(projectId, "回退前自动备份", SnapshotMeta.TRIGGER_AUTO, force = true)
        }.getOrElse { error ->
            return RestoreResult(
                success = false,
                errorMessage = "创建回退前备份失败：${error.message ?: error::class.simpleName}",
            )
        }
        val backup = backupMeta?.let { readSnapshot(projectId, it.id) }
            ?: return RestoreResult(success = false, errorMessage = "无法读取回退前备份，未执行回退")

        return try {
            applyProjectSnapshot(projectId, snap)
        } catch (restoreError: Throwable) {
            val rolledBack = runCatching { applyProjectSnapshot(projectId, backup) }.isSuccess
            val detail = restoreError.message ?: restoreError::class.simpleName ?: "未知错误"
            RestoreResult(
                success = false,
                errorMessage = if (rolledBack) {
                    "快照应用失败，已恢复回退前状态：$detail"
                } else {
                    "快照应用失败，且自动恢复回退前状态也失败：$detail"
                },
            )
        }
    }

    /** Validate the complete payload before the first write, then apply one already-loaded snapshot. */
    private fun applyProjectSnapshot(
        projectId: String,
        snap: ProjectSnapshot,
    ): RestoreResult = synchronized(chapterMutationLock) {
        check(project(projectId) != null) { "项目已删除，无法应用快照" }
        val restoredProject = JSON.decodeFromJsonElement(Project.serializer(), snap.project)
        val restoredChapters = JSON.decodeFromJsonElement(ListSerializer(Chapter.serializer()), snap.chapters)
        val restoredChapterIds = restoredChapters.map { it.id }.toSet()
        val decodedBodies = snap.chapterBodies.mapValues { (_, obj) ->
            JSON.decodeFromJsonElement(ChapterBody.serializer(), obj)
        }
        val decodedIllustrations = restoredChapterIds.associateWith { cid ->
            snap.illustrations[cid]?.let {
                JSON.decodeFromJsonElement(ListSerializer(Illustration.serializer()), it)
            } ?: emptyList()
        }
        // Validate promo payloads before touching live state; the raw JSON is retained for exact
        // round-tripping in the state object below.
        snap.promos.values.forEach { JSON.decodeFromJsonElement(ChapterPromo.serializer(), it) }

        decodedBodies.forEach { (cid, body) -> writeChapterBodyUncheckedLocked(cid, body) }
        synchronized(illustrationMutationLock) {
            decodedIllustrations.forEach { (cid, illustrations) ->
                writeChapterIllustrationsUncheckedLocked(cid, illustrations)
            }
        }

        mutateState { current ->
            val next = current.toMutableMap()
            val projList = readProjects(current).toMutableList()
            val idx = projList.indexOfFirst { it.id == projectId }
            if (idx >= 0) projList[idx] = restoredProject else projList.add(restoredProject)
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

        reconcileKbAfterRestore(projectId, restoredChapterIds, snap.chapterHashes)
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
