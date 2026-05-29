package com.example.novelseek_ultra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.RenameDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.CoverImageConfig
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.data.model.PlotArc
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongNovelScreen(
    vm: AppViewModel,
    projectId: String,
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    onOpenOutline: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenCultivation: () -> Unit,
) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val project = remember(state, projectId) { vm.project(projectId) }
    val arcs = remember(state, projectId) { vm.plotArcs(projectId).sortedBy { it.order } }
    val chapters = remember(state, projectId) { vm.chapters(projectId) }
    val characters = remember(state, projectId) { vm.characters(projectId) }
    val realms = remember(state, projectId) { vm.cultivationRealms(projectId) }
    val isGenerating by vm.isGenerating.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val scope = rememberCoroutineScope()

    var showArcDialog by remember { mutableStateOf<PlotArc?>(null) }
    var showCreateArc by remember { mutableStateOf(false) }
    var showAiArcDialog by remember { mutableStateOf(false) }
    var showCreateChapter by remember { mutableStateOf(false) }
    var showMiniOutlineArcId by remember { mutableStateOf<String?>(null) }
    var showRealmDialog by remember { mutableStateOf(false) }
    var showOutlinePreview by remember { mutableStateOf(false) }
    var showBookSummaryResult by remember { mutableStateOf<String?>(null) }
    var buildingBookSummary by remember { mutableStateOf(false) }
    var deletingArc by remember { mutableStateOf<PlotArc?>(null) }
    var renamingChapter by remember { mutableStateOf<Chapter?>(null) }
    var deletingChapter by remember { mutableStateOf<Chapter?>(null) }
    var arcsExpanded by remember { mutableStateOf(true) }
    var navInput by remember { mutableStateOf("") }
    var showScrollArrows by remember { mutableStateOf(false) }
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
    val snackbarHost = remember { SnackbarHostState() }

    // ── Chapter pagination ──────────────────────────────────────────────────────
    // Render at most CHAPTERS_PER_PAGE chapter cards at a time to avoid laying out hundreds of
    // items at once. The "章节列表" header carries < range > pager controls; the existing nav
    // box jumps to the right page AND scrolls to the chapter.
    val sortedChapters = remember(chapters) { chapters.sortedBy { it.order_index } }
    val chapterPageSize = 20
    val chapterPageCount = if (sortedChapters.isEmpty()) 1
        else (sortedChapters.size + chapterPageSize - 1) / chapterPageSize
    var chapterPage by remember { mutableStateOf(0) }
    val safePage = chapterPage.coerceIn(0, (chapterPageCount - 1).coerceAtLeast(0))
    val pageStart = safePage * chapterPageSize
    val pageEnd = (pageStart + chapterPageSize).coerceAtMost(sortedChapters.size)
    val pageChapters = if (pageStart < pageEnd) sortedChapters.subList(pageStart, pageEnd) else emptyList()
    // posWithinPage to scroll to after a page switch (set by the nav search).
    var pendingScrollPos by remember { mutableStateOf<Int?>(null) }
    // Leading LazyColumn items before the first chapter card: stats(1) + actionRow(1) +
    // arcsHeader(1) + arcs(arcsItemCount) + chaptersHeader(1) = 4 + arcsItemCount.
    val arcsItemCount = if (arcsExpanded) maxOf(1, arcs.size) else 0
    val firstChapterItemIndex = 4 + arcsItemCount

    LaunchedEffect(pendingScrollPos, safePage) {
        val pos = pendingScrollPos ?: return@LaunchedEffect
        lazyListState.animateScrollToItem((firstChapterItemIndex + pos).coerceAtLeast(0))
        pendingScrollPos = null
    }

    // Hide the FAB only when the list is BOTH scrolled past the top AND the last item is fully
    // visible. Brand-new projects whose content doesn't fill a screen can't scroll backward at
    // all, so the FAB stays visible regardless. "Already-at-top" wins over "also-at-bottom".
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

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (lazyListState.isScrollInProgress) {
            showScrollArrows = true
        } else {
            delay(1_000L)
            showScrollArrows = false
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
                },
                title = { Text(project.title, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { showRealmDialog = true }) {
                        Icon(Icons.Outlined.Explore, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !shouldHideFab,
                enter = EnterTransition.None,
                exit = fadeOut(tween(durationMillis = 1000)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateChapter = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(tx(lang, "新建章节", "New Chapter")) },
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                // Full-width tappable stats card → opens markdown outline preview. fillMaxWidth so
                // the card spans the list even when the description is short.
                Card(modifier = Modifier.fillMaxWidth().clickable { showOutlinePreview = true }) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (!project.description.isNullOrBlank()) {
                            Text(project.description, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "${project.current_word_count} ${tx(lang, "字", "words")} · ${chapters.size} ${tx(lang, "章", "chapters")} · ${arcs.size} ${tx(lang, "弧线", "arcs")}",
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
                    FilledTonalButton(onClick = onOpenCultivation) {
                        Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "境界", "Realms"), maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(onClick = onOpenExport) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "导出", "Export"), maxLines = 1, softWrap = false)
                    }
                    // 5th button: generate whole-book synopsis via vm.generateBookSummary, which
                    // rolls up existing chapter/arc summaries via the active text model.
                    FilledTonalButton(
                        enabled = !buildingBookSummary,
                        onClick = {
                            buildingBookSummary = true
                            vm.generateBookSummary(projectId) { payload ->
                                buildingBookSummary = false
                                if (payload != null) {
                                    showBookSummaryResult = payload.summaryText
                                } else {
                                    scope.launch {
                                        snackbarHost.showSnackbar(tx(lang,
                                            "生成失败：可能尚无章节摘要，请先在「设置 → 增强层」重建章节摘要。",
                                            "Failed — chapter summaries are required. Build them in Settings → Augmentation Layers."))
                                    }
                                }
                            }
                        },
                    ) {
                        if (buildingBookSummary) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "全书梗概", "Book Summary"), maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        onClick = { showCoverDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "封面", "Cover"), maxLines = 1, softWrap = false)
                    }
                    // Batch promo: generate chapter header images for all chapters missing one
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

            // ── Arcs section ────────────────────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tx(lang, "剧情弧线", "Story Arcs"),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAiArcDialog = true }) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "AI 生成", "AI"))
                    }
                    TextButton(onClick = { showCreateArc = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(tx(lang, "手动添加", "Add"))
                    }
                    IconButton(onClick = { arcsExpanded = !arcsExpanded }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (arcsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null, modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (arcsExpanded) {
                if (arcs.isEmpty()) {
                    item {
                        Text(tx(lang, "尚未规划弧线", "No arcs planned yet"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    itemsIndexed(arcs, key = { _, arc -> arc.id }) { idx, arc ->
                        ArcCard(arc, idx + 1, lang,
                            onCycleStatus = {
                                val next = nextArcStatus(arc.status)
                                vm.setPlotArcs(projectId, arcs.map { if (it.id == arc.id) it.copy(status = next) else it })
                            },
                            onEdit = { showArcDialog = arc },
                            onDelete = { deletingArc = arc },
                            onPlanChapters = { showMiniOutlineArcId = arc.id },
                            onMoveUp = if (idx > 0) {
                                {
                                    val prev = arcs[idx - 1]
                                    val reordered = arcs.toMutableList()
                                    reordered[idx] = arc.copy(order = prev.order)
                                    reordered[idx - 1] = prev.copy(order = arc.order)
                                    vm.setPlotArcs(projectId, reordered)
                                }
                            } else null,
                            onMoveDown = if (idx < arcs.size - 1) {
                                {
                                    val next = arcs[idx + 1]
                                    val reordered = arcs.toMutableList()
                                    reordered[idx] = arc.copy(order = next.order)
                                    reordered[idx + 1] = next.copy(order = arc.order)
                                    vm.setPlotArcs(projectId, reordered)
                                }
                            } else null,
                        )
                    }
                }
            }

            // ── Chapters section ────────────────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(tx(lang, "章节列表", "Chapters"),
                        style = MaterialTheme.typography.titleMedium)
                    // Pager: < {from}-{to} >  (only when more than one page)
                    if (chapterPageCount > 1) {
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { if (safePage > 0) chapterPage = safePage - 1 },
                            enabled = safePage > 0, modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Outlined.ChevronLeft, null, modifier = Modifier.size(18.dp)) }
                        Text(
                            "${pageStart + 1}-$pageEnd",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { if (safePage < chapterPageCount - 1) chapterPage = safePage + 1 },
                            enabled = safePage < chapterPageCount - 1, modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp)) }
                    }
                    Spacer(Modifier.weight(1f))
                    // Chapter quick-navigation: jump to the page containing the entered chapter #,
                    // then scroll to it.
                    BasicTextField(
                        value = navInput,
                        onValueChange = { navInput = it.filter { c -> c.isDigit() }.take(4) },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .width(34.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                    )
                    IconButton(
                        onClick = {
                            val idx = navInput.toIntOrNull() ?: return@IconButton
                            val pos = sortedChapters.indexOfFirst { it.order_index == idx }
                            if (pos >= 0) {
                                val targetPage = pos / chapterPageSize
                                chapterPage = targetPage
                                pendingScrollPos = pos - targetPage * chapterPageSize
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(16.dp)) }
                }
            }
            if (sortedChapters.isEmpty()) {
                item {
                    Text(tx(lang, "还没有章节", "No chapters yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(pageChapters, key = { it.id }) { ch ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ch.order_index}. ${ch.title}", style = MaterialTheme.typography.bodyLarge)
                                if (!ch.outline_goal.isNullOrBlank())
                                    Text(ch.outline_goal, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                Text("${ch.word_count} ${tx(lang, "字", "words")}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onOpenChapter(ch.id) }) { Text(tx(lang, "打开", "Open")) }
                            IconButton(onClick = { renamingChapter = ch }) {
                                Icon(Icons.Outlined.DriveFileRenameOutline,
                                    contentDescription = tx(lang, "重命名", "Rename"))
                            }
                            IconButton(onClick = { deletingChapter = ch }) {
                                Icon(Icons.Outlined.Delete,
                                    contentDescription = tx(lang, "删除", "Delete"))
                            }
                        }
                    }
                }
            }
        } // end LazyColumn

        // Scroll arrows — visible while scrolling, auto-hide 1s after stop
        AnimatedVisibility(
            visible = showScrollArrows,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { lazyListState.animateScrollToItem(0) } },
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                ) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null) }
                SmallFloatingActionButton(
                    onClick = { scope.launch { lazyListState.animateScrollToItem(lazyListState.layoutInfo.totalItemsCount - 1) } },
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                ) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) }
            }
        }
        } // end Box
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

    showBookSummaryResult?.let { summary ->
        BookSummaryResultDialog(
            lang = lang,
            summary = summary,
            onDismiss = { showBookSummaryResult = null },
        )
    }

    deletingArc?.let { arc ->
        ConfirmDialog(
            title = tx(lang, "删除弧线", "Delete Arc"),
            message = tx(lang,
                "确定要删除弧线「${arc.title}」吗？章节本身不会被删除，但这条弧线的进度、AI 续写参考、未回收伏笔关联都会丢失。",
                "Delete arc \"${arc.title}\"? Chapters themselves stay, but the arc's progress state, AI prompt context, and foreshadowing links will be lost."),
            confirmLabel = tx(lang, "删除", "Delete"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.setPlotArcs(projectId, arcs.filterNot { it.id == arc.id }) },
            onDismiss = { deletingArc = null },
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

    if (showCreateChapter) {
        var title by remember { mutableStateOf("") }
        var goal by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateChapter = false },
            title = { Text(tx(lang, "新建章节", "New Chapter")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text(tx(lang, "标题", "Title")) }, singleLine = true)
                    OutlinedTextField(goal, { goal = it }, label = { Text(tx(lang, "目标", "Goal")) }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addChapter(projectId, title, goal.ifBlank { null }); showCreateChapter = false }, enabled = title.isNotBlank()) {
                    Text(tx(lang, "创建", "Create"))
                }
            },
            dismissButton = { TextButton(onClick = { showCreateChapter = false }) { Text(tx(lang, "取消", "Cancel")) } },
        )
    }

    if (showCreateArc) {
        ArcDialog(
            lang = lang, initial = null,
            onDismiss = { showCreateArc = false },
            onSave = { arc ->
                val id = "arc-${System.currentTimeMillis()}"
                vm.setPlotArcs(projectId, arcs + arc.copy(id = id, order = arcs.size))
                showCreateArc = false
            },
        )
    }
    showArcDialog?.let { arc ->
        ArcDialog(
            lang = lang, initial = arc,
            onDismiss = { showArcDialog = null },
            onSave = { updated ->
                vm.setPlotArcs(projectId, arcs.map { if (it.id == arc.id) updated.copy(id = arc.id, order = arc.order) else it })
                showArcDialog = null
            },
        )
    }
    if (showAiArcDialog) {
        var idea by remember { mutableStateOf("") }
        var targetCh by remember { mutableStateOf("10") }
        var loading by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAiArcDialog = false },
            title = { Text(tx(lang, "AI 生成弧线", "AI Generate Arc")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(idea, { idea = it }, label = { Text(tx(lang, "想法/方向", "Idea / direction")) }, minLines = 2)
                    OutlinedTextField(targetCh, { targetCh = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "目标章节数", "Target chapter count")) }, singleLine = true)
                    if (loading) CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(enabled = idea.isNotBlank() && !loading, onClick = {
                    loading = true
                    scope.launch {
                        vm.generatePlotArc(projectId, idea, targetCh.toIntOrNull() ?: 10)
                        loading = false
                        showAiArcDialog = false
                    }
                }) { Text(tx(lang, "生成", "Generate")) }
            },
            dismissButton = { TextButton(onClick = { showAiArcDialog = false }) { Text(tx(lang, "取消", "Cancel")) } },
        )
    }

    // ── Mini-outline dialog ───────────────────────────────────────────────────
    showMiniOutlineArcId?.let { arcId ->
        val arc = arcs.find { it.id == arcId }
        if (arc != null) {
            val displayText = if (isGenerating) streaming else arc.miniOutline.orEmpty()
            AlertDialog(
                onDismissRequest = { if (!isGenerating) showMiniOutlineArcId = null },
                title = { Text(tx(lang, "章节规划：${arc.title}", "Chapter Plan: ${arc.title}")) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = { vm.generateArcMiniOutline(projectId, arcId) },
                                enabled = !isGenerating,
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tx(lang, "AI 生成计划", "AI Generate Plan"))
                            }
                            if (isGenerating) {
                                FilledTonalButton(onClick = { vm.stopGenerating() }) {
                                    Icon(Icons.Outlined.Stop, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(tx(lang, "停止", "Stop"))
                                }
                            }
                        }
                        if (isGenerating) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(tx(lang, "生成中…", "Generating…"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (displayText.isNotBlank()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Text(
                                    displayText,
                                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!isGenerating) {
                                FilledTonalButton(
                                    onClick = {
                                        val items = vm.parseArcMiniOutline(displayText)
                                        if (items.isNotEmpty()) {
                                            val newChapters = vm.addChaptersBatch(projectId, arcId, items)
                                            showMiniOutlineArcId = null
                                            newChapters.firstOrNull()?.let { onOpenChapter(it.id) }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.LibraryAdd, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(tx(lang, "从计划批量建章", "Batch Create Chapters"))
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { if (!isGenerating) showMiniOutlineArcId = null }) {
                        Text(tx(lang, "关闭", "Close"))
                    }
                },
            )
        }
    }

    // ── Cover dialog (shared) ────────────────────────────────────────────────────
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
            onSetDefault = { id ->
                vm.setDefaultCover(projectId, id)
            },
            onDismiss = { showCoverDialog = false },
        )
    }

    // Batch promo config dialog (shared)
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
private fun ArcCard(
    arc: PlotArc,
    number: Int,
    lang: String,
    onCycleStatus: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlanChapters: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number. ${arc.title}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                // Compact clickable status pill (replaces the taller AssistChip).
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(arcStatusColor(arc.status))
                        .clickable { onCycleStatus() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(arcStatusLabel(arc.status, lang), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (arc.summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    arc.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (arc.chapterCount > 0) {
                    Text("${arc.chapterCount}${tx(lang, "章", "ch")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!arc.miniOutline.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(tx(lang, "计划✓", "plan✓"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.weight(1f))
                // Compact 30dp/17dp icon buttons.
                IconButton(onClick = onPlanChapters, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.LibraryAdd, contentDescription = tx(lang, "规划章节", "Plan chapters"),
                        modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onMoveUp ?: {}, enabled = onMoveUp != null, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onMoveDown ?: {}, enabled = onMoveDown != null, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = tx(lang, "编辑", "Edit"), modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = tx(lang, "删除", "Delete"), modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun ArcDialog(lang: String, initial: PlotArc?, onDismiss: () -> Unit, onSave: (PlotArc) -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var summary by remember { mutableStateOf(initial?.summary.orEmpty()) }
    var count by remember { mutableStateOf((initial?.chapterCount ?: 10).toString()) }
    var status by remember { mutableStateOf(initial?.status ?: "upcoming") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tx(lang, "新建弧线", "New Arc") else tx(lang, "编辑弧线", "Edit Arc")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(tx(lang, "标题", "Title")) }, singleLine = true)
                OutlinedTextField(summary, { summary = it }, label = { Text(tx(lang, "概述", "Summary")) }, minLines = 3)
                OutlinedTextField(count, { count = it.filter { c -> c.isDigit() } },
                    label = { Text(tx(lang, "章节数", "Chapter count")) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("upcoming", "active", "ending", "completed").forEach { s ->
                        FilterChip(selected = status == s, onClick = { status = s },
                            label = { Text(arcStatusLabel(s, lang)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(PlotArc(
                    id = initial?.id ?: "",
                    title = title, summary = summary,
                    order = initial?.order ?: 0,
                    status = status,
                    chapterCount = count.toIntOrNull() ?: 0,
                    miniOutline = initial?.miniOutline,
                    builtChapterIds = initial?.builtChapterIds,
                ))
            }) { Text(tx(lang, "保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}

private fun nextArcStatus(s: String): String = when (s) {
    "upcoming" -> "active"
    "active" -> "ending"
    "ending" -> "completed"
    else -> "upcoming"
}

private fun arcStatusLabel(s: String, lang: String): String = when (s) {
    "upcoming" -> tx(lang, "未开始", "Upcoming")
    "active" -> tx(lang, "进行中", "Active")
    "ending" -> tx(lang, "结尾阶段", "Ending")
    "completed" -> tx(lang, "已完成", "Completed")
    else -> s
}

@Composable
private fun arcStatusColor(s: String) = when (s) {
    "active" -> MaterialTheme.colorScheme.primaryContainer
    "ending" -> MaterialTheme.colorScheme.tertiaryContainer
    "completed" -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surface
}

@Composable
private fun BookSummaryResultDialog(
    lang: String,
    summary: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "全书梗概", "Book Summary")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    tx(lang,
                        "已保存。后续生成新章节时会自动注入此梗概到提示词，帮助保持长程一致性。",
                        "Saved. This synopsis will be auto-injected into prompts for future chapter generation to preserve long-range coherence."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "好的", "OK")) }
        },
    )
}
