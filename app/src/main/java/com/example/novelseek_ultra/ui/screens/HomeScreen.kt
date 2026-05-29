package com.example.novelseek_ultra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.RenameDialog
import com.example.novelseek_ultra.util.tx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onOpen: (projectId: String) -> Unit = {}) {
    val lang by vm.uiLanguage.collectAsState()
    val allProjects by vm.projects.collectAsState()
    val state by vm.state.collectAsState()
    val projects = remember(allProjects, state) {
        allProjects.filter { vm.novelType(it.id) != "long" }
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<Project?>(null) }
    var deletingProject by remember { mutableStateOf<Project?>(null) }

    val lazyListState = rememberLazyListState()
    // Same FAB-hide rule as project pages: only hide when scrolled past the top AND the bottom
    // item is fully visible. "At-top" wins so short project lists keep the FAB visible.
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

    Scaffold(
        topBar = {
            AppTopBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Book, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tx(lang, "短篇小说", "Short Stories"), style = MaterialTheme.typography.titleLarge)
                }
            })
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !shouldHideFab,
                enter = EnterTransition.None,
                exit = fadeOut(tween(durationMillis = 1000)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(tx(lang, "新建项目", "New Project")) },
                    modifier = Modifier.offset(y = 24.dp),
                )
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyState(lang, padding)
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(projects, key = { it.id }) { p ->
                    ProjectCard(
                        p, lang, vm,
                        onOpen = { onOpen(p.id) },
                        onRename = { renamingProject = p },
                        onDelete = { deletingProject = p },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            lang = lang,
            onDismiss = { showCreateDialog = false },
            onCreate = { title, genre, desc ->
                vm.createProject(title, genre, desc)
                showCreateDialog = false
            },
        )
    }

    renamingProject?.let { p ->
        RenameDialog(
            title = tx(lang, "重命名项目", "Rename Project"),
            label = tx(lang, "项目名称", "Project name"),
            initialValue = p.title,
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { newTitle -> vm.updateProject(p.id) { it.copy(title = newTitle) } },
            onDismiss = { renamingProject = null },
        )
    }

    deletingProject?.let { p ->
        ConfirmDialog(
            title = tx(lang, "删除项目", "Delete Project"),
            message = tx(lang,
                "确定要删除「${p.title}」吗？该项目下的全部章节、角色、大纲将一并移除，且无法恢复。",
                "Delete \"${p.title}\"? All its chapters, characters and outline will be removed permanently."),
            confirmLabel = tx(lang, "删除", "Delete"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.deleteProject(p.id) },
            onDismiss = { deletingProject = null },
        )
    }
}

@Composable
private fun EmptyState(lang: String, padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.MenuBook,
            contentDescription = null,
            modifier = Modifier.height(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            tx(lang, "还没有项目", "No projects yet"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            tx(
                lang,
                "点击右下角「新建项目」开始创作，或在「设置」中导入电脑端备份。",
                "Tap the New Project button to start, or import a PC backup from Settings.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectCard(
    p: Project,
    lang: String,
    vm: AppViewModel,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val coverImages = remember(p.id) { vm.getCoverImages(p.id) }
    val chapterCount = remember(p.id) { vm.chapters(p.id).size }
    val defaultId = p.default_cover_id
    val coverBitmap = remember(coverImages, defaultId) {
        val item = if (defaultId != null) coverImages.find { it.id == defaultId } else coverImages.firstOrNull()
        item?.imageBase64?.let { base64ToBitmap(it) }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Content column drives the Box (and card) height
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (coverBitmap != null) 80.dp else 0.dp)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRename) {
                        Icon(Icons.Outlined.DriveFileRenameOutline,
                            contentDescription = tx(lang, "重命名", "Rename"))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"))
                    }
                }
                if (!p.genre.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(p.genre, style = MaterialTheme.typography.labelMedium)
                }
                if (!p.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "$chapterCount ${tx(lang, "章", "chapters")} · ${p.current_word_count} ${tx(lang, "字", "words")} · ${p.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Cover image fills the Box height (determined by the Column above)
            coverBitmap?.let { bmp ->
                // matchParentSize() Box gives a bounded height context, then
                // the image uses width(80.dp) + fillMaxHeight() inside it
                Box(modifier = Modifier.matchParentSize()) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(80.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    lang: String,
    onDismiss: () -> Unit,
    onCreate: (title: String, genre: String?, description: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "新建项目", "New Project")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tx(lang, "标题", "Title")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text(tx(lang, "题材（可选）", "Genre (optional)")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(tx(lang, "简介（可选）", "Description (optional)")) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, genre, description) },
                enabled = title.isNotBlank(),
            ) { Text(tx(lang, "创建", "Create")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) }
        },
    )
}