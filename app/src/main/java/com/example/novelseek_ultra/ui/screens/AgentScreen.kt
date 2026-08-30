package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelseek_ultra.util.ImageShare
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.agent.AgentController
import com.example.novelseek_ultra.agent.AgentPlan
import com.example.novelseek_ultra.agent.AgentPlanStep
import com.example.novelseek_ultra.agent.AgentPlanStepStatus
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.MarkdownText
import com.example.novelseek_ultra.ui.components.RenameDialog
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenChapterReview: (projectId: String, chapterId: String) -> Unit,
) {
    val lang by vm.uiLanguage.collectAsState()
    val focusManager = LocalFocusManager.current
    val screenScope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    val agent = vm.agent
    val steps by agent.steps.collectAsState()
    val status by agent.status.collectAsState()
    val pending by agent.pendingPrompt.collectAsState()
    val pendingReview by agent.pendingReview.collectAsState()
    val streaming by agent.streamingText.collectAsState()
    val sessions by agent.sessions.collectAsState()
    val currentSessionId by agent.currentSessionId.collectAsState()
    val autoApprove by agent.autoApprove.collectAsState()
    val lockedProjectId by agent.activeProjectId.collectAsState()
    val engineMode by agent.engineMode.collectAsState()
    val reasoningLevel by agent.reasoningLevel.collectAsState()
    val sessionCacheMetrics by agent.sessionCacheMetrics.collectAsState()
    val contextUsage by agent.contextUsage.collectAsState()
    val activePlan by agent.activePlan.collectAsState()

    val projects by vm.projects.collectAsState()
    val hasValidLockedProject = lockedProjectId?.let { id -> projects.any { it.id == id } } == true
    val autoContinueActive = autoApprove && hasValidLockedProject
    val agentName = remember(state) { vm.agentName().ifBlank { tx(lang, "智能体", "Agent") } }

    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showReasoningMenu by remember { mutableStateOf(false) }
    var showContextDetails by rememberSaveable(currentSessionId) { mutableStateOf(false) }
    var contextActionMessage by rememberSaveable(currentSessionId) { mutableStateOf<String?>(null) }
    var contextCompressionInProgress by remember(currentSessionId) { mutableStateOf(false) }
    var showRenameAgent by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showLockAuto by remember { mutableStateOf(false) }
    var renamingSession by remember { mutableStateOf<com.example.novelseek_ultra.data.model.AgentSessionMeta?>(null) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hasPriorityBottomContent = pendingReview != null ||
        status == AgentController.Status.AWAITING_CONFIRM
    val planHeightFraction = if (hasPriorityBottomContent) 0.12f else 0.22f
    val planListMaxHeight = minOf(
        240.dp,
        (LocalConfiguration.current.screenHeightDp * planHeightFraction).dp,
    )
    // Presentation state intentionally resets when the user switches sessions or a new planId is
    // published. Progress updates keep the same planId, so they do not unexpectedly fold the list.
    var planExpanded by rememberSaveable(currentSessionId, activePlan?.planId) {
        mutableStateOf(false)
    }
    val listState = rememberLazyListState()

    val running = status == AgentController.Status.RUNNING ||
        status == AgentController.Status.AWAITING_USER ||
        status == AgentController.Status.AWAITING_CONFIRM
    val reviewBlocked = pendingReview != null || status == AgentController.Status.AWAITING_REVIEW
    val reasoningAdjustable = engineMode == AgentController.ENGINE_DUAL && !running && !reviewBlocked
    val contextUi = contextRingUiModel(
        observable = contextUsage.observable,
        capacityTokens = contextUsage.capacityTokens,
        inputBudgetTokens = contextUsage.inputBudgetTokens,
        fixedTokens = contextUsage.fixedTokens,
        historyTokens = contextUsage.historyTokens,
        outputReserveTokens = contextUsage.outputReserveTokens,
        remainingTokens = contextUsage.remainingTokens,
        usageRatio = contextUsage.usageRatio,
    )
    val contextCompressionEnabled = !contextCompressionInProgress && !running && !reviewBlocked &&
        currentSessionId != null && steps.isNotEmpty()

    LaunchedEffect(imeVisible) {
        if (imeVisible) planExpanded = false
    }

    LaunchedEffect(currentSessionId, reasoningAdjustable) {
        showReasoningMenu = false
    }

    LaunchedEffect(steps.size, status, streaming) {
        val count = steps.size + if (streaming.isNotBlank()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun submit() {
        val t = input.trim()
        if (t.isEmpty() || reviewBlocked) return
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
                        val modeLabel = if (engineMode == AgentController.ENGINE_DUAL) {
                            val levelLabel = when (reasoningLevel) {
                                AgentReasoningLevels.LOW -> tx(lang, "低", "Low")
                                AgentReasoningLevels.HIGH -> tx(lang, "高", "High")
                                else -> tx(lang, "中", "Medium")
                            }
                            tx(lang, "双智能体·$levelLabel", "Dual-agent · $levelLabel")
                        } else {
                            tx(lang, "经典 ReAct", "Classic ReAct")
                        }
                        val sub = buildString {
                            append(statusLabel(status, lang))
                            append(" · ").append(modeLabel)
                            if (autoContinueActive) append(tx(lang, " · 自动继续", " · auto"))
                        }
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                                onClick = { showMenu = false; agent.newSession() }, enabled = !running)
                            DropdownMenuItem(text = { Text(tx(lang, "会话列表", "Sessions")) },
                                onClick = { showMenu = false; showSessions = true }, enabled = !running)
                            DropdownMenuItem(text = { Text(tx(lang, "锁定项目 / 自动继续", "Lock project / auto")) },
                                onClick = { showMenu = false; showLockAuto = true }, enabled = !running)
                            DropdownMenuItem(text = { Text(tx(lang, "重命名智能体", "Rename agent")) },
                                onClick = { showMenu = false; showRenameAgent = true })
                            DropdownMenuItem(text = { Text(tx(lang, "清空当前会话", "Clear session")) },
                                onClick = { showMenu = false; confirmClear = true }, enabled = !running)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(modifier = Modifier.imePadding(), tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (engineMode == AgentController.ENGINE_DUAL) {
                        activePlan?.let { plan ->
                            ActivePlanProgressCard(
                                plan = plan,
                                lang = lang,
                                expanded = planExpanded,
                                maxExpandedHeight = planListMaxHeight,
                                onExpandedChange = { planExpanded = it && !imeVisible },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    pendingReview?.let { review ->
                        val projectTitle = projects.firstOrNull { it.id == review.projectId }?.title
                        val chapterTitle = vm.chapters(review.projectId)
                            .firstOrNull { it.id == review.chapterId }
                            ?.let { tx(lang, "第${it.order_index}章《${it.title}》", "Chapter ${it.order_index}: ${it.title}") }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    tx(lang, "候选稿待审核", "Candidate awaiting review"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    listOfNotNull(projectTitle, chapterTitle).joinToString(" · ").ifBlank {
                                        tx(lang, "目标章节", "Target chapter")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    pending ?: tx(
                                        lang,
                                        "采用或拒绝候选稿后，智能体才能继续执行。",
                                        "Accept or reject the candidate before the agent can continue.",
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                FilledTonalButton(
                                    onClick = {
                                        onOpenChapterReview(review.projectId, review.chapterId)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(tx(lang, "打开章节审核", "Open chapter review"))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Confirm bar for sensitive actions.
                    if (status == AgentController.Status.AWAITING_CONFIRM) {
                        Text(pending ?: tx(lang, "确认执行该操作？", "Confirm this action?"),
                            style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { agent.confirm(false) }) { Text(tx(lang, "拒绝", "Deny")) }
                            TextButton(
                                onClick = { agent.setAutoApprove(true); agent.confirm(true) },
                                enabled = hasValidLockedProject,
                            ) {
                                Text(tx(lang, "本项目自动继续", "Auto for project"))
                            }
                            FilledTonalButton(onClick = { agent.confirm(true) }) { Text(tx(lang, "确认", "Approve")) }
                        }
                        if (!hasValidLockedProject) {
                            Text(
                                tx(lang, "锁定一个项目后，才能启用自动继续。", "Lock a project to enable auto-continue."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Continue button when paused with history.
                    if (!running && !reviewBlocked && steps.isNotEmpty() && status != AgentController.Status.DONE) {
                        FilledTonalButton(onClick = { agent.continueRun() }, modifier = Modifier.fillMaxWidth()) {
                            Text(tx(lang, "继续执行", "Continue"))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SessionCacheStatus(
                            lang = lang,
                            observedRequests = sessionCacheMetrics.observedRequests,
                            hitTokens = sessionCacheMetrics.hitTokens,
                            missTokens = sessionCacheMetrics.missTokens,
                            hitRate = sessionCacheMetrics.hitRate(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        ContextUsageChip(
                            lang = lang,
                            model = contextUi,
                            onClick = {
                                focusManager.clearFocus()
                                showContextDetails = true
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            enabled = !reviewBlocked,
                            placeholder = {
                                Text(when (status) {
                                    AgentController.Status.AWAITING_USER -> tx(lang, "回复智能体…", "Reply to the agent…")
                                    AgentController.Status.AWAITING_REVIEW -> tx(
                                        lang,
                                        "请先审核章节候选稿…",
                                        "Review the chapter candidate first…",
                                    )
                                    AgentController.Status.RUNNING -> tx(lang, "执行中，可随时插入指令…", "Running — inject an instruction…")
                                    else -> tx(lang, "告诉智能体要做什么…", "Tell the agent what to do…")
                                })
                            },
                            maxLines = 4,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (engineMode == AgentController.ENGINE_DUAL) {
                            Box {
                                TextButton(
                                    onClick = { showReasoningMenu = true },
                                    enabled = reasoningAdjustable,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics {
                                            stateDescription = tx(
                                                lang,
                                                "当前推理级别：${reasoningLevelLabel(reasoningLevel, lang)}",
                                                "Current reasoning level: ${reasoningLevelLabel(reasoningLevel, lang)}",
                                            )
                                        },
                                ) {
                                    Text(reasoningLevelLabel(reasoningLevel, lang), maxLines = 1)
                                    Icon(
                                        Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showReasoningMenu,
                                    onDismissRequest = { showReasoningMenu = false },
                                ) {
                                    listOf(
                                        AgentReasoningLevels.LOW,
                                        AgentReasoningLevels.MEDIUM,
                                        AgentReasoningLevels.HIGH,
                                    ).forEach { level ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    tx(
                                                        lang,
                                                        "${reasoningLevelLabel(level, lang)}推理",
                                                        "${reasoningLevelLabel(level, lang)} reasoning",
                                                    ),
                                                )
                                            },
                                            onClick = {
                                                showReasoningMenu = false
                                                currentSessionId?.let { expectedSessionId ->
                                                    agent.setSessionReasoningLevel(
                                                        expectedSessionId = expectedSessionId,
                                                        level = level,
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                RadioButton(
                                                    selected = reasoningLevel == level,
                                                    onClick = null,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                        }
                        IconButton(onClick = { submit() }, enabled = input.isNotBlank() && !reviewBlocked) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = tx(lang, "发送", "Send"),
                                tint = if (input.isNotBlank() && !reviewBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                            .clickable(enabled = !running) { agent.switchSession(s.id); showSessions = false }.padding(vertical = 8.dp)) {
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
                            IconButton(onClick = { agent.deleteSession(s.id) }, enabled = !running) {
                                Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { agent.newSession(); showSessions = false }, enabled = !running) { Text(tx(lang, "新建会话", "New")) } },
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
        val hasValidSelection = locked?.let { id -> projects.any { it.id == id } } == true
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
                                    Text(tx(lang, "仅对下方锁定项目生效；删除项目、回退版本等高风险操作仍会确认。",
                                        "Applies only to the locked project; deletion, restore, and other high-risk actions still require confirmation."),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = auto && hasValidSelection,
                                    onCheckedChange = { auto = it },
                                    enabled = hasValidSelection,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!hasValidSelection) {
                            Text(
                                tx(lang, "请先选择项目，才能开启自动继续。", "Select a project before enabling auto-continue."),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(tx(lang, "锁定项目（自动继续必需）：", "Lock a project (required for auto-continue):"),
                            style = MaterialTheme.typography.labelMedium)
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            locked = null
                            auto = false
                        }) {
                            RadioButton(selected = locked == null, onClick = {
                                locked = null
                                auto = false
                            })
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
                    agent.setAutoApprove(auto && hasValidSelection)
                    showLockAuto = false
                }, enabled = !running) { Text(tx(lang, "保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { showLockAuto = false }) { Text(tx(lang, "取消", "Cancel")) } },
        )
    }

    if (showContextDetails) {
        ContextUsageDialog(
            lang = lang,
            model = contextUi,
            compressionCount = contextUsage.compressionCount,
            lastCompressedAt = contextUsage.lastCompressedAt,
            compressionEnabled = contextCompressionEnabled,
            compressionInProgress = contextCompressionInProgress,
            actionMessage = contextActionMessage,
            onCompress = {
                currentSessionId?.takeIf { !contextCompressionInProgress }?.let { expectedSessionId ->
                    contextCompressionInProgress = true
                    contextActionMessage = null
                    screenScope.launch {
                        val accepted = try {
                            withContext(Dispatchers.IO) {
                                agent.compactSessionContext(expectedSessionId)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        // A session switch replaces these keyed states. Do not publish an old
                        // session's result into the newly selected session's dialog.
                        if (currentSessionId == expectedSessionId) {
                            contextCompressionInProgress = false
                            contextActionMessage = if (accepted) {
                                tx(
                                    lang,
                                    "已压缩较早的会话历史；圆环已按新上下文更新。",
                                    "Older session history was compacted; the ring now reflects the new context.",
                                )
                            } else {
                                tx(
                                    lang,
                                    "当前任务状态不允许压缩，或没有可压缩的历史。",
                                    "The current task state does not allow compaction, or there is no history to compact.",
                                )
                            }
                        }
                    }
                }
            },
            onDismiss = { showContextDetails = false },
        )
    }
}

@Composable
private fun SessionCacheStatus(
    lang: String,
    observedRequests: Long,
    hitTokens: Long,
    missTokens: Long,
    hitRate: Double?,
    modifier: Modifier = Modifier,
) {
    val text = sessionCacheStatusText(
        lang = lang,
        observedRequests = observedRequests,
        hitTokens = hitTokens,
        missTokens = missTokens,
        hitRate = hitRate,
    )
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

internal fun sessionCacheStatusText(
    lang: String,
    observedRequests: Long,
    hitTokens: Long,
    missTokens: Long,
    hitRate: Double?,
): String = when {
        observedRequests <= 0L -> tx(
            lang,
            "会话推理缓存：暂无供应商数据",
            "Session reasoning cache: no provider data",
        )
        hitRate == null -> tx(
            lang,
            "会话推理缓存：暂无可统计 token",
            "Session reasoning cache: no measurable tokens",
        )
        else -> {
            val totalTokens = saturatedUiAdd(hitTokens, missTokens)
            val rateTenths = (hitRate * 1_000.0 + 0.5).toInt().coerceIn(0, 1_000)
            val rateLabel = "${rateTenths / 10}.${rateTenths % 10}%"
            tx(
                lang,
                "会话推理缓存：$rateLabel · ${formatAgentTokenCount(hitTokens)} / ${formatAgentTokenCount(totalTokens)} tokens",
                "Session reasoning cache: $rateLabel · ${formatAgentTokenCount(hitTokens)} / ${formatAgentTokenCount(totalTokens)} tokens",
            )
        }
    }

private fun reasoningLevelLabel(level: String, lang: String): String = when (level) {
    AgentReasoningLevels.LOW -> tx(lang, "低", "Low")
    AgentReasoningLevels.HIGH -> tx(lang, "高", "High")
    else -> tx(lang, "中", "Medium")
}

private fun saturatedUiAdd(left: Long, right: Long): Long = when {
    left < 0L || right < 0L -> 0L
    Long.MAX_VALUE - left < right -> Long.MAX_VALUE
    else -> left + right
}

private fun formatAgentTokenCount(value: Long): String =
    java.text.NumberFormat.getIntegerInstance().format(value.coerceAtLeast(0L))

internal data class ContextRingUiModel(
    val observable: Boolean,
    val capacityTokens: Long,
    val inputBudgetTokens: Long,
    val fixedTokens: Long,
    val historyTokens: Long,
    val outputReserveTokens: Long,
    val remainingTokens: Long,
    val usagePercent: Int?,
    val fixedFraction: Float,
    val historyFraction: Float,
    val outputFraction: Float,
    val remainingFraction: Float,
)

internal fun contextRingUiModel(
    observable: Boolean,
    capacityTokens: Long,
    inputBudgetTokens: Long,
    fixedTokens: Long,
    historyTokens: Long,
    outputReserveTokens: Long,
    remainingTokens: Long,
    usageRatio: Double?,
): ContextRingUiModel {
    val capacity = capacityTokens.coerceAtLeast(0L)
    val inputBudget = inputBudgetTokens.coerceIn(0L, capacity)
    val fixed = fixedTokens.coerceAtLeast(0L)
    val history = historyTokens.coerceAtLeast(0L)
    val output = outputReserveTokens.coerceIn(0L, capacity)
    val remaining = remainingTokens.coerceAtLeast(0L)
    val ratio = usageRatio?.takeIf { it.isFinite() && it >= 0.0 }
    val valid = observable && capacity > 0L && inputBudget > 0L && ratio != null
    if (!valid) {
        return ContextRingUiModel(
            observable = false,
            capacityTokens = capacity,
            inputBudgetTokens = inputBudget,
            fixedTokens = fixed,
            historyTokens = history,
            outputReserveTokens = output,
            remainingTokens = remaining,
            usagePercent = null,
            fixedFraction = 0f,
            historyFraction = 0f,
            outputFraction = 0f,
            remainingFraction = 1f,
        )
    }

    val fixedFraction = (fixed.toDouble() / capacity.toDouble()).coerceIn(0.0, 1.0)
    val historyFraction = (history.toDouble() / capacity.toDouble())
        .coerceIn(0.0, 1.0 - fixedFraction)
    val outputFraction = (output.toDouble() / capacity.toDouble())
        .coerceIn(0.0, 1.0 - fixedFraction - historyFraction)
    val remainingFraction = (1.0 - fixedFraction - historyFraction - outputFraction)
        .coerceIn(0.0, 1.0)
    return ContextRingUiModel(
        observable = true,
        capacityTokens = capacity,
        inputBudgetTokens = inputBudget,
        fixedTokens = fixed,
        historyTokens = history,
        outputReserveTokens = output,
        remainingTokens = remaining,
        usagePercent = (ratio * 100.0 + 0.5).toInt().coerceIn(0, 100),
        fixedFraction = fixedFraction.toFloat(),
        historyFraction = historyFraction.toFloat(),
        outputFraction = outputFraction.toFloat(),
        remainingFraction = remainingFraction.toFloat(),
    )
}

internal fun contextUsageDescription(lang: String, model: ContextRingUiModel): String {
    if (!model.observable || model.usagePercent == null) {
        return tx(
            lang,
            "会话上下文：暂无可用预算数据。点击查看详情。",
            "Session context: no budget data available. Tap for details.",
        )
    }
    return tx(
        lang,
        "会话上下文已占用 ${model.usagePercent}%。固定提示 ${formatAgentTokenCount(model.fixedTokens)} tokens，" +
            "会话历史 ${formatAgentTokenCount(model.historyTokens)} tokens，" +
            "输出预留 ${formatAgentTokenCount(model.outputReserveTokens)} tokens，" +
            "输入余量 ${formatAgentTokenCount(model.remainingTokens)} tokens。点击查看详情。",
        "Session context ${model.usagePercent}% occupied. Fixed prompt ${formatAgentTokenCount(model.fixedTokens)} tokens, " +
            "history ${formatAgentTokenCount(model.historyTokens)} tokens, " +
            "output reserve ${formatAgentTokenCount(model.outputReserveTokens)} tokens, " +
            "input remaining ${formatAgentTokenCount(model.remainingTokens)} tokens. Tap for details.",
    )
}

@Composable
private fun ContextUsageChip(
    lang: String,
    model: ContextRingUiModel,
    onClick: () -> Unit,
) {
    val fixedColor = MaterialTheme.colorScheme.primary
    val historyColor = MaterialTheme.colorScheme.tertiary
    val outputColor = MaterialTheme.colorScheme.secondary
    val remainingColor = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(
                role = Role.Button,
                onClickLabel = tx(lang, "查看上下文详情", "View context details"),
                onClick = onClick,
            )
            .semantics {
                stateDescription = contextUsageDescription(lang, model)
            }
            .heightIn(min = 48.dp)
            .padding(start = 7.dp, end = 3.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tx(lang, "上下文", "Context"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        ContextUsageRing(
            model = model,
            size = 38.dp,
            fixedColor = fixedColor,
            historyColor = historyColor,
            outputColor = outputColor,
            remainingColor = remainingColor,
        )
    }
}

@Composable
private fun ContextUsageRing(
    model: ContextRingUiModel,
    size: Dp,
    fixedColor: Color,
    historyColor: Color,
    outputColor: Color,
    remainingColor: Color,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 5.dp.toPx()
            val style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            drawArc(
                color = remainingColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = style,
            )
            if (model.observable) {
                val fixedSweep = model.fixedFraction * 360f
                val historySweep = model.historyFraction * 360f
                val outputSweep = model.outputFraction * 360f
                if (fixedSweep > 0f) {
                    drawArc(fixedColor, -90f, fixedSweep, false, style = style)
                }
                if (historySweep > 0f) {
                    drawArc(historyColor, -90f + fixedSweep, historySweep, false, style = style)
                }
                if (outputSweep > 0f) {
                    drawArc(outputColor, 270f - outputSweep, outputSweep, false, style = style)
                }
            }
        }
        val percent = model.usagePercent
        Text(
            text = percent?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = when {
                percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                percent >= 90 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun ContextUsageDialog(
    lang: String,
    model: ContextRingUiModel,
    compressionCount: Int,
    lastCompressedAt: String?,
    compressionEnabled: Boolean,
    compressionInProgress: Boolean,
    actionMessage: String?,
    onCompress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fixedColor = MaterialTheme.colorScheme.primary
    val historyColor = MaterialTheme.colorScheme.tertiary
    val outputColor = MaterialTheme.colorScheme.secondary
    val remainingColor = MaterialTheme.colorScheme.surfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "会话上下文", "Session context")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.58f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ContextUsageRing(
                        model = model,
                        size = 72.dp,
                        fixedColor = fixedColor,
                        historyColor = historyColor,
                        outputColor = outputColor,
                        remainingColor = remainingColor,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.usagePercent?.let {
                                tx(lang, "输入预算已占用 $it%", "$it% of input budget occupied")
                            } ?: tx(lang, "暂无预算数据", "No budget data"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (model.observable) {
                                tx(
                                    lang,
                                    "可用容量 ${formatAgentTokenCount(model.capacityTokens)} tokens · 输入预算 ${formatAgentTokenCount(model.inputBudgetTokens)} tokens",
                                    "Usable capacity ${formatAgentTokenCount(model.capacityTokens)} tokens · input budget ${formatAgentTokenCount(model.inputBudgetTokens)} tokens",
                                )
                            } else {
                                tx(
                                    lang,
                                    "请检查文本模型配置，或先开始一次智能体会话。",
                                    "Check the text model configuration or start an agent session first.",
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (model.observable) {
                    ContextLegendRow(
                        lang = lang,
                        color = fixedColor,
                        zhLabel = "固定提示",
                        enLabel = "Fixed prompt",
                        tokens = model.fixedTokens,
                        fraction = model.fixedFraction,
                    )
                    ContextLegendRow(
                        lang = lang,
                        color = historyColor,
                        zhLabel = "会话历史",
                        enLabel = "Session history",
                        tokens = model.historyTokens,
                        fraction = model.historyFraction,
                    )
                    ContextLegendRow(
                        lang = lang,
                        color = outputColor,
                        zhLabel = "输出预留",
                        enLabel = "Output reserve",
                        tokens = model.outputReserveTokens,
                        fraction = model.outputFraction,
                    )
                    ContextLegendRow(
                        lang = lang,
                        color = remainingColor,
                        zhLabel = "可用输入余量",
                        enLabel = "Input remaining",
                        tokens = model.remainingTokens,
                        fraction = model.remainingFraction,
                    )
                    Text(
                        tx(
                            lang,
                            "中心百分比只计算固定提示与会话历史；输出预留不会被算作已使用。Token 为发起请求前的保守估算。",
                            "The center percentage counts only fixed prompt and session history; output reserve is not treated as used. Tokens are conservative pre-request estimates.",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (compressionCount > 0) {
                    val whenLabel = lastCompressedAt.orEmpty()
                        .replace('T', ' ')
                        .removeSuffix("Z")
                    Text(
                        tx(
                            lang,
                            "本会话已压缩 $compressionCount 次" +
                                if (whenLabel.isBlank()) "" else " · 最近 $whenLabel",
                            "This session was compacted $compressionCount times" +
                                if (whenLabel.isBlank()) "" else " · latest $whenLabel",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                actionMessage?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("已") || it.startsWith("Older")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Text(
                    tx(
                        lang,
                        "手动压缩在本地整理智能体的较早会话历史，不调用模型，也不会修改小说正文、设定或项目数据。",
                        "Manual compaction locally condenses older agent-session history. It does not call a model or modify novel text, settings, or project data.",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onCompress,
                enabled = compressionEnabled && !compressionInProgress,
            ) {
                if (compressionInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (compressionInProgress) {
                        tx(lang, "压缩中…", "Compacting…")
                    } else {
                        tx(lang, "手动压缩", "Compact history")
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "关闭", "Close")) }
        },
    )
}

@Composable
private fun ContextLegendRow(
    lang: String,
    color: Color,
    zhLabel: String,
    enLabel: String,
    tokens: Long,
    fraction: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            tx(lang, zhLabel, enLabel),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${formatAgentTokenCount(tokens)} · ${formatContextFraction(fraction)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatContextFraction(fraction: Float): String {
    val tenths = (fraction.coerceIn(0f, 1f) * 1_000f + 0.5f).toInt()
    return "${tenths / 10}.${tenths % 10}%"
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
        AgentStep.PLAN -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text(
                    "🧭 ${tx(lang, "规划智能体 · 任务计划", "Planner · Task plan")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                val sc = rememberScrollState()
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(sc)) {
                    MarkdownText(step.text)
                }
            }
        }
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

@Composable
private fun ActivePlanProgressCard(
    plan: AgentPlan,
    lang: String,
    expanded: Boolean,
    maxExpandedHeight: Dp,
    onExpandedChange: (Boolean) -> Unit,
) {
    val completed = plan.steps.count { it.status == AgentPlanStepStatus.COMPLETED }
    val blocked = plan.steps.count { it.status == AgentPlanStepStatus.BLOCKED }
    val current = plan.currentStep
    val toggleLabel = if (expanded) {
        tx(lang, "收起项目规划清单", "Collapse project plan checklist")
    } else {
        tx(lang, "展开项目规划清单", "Expand project plan checklist")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = toggleLabel,
                    ) { onExpandedChange(!expanded) }
                    .semantics {
                        stateDescription = if (expanded) {
                            tx(lang, "已展开", "Expanded")
                        } else {
                            tx(lang, "已收起", "Collapsed")
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "🧭 ${tx(lang, "双智能体计划", "Dual-agent plan")}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (plan.steps.isEmpty()) {
                        tx(lang, "暂无步骤", "No steps")
                    } else {
                        tx(lang, "进度 $completed/${plan.steps.size}", "$completed/${plan.steps.size} done")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                plan.summary.ifBlank { tx(lang, "暂无计划摘要", "No plan summary") },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                current != null -> Text(
                    tx(lang, "当前：${current.goal}", "Current: ${current.goal}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                plan.steps.isNotEmpty() && plan.isComplete -> Text(
                    tx(lang, "✓ 计划已完成", "✓ Plan complete"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                blocked > 0 -> Text(
                    tx(lang, "⚠ 有 $blocked 个步骤受阻", "⚠ $blocked step(s) blocked"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (plan.steps.isEmpty()) {
                    Text(
                        tx(lang, "规划清单暂无可显示步骤。", "The plan checklist has no steps to display."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = maxExpandedHeight),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            items = plan.steps,
                            key = { index, step -> "${plan.planId}:$index:${step.id}" },
                        ) { index, step ->
                            AgentPlanChecklistItem(
                                step = step,
                                index = index,
                                lang = lang,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPlanChecklistItem(
    step: AgentPlanStep,
    index: Int,
    lang: String,
) {
    val statusLabel = when (step.status) {
        AgentPlanStepStatus.PENDING -> tx(lang, "待执行", "Pending")
        AgentPlanStepStatus.IN_PROGRESS -> tx(lang, "进行中", "In progress")
        AgentPlanStepStatus.COMPLETED -> tx(lang, "已完成", "Completed")
        AgentPlanStepStatus.BLOCKED -> tx(lang, "受阻", "Blocked")
    }
    val statusMark = when (step.status) {
        AgentPlanStepStatus.PENDING -> "○"
        AgentPlanStepStatus.IN_PROGRESS -> "●"
        AgentPlanStepStatus.COMPLETED -> "✓"
        AgentPlanStepStatus.BLOCKED -> "!"
    }
    val statusColor = when (step.status) {
        AgentPlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentPlanStepStatus.IN_PROGRESS,
        AgentPlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AgentPlanStepStatus.BLOCKED -> MaterialTheme.colorScheme.error
    }
    val containerColor = when (step.status) {
        AgentPlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primaryContainer
        AgentPlanStepStatus.BLOCKED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                statusMark,
                style = MaterialTheme.typography.titleSmall,
                color = statusColor,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "${index + 1}. ${step.goal}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (step.status == AgentPlanStepStatus.IN_PROGRESS) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
                step.successCriteria?.takeIf { it.isNotBlank() }?.let { criteria ->
                    Text(
                        tx(lang, "完成标准：$criteria", "Success criteria: $criteria"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                step.suggestedTools.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { tools ->
                    Text(
                        tx(lang, "建议工具：${tools.joinToString("、")}", "Suggested tools: ${tools.joinToString(", ")}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun statusLabel(s: AgentController.Status, lang: String): String = when (s) {
    AgentController.Status.IDLE -> tx(lang, "待命", "Idle")
    AgentController.Status.RUNNING -> tx(lang, "执行中", "Running")
    AgentController.Status.AWAITING_USER -> tx(lang, "等待你的回复", "Awaiting your reply")
    AgentController.Status.AWAITING_CONFIRM -> tx(lang, "等待确认", "Awaiting confirm")
    AgentController.Status.AWAITING_REVIEW -> tx(lang, "候选稿待审核", "Awaiting review")
    AgentController.Status.DONE -> tx(lang, "已完成", "Done")
    AgentController.Status.ERROR -> tx(lang, "出错", "Error")
    AgentController.Status.STOPPED -> tx(lang, "已暂停", "Paused")
}
