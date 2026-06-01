package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelseek_ultra.util.ImageShare
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.agent.AgentController
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.MarkdownText
import com.example.novelseek_ultra.ui.components.RenameDialog
import com.example.novelseek_ultra.util.tx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(vm: AppViewModel, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val agent = vm.agent
    val steps by agent.steps.collectAsState()
    val status by agent.status.collectAsState()
    val pending by agent.pendingPrompt.collectAsState()
    val streaming by agent.streamingText.collectAsState()
    val sessions by agent.sessions.collectAsState()
    val currentSessionId by agent.currentSessionId.collectAsState()
    val autoApprove by agent.autoApprove.collectAsState()
    val lockedProjectId by agent.activeProjectId.collectAsState()

    val projects by vm.projects.collectAsState()
    val agentName = remember(state) { vm.agentName().ifBlank { tx(lang, "智能体", "Agent") } }

    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameAgent by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showLockAuto by remember { mutableStateOf(false) }
    var renamingSession by remember { mutableStateOf<com.example.novelseek_ultra.data.model.AgentSessionMeta?>(null) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val running = status == AgentController.Status.RUNNING ||
        status == AgentController.Status.AWAITING_USER ||
        status == AgentController.Status.AWAITING_CONFIRM

    LaunchedEffect(steps.size, status, streaming) {
        val count = steps.size + if (streaming.isNotBlank()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        val t = input.trim()
        if (t.isEmpty()) return
        agent.sendInput(t)
        input = ""
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
                },
                title = {
                    Column {
                        Text(agentName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        val sub = statusLabel(status, lang) + if (autoApprove) tx(lang, " · 自动继续", " · auto") else ""
                        Text(sub, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    if (running) {
                        IconButton(onClick = { agent.stop() }) {
                            Icon(Icons.Outlined.Stop, contentDescription = tx(lang, "停止", "Stop"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = tx(lang, "菜单", "Menu"))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text(tx(lang, "新建会话", "New session")) },
                                onClick = { showMenu = false; agent.newSession() })
                            DropdownMenuItem(text = { Text(tx(lang, "会话列表", "Sessions")) },
                                onClick = { showMenu = false; showSessions = true })
                            DropdownMenuItem(text = { Text(tx(lang, "锁定项目 / 自动继续", "Lock project / auto")) },
                                onClick = { showMenu = false; showLockAuto = true })
                            DropdownMenuItem(text = { Text(tx(lang, "重命名智能体", "Rename agent")) },
                                onClick = { showMenu = false; showRenameAgent = true })
                            DropdownMenuItem(text = { Text(tx(lang, "清空当前会话", "Clear session")) },
                                onClick = { showMenu = false; confirmClear = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Confirm bar for sensitive actions.
                    if (status == AgentController.Status.AWAITING_CONFIRM) {
                        Text(pending ?: tx(lang, "确认执行该操作？", "Confirm this action?"),
                            style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { agent.confirm(false) }) { Text(tx(lang, "拒绝", "Deny")) }
                            TextButton(onClick = { agent.setAutoApprove(true); agent.confirm(true) }) {
                                Text(tx(lang, "始终允许", "Always"))
                            }
                            FilledTonalButton(onClick = { agent.confirm(true) }) { Text(tx(lang, "确认", "Approve")) }
                        }
                    }
                    // Continue button when paused with history.
                    if (!running && steps.isNotEmpty() && status != AgentController.Status.DONE) {
                        FilledTonalButton(onClick = { agent.continueRun() }, modifier = Modifier.fillMaxWidth()) {
                            Text(tx(lang, "继续执行", "Continue"))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(when (status) {
                                    AgentController.Status.AWAITING_USER -> tx(lang, "回复智能体…", "Reply to the agent…")
                                    AgentController.Status.RUNNING -> tx(lang, "执行中，可随时插入指令…", "Running — inject an instruction…")
                                    else -> tx(lang, "告诉智能体要做什么…", "Tell the agent what to do…")
                                })
                            },
                            maxLines = 4,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { submit() }, enabled = input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = tx(lang, "发送", "Send"),
                                tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (steps.isEmpty()) {
                item {
                    Text(
                        tx(lang,
                            "我可以帮你完整地操作这个软件：一句话生成整本小说、修改某副本/弧线/章节、检索内容、审阅前后矛盾、联网搜索等。\n\n关键步骤我会先和你确认。试试：「帮我新建一个玄幻长篇并生成大纲」。",
                            "I can operate the whole app for you: generate a whole novel, edit volumes/arcs/chapters, retrieve info, review consistency, search the web, etc. I'll confirm important steps."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(steps, key = { it.id }) { step -> StepRow(step, lang, onImageClick = { fullscreenImage = it }) }
            if (streaming.isNotBlank()) {
                item {
                    // Live generation preview — fixed-height window that always scrolls to the
                    // newest line (earlier lines stay put and scroll up, so nothing "reflows").
                    val scrollState = rememberScrollState()
                    LaunchedEffect(streaming) { scrollState.scrollTo(scrollState.maxValue) }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Text(tx(lang, "正在生成…", "Generating…"), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                streaming,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().height(150.dp).verticalScroll(scrollState),
                            )
                        }
                    }
                }
            } else if (status == AgentController.Status.RUNNING) {
                item {
                    val last = steps.lastOrNull()
                    val label = if (last?.type == AgentStep.ACTION && last.tool.isNotBlank())
                        tx(lang, "正在执行：${last.tool}…（较长任务请稍候）", "Running ${last.tool}…")
                    else tx(lang, "思考中…", "Thinking…")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = tx(lang, "清空会话", "Clear session"),
            message = tx(lang, "确定清空当前会话的全部执行记录？", "Clear this session's history?"),
            confirmLabel = tx(lang, "清空", "Clear"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { agent.clearSession() },
            onDismiss = { confirmClear = false },
        )
    }

    if (showRenameAgent) {
        RenameDialog(
            title = tx(lang, "重命名智能体", "Rename agent"),
            label = tx(lang, "名字", "Name"),
            initialValue = vm.agentName(),
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.setAgentName(it) },
            onDismiss = { showRenameAgent = false },
        )
    }

    if (showSessions) {
        AlertDialog(
            onDismissRequest = { showSessions = false },
            title = { Text(tx(lang, "会话列表", "Sessions")) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { agent.switchSession(s.id); showSessions = false }.padding(vertical = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title.ifBlank { tx(lang, "未命名会话", "Untitled") },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (s.id == currentSessionId) FontWeight.Bold else FontWeight.Normal)
                                Text(s.createdAt.replace('T', ' ').removeSuffix("Z"), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.id == currentSessionId) Text(tx(lang, "当前", "current"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = { renamingSession = s }) {
                                Icon(Icons.Outlined.Edit, contentDescription = tx(lang, "重命名", "Rename"), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { agent.deleteSession(s.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { agent.newSession(); showSessions = false }) { Text(tx(lang, "新建会话", "New")) } },
            dismissButton = { TextButton(onClick = { showSessions = false }) { Text(tx(lang, "关闭", "Close")) } },
        )
    }

    renamingSession?.let { s ->
        RenameDialog(
            title = tx(lang, "重命名会话", "Rename session"),
            label = tx(lang, "会话名称", "Title"),
            initialValue = s.title,
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { agent.renameSession(s.id, it) },
            onDismiss = { renamingSession = null },
        )
    }

    fullscreenImage?.let { path ->
        val ctx = LocalContext.current
        val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
        Dialog(onDismissRequest = { fullscreenImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(onClick = {
                        val ok = ImageShare.saveToGallery(ctx, path)
                        android.widget.Toast.makeText(ctx, if (ok) tx(lang, "已保存到相册", "Saved to gallery") else tx(lang, "保存失败", "Save failed"), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Outlined.FileDownload, contentDescription = tx(lang, "保存", "Save")); Spacer(Modifier.width(4.dp)); Text(tx(lang, "保存", "Save")) }
                    FilledTonalButton(onClick = { ImageShare.share(ctx, path) }) {
                        Icon(Icons.Outlined.Share, contentDescription = tx(lang, "分享", "Share")); Spacer(Modifier.width(4.dp)); Text(tx(lang, "分享", "Share"))
                    }
                    IconButton(onClick = { fullscreenImage = null }) {
                        Icon(Icons.Outlined.Close, contentDescription = tx(lang, "关闭", "Close"), tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }

    if (showLockAuto) {
        var auto by remember { mutableStateOf(autoApprove) }
        var locked by remember { mutableStateOf(lockedProjectId) }
        AlertDialog(
            onDismissRequest = { showLockAuto = false },
            title = { Text(tx(lang, "锁定项目 / 自动继续", "Lock project / auto-continue")) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Auto-continue is the key switch — keep it at the TOP so it's never scrolled out of view.
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(tx(lang, "允许自动继续执行", "Allow auto-continue"),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(tx(lang, "开启后不再逐步确认（生成章节、覆盖、回退等照常执行）",
                                        "Skip per-step confirmation (chapters, overwrites, restore run automatically)"),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = auto, onCheckedChange = { auto = it })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(tx(lang, "锁定项目（智能体围绕它工作，可选）：", "Lock a project (optional):"),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { locked = null }) {
                            RadioButton(selected = locked == null, onClick = { locked = null })
                            Text(tx(lang, "不锁定", "None"))
                        }
                    }
                    items(projects, key = { it.id }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { locked = p.id }) {
                            RadioButton(selected = locked == p.id, onClick = { locked = p.id })
                            Text(p.title, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    agent.lockProject(locked)
                    agent.setAutoApprove(auto)   // the switch alone controls confirmations
                    showLockAuto = false
                }) { Text(tx(lang, "保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { showLockAuto = false }) { Text(tx(lang, "取消", "Cancel")) } },
        )
    }
}

@Composable
private fun StepRow(step: AgentStep, lang: String, onImageClick: (String) -> Unit = {}) {
    when (step.type) {
        AgentStep.IMAGE -> {
            val bmp = remember(step.image) { runCatching { android.graphics.BitmapFactory.decodeFile(step.image) }.getOrNull() }
            Column {
                if (step.text.isNotBlank()) {
                    Text("🖼 ${step.text}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = step.text,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(step.image) },
                    )
                    Text(tx(lang, "点击查看大图", "Tap to view full screen"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(tx(lang, "（图片已失效）", "(image unavailable)"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AgentStep.USER, AgentStep.ANSWER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) { Text(step.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        AgentStep.THOUGHT -> Text("💭 ${step.text}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
        AgentStep.ACTION -> Text("▶ ${step.tool}  ${step.text}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        AgentStep.OBSERVATION -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            // Tool results (chapter lists / structure trees) can be hundreds of lines — cap the
            // bubble height and let the overflow scroll inside.
            val sc = rememberScrollState()
            Text(
                step.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp).fillMaxWidth().heightIn(max = 200.dp).verticalScroll(sc),
            )
        }
        AgentStep.QUESTION -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("❓ ${tx(lang, "智能体提问", "Agent asks")}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp)); Text(step.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        AgentStep.ERROR -> Text("⚠ ${step.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> Card {
            val sc = rememberScrollState()
            Column(Modifier.padding(12.dp).fillMaxWidth().heightIn(max = 280.dp).verticalScroll(sc)) { MarkdownText(step.text) }
        }
    }
}

private fun statusLabel(s: AgentController.Status, lang: String): String = when (s) {
    AgentController.Status.IDLE -> tx(lang, "待命", "Idle")
    AgentController.Status.RUNNING -> tx(lang, "执行中", "Running")
    AgentController.Status.AWAITING_USER -> tx(lang, "等待你的回复", "Awaiting your reply")
    AgentController.Status.AWAITING_CONFIRM -> tx(lang, "等待确认", "Awaiting confirm")
    AgentController.Status.DONE -> tx(lang, "已完成", "Done")
    AgentController.Status.ERROR -> tx(lang, "出错", "Error")
    AgentController.Status.STOPPED -> tx(lang, "已暂停", "Paused")
}
