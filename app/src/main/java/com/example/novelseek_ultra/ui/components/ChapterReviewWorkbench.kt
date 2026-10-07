package com.example.novelseek_ultra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ChapterReviewFindingSeverity { INFO, SUGGESTION, CONFLICT }

data class ChapterReviewFinding(
    val id: String,
    val title: String,
    val description: String,
    val excerpt: String = "",
    val severity: ChapterReviewFindingSeverity = ChapterReviewFindingSeverity.SUGGESTION,
)

private data class ReviewDiffPreviewBlock(
    val block: ChapterReviewDiffBlock,
    val baselineChunks: List<String>,
    val candidateChunks: List<String>,
)

private data class ReviewPreview(
    val baselineText: String,
    val candidateText: String,
    val blocks: List<ReviewDiffPreviewBlock>,
    val candidateChunks: List<String>,
)

/**
 * Reusable, in-place chapter review. Selection produces a complete text, allowing the owner to
 * use its normal revision/validation/transaction pipeline for partial adoption. Findings are
 * supplied by that owner; this component never makes an AI request or writes a chapter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterReviewWorkbench(
    language: String,
    title: String,
    baselineText: String,
    candidateText: String,
    findings: List<ChapterReviewFinding> = emptyList(),
    isBusy: Boolean = false,
    canAdopt: Boolean = true,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onAdopt: (assembledText: String) -> Unit,
    onReject: () -> Unit,
    onRequestRevision: (instruction: String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(if (baselineText.isBlank()) 0 else 1) }
    var adoptedChangeIds by remember(baselineText, candidateText) { mutableStateOf<Set<Int>?>(null) }
    var revisionExpanded by remember { mutableStateOf(false) }
    var revisionInstruction by remember { mutableStateOf("") }
    val computedPreview by produceState<ReviewPreview?>(null, baselineText, candidateText) {
        value = withContext(Dispatchers.Default) {
            ReviewPreview(
                baselineText,
                candidateText,
                chapterReviewDiff(baselineText, candidateText).map { block ->
                    ReviewDiffPreviewBlock(block, chapterReviewTextChunks(block.baselineText), chapterReviewTextChunks(block.candidateText))
                },
                chapterReviewTextChunks(candidateText),
            )
        }
    }
    val preview = computedPreview?.takeIf { it.baselineText == baselineText && it.candidateText == candidateText }
    val blocks = preview?.blocks.orEmpty()
    val allChangeIds = remember(blocks) { blocks.filter { it.block.changed }.map { it.block.id }.toSet() }
    val selectedIds = adoptedChangeIds ?: allChangeIds
    val assembledText = remember(blocks, selectedIds) {
        assembleChapterReviewText(blocks.map { it.block }, selectedIds)
    }
    val adoptEnabled = !isBusy && canAdopt && preview != null && assembledText.isNotBlank() && assembledText != baselineText

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !isBusy, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.imePadding(),
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    AppTopBar(
                        title = {
                            Column {
                                Text(tx(language, "审稿与修订", "Review and revise"), style = MaterialTheme.typography.titleMedium)
                                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !isBusy) {
                                Icon(Icons.Outlined.Close, contentDescription = tx(language, "关闭审核", "Close review"))
                            }
                        },
                        actions = {
                            if (isBusy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                        },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            errorMessage?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (revisionExpanded) {
                                OutlinedTextField(
                                    value = revisionInstruction,
                                    onValueChange = { revisionInstruction = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(tx(language, "修改意见", "Revision instructions")) },
                                    placeholder = { Text(tx(language, "例如：保留开场，重写结尾；主角此时还不知道真相。", "For example: preserve the opening, revise the ending; the protagonist does not know the truth yet.")) },
                                    minLines = 2,
                                    maxLines = 4,
                                    enabled = !isBusy,
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { revisionExpanded = false }, enabled = !isBusy) { Text(tx(language, "收起", "Collapse")) }
                                    FilledTonalButton(
                                        onClick = { onRequestRevision(revisionInstruction.trim()) },
                                        enabled = !isBusy && revisionInstruction.isNotBlank(),
                                    ) { Text(tx(language, "按意见修订", "Request revision")) }
                                }
                            } else {
                                TextButton(onClick = { revisionExpanded = true }, enabled = !isBusy) { Text(tx(language, "提出修改意见", "Add revision instructions")) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onReject, enabled = !isBusy, modifier = Modifier.weight(1f)) { Text(tx(language, "拒绝候选稿", "Reject candidate")) }
                                Button(onClick = { if (adoptEnabled) onAdopt(assembledText) }, enabled = adoptEnabled, modifier = Modifier.weight(1f)) {
                                    Text(if (selectedIds.size == allChangeIds.size) tx(language, "通过并采用", "Approve and adopt") else tx(language, "采用所选修改", "Adopt selected changes"))
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        listOf(tx(language, "候选正文", "Candidate"), tx(language, "修改对比", "Changes"), tx(language, "审稿意见", "Findings")).forEachIndexed { index, label ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                        }
                    }
                    if (preview == null) {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    } else when (selectedTab) {
                        0 -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item { Text(tx(language, "完整候选稿 · ${candidateText.length} 字符", "Full candidate · ${candidateText.length} characters"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(preview!!.candidateChunks) { chunk -> Text(chunk, style = MaterialTheme.typography.bodyLarge) }
                        }
                        1 -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item {
                                Text(tx(language, "按唯一段落锚点对比；重复或移动段落会合并显示。所有正文均完整保留。", "Compared using unique paragraph anchors; repeated or moved paragraphs appear as grouped changes. Both texts are preserved in full."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (allChangeIds.isEmpty()) item { Text(tx(language, "候选稿与原文相同。", "The candidate matches the original.")) }
                            else item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(tx(language, "已选 ${selectedIds.size}/${allChangeIds.size} 处修改", "${selectedIds.size}/${allChangeIds.size} changes selected"), style = MaterialTheme.typography.labelMedium)
                                    TextButton(onClick = { adoptedChangeIds = if (selectedIds.size == allChangeIds.size) emptySet() else allChangeIds }, enabled = !isBusy) {
                                        Text(if (selectedIds.size == allChangeIds.size) tx(language, "全部保留原文", "Keep all original") else tx(language, "全部采用", "Select all"))
                                    }
                                }
                            }
                            blocks.forEach { previewBlock ->
                                val block = previewBlock.block
                                if (block.changed) {
                                    item(key = "change-${block.id}") {
                                        HorizontalDivider(Modifier.padding(top = 8.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(tx(language, "修改 ${block.id + 1}", "Change ${block.id + 1}"), fontWeight = FontWeight.SemiBold)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(tx(language, "采用", "Adopt"), style = MaterialTheme.typography.labelMedium)
                                                Switch(
                                                    checked = block.id in selectedIds,
                                                    onCheckedChange = { checked -> adoptedChangeIds = if (checked) selectedIds + block.id else selectedIds - block.id },
                                                    enabled = !isBusy,
                                                )
                                            }
                                        }
                                        Text(tx(language, "原文", "Original"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (previewBlock.baselineChunks.isEmpty()) item(key = "empty-original-${block.id}") { Text(tx(language, "（新增内容）", "(New content)"), style = MaterialTheme.typography.bodySmall) }
                                    items(previewBlock.baselineChunks.size, key = { "original-${block.id}-$it" }) { index ->
                                        Text(previewBlock.baselineChunks[index], modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)).padding(8.dp), style = MaterialTheme.typography.bodyLarge)
                                    }
                                    item(key = "candidate-label-${block.id}") { Text(tx(language, "候选", "Candidate"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                    if (previewBlock.candidateChunks.isEmpty()) item(key = "empty-candidate-${block.id}") { Text(tx(language, "（删除该段）", "(Paragraph removed)"), style = MaterialTheme.typography.bodySmall) }
                                    items(previewBlock.candidateChunks.size, key = { "candidate-${block.id}-$it" }) { index ->
                                        Text(previewBlock.candidateChunks[index], modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)).padding(8.dp), style = MaterialTheme.typography.bodyLarge)
                                    }
                                } else {
                                    items(previewBlock.candidateChunks.size, key = { "unchanged-${block.id}-$it" }) { index ->
                                        Text(previewBlock.candidateChunks[index], style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (findings.isEmpty()) item {
                                Text(tx(language, "暂无审稿意见。可以阅读正文后直接提出修改意见。", "No findings yet. Read the chapter and provide revision instructions."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(findings, key = { it.id }) { finding ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            finding.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (finding.severity == ChapterReviewFindingSeverity.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(finding.description, style = MaterialTheme.typography.bodyMedium)
                                        if (finding.excerpt.isNotBlank()) Text("“${finding.excerpt}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
