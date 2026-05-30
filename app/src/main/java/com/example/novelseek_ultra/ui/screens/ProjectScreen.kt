package com.example.novelseek_ultra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.novelseek_ultra.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.CoverImageConfig
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.RenameDialog
import com.example.novelseek_ultra.util.tx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    vm: AppViewModel,
    projectId: String,
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    onOpenOutline: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val project = remember(state, projectId) { vm.project(projectId) }
    val chapters = remember(state) { vm.chapters(projectId) }
    var showCreateChapter by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var renamingChapter by remember { mutableStateOf<Chapter?>(null) }
    var deletingChapter by remember { mutableStateOf<Chapter?>(null) }
    var showImportFromOutline by remember { mutableStateOf(false) }

    // Outline preview (stats-box tap) + cover generation + batch promo — mirrors LongNovelScreen.
    var showOutlinePreview by remember { mutableStateOf(false) }
    var showCoverDialog by remember { mutableStateOf(false) }
    var coverImages by remember(projectId) { mutableStateOf(vm.getCoverImages(projectId)) }
    var coverIndex by remember { mutableStateOf(0) }
    var coverGenerating by remember { mutableStateOf(false) }
    var coverError by remember { mutableStateOf<String?>(null) }
    var coverConfig by remember { mutableStateOf(CoverImageConfig()) }
    var showBatchPromoConfig by remember { mutableStateOf(false) }
    var batchPromoRunning by remember { mutableStateOf(false) }
    var batchPromoProgress by remember { mutableStateOf("") }

    val lazyListState = rememberLazyListState()
    // Hide the FAB only when the list is BOTH scrolled past the top AND the last item is fully
    // visible. Brand-new projects whose content doesn't fill a screen can't scroll backward at
    // all, so the FAB stays visible — "already-at-top" wins over "also-at-bottom".
    val shouldHideFab by remember {
        derivedStateOf {
            val info = lazyListState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty() || info.totalItemsCount == 0) return@derivedStateOf false
            val last = visible.last()
            val atBottom = last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset
            atBottom && lazyListState.canScrollBackward
        }
    }

    if (project == null) {
        Text(tx(lang, "项目不存在", "Project not found"), modifier = Modifier.padding(16.dp))
        return
    }

    Scaffold(
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text(project.title, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !shouldHideFab,
                enter = EnterTransition.None,  // instant return when user scrolls up
                exit = fadeOut(tween(durationMillis = 1000)),  // 1s fade, then fully removed
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateChapter = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(tx(lang, "新建章节", "New Chapter")) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                // Full-width (fillMaxWidth) so the card spans the list even when the description
                // is short — otherwise the Card shrink-wraps to its narrow text content.
                // Tappable → opens the markdown outline preview, same as LongNovelScreen.
                Card(modifier = Modifier.fillMaxWidth().clickable { showOutlinePreview = true }) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (!project.genre.isNullOrBlank()) {
                            Text(project.genre, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (!project.description.isNullOrBlank()) {
                            Text(
                                project.description,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "${chapters.size} ${tx(lang, "章", "chapters")} · ${project.current_word_count} ${tx(lang, "字", "words")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tx(lang, "点击查看大纲", "Tap to view outline"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                // Horizontally scrollable so the 5 buttons (Outline/Characters/Export/Cover/
                // Batch Promo) never get squeezed, matching the LongNovelScreen action row.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onOpenOutline) {
                        Icon(Icons.Outlined.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "大纲", "Outline"), maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(onClick = onOpenCharacters) {
                        Icon(Icons.Outlined.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "角色", "Characters"), maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(onClick = onOpenExport) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "导出", "Export"), maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(onClick = onOpenHistory) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "版本", "Versions"), maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        onClick = { showCoverDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "封面", "Cover"), maxLines = 1, softWrap = false)
                    }
                    // Batch promo: generate chapter header images for chapters missing one.
                    FilledTonalButton(
                        enabled = !batchPromoRunning,
                        onClick = { showBatchPromoConfig = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        if (batchPromoRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (batchPromoRunning) batchPromoProgress.ifBlank { tx(lang, "生成中…", "Generating…") }
                            else tx(lang, "批量推文", "Batch Promo"),
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        tx(lang, "章节列表", "Chapters"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showImportFromOutline = true }) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = null,
                            modifier = Modifier.then(Modifier.padding(end = 4.dp)))
                        Text(tx(lang, "从大纲导入", "Import from Outline"),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (chapters.isEmpty()) {
                item {
                    Text(
                        tx(lang, "还没有章节，点击右下角新建。", "No chapters yet — tap the button to add one."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(chapters, key = { it.id }) { ch ->
                    ChapterRow(
                        ch, lang,
                        onClick = { onOpenChapter(ch.id) },
                        onRename = { renamingChapter = ch },
                        onDelete = { deletingChapter = ch },
                    )
                }
            }
        }
    }

    if (showCreateChapter) {
        CreateChapterDialog(
            lang = lang,
            onDismiss = { showCreateChapter = false },
            onCreate = { title, goal ->
                vm.addChapter(projectId, title, goal)
                showCreateChapter = false
            },
        )
    }

    if (editing) {
        EditProjectDialog(
            lang = lang,
            initialTitle = project.title,
            initialGenre = project.genre.orEmpty(),
            initialDescription = project.description.orEmpty(),
            onDismiss = { editing = false },
            onSave = { t, g, d ->
                vm.updateProject(projectId) { it.copy(title = t, genre = g.ifBlank { null }, description = d.ifBlank { null }) }
                editing = false
            },
        )
    }

    renamingChapter?.let { ch ->
        RenameDialog(
            title = tx(lang, "重命名章节", "Rename Chapter"),
            label = tx(lang, "章节标题", "Chapter title"),
            initialValue = ch.title,
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { newTitle ->
                vm.upsertChapter(projectId, ch.copy(title = newTitle))
            },
            onDismiss = { renamingChapter = null },
        )
    }

    deletingChapter?.let { ch ->
        ConfirmDialog(
            title = tx(lang, "删除章节", "Delete Chapter"),
            message = tx(lang,
                "确定要删除「${ch.order_index}. ${ch.title}」吗？章节正文和草稿将一并永久删除。",
                "Delete \"${ch.order_index}. ${ch.title}\"? Its final and draft content will be permanently removed."),
            confirmLabel = tx(lang, "删除", "Delete"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.deleteChapter(projectId, ch.id) },
            onDismiss = { deletingChapter = null },
        )
    }

    if (showImportFromOutline) {
        val outlineText = remember { vm.outlineText(projectId) }
        val parsedChapters = remember(outlineText) { parseShortChapters(outlineText) }
        if (parsedChapters.isEmpty()) {
            AlertDialog(
                onDismissRequest = { showImportFromOutline = false },
                title = { Text(tx(lang, "无法导入", "Nothing to Import")) },
                text = {
                    Text(
                        tx(lang,
                            "大纲中未检测到章节结构。请先在「大纲」页生成包含「章节大纲」部分的大纲，并确保每章使用「### 第N章：标题」格式。",
                            "No chapter structure found in the outline. Generate an outline with a '## Chapter Outline' section first, making sure each chapter uses the '### Chapter N: Title' format."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showImportFromOutline = false }) { Text(tx(lang, "好的", "OK")) }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showImportFromOutline = false },
                title = { Text(tx(lang, "从大纲导入章节", "Import Chapters from Outline")) },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tx(lang,
                                "将导入 ${parsedChapters.size} 个空白章节（情节和关键场景已填入章节目标）。${if (chapters.isNotEmpty()) "已有 ${chapters.size} 个章节，新章节将追加到末尾。" else ""}",
                                "Will import ${parsedChapters.size} blank chapters (plot & key scene stored as chapter goal). ${if (chapters.isNotEmpty()) "${chapters.size} existing chapters — new ones appended." else ""}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        parsedChapters.forEachIndexed { i, ch ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "${chapters.size + i + 1}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(ch.title, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        parsedChapters.forEach { pc ->
                            val goal = buildString {
                                if (pc.plot.isNotBlank()) append(pc.plot)
                                if (pc.keyScene.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append(tx(lang, "关键场景：", "Key scene: "))
                                    append(pc.keyScene)
                                }
                            }.ifBlank { null }
                            vm.addChapter(projectId, pc.title, goal)
                        }
                        showImportFromOutline = false
                    }) {
                        Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "一键导入", "Import"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportFromOutline = false }) { Text(tx(lang, "取消", "Cancel")) }
                },
            )
        }
    }

    // ── Outline preview (stats-box tap) ──────────────────────────────────────────
    if (showOutlinePreview) {
        OutlinePreviewDialog(
            lang = lang,
            outline = vm.outlineText(projectId),
            onOpenOutlinePage = {
                showOutlinePreview = false
                onOpenOutline()
            },
            onDismiss = { showOutlinePreview = false },
        )
    }

    // ── Cover dialog ─────────────────────────────────────────────────────────────
    if (showCoverDialog) {
        CoverDialog(
            lang = lang,
            projectId = projectId,
            vm = vm,
            images = coverImages,
            index = coverIndex,
            onIndexChange = { coverIndex = it },
            generating = coverGenerating,
            error = coverError,
            config = coverConfig,
            onConfigChange = { coverConfig = it },
            onGenerate = { charactersInfo ->
                coverGenerating = true
                coverError = null
                vm.generateProjectCover(
                    projectId = projectId,
                    style = coverConfig.style.ifBlank { null },
                    model = coverConfig.model,
                    width = coverConfig.width,
                    height = coverConfig.height,
                    existingCount = coverImages.size,
                    charactersInfo = charactersInfo,
                ) { result, err ->
                    coverGenerating = false
                    if (result != null) {
                        coverImages = coverImages + result
                        coverIndex = coverImages.lastIndex
                    } else {
                        coverError = err
                    }
                }
            },
            onDelete = { id ->
                vm.deleteProjectCover(projectId, id)
                coverImages = coverImages.filter { it.id != id }
                if (coverIndex >= coverImages.size) coverIndex = (coverImages.size - 1).coerceAtLeast(0)
            },
            onSetDefault = { id -> vm.setDefaultCover(projectId, id) },
            onDismiss = { showCoverDialog = false },
        )
    }

    // ── Batch promo config dialog ────────────────────────────────────────────────
    if (showBatchPromoConfig) {
        BatchPromoConfigDialog(
            lang = lang,
            onDismiss = { showBatchPromoConfig = false },
            onConfirm = { style, model, width, height ->
                showBatchPromoConfig = false
                val pending = chapters.filter { vm.getChapterPromo(it.id) == null }
                if (pending.isEmpty()) return@BatchPromoConfigDialog
                batchPromoRunning = true
                val total = pending.size
                fun next(remaining: List<Chapter>, done: Int) {
                    if (remaining.isEmpty()) {
                        batchPromoRunning = false
                        batchPromoProgress = ""
                        return
                    }
                    val chapter = remaining.first()
                    val body = vm.chapterBody(chapter.id)
                    val content = body.final.ifBlank { body.draft }
                    batchPromoProgress = "${done + 1}/$total"
                    if (content.length < 100) {
                        next(remaining.drop(1), done + 1)
                        return
                    }
                    vm.generateChapterPromo(
                        chapterId = chapter.id,
                        chapterTitle = chapter.title,
                        chapterContent = content,
                        style = style.ifBlank { null },
                        model = model,
                        width = width,
                        height = height,
                    ) { _, _ -> next(remaining.drop(1), done + 1) }
                }
                next(pending, 0)
            },
        )
    }
}

@Composable
private fun ChapterRow(
    ch: Chapter,
    lang: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${ch.order_index}. ${ch.title}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!ch.outline_goal.isNullOrBlank()) {
                    Text(
                        ch.outline_goal,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Text(
                    "${ch.word_count} ${tx(lang, "字", "words")} · ${ch.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) { Text(tx(lang, "打开", "Open")) }
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.DriveFileRenameOutline,
                    contentDescription = tx(lang, "重命名", "Rename"))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete,
                    contentDescription = tx(lang, "删除", "Delete"))
            }
        }
    }
}

@Composable
private fun CreateChapterDialog(
    lang: String,
    onDismiss: () -> Unit,
    onCreate: (title: String, goal: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "新建章节", "New Chapter")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text(tx(lang, "章节标题", "Title")) }, singleLine = true)
                OutlinedTextField(value = goal, onValueChange = { goal = it },
                    label = { Text(tx(lang, "本章目标（可选）", "Chapter goal (optional)")) }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, goal.ifBlank { null }) }, enabled = title.isNotBlank()) {
                Text(tx(lang, "创建", "Create"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}

@Composable
private fun EditProjectDialog(
    lang: String,
    initialTitle: String,
    initialGenre: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var genre by remember { mutableStateOf(initialGenre) }
    var desc by remember { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "编辑项目", "Edit Project")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text(tx(lang, "标题", "Title")) }, singleLine = true)
                OutlinedTextField(value = genre, onValueChange = { genre = it },
                    label = { Text(tx(lang, "题材", "Genre")) }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    label = { Text(tx(lang, "简介", "Description")) }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, genre, desc) }, enabled = title.isNotBlank()) {
                Text(tx(lang, "保存", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}
