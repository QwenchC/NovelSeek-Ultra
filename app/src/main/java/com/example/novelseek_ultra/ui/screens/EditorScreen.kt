package com.example.novelseek_ultra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterPromo
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.Illustration
import com.example.novelseek_ultra.data.model.PlotArc
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.RenameDialog
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    vm: AppViewModel,
    projectId: String,
    chapterId: String,
    onBack: () -> Unit,
    onOpenQa: () -> Unit = {},
    onOpenContainer: () -> Unit = {},
    onNavigateToChapter: (chapterId: String) -> Unit = {},
) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val chapter = remember(state, chapterId) { vm.chapters(projectId).firstOrNull { it.id == chapterId } }
    val isGenerating by vm.isGenerating.collectAsState()
    val isChapterGenerating by vm.isChapterGenerating.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val aiFillText by vm.aiFillText.collectAsState()
    val isAiFilling by vm.isAiFilling.collectAsState()
    val status by vm.statusMessage.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    if (chapter == null) {
        Text(tx(lang, "章节不存在", "Chapter not found"), modifier = Modifier.padding(16.dp))
        return
    }

    val isLongNovel = remember(projectId) { vm.novelType(projectId) == "long" }
    val arcs: List<PlotArc> = remember(state, projectId) {
        if (isLongNovel) vm.plotArcs(projectId) else emptyList()
    }
    val activeArc: PlotArc? = arcs.find { it.status == "active" || it.status == "ending" }
    val characters = remember(state, projectId) { vm.characters(projectId) }
    val realms = remember(state, projectId) { vm.cultivationRealms(projectId) }

    // Sorted chapter list — used by prev/next navigation and the switcher popup.
    val orderedChapters = remember(state, projectId) {
        vm.chapters(projectId).sortedBy { it.order_index }
    }
    val currentIdx = orderedChapters.indexOfFirst { it.id == chapterId }
    val prevChapter = orderedChapters.getOrNull(currentIdx - 1)
    val nextChapter = orderedChapters.getOrNull(currentIdx + 1)

    var tab by remember(chapterId) { mutableStateOf(EditorTab.Final) }
    var draftText by remember(chapterId) { mutableStateOf(vm.chapterBody(chapterId).draft) }
    var finalText by remember(chapterId) { mutableStateOf(vm.chapterBody(chapterId).final) }
    var goal by remember(chapterId) { mutableStateOf(chapter.outline_goal.orEmpty()) }
    var conflict by remember(chapterId) { mutableStateOf(chapter.conflict.orEmpty()) }

    // ── Dirty tracking ────────────────────────────────────────────────────────
    // Snapshot what's on disk so we can detect unsaved edits.
    var savedDraft by remember(chapterId) { mutableStateOf(draftText) }
    var savedFinal by remember(chapterId) { mutableStateOf(finalText) }
    var savedGoal by remember(chapterId) { mutableStateOf(goal) }
    var savedConflict by remember(chapterId) { mutableStateOf(conflict) }
    val isDirty by remember {
        derivedStateOf {
            draftText != savedDraft || finalText != savedFinal ||
                    goal != savedGoal || conflict != savedConflict
        }
    }

    var planExpanded by remember(chapterId) { mutableStateOf(finalText.isBlank() && draftText.isBlank()) }
    var showRevise by remember { mutableStateOf(false) }
    var showInsertDialog by remember { mutableStateOf(false) }
    var reviseSelection by remember { mutableStateOf("") }
    var reviseInProgress by remember { mutableStateOf(false) }
    var showAiFill by remember { mutableStateOf(false) }
    var pendingTitleFromFill by remember { mutableStateOf<String?>(null) }
    var showRealmDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showChapterSwitcher by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    // ── Illustration mode (replaces the old draft tab) ─────────────────────────
    // PC parity: paragraphs are selectable, AI generates an image prompt from the selection,
    // image is fetched from Pollinations and anchored to the first selected paragraph.
    val illustrations = remember(chapterId) {
        mutableStateListOf<Illustration>().apply { addAll(vm.chapterIllustrations(chapterId)) }
    }
    val selectedParagraphs = remember(chapterId) { mutableStateListOf<Int>() }
    var isGeneratingIllustration by remember { mutableStateOf(false) }
    var illustrationError by remember { mutableStateOf<String?>(null) }
    var showIllustrationConfig by remember { mutableStateOf(false) }
    var pendingDeleteIllustrationId by remember { mutableStateOf<String?>(null) }
    var illustrationConfig by remember {
        mutableStateOf(IllustrationConfig(model = "zimage", width = 1024, height = 576, style = ""))
    }

    // ── Chapter promo ("推文") state ─────────────────────────────────────────────────────────
    var promoResult by remember(chapterId) { mutableStateOf<ChapterPromo?>(vm.getChapterPromo(chapterId)) }
    var isGeneratingPromo by remember { mutableStateOf(false) }
    var promoError by remember { mutableStateOf<String?>(null) }
    var showPromoConfig by remember { mutableStateOf(false) }
    var promoExpanded by remember { mutableStateOf(false) }

    // Only update finalText from streaming when chapter generation is running
    LaunchedEffect(streaming, isChapterGenerating) {
        if (isChapterGenerating && streaming.isNotEmpty()) finalText = streaming
    }

    // When chapter generation finishes, the ViewModel auto-saves the new text to disk. Re-align
    // the saved snapshot so the dirty asterisk doesn't linger on freshly generated content.
    var wasChapterGenerating by remember(chapterId) { mutableStateOf(false) }
    LaunchedEffect(isChapterGenerating) {
        if (wasChapterGenerating && !isChapterGenerating) {
            val body = vm.chapterBody(chapterId)
            savedFinal = body.final
            savedDraft = body.draft
            finalText = body.final
            draftText = body.draft
        }
        wasChapterGenerating = isChapterGenerating
    }

    // ── Save action — emits a Snackbar on success/failure ─────────────────────
    val save: () -> Unit = {
        scope.launch {
            val ok = runCatching {
                vm.saveChapterBody(chapterId, AppRepository.ChapterBody(draft = draftText, final = finalText))
                val wc = countWords(finalText.ifBlank { draftText })
                vm.upsertChapter(projectId, chapter.copy(
                    outline_goal = goal.ifBlank { null },
                    conflict = conflict.ifBlank { null },
                    word_count = wc,
                ))
            }.isSuccess
            if (ok) {
                savedDraft = draftText; savedFinal = finalText
                savedGoal = goal; savedConflict = conflict
                snackbarHost.showSnackbar(tx(lang, "已保存", "Saved"))
                // KB hooks — fan out re-index / summary / entity jobs if their toggles are on.
                // Each one is fire-and-forget on the VM coroutine scope; UI does not block.
                vm.onChapterSaved(projectId, chapterId, chapter.title, finalText.ifBlank { draftText })
            } else {
                snackbarHost.showSnackbar(tx(lang, "保存失败", "Save failed"))
            }
        }
    }

    // Navigate to another chapter; auto-save current first if dirty.
    val gotoChapter: (String) -> Unit = { targetId ->
        if (isDirty) {
            // Fire-and-forget save (no Snackbar — navigation will tear down host anyway).
            vm.saveChapterBody(chapterId, AppRepository.ChapterBody(draft = draftText, final = finalText))
            val wc = countWords(finalText.ifBlank { draftText })
            vm.upsertChapter(projectId, chapter.copy(
                outline_goal = goal.ifBlank { null },
                conflict = conflict.ifBlank { null },
                word_count = wc,
            ))
        }
        onNavigateToChapter(targetId)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = { if (isDirty) save(); onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    // Title interactions:
                    //   long novel:  tap → chapter switcher,  long-press → rename
                    //   short novel: tap → rename             (no switcher available)
                    // The dirty marker " *" comes from the editor having unsaved edits.
                    val titleText = buildString {
                        append("${chapter.order_index}. ${chapter.title}")
                        if (isDirty) append(" *")
                    }
                    val titleModifier = if (isLongNovel) {
                        Modifier.combinedClickable(
                            onClick = { showChapterSwitcher = true },
                            onLongClick = { showRenameDialog = true },
                        )
                    } else {
                        Modifier.combinedClickable(
                            onClick = { showRenameDialog = true },
                            onLongClick = { showRenameDialog = true },
                        )
                    }
                    Text(
                        titleText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier,
                    )
                },
                actions = {
                    // Long-novel-only: prev / next chapter shortcuts. Sit to the LEFT of 境界/保存.
                    if (isLongNovel) {
                        IconButton(
                            onClick = { prevChapter?.let { gotoChapter(it.id) } },
                            enabled = prevChapter != null,
                        ) {
                            Icon(Icons.Outlined.ChevronLeft,
                                contentDescription = tx(lang, "上一章", "Previous chapter"))
                        }
                        IconButton(
                            onClick = { nextChapter?.let { gotoChapter(it.id) } },
                            enabled = nextChapter != null,
                        ) {
                            Icon(Icons.Outlined.ChevronRight,
                                contentDescription = tx(lang, "下一章", "Next chapter"))
                        }
                    }
                    IconButton(onClick = save) { Icon(Icons.Outlined.Save, contentDescription = null) }
                    // Overflow menu: collapses 问答 / 容器 / 境界 so the title keeps its space.
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = tx(lang, "更多", "More"))
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(tx(lang, "问答", "Ask AI")) },
                                leadingIcon = { Icon(Icons.Outlined.QuestionAnswer, contentDescription = null) },
                                onClick = { showOverflowMenu = false; onOpenQa() },
                            )
                            DropdownMenuItem(
                                text = { Text(tx(lang, "容器", "Containers")) },
                                leadingIcon = { Icon(Icons.Outlined.Inventory2, contentDescription = null) },
                                onClick = { showOverflowMenu = false; onOpenContainer() },
                            )
                            DropdownMenuItem(
                                text = { Text(tx(lang, "境界", "Realms")) },
                                leadingIcon = { Icon(Icons.Outlined.Explore, contentDescription = null) },
                                onClick = { showOverflowMenu = false; showRealmDialog = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
        ) {
            // ── Planning card ────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tx(lang, "规划", "Plan"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (!planExpanded && goal.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                goal.take(40) + if (goal.length > 40) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        IconButton(onClick = { planExpanded = !planExpanded }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                if (planExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    AnimatedVisibility(visible = planExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spacer(Modifier.height(2.dp))
                            OutlinedTextField(
                                value = goal, onValueChange = { goal = it },
                                label = { Text(tx(lang, "本章目标", "Chapter goal")) },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                            )
                            OutlinedTextField(
                                value = conflict, onValueChange = { conflict = it },
                                label = { Text(tx(lang, "核心冲突", "Core conflict")) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            TextButton(
                                onClick = { showAiFill = true },
                                enabled = !isGenerating && !isAiFilling,
                                modifier = Modifier.padding(0.dp),
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tx(lang, "AI 助填 目标/冲突", "AI Fill Goal/Conflict"),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            enabled = !isGenerating,
                            onClick = {
                                vm.generateChapter(
                                    projectId = projectId,
                                    chapter = chapter.copy(
                                        outline_goal = goal.ifBlank { null },
                                        conflict = conflict.ifBlank { null },
                                    ),
                                    currentContent = null,
                                )
                                planExpanded = false
                            },
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "生成", "Generate"), style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            enabled = !isGenerating && finalText.isNotBlank(),
                            onClick = {
                                vm.generateChapter(
                                    projectId = projectId,
                                    chapter = chapter.copy(
                                        outline_goal = goal.ifBlank { null },
                                        conflict = conflict.ifBlank { null },
                                    ),
                                    currentContent = finalText,
                                )
                                planExpanded = false
                            },
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "续写", "Continue"), style = MaterialTheme.typography.labelMedium)
                        }
                        if (isGenerating) {
                            FilledTonalButton(onClick = { vm.stopGenerating() }) {
                                Icon(Icons.Outlined.Stop, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tx(lang, "停止", "Stop"), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FilledTonalButton(
                            onClick = { reviseSelection = finalText; showRevise = true },
                            enabled = finalText.isNotBlank() && !isGenerating,
                        ) {
                            Icon(Icons.Outlined.SwapVert, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "润色", "Revise"), style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = {
                                if (finalText.length < 100) {
                                    promoError = tx(lang, "章节内容太少（至少需要100字）", "Chapter too short (need 100+ chars)")
                                } else {
                                    promoError = null
                                    showPromoConfig = true
                                }
                            },
                            enabled = !isGeneratingPromo && !isGenerating,
                        ) {
                            if (isGeneratingPromo) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Image, null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "推文", "Promo"), style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = { showInsertDialog = true },
                            enabled = !isGenerating,
                        ) {
                            Icon(Icons.Outlined.PlaylistAdd, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "插入章节", "Insert"), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Only surface non-"saved" status messages here. Save success/failure goes
                    // through the Snackbar now; saved/dirty state lives in the title's "*".
                    if (status.isNotBlank() &&
                        status != tx(lang, "已保存", "Saved") &&
                        status != "已保存" && status != "Saved"
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // ── Promo error / banner ──────────────────────────────────────────────
                    promoError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    promoResult?.let { promo ->
                        val bmp = remember(promo.imageBase64) { promo.imageBase64?.let { base64ToBitmap(it) } }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { promoExpanded = !promoExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Image, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                tx(lang, "章节推文", "Chapter Promo"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { promoExpanded = !promoExpanded }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    if (promoExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null, modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        AnimatedVisibility(visible = promoExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                bmp?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(3f),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (promo.summary.isNotBlank()) {
                                    Text(promo.summary, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(
                                    onClick = {
                                        if (finalText.length < 100) {
                                            promoError = tx(lang, "章节内容太少（至少需要100字）", "Chapter too short (need 100+ chars)")
                                        } else {
                                            promoError = null; showPromoConfig = true
                                        }
                                    },
                                    enabled = !isGeneratingPromo,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(tx(lang, "重新生成", "Regenerate"), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            // ── Arc status + Final/Draft tabs on the SAME row (saves a full row of vertical
            // space vs. the old stacked layout). Ratio 1:2 — arc pill on the left, tabs on the
            // right. Whole row uses labelSmall to match the arc banner's original size. For long
            // novels the arc pill ALWAYS shows: "active / ending / no-active" — the placeholder
            // case still renders so the row's layout doesn't shift as the active arc changes.
            // Short novels skip the arc area entirely.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLongNovel) {
                    val (bg, fg, label) = when {
                        activeArc == null -> Triple(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            tx(lang, "无激活弧线", "No active arc"),
                        )
                        activeArc.status == "ending" -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            tx(lang, "⚠ 弧线结尾：${activeArc.title}",
                               "⚠ Arc ending: ${activeArc.title}"),
                        )
                        else -> Triple(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                            tx(lang, "弧线进行中：${activeArc.title}",
                               "Arc active: ${activeArc.title}"),
                        )
                    }
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(bg, shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(2f)) {
                        SegmentedButton(
                            selected = tab == EditorTab.Final, onClick = { tab = EditorTab.Final },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(tx(lang, "正文", "Final"), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(
                            selected = tab == EditorTab.Illustration, onClick = { tab = EditorTab.Illustration },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(tx(lang, "插图", "Illustration"), style = MaterialTheme.typography.labelSmall) }
                    }
                } else {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = tab == EditorTab.Final, onClick = { tab = EditorTab.Final },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(tx(lang, "正文", "Final"), style = MaterialTheme.typography.labelSmall) }
                        SegmentedButton(
                            selected = tab == EditorTab.Illustration, onClick = { tab = EditorTab.Illustration },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(tx(lang, "插图", "Illustration"), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }

            // Editor body — claims ALL remaining vertical space right down to the window's
            // bottom inset. Renders one of two views based on the selected tab:
            //   - Final: classic full-screen prose editor with word-count footer
            //   - Illustration: paragraph list with selection checkboxes + anchored inline images
            Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                when (tab) {
                    EditorTab.Final -> Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp)
                                .padding(top = 12.dp, bottom = 6.dp),
                        ) {
                            BasicTextField(
                                value = finalText,
                                onValueChange = { finalText = it },
                                textStyle = TextStyle(
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Serif,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Fixed word-count footer — always visible, no overlap, no wasted gap.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                "${countWords(finalText)} ${tx(lang, "字", "words")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    EditorTab.Illustration -> IllustrationTab(
                        lang = lang,
                        finalText = finalText,
                        illustrations = illustrations,
                        selectedParagraphs = selectedParagraphs,
                        isGenerating = isGeneratingIllustration,
                        errorMessage = illustrationError,
                        onOpenConfig = {
                            if (finalText.isBlank()) {
                                illustrationError = tx(lang,
                                    "正文为空，无法生成插图。请先在「正文」tab 写入内容。",
                                    "Final text is empty. Add prose in the Final tab first.")
                            } else if (selectedParagraphs.isEmpty()) {
                                illustrationError = tx(lang,
                                    "请先勾选需要生成插图的段落。",
                                    "Select paragraphs before generating illustrations.")
                            } else {
                                illustrationError = null
                                showIllustrationConfig = true
                            }
                        },
                        onClearSelection = { selectedParagraphs.clear() },
                        onAskDelete = { id -> pendingDeleteIllustrationId = id },
                        onMoveIllustration = { id, delta ->
                            val idx = illustrations.indexOfFirst { it.id == id }
                            if (idx >= 0) {
                                val ill = illustrations[idx]
                                val updated = ill.copy(anchorIndex = ill.anchorIndex + delta)
                                illustrations[idx] = updated
                                vm.updateIllustration(chapterId, updated)
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Chapter switcher popup (long-novel only) ──────────────────────────────
    if (showChapterSwitcher) {
        ChapterSwitcherDialog(
            lang = lang,
            chapters = orderedChapters,
            currentChapterId = chapterId,
            onPick = { picked ->
                showChapterSwitcher = false
                if (picked != chapterId) gotoChapter(picked)
            },
            onDismiss = { showChapterSwitcher = false },
        )
    }

    // ── Insert new chapter dialog (before / after current) ───────────────────────
    if (showInsertDialog) {
        val defaultTitle = tx(lang, "新章节", "New Chapter")
        val insert: (Boolean) -> Unit = { before ->
            showInsertDialog = false
            val created = vm.insertChapter(projectId, chapterId, before, defaultTitle)
            if (created != null) gotoChapter(created.id)
        }
        AlertDialog(
            onDismissRequest = { showInsertDialog = false },
            title = { Text(tx(lang, "插入新章节", "Insert New Chapter")) },
            text = {
                Text(tx(lang,
                    "在本章「${chapter.title}」的哪个位置插入新章节？",
                    "Where should the new chapter go relative to \"${chapter.title}\"?"))
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { insert(true) }) { Text(tx(lang, "插入到前面", "Before")) }
                    TextButton(onClick = { insert(false) }) { Text(tx(lang, "插入到后面", "After")) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showInsertDialog = false }) { Text(tx(lang, "取消", "Cancel")) }
            },
        )
    }

    // ── Rename chapter dialog (long-press on title, tap-on-title in short mode) ───
    if (showRenameDialog) {
        RenameDialog(
            title = tx(lang, "重命名章节", "Rename Chapter"),
            label = tx(lang, "章节标题", "Chapter title"),
            initialValue = chapter.title,
            confirmLabel = tx(lang, "保存", "Save"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { newTitle ->
                vm.upsertChapter(projectId, chapter.copy(title = newTitle))
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    // ── AI 助填 dialog ─────────────────────────────────────────────────────────
    if (showAiFill) {
        var requirements by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.stopAiFill(); vm.clearAiFill(); showAiFill = false },
            title = { Text(tx(lang, "AI 助填", "AI Fill")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = requirements, onValueChange = { requirements = it },
                        label = { Text(tx(lang, "要求（可选）", "Requirements (optional)")) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                vm.generateChapterOutline(
                                    projectId = projectId,
                                    chapterOrderIndex = chapter.order_index,
                                    userRequirements = requirements,
                                )
                            },
                            enabled = !isAiFilling,
                        ) { Text(tx(lang, "生成", "Generate")) }
                        if (isAiFilling) {
                            FilledTonalButton(onClick = { vm.stopAiFill() }) {
                                Icon(Icons.Outlined.Stop, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tx(lang, "停止", "Stop"))
                            }
                        }
                    }
                    if (isAiFilling) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(tx(lang, "生成中…", "Generating…"),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (aiFillText.isNotBlank()) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Text(aiFillText, modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = aiFillText.isNotBlank() && !isAiFilling,
                    onClick = {
                        val titleLine = Regex("""(?:标题|Title)[：:＊*\s]*(.+)""").find(aiFillText)?.groupValues?.getOrNull(1)?.trim()
                        val goalLine  = Regex("""(?:目标|Goal)[：:＊*\s]*(.+)""").find(aiFillText)?.groupValues?.getOrNull(1)?.trim()
                        val conflictLine = Regex("""(?:冲突|Conflict)[：:＊*\s]*(.+)""").find(aiFillText)?.groupValues?.getOrNull(1)?.trim()
                        if (!goalLine.isNullOrBlank()) goal = goalLine
                        if (!conflictLine.isNullOrBlank()) conflict = conflictLine
                        vm.clearAiFill()
                        showAiFill = false
                        if (!titleLine.isNullOrBlank() && titleLine != chapter.title) {
                            pendingTitleFromFill = titleLine
                        }
                    },
                ) { Text(tx(lang, "应用", "Apply")) }
            },
            dismissButton = {
                TextButton(onClick = { vm.stopAiFill(); vm.clearAiFill(); showAiFill = false }) {
                    Text(tx(lang, "关闭", "Close"))
                }
            },
        )
    }

    pendingTitleFromFill?.let { newTitle ->
        AlertDialog(
            onDismissRequest = { pendingTitleFromFill = null },
            title = { Text(tx(lang, "更换章节标题？", "Replace Chapter Title?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tx(lang, "当前标题：${chapter.title}", "Current: ${chapter.title}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tx(lang, "AI 建议：$newTitle", "AI suggested: $newTitle"),
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.upsertChapter(projectId, chapter.copy(title = newTitle))
                    pendingTitleFromFill = null
                }) { Text(tx(lang, "更换", "Replace")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTitleFromFill = null }) {
                    Text(tx(lang, "保留原标题", "Keep original"))
                }
            },
        )
    }

    if (showRevise) {
        var goals by remember { mutableStateOf("") }
        var revised by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRevise = false },
            title = { Text(tx(lang, "AI 润色", "AI Revise")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = goals, onValueChange = { goals = it },
                        label = { Text(tx(lang, "润色目标（可选）", "Goals (optional)")) }, singleLine = true)
                    OutlinedTextField(value = reviseSelection, onValueChange = { reviseSelection = it },
                        label = { Text(tx(lang, "待润色段落", "Passage")) }, minLines = 4, maxLines = 8)
                    if (revised.isNotBlank()) {
                        Text(tx(lang, "润色结果：", "Result:"), style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(value = revised, onValueChange = { revised = it },
                            modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8)
                    }
                    if (reviseInProgress) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (reviseSelection.isBlank()) return@TextButton
                        reviseInProgress = true
                        scope.launch {
                            val r = vm.reviseSelection(reviseSelection, goals.ifBlank { null })
                            if (r != null) revised = r
                            reviseInProgress = false
                        }
                    }) { Text(tx(lang, "重写", "Rewrite")) }
                    TextButton(
                        enabled = revised.isNotBlank(),
                        onClick = { finalText = finalText.replace(reviseSelection, revised); showRevise = false },
                    ) { Text(tx(lang, "应用", "Apply")) }
                }
            },
            dismissButton = { TextButton(onClick = { showRevise = false }) { Text(tx(lang, "关闭", "Close")) } },
        )
    }

    if (showRealmDialog) {
        RealmInfoDialog(
            lang = lang,
            realms = realms,
            characters = characters,
            onUpdateCharacter = { updated ->
                vm.setCharacters(projectId, characters.map { if (it.id == updated.id) updated else it })
            },
            onDismiss = { showRealmDialog = false },
        )
    }

    // ── Illustration config dialog (model / width / height / style) ────────────────
    if (showIllustrationConfig) {
        IllustrationConfigDialog(
            lang = lang,
            initial = illustrationConfig,
            characters = characters,
            onDismiss = { showIllustrationConfig = false },
            onConfirm = { cfg, charactersInfo ->
                illustrationConfig = cfg
                showIllustrationConfig = false
                // Resolve the actual paragraph text + first-paragraph anchor at confirm time so
                // the user's last-second toggles are honoured.
                val paragraphs = splitParagraphs(finalText)
                val sortedSel = selectedParagraphs.toSet().sorted()
                if (sortedSel.isEmpty()) return@IllustrationConfigDialog
                val anchor = sortedSel.first()
                val selectedText = sortedSel
                    .mapNotNull { idx -> paragraphs.getOrNull(idx - 1) }
                    .joinToString("\n\n")
                isGeneratingIllustration = true
                illustrationError = null
                vm.generateIllustration(
                    chapterId = chapterId,
                    paragraphText = selectedText,
                    anchorIndex = anchor,
                    paragraphIndices = sortedSel,
                    style = cfg.style.ifBlank { null },
                    model = cfg.model.ifBlank { "zimage" },
                    width = cfg.width.coerceAtLeast(64),
                    height = cfg.height.coerceAtLeast(64),
                    charactersInfo = charactersInfo,
                ) { result, err ->
                    isGeneratingIllustration = false
                    if (result != null) {
                        illustrations += result
                        selectedParagraphs.clear()
                        illustrationError = null
                    } else {
                        illustrationError = err
                    }
                }
            },
        )
    }

    // ── Delete-illustration confirmation ───────────────────────────────────────────
    pendingDeleteIllustrationId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteIllustrationId = null },
            title = { Text(tx(lang, "删除插图？", "Delete this illustration?")) },
            text = {
                Text(
                    tx(lang, "删除后无法恢复。", "This cannot be undone."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteIllustration(chapterId, id)
                    illustrations.removeAll { it.id == id }
                    pendingDeleteIllustrationId = null
                }) { Text(tx(lang, "删除", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIllustrationId = null }) {
                    Text(tx(lang, "取消", "Cancel"))
                }
            },
        )
    }

    // ── Promo config dialog ────────────────────────────────────────────────────────────────
    if (showPromoConfig) {
        PromoConfigDialog(
            lang = lang,
            onDismiss = { showPromoConfig = false },
            onConfirm = { style, model, width, height ->
                showPromoConfig = false
                isGeneratingPromo = true
                promoError = null
                vm.generateChapterPromo(
                    chapterId = chapterId,
                    chapterTitle = chapter.title,
                    chapterContent = finalText,
                    style = style.ifBlank { null },
                    model = model,
                    width = width,
                    height = height,
                ) { result, err ->
                    isGeneratingPromo = false
                    if (result != null) {
                        promoResult = result
                        promoExpanded = true
                    } else {
                        promoError = err
                    }
                }
            },
        )
    }
}

/** Popup list of all chapters in this project; current one is highlighted. */
@Composable
private fun ChapterSwitcherDialog(
    lang: String,
    chapters: List<Chapter>,
    currentChapterId: String,
    onPick: (chapterId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "切换章节", "Switch Chapter")) },
        text = {
            if (chapters.isEmpty()) {
                Text(tx(lang, "暂无章节", "No chapters"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(chapters, key = { it.id }) { ch ->
                        val isCurrent = ch.id == currentChapterId
                        val bg = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                 else MaterialTheme.colorScheme.surface
                        val fg = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                 else MaterialTheme.colorScheme.onSurface
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg, shape = MaterialTheme.shapes.small)
                                .clickable { onPick(ch.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${ch.order_index}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = fg,
                                modifier = Modifier.width(28.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ch.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!ch.outline_goal.isNullOrBlank()) {
                                    Text(
                                        ch.outline_goal,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = fg.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (ch.word_count > 0) {
                                Text(
                                    "${ch.word_count}${tx(lang, "字", "w")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = fg.copy(alpha = 0.7f),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "关闭", "Close")) }
        },
    )
}

private enum class EditorTab { Final, Illustration }

// PC parity: model / width / height / style — written verbatim into the Pollinations request.
private data class IllustrationConfig(
    val model: String,
    val width: Int,
    val height: Int,
    val style: String,
)

private fun countWords(text: String): Int {
    var count = 0; var inWord = false
    for (ch in text) {
        if (ch.code in 0x4E00..0x9FFF) { count += 1; inWord = false }
        else if (ch.isLetterOrDigit()) { if (!inWord) { count += 1; inWord = true } }
        else inWord = false
    }
    return count
}

/** Split chapter text into paragraphs the same way PC does: by one-or-more blank lines. */
private fun splitParagraphs(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isEmpty()) return emptyList()
    return normalized
        .split(Regex("\\n\\s*\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

// `base64ToBitmap` lives in CharactersScreen.kt as `internal` and is already accessible from
// here within the same module — no need to redeclare. Keeping a single definition avoids the
// "Conflicting overloads" / "Overload resolution ambiguity" errors we hit on first build.

@Composable
private fun IllustrationTab(
    lang: String,
    finalText: String,
    illustrations: List<Illustration>,
    selectedParagraphs: androidx.compose.runtime.snapshots.SnapshotStateList<Int>,
    isGenerating: Boolean,
    errorMessage: String?,
    onOpenConfig: () -> Unit,
    onClearSelection: () -> Unit,
    onAskDelete: (illustrationId: String) -> Unit,
    onMoveIllustration: (illustrationId: String, delta: Int) -> Unit,
) {
    val paragraphs = remember(finalText) { splitParagraphs(finalText) }
    val byAnchor by remember { derivedStateOf { illustrations.groupBy { it.anchorIndex } } }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Toolbar row ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tx(lang,
                    "已选 ${selectedParagraphs.size} 段 · 共 ${paragraphs.size} 段",
                    "${selectedParagraphs.size} selected · ${paragraphs.size} paragraphs"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selectedParagraphs.isNotEmpty()) {
                TextButton(onClick = onClearSelection) {
                    Icon(Icons.Outlined.Clear, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tx(lang, "清除", "Clear"),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            FilledTonalButton(
                onClick = onOpenConfig,
                enabled = !isGenerating,
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Image, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    tx(lang, "生成插图", "Generate"),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        // ── Paragraph list — each paragraph is a checkable row with its anchored images ─
        if (paragraphs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tx(lang,
                        "正文为空。先去「正文」tab 写内容，再来这里勾选段落生成插图。",
                        "No prose yet. Write some text in the Final tab, then return here to add illustrations."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                items(paragraphs.size, key = { it }) { i ->
                    val idx = i + 1  // 1-based per PC convention
                    val isSelected = idx in selectedParagraphs
                    val attached = byAnchor[idx].orEmpty()
                    ParagraphRow(
                        index = idx,
                        text = paragraphs[i],
                        selected = isSelected,
                        onToggle = {
                            if (isSelected) selectedParagraphs.remove(idx)
                            else selectedParagraphs.add(idx)
                        },
                        attached = attached,
                        totalParagraphs = paragraphs.size,
                        onAskDelete = onAskDelete,
                        onMoveIllustration = onMoveIllustration,
                        lang = lang,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ParagraphRow(
    index: Int,
    text: String,
    selected: Boolean,
    onToggle: () -> Unit,
    attached: List<Illustration>,
    totalParagraphs: Int,
    onAskDelete: (String) -> Unit,
    onMoveIllustration: (illustrationId: String, delta: Int) -> Unit,
    lang: String,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(top = 10.dp, start = 2.dp)) {
                Text(
                    "$index. $text",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        attached.forEach { ill ->
            IllustrationCard(
                ill = ill,
                expanded = expandedId == ill.id,
                onToggleExpand = { expandedId = if (expandedId == ill.id) null else ill.id },
                canMoveUp = ill.anchorIndex > 1,
                canMoveDown = ill.anchorIndex < totalParagraphs,
                onMoveUp = { onMoveIllustration(ill.id, -1) },
                onMoveDown = { onMoveIllustration(ill.id, +1) },
                onDelete = { onAskDelete(ill.id) },
                lang = lang,
            )
        }
    }
}

@Composable
private fun IllustrationCard(
    ill: Illustration,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    lang: String,
) {
    val bmp = remember(ill.imageBase64) { base64ToBitmap(ill.imageBase64) }
    var showPreview by remember { mutableStateOf(false) }

    // Full-screen preview dialog
    if (showPreview && bmp != null) {
        Dialog(
            onDismissRequest = { showPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { showPreview = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (bmp != null) {
                val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect.coerceAtLeast(0.5f).coerceAtMost(3f))
                        .clickable { showPreview = true },
                )
            } else {
                Text(
                    tx(lang, "（图像数据损坏）", "(image data corrupted)"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ill.prompt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { onToggleExpand() },
                )
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = tx(lang, "上移", "Move up"),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = tx(lang, "下移", "Move down"),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = tx(lang, "删除", "Delete"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun IllustrationConfigDialog(
    lang: String,
    initial: IllustrationConfig,
    characters: List<Character>,
    onDismiss: () -> Unit,
    onConfirm: (IllustrationConfig, charactersInfo: String?) -> Unit,
) {
    var model by remember { mutableStateOf(initial.model) }
    var width by remember { mutableStateOf(initial.width.toString()) }
    var height by remember { mutableStateOf(initial.height.toString()) }
    var style by remember { mutableStateOf(initial.style) }
    var selectedCharIds by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "插图生成参数", "Illustration Settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(tx(lang, "图像模型", "Image model")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "宽度", "Width")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "高度", "Height")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text(tx(lang, "画风（可选）", "Style hint (optional)")) },
                    placeholder = {
                        Text(
                            tx(lang, "如：水墨画、赛博朋克、漫画…",
                               "e.g. ink wash, cyberpunk, anime…"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Character consistency: depict the picked characters with their saved appearance.
                CharacterConsistencyPicker(lang, characters, selectedCharIds) { id ->
                    selectedCharIds = if (id in selectedCharIds) selectedCharIds - id else selectedCharIds + id
                }
                Text(
                    tx(lang,
                        "由 gen.pollinations.ai 提供。默认 zimage 质量最好；备选 flux / turbo / sdxl。建议在「设置 → Pollinations Key」填入 sk_/pk_ key（从 auth.pollinations.ai 获取），匿名调用画质和限流都较差。",
                        "Served by gen.pollinations.ai. Default 'zimage' is the highest-quality option; flux / turbo / sdxl are alternatives. Fill in an sk_/pk_ key in Settings → Pollinations Key (from auth.pollinations.ai) for proper quality and quotas — anonymous calls are heavily throttled."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    IllustrationConfig(
                        model = model.trim().ifBlank { "zimage" },
                        width = width.toIntOrNull()?.coerceAtLeast(64) ?: 1024,
                        height = height.toIntOrNull()?.coerceAtLeast(64) ?: 576,
                        style = style.trim(),
                    ),
                    buildCharactersInfo(characters, selectedCharIds),
                )
            }) { Text(tx(lang, "开始生成", "Generate")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) }
        },
    )
}

@Composable
private fun PromoConfigDialog(
    lang: String,
    onDismiss: () -> Unit,
    onConfirm: (style: String, model: String, width: Int, height: Int) -> Unit,
) {
    var model by remember { mutableStateOf("zimage") }
    var style by remember { mutableStateOf("cinematic") }
    var widthStr by remember { mutableStateOf("1200") }
    var heightStr by remember { mutableStateOf("400") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "推文生成参数", "Promo Settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text(tx(lang, "图像模型", "Image Model")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = style, onValueChange = { style = it },
                    label = { Text(tx(lang, "图片风格（可选）", "Style hint (optional)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthStr, onValueChange = { widthStr = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "宽度", "Width")) },
                        modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        value = heightStr, onValueChange = { heightStr = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "高度", "Height")) },
                        modifier = Modifier.weight(1f), singleLine = true,
                    )
                }
                Text(
                    tx(lang,
                        "推文图片为本章顶部横幅（3:1 比例），建议宽为高的3倍，如 1200×400。由 gen.pollinations.ai 提供。",
                        "Promo image is a 3:1 chapter header banner. Recommended: width = 3× height (e.g. 1200×400). Served by gen.pollinations.ai."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    style.trim(),
                    model.trim().ifBlank { "zimage" },
                    widthStr.toIntOrNull()?.coerceAtLeast(64) ?: 1200,
                    heightStr.toIntOrNull()?.coerceAtLeast(64) ?: 400,
                )
            }) { Text(tx(lang, "开始生成", "Generate")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) }
        },
    )
}
