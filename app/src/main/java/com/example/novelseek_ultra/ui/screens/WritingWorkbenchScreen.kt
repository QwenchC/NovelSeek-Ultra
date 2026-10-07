package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.util.formatWordCount
import com.example.novelseek_ultra.util.tx

data class WorkbenchProject(
    val id: String,
    val title: String,
    val description: String = "",
    val genre: String = "",
    val wordCount: Int = 0,
    val targetWordCount: Int? = null,
)

data class WorkbenchChapter(
    val id: String,
    val title: String,
    val sequence: Int,
    val wordCount: Int = 0,
    val hasText: Boolean = false,
    val sceneCount: Int = 0,
    val pendingReview: Boolean = false,
    val summary: String = "",
    val statusLabel: String = "",
    val modifiedLabel: String = "",
)

data class WorkbenchPendingReview(
    val id: String,
    val chapterId: String,
    val chapterTitle: String,
    val summary: String = "",
)

data class WorkbenchScenePlan(
    val id: String,
    val chapterId: String,
    val chapterTitle: String,
    val statusLabel: String,
    val sceneCount: Int,
    val completedScenes: Int = 0,
)

data class WorkbenchTask(
    val id: String,
    val title: String,
    val stateLabel: String,
    val detail: String = "",
    val progress: Float? = null,
    val isRunning: Boolean = false,
    val canResume: Boolean = false,
    val canPause: Boolean = false,
    val canCancel: Boolean = false,
    val failureReason: String = "",
    val costSummary: String = "",
    val canPreviewSavedScenes: Boolean = false,
)

data class WorkbenchWritingPreferences(
    val modeLabel: String = "",
    val viewpoint: String = "",
    val styleSummary: String = "",
    val forbiddenExpressions: String = "",
    val requestLimitSummary: String = "",
    val modelSummary: String = "",
)

/** Project dashboard with no ViewModel dependency; mobile stacks cards, wide screens split panes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingWorkbenchScreen(
    language: String,
    project: WorkbenchProject,
    chapters: List<WorkbenchChapter>,
    pendingReviews: List<WorkbenchPendingReview> = emptyList(),
    scenePlans: List<WorkbenchScenePlan> = emptyList(),
    tasks: List<WorkbenchTask> = emptyList(),
    preferences: WorkbenchWritingPreferences = WorkbenchWritingPreferences(),
    nextChapterId: String? = null,
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    onOpenOutline: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenLegacyProject: () -> Unit,
    onCreateChapter: () -> Unit,
    onCreateScenePlan: (chapterId: String) -> Unit,
    onResumeTask: (taskId: String) -> Unit,
    onReview: (reviewId: String) -> Unit,
    onEditPreferences: () -> Unit,
    onOpenStoryNotes: () -> Unit = {},
    onImportManuscript: () -> Unit = {},
    onPauseTask: (taskId: String) -> Unit = {},
    onCancelTask: (taskId: String) -> Unit = {},
    onStartChapter: (chapterId: String) -> Unit = {},
    onRegenerateScenePlan: (chapterId: String) -> Unit = {},
    onPreviewSavedScenes: (taskId: String) -> Unit = {},
    message: String? = null,
) {
    val orderedChapters = remember(chapters) { chapters.sortedBy { it.sequence } }
    val nextChapter = remember(orderedChapters, nextChapterId) {
        orderedChapters.firstOrNull { it.id == nextChapterId } ?: orderedChapters.firstOrNull { !it.hasText } ?: orderedChapters.lastOrNull()
    }
    var taskFailure by remember(project.id) { mutableStateOf<WorkbenchTask?>(null) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = {
                    Column {
                        Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tx(language, "创作工作台", "Writing workbench"), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tx(language, "返回", "Back")) }
                },
                actions = {
                    TextButton(onClick = onOpenLegacyProject) { Text(tx(language, "项目管理", "Manage")) }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val overview: LazyListScope.() -> Unit = {
                if (!message.isNullOrBlank()) item(key = "workspace-message") {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                item(key = "project-summary") {
                    ProjectWritingSummary(language, project, orderedChapters, nextChapter, onOpenChapter, onCreateChapter, onCreateScenePlan)
                }
                item(key = "writing-navigation") {
                    WorkbenchQuickActions(language, onOpenOutline, onOpenCharacters, onOpenAgent, onOpenStoryNotes, onImportManuscript)
                }
                if (pendingReviews.isNotEmpty()) {
                    item(key = "review-heading") { WorkbenchHeading(tx(language, "待审核 · ${pendingReviews.size}", "Awaiting review · ${pendingReviews.size}")) }
                    items(pendingReviews, key = { "review-${it.id}" }) { review ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(review.chapterTitle, style = MaterialTheme.typography.titleSmall)
                                if (review.summary.isNotBlank()) Text(review.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                FilledTonalButton(onClick = { onReview(review.id) }) { Text(tx(language, "预览并审核", "Preview and review")) }
                            }
                        }
                    }
                }
                item(key = "task-heading") { WorkbenchHeading(tx(language, "创作任务", "Writing tasks")) }
                if (tasks.isEmpty()) item(key = "no-task") {
                    WorkbenchEmptyCard(tx(language, "当前没有创作任务。为章节规划场景，或让智能体协助创作。", "No active writing tasks. Plan scenes for a chapter or ask the agent to help."))
                }
                items(tasks, key = { "task-${it.id}" }) { task ->
                    WorkbenchTaskCard(language, task, onResumeTask, onPauseTask, onCancelTask, { taskFailure = task }, onPreviewSavedScenes)
                }
                item(key = "writing-preferences") { WorkbenchPreferencesCard(language, preferences, onEditPreferences) }
            }
            val writing: LazyListScope.() -> Unit = {
                if (scenePlans.isNotEmpty()) {
                    item(key = "scene-heading") { WorkbenchHeading(tx(language, "场景计划", "Scene plans")) }
                    items(scenePlans, key = { "plan-${it.id}" }) { plan ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onCreateScenePlan(plan.chapterId) }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(plan.chapterTitle, style = MaterialTheme.typography.titleSmall)
                                Text("${plan.statusLabel} · ${plan.completedScenes}/${plan.sceneCount} ${tx(language, "场景", "scenes")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (plan.sceneCount > 0) LinearProgressIndicator(progress = { (plan.completedScenes.toFloat() / plan.sceneCount).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(tx(language, "查看与调整场景", "View and adjust scenes"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { onRegenerateScenePlan(plan.chapterId) }, enabled = tasks.none { it.id == plan.chapterId && it.isRunning }) {
                                    Text(tx(language, "重新规划场景", "Regenerate scene plan"))
                                }
                                FilledTonalButton(onClick = { onStartChapter(plan.chapterId) }, enabled = tasks.none { it.id == plan.chapterId && it.isRunning }) {
                                    Text(tx(language, "按偏好开始写作", "Write with preferences"))
                                }
                            }
                        }
                    }
                }
                item(key = "chapter-heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        WorkbenchHeading(tx(language, "章节 · ${orderedChapters.size}", "Chapters · ${orderedChapters.size}"))
                        TextButton(onClick = onCreateChapter) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(tx(language, "新建", "New"))
                        }
                    }
                }
                if (orderedChapters.isEmpty()) item(key = "no-chapter") {
                    WorkbenchEmptyCard(tx(language, "从第一章开始。你也可以导入已有书稿，继续创作。", "Start with chapter one, or import an existing manuscript to continue writing."))
                }
                items(orderedChapters, key = { "chapter-${it.id}" }) { chapter ->
                    WorkbenchChapterCard(language, chapter, onOpenChapter, onCreateScenePlan)
                }
            }
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LazyColumn(
                        modifier = Modifier.weight(0.42f),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = overview,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(0.58f),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = writing,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    overview()
                    writing()
                }
            }
        }
    }
    taskFailure?.let { task ->
        AlertDialog(
            onDismissRequest = { taskFailure = null },
            title = { Text(task.title) },
            text = { Text(task.failureReason) },
            confirmButton = { TextButton(onClick = { taskFailure = null }) { Text(tx(language, "关闭", "Close")) } },
        )
    }
}

@Composable
private fun ProjectWritingSummary(
    language: String,
    project: WorkbenchProject,
    chapters: List<WorkbenchChapter>,
    nextChapter: WorkbenchChapter?,
    onOpenChapter: (String) -> Unit,
    onCreateChapter: () -> Unit,
    onCreateScenePlan: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (project.genre.isNotBlank()) Text(project.genre, style = MaterialTheme.typography.labelMedium)
            Text(
                "${formatWordCount(project.wordCount)} ${tx(language, "字", "words")} · ${chapters.count { it.hasText }}/${chapters.size} ${tx(language, "章已写", "chapters drafted")}",
                style = MaterialTheme.typography.titleMedium,
            )
            project.targetWordCount?.takeIf { it > 0 }?.let { target ->
                LinearProgressIndicator(progress = { (project.wordCount.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(tx(language, "目标 ${formatWordCount(target)} 字", "Target ${formatWordCount(target)} words"), style = MaterialTheme.typography.labelSmall)
            }
            if (project.description.isNotBlank()) Text(project.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (nextChapter != null) {
                Text(tx(language, "继续创作：${nextChapter.title}", "Continue: ${nextChapter.title}"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenChapter(nextChapter.id) }, modifier = Modifier.weight(1f)) { Text(tx(language, "继续写作", "Continue writing")) }
                    FilledTonalButton(onClick = { onCreateScenePlan(nextChapter.id) }, modifier = Modifier.weight(1f)) { Text(tx(language, "规划场景", "Plan scenes")) }
                }
            } else Button(onClick = onCreateChapter) { Text(tx(language, "创建第一章", "Create chapter one")) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchQuickActions(
    language: String,
    onOpenOutline: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenStoryNotes: () -> Unit,
    onImportManuscript: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilledTonalButton(onClick = onOpenOutline) {
            Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(tx(language, "大纲", "Outline"), modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onOpenCharacters) {
            Icon(Icons.Outlined.Group, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(tx(language, "角色", "Characters"), modifier = Modifier.padding(start = 4.dp))
        }
        FilledTonalButton(onClick = onOpenAgent) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(tx(language, "智能体", "Agent"), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = onOpenStoryNotes) { Text(tx(language, "故事状态", "Story state")) }
        OutlinedButton(onClick = onImportManuscript) { Text(tx(language, "导入书稿", "Import manuscript")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkbenchTaskCard(
    language: String,
    task: WorkbenchTask,
    onResumeTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onShowFailure: () -> Unit,
    onPreviewSavedScenes: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(task.stateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (task.detail.isNotBlank()) Text(task.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val progress = task.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else if (task.isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (task.costSummary.isNotBlank()) Text(task.costSummary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.canPreviewSavedScenes) TextButton(onClick = { onPreviewSavedScenes(task.id) }) { Text(tx(language, "查看已保存场景", "View saved scenes")) }
                if (task.canResume) FilledTonalButton(onClick = { onResumeTask(task.id) }) { Text(tx(language, "继续任务", "Resume")) }
                if (task.canPause) TextButton(onClick = { onPauseTask(task.id) }) { Text(tx(language, "暂停", "Pause")) }
                if (task.canCancel) TextButton(onClick = { onCancelTask(task.id) }) { Text(tx(language, "取消", "Cancel")) }
                if (task.failureReason.isNotBlank()) TextButton(onClick = onShowFailure) { Text(tx(language, "查看失败原因", "View failure")) }
            }
        }
    }
}

@Composable
private fun WorkbenchPreferencesCard(language: String, preferences: WorkbenchWritingPreferences, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tx(language, "本书写作偏好", "Writing preferences"), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = tx(language, "编辑写作偏好", "Edit writing preferences")) }
            }
            val summaries = listOf(preferences.modeLabel, preferences.viewpoint, preferences.styleSummary, preferences.requestLimitSummary, preferences.modelSummary).filter { it.isNotBlank() }
            if (summaries.isEmpty()) Text(tx(language, "设置视角、文风与禁用表达，让整本书保持一致。", "Set viewpoint, style and forbidden expressions to keep the book consistent."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else summaries.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (preferences.forbiddenExpressions.isNotBlank()) Text(tx(language, "禁用表达：${preferences.forbiddenExpressions}", "Avoid: ${preferences.forbiddenExpressions}"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WorkbenchChapterCard(language: String, chapter: WorkbenchChapter, onOpenChapter: (String) -> Unit, onCreateScenePlan: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(chapter.title, style = MaterialTheme.typography.titleSmall)
            val status = chapter.statusLabel.ifBlank {
                when {
                    chapter.pendingReview -> tx(language, "待审核", "Awaiting review")
                    chapter.hasText -> tx(language, "已有正文", "Drafted")
                    else -> tx(language, "待写作", "Not drafted")
                }
            }
            Text("$status · ${chapter.wordCount} ${tx(language, "字", "words")} · ${chapter.sceneCount} ${tx(language, "场景", "scenes")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chapter.summary.isNotBlank()) Text(chapter.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (chapter.modifiedLabel.isNotBlank()) Text(chapter.modifiedLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenChapter(chapter.id) }) { Text(tx(language, "打开正文", "Open chapter")) }
                TextButton(onClick = { onCreateScenePlan(chapter.id) }) { Text(tx(language, "场景计划", "Scene plan")) }
            }
        }
    }
}

@Composable
private fun WorkbenchHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun WorkbenchEmptyCard(message: String) {
    Card(Modifier.fillMaxWidth()) { Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
