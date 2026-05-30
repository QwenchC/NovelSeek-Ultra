package com.example.novelseek_ultra.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.SnapshotMeta
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.RenameDialog
import com.example.novelseek_ultra.util.tx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistoryScreen(
    vm: AppViewModel,
    projectId: String,
    onBack: () -> Unit,
) {
    val lang by vm.uiLanguage.collectAsState()
    // Recompose the snapshot list whenever the store's snapshot revision bumps (create/delete/restore).
    val rev by vm.snapshotRevision.collectAsState()
    val state by vm.state.collectAsState()

    val snapshots = remember(rev, projectId) { vm.listSnapshots(projectId) }
    val staleCount = remember(state, projectId) { vm.kbStaleCount(projectId) }
    val kbEnabled = remember(state) { vm.knowledgeBaseEnabled() }

    var saving by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SnapshotMeta?>(null) }
    var deleting by remember { mutableStateOf<SnapshotMeta?>(null) }
    var restoring by remember { mutableStateOf<SnapshotMeta?>(null) }
    var rebuilding by remember { mutableStateOf(false) }
    var rebuildProgress by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text(tx(lang, "版本历史", "Version History"), style = MaterialTheme.typography.titleLarge) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSaveDialog = true },
                icon = {
                    if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Restore, contentDescription = null)
                },
                text = { Text(tx(lang, "保存当前版本", "Save version")) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // KB stale banner — only meaningful when the knowledge base is on.
            if (kbEnabled && staleCount > 0) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                tx(lang, "知识库有 $staleCount 章与当前版本不一致", "$staleCount chapters' KB are out of sync"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tx(lang, "回退后向量索引未刷新，可一键重建（仅重建变化章节）。",
                                    "Vectors weren't refreshed after restore. Rebuild only the changed chapters."),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                enabled = !rebuilding,
                                onClick = {
                                    rebuilding = true
                                    vm.rebuildStaleKb(
                                        projectId,
                                        onProgress = { d, t -> rebuildProgress = "$d/$t" },
                                        onDone = { rebuilding = false; rebuildProgress = "" },
                                    )
                                },
                            ) {
                                if (rebuilding) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tx(lang, "重建中 $rebuildProgress", "Rebuilding $rebuildProgress"))
                                } else {
                                    Text(tx(lang, "重建知识库", "Rebuild KB"))
                                }
                            }
                        }
                    }
                }
            }

            if (snapshots.isEmpty()) {
                item {
                    Text(
                        tx(lang, "暂无历史版本。点击下方按钮保存当前版本。",
                            "No versions yet. Tap the button below to save the current state."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            items(snapshots, key = { it.id }) { snap ->
                SnapshotCard(
                    snap = snap,
                    lang = lang,
                    onRestore = { restoring = snap },
                    onRename = { renaming = snap },
                    onDelete = { deleting = snap },
                )
            }
        }
    }

    if (showSaveDialog) {
        RenameDialog(
            title = tx(lang, "保存当前版本", "Save version"),
            label = tx(lang, "备注（可选）", "Label (optional)"),
            initialValue = "",
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { label ->
                saving = true
                vm.saveSnapshot(projectId, label) { saving = false }
            },
            onDismiss = { showSaveDialog = false },
        )
    }

    renaming?.let { snap ->
        RenameDialog(
            title = tx(lang, "重命名版本", "Rename version"),
            label = tx(lang, "备注", "Label"),
            initialValue = snap.label,
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.renameSnapshot(projectId, snap.id, it) },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { snap ->
        ConfirmDialog(
            title = tx(lang, "删除版本", "Delete version"),
            message = tx(lang, "确定删除该历史版本？此操作不可撤销。", "Delete this version? This cannot be undone."),
            confirmLabel = tx(lang, "删除", "Delete"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.deleteSnapshot(projectId, snap.id) },
            onDismiss = { deleting = null },
        )
    }

    restoring?.let { snap ->
        ConfirmDialog(
            title = tx(lang, "回退到此版本", "Restore this version"),
            message = tx(lang,
                "当前内容会被该版本覆盖（回退前会自动备份当前状态，可再次回退撤销）。继续？",
                "Current content will be overwritten (the current state is auto-backed-up first, so this is undoable). Continue?"),
            confirmLabel = tx(lang, "回退", "Restore"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            destructive = false,
            onConfirm = { vm.restoreSnapshot(projectId, snap.id) },
            onDismiss = { restoring = null },
        )
    }
}

@Composable
private fun SnapshotCard(
    snap: SnapshotMeta,
    lang: String,
    onRestore: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snap.label.ifBlank { tx(lang, "未命名版本", "Untitled version") },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TriggerTag(snap.trigger, lang)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                formatTime(snap.createdAt) +
                    "  ·  ${snap.chapterCount} ${tx(lang, "章", "ch")}  ·  ${snap.wordCount} ${tx(lang, "字", "words")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onRestore) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tx(lang, "回退", "Restore"))
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = tx(lang, "重命名", "Rename"))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"))
                }
            }
        }
    }
}

@Composable
private fun TriggerTag(trigger: String, lang: String) {
    val (text, color) = when (trigger) {
        SnapshotMeta.TRIGGER_MANUAL -> tx(lang, "手动", "Manual") to MaterialTheme.colorScheme.primary
        SnapshotMeta.TRIGGER_PRE_AI -> tx(lang, "AI前", "Pre-AI") to MaterialTheme.colorScheme.tertiary
        else -> tx(lang, "自动", "Auto") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

/** ISO `yyyy-MM-dd'T'HH:mm:ss'Z'` → `MM-dd HH:mm` for compact display. */
private fun formatTime(iso: String): String = runCatching {
    val date = iso.substringBefore('T')
    val time = iso.substringAfter('T').substringBefore('Z')
    val md = date.substringAfter('-')           // MM-dd
    val hm = time.substring(0, 5)               // HH:mm
    "$md $hm"
}.getOrDefault(iso)
