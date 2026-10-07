package com.example.novelseek_ultra.ui.screens

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelseek_ultra.data.model.CandidateAdoptionResult
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.writing.ChapterScenePlan
import com.example.novelseek_ultra.data.writing.ManuscriptImporter
import com.example.novelseek_ultra.data.writing.ManuscriptPreview
import com.example.novelseek_ultra.data.writing.ReviewSeverity
import com.example.novelseek_ultra.data.writing.WritingCheckpoint
import com.example.novelseek_ultra.data.writing.WritingStatus
import com.example.novelseek_ultra.data.writing.WritingUsageEntry
import com.example.novelseek_ultra.data.writing.WritingWorkspace
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.ChapterReviewFinding
import com.example.novelseek_ultra.ui.components.ChapterReviewFindingSeverity
import com.example.novelseek_ultra.ui.components.ChapterReviewWorkbench
import com.example.novelseek_ultra.ui.components.chapterReviewTextChunks
import com.example.novelseek_ultra.util.tx
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class WorkspaceData(
    val workspace: WritingWorkspace,
    val profiles: List<TextModelProfile>,
    val plans: List<ChapterScenePlan>,
    val checkpoints: List<WritingCheckpoint>,
    val usage: List<WritingUsageEntry>,
    val reviewRuns: List<GenerationRun>,
    val storyNoteStatuses: Map<String, String>,
)

private data class SavedScenePreview(
    val title: String,
    val completedSceneCount: Int,
    val draftChunks: List<String>,
    val baselineChunks: List<String>,
)

@Composable
fun WritingWorkspaceRoute(
    vm: AppViewModel,
    projectId: String,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenOutline: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenLegacyProject: () -> Unit,
) {
    val language by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val projects by vm.projects.collectAsState()
    val writingRevision by vm.writingRevision.collectAsState()
    val generationRevision by vm.generationRunRevision.collectAsState()
    val stages by vm.writingStages.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val chapters = remember(state, projectId) { vm.chapters(projectId).sortedBy { it.order_index } }
    val characters = remember(state, projectId) { vm.characters(projectId) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    var data by remember(projectId) { mutableStateOf<WorkspaceData?>(null) }
    var localRevision by remember(projectId) { mutableStateOf(0) }
    var loadError by remember(projectId) { mutableStateOf<String?>(null) }
    var showPreferences by remember(projectId) { mutableStateOf(false) }
    var showStoryNotes by remember(projectId) { mutableStateOf(false) }
    var showCreateChapter by remember(projectId) { mutableStateOf(false) }
    var creatingTitle by remember(projectId) { mutableStateOf("") }
    var creatingGoal by remember(projectId) { mutableStateOf("") }
    var createBusy by remember(projectId) { mutableStateOf(false) }
    var editingPlan by remember(projectId) { mutableStateOf<ChapterScenePlan?>(null) }
    var waitingForPlanChapter by remember(projectId) { mutableStateOf<String?>(null) }
    var waitingForPlanAfter by remember(projectId) { mutableStateOf(Long.MIN_VALUE) }
    var reviewingCandidate by remember(projectId) { mutableStateOf<Pair<String, String>?>(null) }
    var importPreview by remember(projectId) { mutableStateOf<ManuscriptPreview?>(null) }
    var importBusy by remember(projectId) { mutableStateOf(false) }
    var importError by remember(projectId) { mutableStateOf<String?>(null) }
    var savedSceneKey by remember(projectId) { mutableStateOf<Pair<String, String>?>(null) }
    var savedScenePreview by remember(projectId) { mutableStateOf<SavedScenePreview?>(null) }
    var savedSceneLoading by remember(projectId) { mutableStateOf(false) }
    var savedSceneError by remember(projectId) { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId, savedSceneKey) {
        val expected = savedSceneKey ?: return@LaunchedEffect
        savedScenePreview = null
        savedSceneError = null
        savedSceneLoading = true
        try {
            val preview = withContext(Dispatchers.IO) {
                val checkpoint = vm.writingCheckpoints(projectId).firstOrNull {
                    it.plan.chapterId == expected.first && it.runId == expected.second
                } ?: throw IllegalStateException(tx(language, "这次任务的已保存场景已被更新或不存在，请关闭后刷新任务列表。", "This run's saved scenes have changed or are unavailable. Close and refresh the task list."))
                check(checkpoint.plan.projectId == projectId && checkpoint.completedScenes.isNotEmpty()) {
                    tx(language, "此任务没有可预览的已完成场景。", "This task has no completed scenes to preview.")
                }
                val chapter = vm.chapters(projectId).firstOrNull { it.id == expected.first }
                    ?: throw IllegalStateException(tx(language, "目标章节已不存在；未采用草稿没有写入正式正文。", "The chapter is unavailable; the draft has not been written into the official text."))
                val baseline = vm.chapterBody(chapter.id)
                SavedScenePreview(chapter.title, checkpoint.completedScenes.size,
                    chapterReviewTextChunks(checkpoint.body), chapterReviewTextChunks(baseline.final.ifBlank { baseline.draft }))
            }
            if (savedSceneKey == expected) savedScenePreview = preview
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { if (savedSceneKey == expected) savedSceneError = failure.message ?: tx(language, "已保存场景读取失败。", "Unable to read saved scenes.") }
        finally { if (savedSceneKey == expected) savedSceneLoading = false }
    }

    LaunchedEffect(projectId, state, chapters, writingRevision, generationRevision, localRevision) {
        try {
            data = withContext(Dispatchers.IO) {
                WorkspaceData(vm.writingWorkspace(projectId), vm.textModelProfiles(), vm.scenePlans(projectId),
                    vm.writingCheckpoints(projectId), vm.writingUsage(projectId), chapters.mapNotNull {
                        vm.latestReviewableGenerationRun(projectId, it.id)
                    }, vm.storyNoteStatusReport(projectId))
            }
            loadError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { loadError = failure.message ?: tx(language, "工作台读取失败", "Unable to load workbench") }
    }
    LaunchedEffect(data?.plans, waitingForPlanChapter) {
        waitingForPlanChapter?.let { id ->
            data?.plans?.firstOrNull { it.chapterId == id && it.updatedAt > waitingForPlanAfter }?.let { editingPlan = it; waitingForPlanChapter = null }
        }
    }
    val chooseManuscript = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importBusy = true
            importError = null
            try {
                importPreview = withContext(Dispatchers.IO) { ManuscriptImporter.preview(readBoundedManuscript(resolver, uri)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { importError = failure.message ?: tx(language, "书稿读取失败", "Unable to read manuscript") }
            finally { importBusy = false }
        }
    }
    if (project == null) {
        Text(tx(language, "项目不存在", "Project not found"), modifier = Modifier.padding(24.dp))
        return
    }
    val current = data
    val runs = current?.reviewRuns.orEmpty()
    val checkpointByChapter = current?.checkpoints.orEmpty().associateBy { it.plan.chapterId }
    val plansByChapter = current?.plans.orEmpty().associateBy { it.chapterId }
    val usageByRun = current?.usage.orEmpty().associateBy { it.runId }
    val latestUsageByChapter = current?.usage.orEmpty().filter { !it.chapterId.isNullOrBlank() }
        .sortedBy { it.completedAt }.associateBy { it.chapterId }
    val tasks = chapters.mapNotNull { chapter ->
        val checkpoint = checkpointByChapter[chapter.id]
        val stage = stages["$projectId/${chapter.id}"]
        if (checkpoint == null && stage == null) return@mapNotNull null
        val running = stage != null
        val resumable = !running && checkpoint?.status in setOf(WritingStatus.INTERRUPTED, WritingStatus.FAILED, WritingStatus.RUNNING)
        val completedScenes = checkpoint?.completedScenes?.size ?: 0
        val sceneCount = checkpoint?.plan?.scenes?.size ?: 0
        WorkbenchTask(
            id = chapter.id, title = chapter.title,
            stateLabel = if (running) tx(language, "运行中", "Running") else when (checkpoint?.status) {
                WritingStatus.COMPLETED -> if (runs.any { it.chapterId == chapter.id }) tx(language, "待审核", "Awaiting review") else tx(language, "已完成", "Completed")
                WritingStatus.FAILED -> tx(language, "失败", "Failed")
                WritingStatus.INTERRUPTED, WritingStatus.RUNNING -> tx(language, "可恢复", "Resumable")
                else -> tx(language, "已规划", "Planned")
            },
            detail = stage ?: tx(language, "已完成 $completedScenes/$sceneCount 场景", "$completedScenes/$sceneCount scenes completed"),
            progress = if (sceneCount > 0) completedScenes.toFloat() / sceneCount else null,
            isRunning = running, canResume = resumable, canPause = running, canCancel = running || resumable,
            failureReason = checkpoint?.error.orEmpty(),
            costSummary = latestUsageByChapter[chapter.id]?.let { usage -> writingUsageSummary(language, usage) }.orEmpty(),
            canPreviewSavedScenes = completedScenes > 0,
        )
    }
    val workspace = current?.workspace ?: WritingWorkspace()
    WritingWorkbenchScreen(
        language, WorkbenchProject(project.id, project.title, project.description.orEmpty(), project.genre.orEmpty(), project.current_word_count, project.target_word_count),
        chapters.map { chapter -> WorkbenchChapter(chapter.id, chapter.title, chapter.order_index, chapter.word_count,
            hasText = chapter.word_count > 0, sceneCount = plansByChapter[chapter.id]?.scenes?.size ?: 0,
            pendingReview = runs.any { it.chapterId == chapter.id }, summary = chapter.outline_goal.orEmpty(), modifiedLabel = chapter.updated_at) },
        pendingReviews = runs.mapNotNull { run -> run.candidates.firstOrNull { it.status == CandidateChapter.STATUS_COMPLETED }?.let { candidate ->
            WorkbenchPendingReview("${run.id}:${candidate.id}", run.chapterId, chapters.firstOrNull { it.id == run.chapterId }?.title.orEmpty(), listOfNotNull(
                tx(language, "${candidate.wordCount} 字 · ${run.reviewRevisions.size} 次修订", "${candidate.wordCount} words · ${run.reviewRevisions.size} revisions"),
                usageByRun[run.id]?.let { writingUsageSummary(language, it) },
            ).joinToString("\n"))
        } },
        scenePlans = current?.plans.orEmpty().map { plan -> WorkbenchScenePlan(plan.chapterId, plan.chapterId,
            chapters.firstOrNull { it.id == plan.chapterId }?.title.orEmpty(), tx(language, "已规划", "Planned"), plan.scenes.size,
            checkpointByChapter[plan.chapterId]?.completedScenes?.size ?: 0) },
        tasks = tasks,
        preferences = WorkbenchWritingPreferences(
            modeLabel = when (workspace.mode) { "quick" -> tx(language, "快速写作", "Quick writing"); "polish" -> tx(language, "精修审稿", "Polished writing"); else -> tx(language, "场景写作", "Scene writing") },
            viewpoint = workspace.perspective, styleSummary = workspace.style, forbiddenExpressions = workspace.forbiddenExpressions,
            requestLimitSummary = tx(language, "单次任务最多 ${workspace.maxRequestsPerRun} 次请求", "Up to ${workspace.maxRequestsPerRun} requests per run"),
        ),
        nextChapterId = chapters.firstOrNull { it.word_count == 0 }?.id ?: chapters.lastOrNull()?.id,
        onBack = onBack, onOpenChapter = onOpenChapter, onOpenOutline = onOpenOutline, onOpenCharacters = onOpenCharacters,
        onOpenAgent = onOpenAgent, onOpenLegacyProject = onOpenLegacyProject,
        onCreateChapter = { showCreateChapter = true },
        onCreateScenePlan = { chapterId ->
            plansByChapter[chapterId]?.let { editingPlan = it } ?: run { waitingForPlanAfter = Long.MIN_VALUE; waitingForPlanChapter = chapterId; vm.generateScenePlan(projectId, chapterId) }
        },
        onResumeTask = { chapterId -> checkpointByChapter[chapterId]?.let { vm.startWorkspaceChapter(projectId, chapterId, it.runId) } },
        onReview = { reviewId -> runs.forEach { run -> run.candidates.firstOrNull { "${run.id}:${it.id}" == reviewId }?.let { reviewingCandidate = run.id to it.id } } },
        onEditPreferences = { showPreferences = true }, onOpenStoryNotes = { showStoryNotes = true },
        onImportManuscript = { chooseManuscript.launch(arrayOf("text/*", "application/octet-stream")) },
        onPauseTask = { vm.pauseWritingTask(projectId, it) }, onCancelTask = { vm.cancelWritingTask(projectId, it) },
        onStartChapter = { vm.startWorkspaceChapter(projectId, it) },
        onRegenerateScenePlan = { chapterId ->
            waitingForPlanAfter = plansByChapter[chapterId]?.updatedAt ?: Long.MIN_VALUE
            waitingForPlanChapter = chapterId
            vm.generateScenePlan(projectId, chapterId)
        },
        onPreviewSavedScenes = { chapterId ->
            checkpointByChapter[chapterId]?.takeIf { it.completedScenes.isNotEmpty() }?.let {
                savedScenePreview = null
                savedSceneError = null
                savedSceneKey = chapterId to it.runId
            }
        },
        message = loadError ?: if (current == null) tx(language, "正在读取创作工作台…", "Loading writing workbench…") else statusMessage.takeIf { it.isNotBlank() },
    )
    if (showPreferences && current != null) WritingPreferencesDialog(language, current.workspace, current.profiles,
        onDismiss = { showPreferences = false }, onSave = { vm.saveWritingWorkspace(projectId, it); localRevision++ })
    if (showStoryNotes && current != null) StoryNotesDialog(language, current.workspace, chapters, characters,
        onDismiss = { showStoryNotes = false }, onSave = { vm.saveWritingWorkspace(projectId, it); localRevision++ }, sourceStatuses = current.storyNoteStatuses)
    editingPlan?.let { plan -> ScenePlanEditorDialog(language, plan, onDismiss = { editingPlan = null }, onSave = { vm.saveScenePlan(it); localRevision++ }) }
    reviewingCandidate?.let { (runId, candidateId) -> ChapterCandidateReviewDialog(vm, projectId, runId, candidateId,
        onDismiss = { reviewingCandidate = null }, onAdopted = { localRevision++ }) }
    if (savedSceneKey != null) SavedScenePreviewDialog(language, savedScenePreview, savedSceneLoading,
        savedSceneError, onDismiss = { savedSceneKey = null; savedScenePreview = null })
    if (showCreateChapter) AlertDialog(
        onDismissRequest = { if (!createBusy) showCreateChapter = false }, title = { Text(tx(language, "新建章节", "New chapter")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(creatingTitle, { creatingTitle = it }, label = { Text(tx(language, "章节标题", "Chapter title")) }, singleLine = true, enabled = !createBusy)
            OutlinedTextField(creatingGoal, { creatingGoal = it }, label = { Text(tx(language, "章节目标", "Chapter goal")) }, maxLines = 3, enabled = !createBusy)
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !createBusy && creatingTitle.isNotBlank(), onClick = {
            val title = creatingTitle.trim()
            val goal = creatingGoal.trim().ifBlank { null }
            createBusy = true
            scope.launch {
                try {
                    val chapter = withContext(Dispatchers.IO) { vm.addChapter(projectId, title, goal) }
                    creatingTitle = ""; creatingGoal = ""; showCreateChapter = false
                    onOpenChapter(chapter.id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { loadError = failure.message }
                finally { createBusy = false }
            }
        }) { Text(tx(language, "创建", "Create")) } },
        dismissButton = { TextButton(enabled = !createBusy, onClick = { showCreateChapter = false }) { Text(tx(language, "取消", "Cancel")) } },
    )
    if (importBusy || importPreview != null || importError != null) AlertDialog(
        onDismissRequest = { if (!importBusy) { importPreview = null; importError = null } },
        title = { Text(tx(language, "书稿导入预览", "Manuscript import preview")) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (importBusy) item { CircularProgressIndicator() }
                importError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                importPreview?.let { preview ->
                    item { Text(tx(language, "识别 ${preview.chapters.size} 章，共 ${preview.totalCharacters} 字符。确认后追加到本项目，不覆盖已有章节。", "${preview.chapters.size} chapters, ${preview.totalCharacters} characters. Import appends chapters without overwriting existing ones.")) }
                    items(preview.chapters) { chapter -> Column { Text(chapter.title, style = MaterialTheme.typography.titleSmall); Text(chapter.body.take(180), style = MaterialTheme.typography.bodySmall) } }
                }
            }
        },
        confirmButton = { TextButton(enabled = !importBusy && importPreview != null, onClick = {
            val preview = importPreview ?: return@TextButton
            importBusy = true; importError = null
            scope.launch {
                try { withContext(Dispatchers.IO) { vm.importManuscript(projectId, preview) }; importPreview = null; localRevision++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { importError = failure.message }
                finally { importBusy = false }
            }
        }) { Text(tx(language, "确认导入", "Import")) } },
        dismissButton = { TextButton(enabled = !importBusy, onClick = { importPreview = null; importError = null }) { Text(tx(language, "取消", "Cancel")) } },
    )
}

/** Read-only escape hatch for interrupted/stale checkpoints. It exposes no adoption or AI action. */
@Composable
private fun SavedScenePreviewDialog(
    language: String,
    preview: SavedScenePreview?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tx(language, "未采用的场景草稿", "Unadopted scene draft"), style = MaterialTheme.typography.titleMedium)
                        preview?.let { Text(it.title, style = MaterialTheme.typography.labelMedium) }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tx(language, "关闭草稿预览", "Close draft preview")) }
                }
                Text(tx(language, "来源可能已过期；仅供查看，正式正文不会被改动。", "The source may be stale. This preview is read-only and does not change the chapter."), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        items(chapterReviewTextChunks(error)) { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    preview != null -> {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(tx(language, "已保存草稿", "Saved draft")) })
                            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(tx(language, "当前正文对照", "Current chapter")) })
                        }
                        val chunks = if (selectedTab == 0) preview.draftChunks else preview.baselineChunks
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { Text(
                                if (selectedTab == 0) tx(language, "已保存 ${preview.completedSceneCount} 个场景，内容保持生成时原文。", "${preview.completedSceneCount} saved scenes, shown exactly as generated.")
                                else tx(language, "当前正式正文或草稿，仅用于对照；不代表生成时的旧来源。", "Current official text or draft for reference; this is not the original generation source."),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ) }
                            if (chunks.isEmpty()) item { Text(tx(language, "暂无正文。", "No text available.")) }
                            items(chunks.size, key = { "preview-$selectedTab-$it" }) { index -> Text(chunks[index], style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                    else -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text(tx(language, "正在读取已保存场景…", "Loading saved scenes…")) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(tx(language, "关闭", "Close")) }
            }
        }
    }
}

private data class CandidateReviewPayload(val run: GenerationRun, val candidate: CandidateChapter, val baseline: String)

/** Exact-run review shared by workbench and editor. Source/candidate CAS lives in the ViewModel. */
@Composable
fun ChapterCandidateReviewDialog(
    vm: AppViewModel,
    projectId: String,
    runId: String,
    candidateId: String,
    onDismiss: () -> Unit,
    onAdopted: () -> Unit = {},
    beforeAdopt: suspend () -> Boolean = { true },
) {
    val language by vm.uiLanguage.collectAsState()
    val generationRevision by vm.generationRunRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var payload by remember(projectId, runId, candidateId) { mutableStateOf<CandidateReviewPayload?>(null) }
    var error by remember(projectId, runId, candidateId) { mutableStateOf<String?>(null) }
    var busy by remember(projectId, runId, candidateId) { mutableStateOf(false) }
    var reload by remember(projectId, runId, candidateId) { mutableStateOf(0) }
    LaunchedEffect(projectId, runId, candidateId, generationRevision, reload, busy) {
        if (busy) return@LaunchedEffect
        try {
            payload = withContext(Dispatchers.IO) {
                val run = vm.chapterGenerationRun(projectId, runId) ?: throw IllegalStateException("候选稿已不存在")
                check(run.projectId == projectId && run.status == GenerationRun.STATUS_COMPLETED) { "候选稿已被处理或状态已改变" }
                val candidate = run.candidates.firstOrNull { it.id == candidateId && it.status == CandidateChapter.STATUS_COMPLETED } ?: throw IllegalStateException("候选稿尚未完整生成")
                CandidateReviewPayload(run, candidate, vm.reviewBaselineText(run))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { payload = null; error = failure.message }
    }
    val current = payload
    if (current == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(tx(language, "读取候选稿", "Load candidate")) },
            text = { if (error == null) CircularProgressIndicator() else Text(error.orEmpty()) },
            confirmButton = { TextButton(onClick = { error = null; reload++ }) { Text(tx(language, "重试", "Retry")) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(tx(language, "关闭", "Close")) } })
        return
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { error = failure.message ?: tx(language, "操作失败，候选稿已保留。", "Operation failed; the candidate was kept.") }
            finally { busy = false }
        }
    }
    ChapterReviewWorkbench(language, current.run.spec.title, current.baseline, current.candidate.body,
        findings = chapterCandidateFindings(current.run, current.candidate, language), isBusy = busy,
        canAdopt = current.candidate.qualityReport?.let { it.completedStream && !it.blocking } == true,
        errorMessage = error, onDismiss = onDismiss,
        onAdopt = { text -> perform {
            check(beforeAdopt()) { tx(language, "编辑内容已变化，采用已阻止；请先保存编辑并重新生成候选稿。", "Editor content changed; save your edits and regenerate the candidate.") }
            val result = withContext(Dispatchers.IO) { vm.adoptReviewedCandidate(projectId, runId, candidateId, current.candidate.body, text) }
            when (result) {
                is CandidateAdoptionResult.Adopted -> { onAdopted(); onDismiss() }
                is CandidateAdoptionResult.SourceChanged -> throw IllegalStateException(tx(language, "章节或上下文已变化，候选稿已保留，未覆盖正文。", "The chapter or context changed. Candidate kept; chapter unchanged."))
                is CandidateAdoptionResult.Unavailable -> throw IllegalStateException(tx(language, "候选稿不能采用：${result.reason}", "Candidate unavailable: ${result.reason}"))
            }
        } },
        onReject = { perform { check(withContext(Dispatchers.IO) { vm.rejectChapterGeneration(projectId, runId) }) { tx(language, "候选稿已被处理或拒绝失败。", "Candidate already resolved or rejection failed.") }; onDismiss() } },
        onRequestRevision = { instruction -> perform {
            val run = vm.reviseChapterCandidate(projectId, runId, candidateId, instruction)
            val revised = run.candidates.firstOrNull { it.id == candidateId } ?: throw IllegalStateException("修订稿已不存在")
            payload = CandidateReviewPayload(run, revised, current.baseline)
            reload++
        } },
    )
}

internal fun chapterCandidateFindings(run: GenerationRun, candidate: CandidateChapter, language: String = "zh"): List<ChapterReviewFinding> =
    candidate.qualityReport?.findings.orEmpty().mapIndexed { index, finding -> ChapterReviewFinding(
        "quality-$index", tx(language, "正文完整性检查", "Text completeness check"), finding.message,
        severity = when (finding.severity) { "error" -> ChapterReviewFindingSeverity.CONFLICT; "info" -> ChapterReviewFindingSeverity.INFO; else -> ChapterReviewFindingSeverity.SUGGESTION },
    ) } + run.sceneReviewFindings.mapIndexed { index, finding -> ChapterReviewFinding(
        "scene-$index", when (finding.category) {
            com.example.novelseek_ultra.data.writing.ReviewCategory.FACT_CONFLICT -> tx(language, "事实冲突", "Fact conflict")
            com.example.novelseek_ultra.data.writing.ReviewCategory.KNOWLEDGE_LEAK -> tx(language, "角色知情范围", "Character knowledge")
            com.example.novelseek_ultra.data.writing.ReviewCategory.MISSING_EVENT -> tx(language, "缺少必需事件", "Missing required event")
            com.example.novelseek_ultra.data.writing.ReviewCategory.STYLE -> tx(language, "文风", "Style")
            com.example.novelseek_ultra.data.writing.ReviewCategory.PACING -> tx(language, "节奏", "Pacing")
            else -> tx(language, "审稿建议", "Review suggestion")
        }, listOf(finding.explanation, finding.constraint, finding.suggestion).filter { it.isNotBlank() }.joinToString("\n"), finding.quote,
        if (finding.severity == ReviewSeverity.BLOCKING) ChapterReviewFindingSeverity.CONFLICT else ChapterReviewFindingSeverity.SUGGESTION,
    ) }

private fun writingUsageSummary(language: String, usage: WritingUsageEntry): String {
    val input = usage.promptTokens?.toString() ?: tx(language, "未知", "unknown")
    val output = usage.completionTokens?.toString() ?: tx(language, "未知", "unknown")
    return tx(language, "${usage.requestCount} 次请求 · 输入 $input / 输出 $output Tokens", "${usage.requestCount} requests · input $input / output $output tokens")
}

private suspend fun readBoundedManuscript(resolver: ContentResolver, uri: Uri): String {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) require(cursor.getLong(sizeColumn) <= MAX_MANUSCRIPT_BYTES) { "文件超过 10 MiB，请分卷导入" }
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0) {
                val name = cursor.getString(nameColumn).orEmpty().lowercase()
                require(name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")) { "请选择 TXT 或 Markdown 书稿" }
            }
        }
    }
    val source = resolver.openInputStream(uri) ?: error("无法打开书稿文件")
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
        InputStreamReader(LimitedManuscriptStream(source), decoder).use { reader ->
            val buffer = CharArray(8_192)
            val text = StringBuilder()
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                require(text.length.toLong() + count <= ManuscriptImporter.MAX_CHARACTERS) { "书稿超过 500 万字符，请分卷导入" }
                text.append(buffer, 0, count)
            }
            require(text.indexOf("\u0000") < 0) { "文件不是可读取的文本书稿" }
            return text.toString()
        }
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("书稿不是 UTF-8 文本，请以 UTF-8 编码另存后导入", failure)
    }
}

private const val MAX_MANUSCRIPT_BYTES = 10L * 1_024 * 1_024
private class LimitedManuscriptStream(source: InputStream) : FilterInputStream(source) {
    private var count = 0L
    override fun read(): Int = `in`.read().also { if (it >= 0) { count++; checkLimit() } }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, minOf(length.toLong(), MAX_MANUSCRIPT_BYTES - count + 1).toInt()).also {
        if (it > 0) { count += it; checkLimit() }
    }
    private fun checkLimit() { require(count <= MAX_MANUSCRIPT_BYTES) { "文件超过 10 MiB，请分卷导入" } }
}
