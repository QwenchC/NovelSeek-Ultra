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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
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
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.EditProjectDialog
import com.example.novelseek_ultra.util.tx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongNovelsHomeScreen(vm: AppViewModel, onOpen: (projectId: String) -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val projects by vm.projects.collectAsState()
    val state by vm.state.collectAsState()
    val longProjects = remember(projects, state) { projects.filter { vm.novelType(it.id) == "long" } }
    var showCreate by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<Project?>(null) }
    var deletingProject by remember { mutableStateOf<Project?>(null) }

    val lazyListState = rememberLazyListState()
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
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            AppTopBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tx(lang, "长篇小说", "Long Novels"), style = MaterialTheme.typography.titleLarge)
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
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(tx(lang, "新建长篇", "New Long Novel")) },
                )
            }
        },
    ) { padding ->
        if (longProjects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tx(lang, "还没有长篇项目", "No long novels yet"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        tx(lang, "以「剧情弧线」推进的长篇连载创作模式。",
                            "Long-form serialized mode driven by story arcs."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(longProjects, key = { it.id }) { p ->
                    LongProjectCard(p, lang, vm,
                        onOpen = { onOpen(p.id) },
                        onRename = { renamingProject = p },
                        onDelete = { deletingProject = p })
                }
            }
        }
    }

    renamingProject?.let { p ->
        EditProjectDialog(
            lang = lang,
            initialTitle = p.title,
            initialDescription = p.description.orEmpty(),
            onConfirm = { title, desc ->
                vm.updateProject(p.id) { it.copy(title = title, description = desc.ifBlank { null }) }
            },
            onDismiss = { renamingProject = null },
        )
    }

    deletingProject?.let { p ->
        ConfirmDialog(
            title = tx(lang, "删除项目", "Delete Project"),
            message = tx(lang,
                "确定要删除「${p.title}」吗？该项目下的全部章节、角色、大纲、弧线将一并移除，且无法恢复。",
                "Delete \"${p.title}\"? All chapters, characters, outline and arcs will be permanently removed."),
            confirmLabel = tx(lang, "删除", "Delete"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.deleteProject(p.id) },
            onDismiss = { deletingProject = null },
        )
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        var genre by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(tx(lang, "新建长篇", "New Long Novel")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text(tx(lang, "标题", "Title")) }, singleLine = true)
                    OutlinedTextField(genre, { genre = it }, label = { Text(tx(lang, "题材", "Genre")) }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text(tx(lang, "简介", "Description")) }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = vm.createProject(title, genre, desc, isLong = true)
                    showCreate = false
                    onOpen(id)
                }, enabled = title.isNotBlank()) { Text(tx(lang, "创建", "Create")) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(tx(lang, "取消", "Cancel")) } },
        )
    }
}

@Composable
private fun LongProjectCard(
    p: Project,
    lang: String,
    vm: AppViewModel,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val arcs = vm.plotArcs(p.id)
    val chapters = vm.chapters(p.id)
    val coverImages = remember(p.id) { vm.getCoverImages(p.id) }
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
            // Content column drives the Box (and card) height. Rename/Delete are a horizontal
            // pair in the title row's top-right, mirroring the short-novel ProjectCard.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (coverBitmap != null) 80.dp else 0.dp)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onRename) {
                        Icon(Icons.Outlined.DriveFileRenameOutline,
                            contentDescription = tx(lang, "重命名", "Rename"))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"))
                    }
                }
                if (!p.genre.isNullOrBlank()) Text(p.genre, style = MaterialTheme.typography.labelMedium)
                if (!p.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        p.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${chapters.size} ${tx(lang, "章", "chapters")} · ${p.current_word_count} ${tx(lang, "字", "words")} · ${arcs.size} ${tx(lang, "弧线", "arcs")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Cover image fills the Box height (determined by the Column above)
            coverBitmap?.let { bmp ->
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

