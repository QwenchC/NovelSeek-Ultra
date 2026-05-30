package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.example.novelseek_ultra.data.model.Container
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.util.tx

private fun typeLabel(type: String, lang: String) = when (type) {
    Container.BY_CHARACTER -> tx(lang, "依角色分块", "By character")
    Container.BY_CHAPTER -> tx(lang, "依章节分块", "By chapter")
    else -> tx(lang, "不分块", "Single")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()

    var openContainerId by remember { mutableStateOf<String?>(null) }
    var openBlockKey by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    val containers = remember(state) { vm.containers(projectId) }
    val openContainer = openContainerId?.let { id -> containers.firstOrNull { it.id == id } }
    // If the open container vanished (deleted), drop back to the list.
    if (openContainerId != null && openContainer == null) { openContainerId = null; openBlockKey = null }

    val handleBack: () -> Unit = {
        when {
            openBlockKey != null -> openBlockKey = null
            openContainerId != null -> openContainerId = null
            else -> onBack()
        }
    }

    val title = when {
        openContainer != null && openBlockKey != null ->
            vm.containerBlocks(projectId, openContainer).firstOrNull { it.first == openBlockKey }?.second
                ?: tx(lang, "分块", "Block")
        openContainer != null -> openContainer.name
        else -> tx(lang, "容器", "Containers")
    }

    Scaffold(
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
            )
        },
        floatingActionButton = {
            if (openContainerId == null) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(tx(lang, "新建容器", "New Container")) },
                )
            }
        },
    ) { padding ->
        val mod = Modifier.fillMaxSize().padding(padding)
        when {
            openContainer != null && openBlockKey != null ->
                BlockChainView(vm, projectId, openContainer, openBlockKey!!, lang, mod)
            openContainer != null ->
                ContainerDetail(vm, projectId, openContainer, lang, mod, onOpenBlock = { openBlockKey = it })
            else ->
                ContainerList(vm, projectId, containers, lang, mod, onOpen = { openContainerId = it })
        }
    }

    if (showCreate) {
        NewContainerDialog(lang = lang, onDismiss = { showCreate = false }, onCreate = { c ->
            vm.createContainer(projectId, c); showCreate = false
        })
    }
}

@Composable
private fun ContainerList(
    vm: AppViewModel, projectId: String, containers: List<Container>, lang: String,
    modifier: Modifier, onOpen: (String) -> Unit,
) {
    if (containers.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tx(lang, "还没有容器，点击右下角新建", "No containers yet — tap + to create"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var deleting by remember { mutableStateOf<Container?>(null) }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        items(containers, key = { it.id }) { c ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(c.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = {}, label = { Text(typeLabel(c.type, lang)) }, enabled = false)
                            if (c.autoUpdatePerChapter) AssistChip(onClick = {}, label = { Text(tx(lang, "按章更新", "Auto")) }, enabled = false)
                            if (c.affectsGeneration) AssistChip(onClick = {}, label = { Text(tx(lang, "影响生成", "Influences")) }, enabled = false)
                        }
                    }
                    IconButton(onClick = { deleting = c }) {
                        Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"))
                    }
                }
            }
        }
    }
    deleting?.let { c ->
        ConfirmDialog(
            title = tx(lang, "删除容器", "Delete container"),
            message = tx(lang, "确定删除容器《${c.name}》及其全部值？", "Delete container \"${c.name}\" and all its values?"),
            confirmLabel = tx(lang, "删除", "Delete"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.deleteContainer(projectId, c.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ContainerDetail(
    vm: AppViewModel, projectId: String, container: Container, lang: String,
    modifier: Modifier, onOpenBlock: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val blocks = remember(state, container.id) { vm.containerBlocks(projectId, container) }
    var updating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tx(lang, "容器信息", "Container info"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("${tx(lang, "类型", "Type")}：${typeLabel(container.type, lang)}（${tx(lang, "不可更改", "fixed")}）",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${tx(lang, "按章更新", "Auto-update per chapter")}：${if (container.autoUpdatePerChapter) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${tx(lang, "影响章节生成", "Affects generation")}：${if (container.affectsGeneration) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodyMedium)
                    if (container.autoUpdatePerChapter) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            enabled = !updating,
                            onClick = { updating = true; vm.updateContainerNow(projectId, container.id) { updating = false } },
                        ) {
                            if (updating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tx(lang, "用最新章节立即更新", "Update from latest chapter"))
                        }
                    }
                }
            }
        }
        item {
            Text(tx(lang, "分块（${blocks.size}）", "Blocks (${blocks.size})"),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        items(blocks, key = { it.first }) { (key, label) ->
            val count = vm.containerEntries(projectId, container.id, key).size
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenBlock(key) }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(tx(lang, "$count 条", "$count"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
            }
        }
        if (blocks.isEmpty()) {
            item {
                Text(tx(lang, "暂无分块（请先添加角色/章节）", "No blocks yet (add characters/chapters first)"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BlockChainView(
    vm: AppViewModel, projectId: String, container: Container, blockKey: String, lang: String, modifier: Modifier,
) {
    val state by vm.state.collectAsState()
    val entries = remember(state, container.id, blockKey) { vm.containerEntries(projectId, container.id, blockKey) }
    var editValue by remember(entries.lastOrNull()?.id) { mutableStateOf(entries.lastOrNull()?.value ?: "") }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        if (entries.isEmpty()) {
            item {
                Text(tx(lang, "该分块暂无值", "No values in this block yet"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { vm.addContainerEntry(projectId, container.id, blockKey, "") }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tx(lang, "添加一个值", "Add a value"))
                }
            }
            return@LazyColumn
        }

        val latest = entries.last()
        val history = entries.dropLast(1).reversed()

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(tx(lang, "最新值（可编辑）", "Latest value (editable)"),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    EntrySource(latest.sourceChapterOrder, latest.sourceChapterTitle, latest.manual, lang)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editValue, onValueChange = { editValue = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = editValue != latest.value,
                            onClick = { vm.editLatestContainerEntry(projectId, container.id, blockKey, editValue) },
                        ) { Text(tx(lang, "保存修改", "Save")) }
                        TextButton(onClick = { vm.addContainerEntry(projectId, container.id, blockKey, "") }) {
                            Text(tx(lang, "新增一条", "Add new"))
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item {
                Text(tx(lang, "历史", "History"), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        items(history, key = { it.id }) { e ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    EntrySource(e.sourceChapterOrder, e.sourceChapterTitle, e.manual, lang)
                    Spacer(Modifier.height(4.dp))
                    Text(e.value.ifBlank { tx(lang, "（空）", "(empty)") }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun EntrySource(order: Int?, title: String?, manual: Boolean, lang: String) {
    val text = when {
        manual -> tx(lang, "手动编辑", "Manual edit")
        order != null -> "${tx(lang, "来自", "From")} 第${order}章 ${title.orEmpty()}"
        else -> ""
    }
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewContainerDialog(lang: String, onDismiss: () -> Unit, onCreate: (Container) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(Container.BY_CHARACTER) }
    var autoUpdate by remember { mutableStateOf(false) }
    var affects by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "新建容器", "New Container")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(name, { name = it }, label = { Text(tx(lang, "容器名称", "Name")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Text(tx(lang, "容器类型（创建后不可更改）", "Type (fixed after creation)"),
                        style = MaterialTheme.typography.labelMedium)
                }
                items(
                    listOf(
                        Container.BY_CHARACTER to tx(lang, "依角色分块", "By character"),
                        Container.BY_CHAPTER to tx(lang, "依章节分块", "By chapter"),
                        Container.SINGLE to tx(lang, "不分块", "Single"),
                    ),
                ) { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { type = value }) {
                        RadioButton(selected = type == value, onClick = { type = value })
                        Text(label)
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { autoUpdate = !autoUpdate }) {
                        Checkbox(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                        Text(tx(lang, "按章更新（保存最新章时 AI 自动更新）", "Auto-update per chapter (AI)"))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { affects = !affects }) {
                        Checkbox(checked = affects, onCheckedChange = { affects = it })
                        Text(tx(lang, "影响章节生成（作为软引导注入）", "Affects generation (soft guidance)"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(Container(
                        id = "ctn-${System.currentTimeMillis()}",
                        name = name.trim(), type = type,
                        autoUpdatePerChapter = autoUpdate, affectsGeneration = affects,
                        createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()),
                    ))
                },
            ) { Text(tx(lang, "创建", "Create")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}
