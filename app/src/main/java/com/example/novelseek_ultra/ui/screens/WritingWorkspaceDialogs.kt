package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.writing.ChapterScenePlan
import com.example.novelseek_ultra.data.writing.ScenePlanProtocol
import com.example.novelseek_ultra.data.writing.SceneSpec
import com.example.novelseek_ultra.data.writing.StoryNote
import com.example.novelseek_ultra.data.writing.WritingWorkspace
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.util.tx
import java.util.UUID

/** onSave must finish persistence or throw; failure stays visible without losing the draft. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WritingPreferencesDialog(
    language: String,
    workspace: WritingWorkspace,
    profiles: List<TextModelProfile>,
    onDismiss: () -> Unit,
    onSave: (WritingWorkspace) -> Unit,
) {
    var draft by remember { mutableStateOf(workspace) }
    var requestLimit by remember { mutableStateOf(workspace.maxRequestsPerRun.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val modelChoices = remember(profiles, language) {
        listOf(WorkspaceChoice(null, tx(language, "沿用全局模型", "Use global model"))) + profiles.map {
            WorkspaceChoice(it.id, "${it.name} · ${it.model}")
        }
    }
    WorkspaceDialogShell(
        language = language,
        title = tx(language, "本书写作偏好", "Writing preferences"),
        error = error,
        onDismiss = onDismiss,
        onSave = {
            runCatching {
                val maximum = requestLimit.toIntOrNull()
                    ?: throw IllegalArgumentException(tx(language, "单次请求上限请输入 1–64 的整数。", "Enter a request limit from 1 to 64."))
                val value = draft.copy(maxRequestsPerRun = maximum).validated()
                onSave(value)
            }.onSuccess { onDismiss() }.onFailure { error = it.message ?: tx(language, "保存失败，请重试。", "Unable to save. Please retry.") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(tx(language, "写作模式", "Writing mode"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("quick" to tx(language, "快速写作", "Quick"), "scene" to tx(language, "场景写作", "Scenes"), "polish" to tx(language, "精修审稿", "Polished")).forEach { (key, label) ->
                        FilterChip(selected = draft.mode == key, onClick = { draft = draft.copy(mode = key) }, label = { Text(label) })
                    }
                }
                Text(
                    when (draft.mode) {
                        "quick" -> tx(language, "直接生成整章，适合短章节与快速试写。", "Generate a whole chapter directly for short chapters and quick drafts.")
                        "polish" -> tx(language, "按场景写作，再检查约束、事实和角色知情范围；最终由你审核采用。", "Write scenes, then review constraints, facts and character knowledge. You approve the final draft.")
                        else -> tx(language, "先规划场景，再逐场写作；支持中断后继续和局部重写。", "Plan scenes and write them in order, with resumable progress and focused revisions.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { WorkspaceTextField(tx(language, "叙述人称与视角", "Narration and viewpoint"), draft.perspective, { draft = draft.copy(perspective = it) }, tx(language, "例如：第三人称限知，严格跟随女主视角", "For example: third-person limited, following the protagonist")) }
            item { WorkspaceTextField(tx(language, "文风与节奏", "Style and pacing"), draft.style, { draft = draft.copy(style = it) }, tx(language, "描写密度、对话比例、节奏、用词偏好", "Description, dialogue, pacing and diction")) }
            item { WorkspaceTextField(tx(language, "禁用表达", "Forbidden expressions"), draft.forbiddenExpressions, { draft = draft.copy(forbiddenExpressions = it) }, tx(language, "写下不希望反复出现的措辞或套路", "Phrases and habits to avoid")) }
            item { WorkspaceTextField(tx(language, "认可的正文样例", "Preferred prose sample"), draft.sampleProse, { draft = draft.copy(sampleProse = it) }, tx(language, "只参考表达方式，不复制样例中的剧情", "Use this as a style reference, without copying its plot"), maxLines = 6) }
            item {
                OutlinedTextField(
                    value = requestLimit,
                    onValueChange = { requestLimit = it },
                    label = { Text(tx(language, "单次任务请求上限（1–64）", "Requests per run (1–64)")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(tx(language, "规划、写作、续写、重试与审稿均计入上限。", "Includes planning, writing, continuation, retries and review.")) },
                )
            }
            item {
                Text(tx(language, "按创作阶段选择模型", "Models by writing stage"), style = MaterialTheme.typography.titleSmall)
                Text(tx(language, "未单独指定时沿用全局模型；可在软件设置中管理模型配置。", "Unassigned roles use the global model. Manage profiles in app settings."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { WorkspaceChoiceField(language, tx(language, "规划模型", "Planning model"), draft.planningProfileId, modelChoices) { draft = draft.copy(planningProfileId = it) } }
            item { WorkspaceChoiceField(language, tx(language, "正文模型", "Writing model"), draft.writingProfileId, modelChoices) { draft = draft.copy(writingProfileId = it) } }
            item { WorkspaceChoiceField(language, tx(language, "审稿模型", "Review model"), draft.reviewProfileId, modelChoices) { draft = draft.copy(reviewProfileId = it) } }
            item { WorkspaceChoiceField(language, tx(language, "资料提取模型", "Extraction model"), draft.extractionProfileId, modelChoices) { draft = draft.copy(extractionProfileId = it) } }
        }
    }
}

/** Cards are author-maintained; planned events never become facts merely by saving this dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryNotesDialog(
    language: String,
    workspace: WritingWorkspace,
    chapters: List<Chapter>,
    characters: List<Character>,
    onDismiss: () -> Unit,
    onSave: (WritingWorkspace) -> Unit,
    sourceStatuses: Map<String, String> = emptyMap(),
) {
    var notes by remember { mutableStateOf(workspace.notes) }
    var selectedKind by remember { mutableStateOf<String?>(null) }
    var editingNote by remember { mutableStateOf<StoryNote?>(null) }
    var deletingNote by remember { mutableStateOf<StoryNote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val chapterNames = remember(chapters) { chapters.associate { it.id to it.title } }
    val characterNames = remember(characters) { characters.associate { it.id to it.name } }
    val visibleNotes = remember(notes, selectedKind) { notes.filter { selectedKind == null || it.kind == selectedKind } }
    WorkspaceDialogShell(
        language,
        tx(language, "故事状态与伏笔", "Story state and foreshadowing"),
        error,
        onDismiss,
        onSave = {
            runCatching { onSave(workspace.copy(notes = notes).validated()) }
                .onSuccess { onDismiss() }.onFailure { error = it.message ?: tx(language, "保存失败，请重试。", "Unable to save. Please retry.") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(tx(language, "区分作者设定、未来计划、已发生事实和角色认知。卡片由你维护，保存不会自动认定剧情已经发生。", "Keep author canon, future plans, established facts and character beliefs separate. You maintain these cards; saving does not establish that an event happened."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tx(language, "关联来源章节的卡片会绑定保存时的正文。来源正文修改后，该卡片会自动退出生成上下文；请核对内容后重新绑定。", "Source-linked cards are bound to the saved chapter text. If that text changes, the card is excluded from generation until you verify and rebind it."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedKind == null, onClick = { selectedKind = null }, label = { Text(tx(language, "全部", "All")) })
                    storyKinds.forEach { kind -> FilterChip(selected = selectedKind == kind, onClick = { selectedKind = kind }, label = { Text(storyKindLabel(language, kind)) }) }
                }
                FilledTonalButton(
                    onClick = { editingNote = StoryNote(id = UUID.randomUUID().toString(), kind = selectedKind ?: "canon") },
                    enabled = notes.size < 2_000,
                ) { Text(tx(language, "新增故事卡片", "Add story card")) }
            }
            if (visibleNotes.isEmpty()) item { Text(tx(language, "此类型暂无卡片。", "No cards in this category."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(visibleNotes, key = { it.id }) { note ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(storyKindLabel(language, note.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(importanceLabel(language, note.importance), style = MaterialTheme.typography.labelSmall)
                        }
                        if (note.subject.isNotBlank()) Text(note.subject, style = MaterialTheme.typography.titleSmall)
                        Text(note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 7, overflow = TextOverflow.Ellipsis)
                        note.sourceChapterId?.let { Text(tx(language, "来源：${chapterNames[it] ?: "已删除章节"}", "Source: ${chapterNames[it] ?: "Deleted chapter"}"), style = MaterialTheme.typography.labelSmall) }
                        if (note.sourceChapterId != null) {
                            val freshness = if (note.sourceBodyHash == null) tx(language, "保存后绑定当前来源正文", "Will bind to current source text on save") else sourceStatuses[note.id]
                            freshness?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        if (note.knownByCharacterIds.isNotEmpty()) Text(tx(language, "知情人物：", "Known by: ") + note.knownByCharacterIds.joinToString { characterNames[it] ?: tx(language, "已删除角色", "Deleted character") }, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        note.payoffChapterId?.let { Text(tx(language, "计划回收：${chapterNames[it] ?: "已删除章节"}", "Payoff: ${chapterNames[it] ?: "Deleted chapter"}"), style = MaterialTheme.typography.labelSmall) }
                        if (note.kind == "foreshadowing") Text(if (note.resolved) tx(language, "已回收", "Resolved") else tx(language, "尚未回收", "Unresolved"), style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingNote = note }) { Text(tx(language, "编辑", "Edit")) }
                            TextButton(onClick = { deletingNote = note }) { Text(tx(language, "删除", "Delete")) }
                        }
                    }
                }
            }
        }
    }
    editingNote?.let { initial ->
        StoryNoteEditorDialog(
            language, initial, chapters, characters, sourceStatuses[initial.id],
            onDismiss = { editingNote = null },
            onSave = { value -> notes = if (notes.any { it.id == value.id }) notes.map { if (it.id == value.id) value else it } else notes + value },
        )
    }
    deletingNote?.let { note ->
        AlertDialog(
            onDismissRequest = { deletingNote = null },
            title = { Text(tx(language, "删除故事卡片？", "Delete story card?")) },
            text = { Text(note.subject.ifBlank { note.text.take(160) }) },
            confirmButton = { TextButton(onClick = { notes = notes.filterNot { it.id == note.id }; deletingNote = null }) { Text(tx(language, "删除", "Delete")) } },
            dismissButton = { TextButton(onClick = { deletingNote = null }) { Text(tx(language, "取消", "Cancel")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryNoteEditorDialog(
    language: String,
    initial: StoryNote,
    chapters: List<Chapter>,
    characters: List<Character>,
    sourceStatus: String?,
    onDismiss: () -> Unit,
    onSave: (StoryNote) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var choosingCharacters by remember { mutableStateOf(false) }
    var sourceConfirmed by remember(initial.id) { mutableStateOf(false) }
    val chapterChoices = remember(chapters, language) {
        listOf(WorkspaceChoice(null, tx(language, "不指定章节", "No chapter"))) + chapters.sortedBy { it.order_index }.map { WorkspaceChoice(it.id, it.title) }
    }
    WorkspaceDialogShell(language, tx(language, "编辑故事卡片", "Edit story card"), error, onDismiss, onSave = {
        runCatching {
            val value = draft.copy(subject = draft.subject.trim(), text = draft.text.trim()).validated()
            require(value.sourceChapterId == null || chapters.any { it.id == value.sourceChapterId }) { tx(language, "来源章节已不存在，请重新选择。", "The source chapter is unavailable. Choose another.") }
            require(value.payoffChapterId == null || chapters.any { it.id == value.payoffChapterId }) { tx(language, "回收章节已不存在，请重新选择。", "The payoff chapter is unavailable. Choose another.") }
            require(value.knownByCharacterIds.all { id -> characters.any { it.id == id } }) { tx(language, "有知情人物已不存在，请重新选择。", "A selected character is unavailable. Choose again.") }
            onSave(value)
        }.onSuccess { onDismiss() }.onFailure { error = it.message ?: tx(language, "卡片内容无效。", "Invalid card.") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    storyKinds.forEach { kind -> FilterChip(selected = draft.kind == kind, onClick = { draft = draft.copy(kind = kind) }, label = { Text(storyKindLabel(language, kind)) }) }
                }
                Text(storyKindDescription(language, draft.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { WorkspaceTextField(tx(language, "人物、物品或主题", "Subject"), draft.subject, { draft = draft.copy(subject = it) }, maxLines = 2) }
            item { WorkspaceTextField(tx(language, "卡片内容", "Card content"), draft.text, { draft = draft.copy(text = it) }, maxLines = 8) }
            item { WorkspaceChoiceField(language, tx(language, "来源章节${if (draft.kind == "fact") "（必选）" else ""}", "Source chapter${if (draft.kind == "fact") " (required)" else ""}"), draft.sourceChapterId, chapterChoices) {
                if (it != draft.sourceChapterId) { draft = draft.copy(sourceChapterId = it, sourceBodyHash = null); sourceConfirmed = false }
            } }
            if (draft.sourceChapterId != null) item {
                Text(
                    if (sourceConfirmed) tx(language, "已核对。保存卡片和故事状态后，将重新绑定当前正文。", "Verified. Saving the card and story state will bind it to the current chapter text.")
                    else sourceStatus ?: tx(language, "保存时绑定来源正文；来源发生修改后需重新核对。", "Binds to the source text on save; verify again after source changes."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                FilledTonalButton(onClick = { draft = draft.copy(sourceBodyHash = null); sourceConfirmed = true }) { Text(tx(language, "已核对当前正文", "Verified against current text")) }
            }
            item {
                OutlinedButton(onClick = { choosingCharacters = true }, modifier = Modifier.fillMaxWidth()) { Text(tx(language, "知情人物 · ${draft.knownByCharacterIds.size} 人", "Known by · ${draft.knownByCharacterIds.size} characters")) }
                Text(tx(language, "指定谁知道这条信息，避免其他人物提前获知。角色认知也可以是误解。", "Specify who knows this information. Character beliefs can be mistaken."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.knownByCharacterIds.isNotEmpty()) Text(draft.knownByCharacterIds.joinToString { id -> characters.firstOrNull { it.id == id }?.name ?: tx(language, "已删除角色", "Deleted character") }, style = MaterialTheme.typography.bodySmall)
            }
            item { WorkspaceChoiceField(language, tx(language, "计划回收章节", "Planned payoff chapter"), draft.payoffChapterId, chapterChoices) { draft = draft.copy(payoffChapterId = it) } }
            item {
                Text(tx(language, "重要度", "Importance"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { importance -> FilterChip(selected = draft.importance == importance, onClick = { draft = draft.copy(importance = importance) }, label = { Text(importanceLabel(language, importance)) }) }
                }
            }
            if (draft.kind == "foreshadowing") item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tx(language, "伏笔已回收", "Foreshadowing resolved"))
                    Switch(checked = draft.resolved, onCheckedChange = { draft = draft.copy(resolved = it) })
                }
            }
        }
    }
    if (choosingCharacters) {
        var selectedIds by remember { mutableStateOf(draft.knownByCharacterIds.toSet()) }
        AlertDialog(
            onDismissRequest = { choosingCharacters = false },
            title = { Text(tx(language, "选择知情人物", "Characters who know")) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (characters.isEmpty()) item { Text(tx(language, "项目中尚无角色。", "No characters in this project.")) }
                    items(characters, key = { it.id }) { character ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedIds = if (character.id in selectedIds) selectedIds - character.id else if (selectedIds.size < 100) selectedIds + character.id else selectedIds
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = character.id in selectedIds,
                                onCheckedChange = { checked -> selectedIds = if (!checked) selectedIds - character.id else if (selectedIds.size < 100) selectedIds + character.id else selectedIds },
                            )
                            Text(character.name, modifier = Modifier.weight(1f))
                        }
                    }
                    if (selectedIds.any { id -> characters.none { it.id == id } }) item {
                        TextButton(onClick = { selectedIds = selectedIds.filter { id -> characters.any { it.id == id } }.toSet() }) { Text(tx(language, "移除已删除角色", "Remove deleted characters")) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { draft = draft.copy(knownByCharacterIds = selectedIds.toList()); choosingCharacters = false }) { Text(tx(language, "确定", "Done")) } },
            dismissButton = { TextButton(onClick = { choosingCharacters = false }) { Text(tx(language, "取消", "Cancel")) } },
        )
    }
}

private data class EditableScene(
    val spec: SceneSpec,
    val targetWords: String = spec.targetWords.toString(),
    val requiredEvents: String = spec.requiredEvents.joinToString("\n"),
    val forbiddenEvents: String = spec.forbiddenEvents.joinToString("\n"),
)

@Composable
fun ScenePlanEditorDialog(
    language: String,
    plan: ChapterScenePlan,
    onDismiss: () -> Unit,
    onSave: (ChapterScenePlan) -> Unit,
) {
    var scenes by remember { mutableStateOf(plan.scenes.map { EditableScene(it) }) }
    var expandedIds by remember { mutableStateOf(plan.scenes.take(1).map { it.id }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun updateScene(id: String, transform: (EditableScene) -> EditableScene) {
        scenes = scenes.map { if (it.spec.id == id) transform(it) else it }
    }
    fun moveScene(index: Int, delta: Int) {
        val destination = index + delta
        if (destination !in scenes.indices) return
        scenes = scenes.toMutableList().also { items -> val item = items.removeAt(index); items.add(destination, item) }
    }
    WorkspaceDialogShell(language, tx(language, "章节场景计划", "Chapter scene plan"), error, onDismiss, onSave = {
        runCatching {
            require(scenes.size in 1..8) { tx(language, "每章请保留 1–8 个场景。", "Keep 1–8 scenes per chapter.") }
            val value = plan.copy(
                scenes = scenes.mapIndexed { index, editable ->
                    editable.spec.copy(
                        title = editable.spec.title.trim(),
                        targetWords = editable.targetWords.toIntOrNull()
                            ?: throw IllegalArgumentException(tx(language, "场景 ${index + 1} 的目标字数须为整数。", "Scene ${index + 1}: target length must be an integer.")),
                        requiredEvents = editable.requiredEvents.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        forbiddenEvents = editable.forbiddenEvents.lines().map { it.trim() }.filter { it.isNotEmpty() },
                    )
                },
                updatedAt = System.currentTimeMillis(),
            )
            ScenePlanProtocol.validate(value)
            onSave(value)
        }.onSuccess { onDismiss() }.onFailure { error = it.message ?: tx(language, "场景计划保存失败。", "Unable to save scene plan.") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(tx(language, "先明确每场的视角、冲突和状态变化，再生成正文。调整顺序会改变场景执行顺序；修改后的计划需要重新生成候选稿。", "Define viewpoint, conflict and state changes before writing. Reordering changes execution order; an edited plan requires a new candidate run."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tx(language, "${scenes.size}/8 场景", "${scenes.size}/8 scenes"), style = MaterialTheme.typography.labelMedium)
            }
            items(scenes, key = { it.spec.id }) { editable ->
                val scene = editable.spec
                val index = scenes.indexOfFirst { it.spec.id == scene.id }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${scene.title.ifBlank { tx(language, "未命名场景", "Untitled scene") }}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { moveScene(index, -1) }, enabled = index > 0) { Icon(Icons.Outlined.ArrowUpward, contentDescription = tx(language, "上移场景", "Move scene up"), modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { moveScene(index, 1) }, enabled = index < scenes.lastIndex) { Icon(Icons.Outlined.ArrowDownward, contentDescription = tx(language, "下移场景", "Move scene down"), modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { expandedIds = if (scene.id in expandedIds) expandedIds - scene.id else expandedIds + scene.id }) { Icon(if (scene.id in expandedIds) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = tx(language, "展开或收起场景", "Expand or collapse scene")) }
                        }
                        if (scene.id in expandedIds) {
                            WorkspaceTextField(tx(language, "场景标题", "Scene title"), scene.title, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(title = value)) } }, maxLines = 2)
                            WorkspaceTextField(tx(language, "视角人物", "Viewpoint character"), scene.pov, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(pov = value)) } }, maxLines = 2)
                            WorkspaceTextField(tx(language, "时间", "Time"), scene.time, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(time = value)) } }, maxLines = 2)
                            WorkspaceTextField(tx(language, "地点", "Location"), scene.location, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(location = value)) } }, maxLines = 2)
                            WorkspaceTextField(tx(language, "本场目标（必填）", "Scene goal (required)"), scene.goal, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(goal = value)) } })
                            WorkspaceTextField(tx(language, "冲突", "Conflict"), scene.conflict, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(conflict = value)) } })
                            WorkspaceTextField(tx(language, "转折", "Turning point"), scene.turn, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(turn = value)) } })
                            WorkspaceTextField(tx(language, "入场状态", "Entry state"), scene.entryState, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(entryState = value)) } })
                            WorkspaceTextField(tx(language, "结束状态", "Exit state"), scene.exitState, { value -> updateScene(scene.id) { it.copy(spec = it.spec.copy(exitState = value)) } })
                            WorkspaceTextField(tx(language, "必须发生的事件（每行一条）", "Required events (one per line)"), editable.requiredEvents, { value -> updateScene(scene.id) { it.copy(requiredEvents = value) } }, maxLines = 5)
                            WorkspaceTextField(tx(language, "禁止发生或提前泄露的事件（每行一条）", "Forbidden events or disclosures (one per line)"), editable.forbiddenEvents, { value -> updateScene(scene.id) { it.copy(forbiddenEvents = value) } }, maxLines = 5)
                            OutlinedTextField(
                                value = editable.targetWords,
                                onValueChange = { value -> updateScene(scene.id) { it.copy(targetWords = value) } },
                                label = { Text(tx(language, "目标字数（100–${ScenePlanProtocol.MAX_SCENE_WORDS}）", "Target length (100–${ScenePlanProtocol.MAX_SCENE_WORDS})")) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            TextButton(onClick = { scenes = scenes.filterNot { it.spec.id == scene.id }; expandedIds = expandedIds - scene.id }, enabled = scenes.size > 1) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(tx(language, "删除此场景", "Delete scene"))
                            }
                        } else {
                            Text(scene.goal.ifBlank { tx(language, "尚未填写场景目标", "Scene goal missing") }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(tx(language, "${editable.targetWords} 字 · ${scene.pov.ifBlank { "未指定视角" }}", "${editable.targetWords} words · ${scene.pov.ifBlank { "Viewpoint not set" }}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = {
                    val scene = SceneSpec(UUID.randomUUID().toString(), tx(language, "场景 ${scenes.size + 1}", "Scene ${scenes.size + 1}"))
                    scenes = scenes + EditableScene(scene)
                    expandedIds = expandedIds + scene.id
                }, enabled = scenes.size < 8, modifier = Modifier.fillMaxWidth()) { Text(tx(language, "新增场景", "Add scene")) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceDialogShell(
    language: String,
    title: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.imePadding(),
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    AppTopBar(title = { Text(title, style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = tx(language, "关闭", "Close")) }
                    })
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(tx(language, "取消", "Cancel")) }
                                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(tx(language, "保存", "Save")) }
                            }
                        }
                    }
                },
                content = content,
            )
        }
    }
}

@Composable
private fun WorkspaceTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    maxLines: Int = 4,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (hint.isNotEmpty()) Text(hint) },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (maxLines > 2) 2 else 1,
        maxLines = maxLines,
    )
}

private data class WorkspaceChoice(val id: String?, val label: String)

/** Lazy chooser avoids creating hundreds of chapter/profile menu rows on the main screen. */
@Composable
private fun WorkspaceChoiceField(
    language: String,
    label: String,
    selectedId: String?,
    choices: List<WorkspaceChoice>,
    onSelect: (String?) -> Unit,
) {
    var showChooser by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { showChooser = true }, modifier = Modifier.fillMaxWidth()) {
            Text(choices.firstOrNull { it.id == selectedId }?.label ?: tx(language, "已失效的选择，请重新指定", "Unavailable selection; choose again"), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, contentDescription = null)
        }
    }
    if (showChooser) AlertDialog(
        onDismissRequest = { showChooser = false },
        title = { Text(label) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(choices, key = { it.id ?: "__global__" }) { choice ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(choice.id); showChooser = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedId == choice.id, onClick = { onSelect(choice.id); showChooser = false })
                        Text(choice.label, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showChooser = false }) { Text(tx(language, "取消", "Cancel")) } },
    )
}

private val storyKinds = listOf("canon", "plan", "fact", "belief", "foreshadowing")

private fun storyKindLabel(language: String, kind: String): String = when (kind) {
    "canon" -> tx(language, "作者设定", "Canon")
    "plan" -> tx(language, "未来计划", "Plans")
    "fact" -> tx(language, "已发生事实", "Facts")
    "belief" -> tx(language, "角色认知", "Beliefs")
    else -> tx(language, "伏笔", "Foreshadowing")
}

private fun storyKindDescription(language: String, kind: String): String = when (kind) {
    "canon" -> tx(language, "作者确定的世界规则与设定，不代表任何角色已经知道。", "Author-established rules and canon; not automatically known by characters.")
    "plan" -> tx(language, "未来打算发生的剧情，不能作为已经发生的事实引用。", "Future events to plan for, not facts that already happened.")
    "fact" -> tx(language, "正文中已发生的事实，必须选择来源章节。", "An event established in the prose; a source chapter is required.")
    "belief" -> tx(language, "某个角色知道、猜测或误解的信息，并不一定是真相。", "What a character knows, suspects or misunderstands; not necessarily true.")
    else -> tx(language, "未完成的铺垫与回收计划；已回收后请勾选状态。", "Setups and payoff plans; mark resolved after the payoff occurs.")
}

private fun importanceLabel(language: String, importance: Int): String = when (importance) {
    3 -> tx(language, "关键", "Critical")
    2 -> tx(language, "重要", "Important")
    else -> tx(language, "一般", "Normal")
}
