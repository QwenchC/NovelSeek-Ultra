package com.example.novelseek_ultra.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.NovelChatMessage
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.ConfirmDialog
import com.example.novelseek_ultra.ui.components.MarkdownText
import com.example.novelseek_ultra.util.tx
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelQaScreen(
    vm: AppViewModel,
    projectId: String,
    onBack: () -> Unit,
) {
    val lang by vm.uiLanguage.collectAsState()
    val rev by vm.novelChatRevision.collectAsState()
    val streaming by vm.qaStreamingText.collectAsState()
    val generating by vm.qaGenerating.collectAsState()

    val history = remember(rev, projectId) { vm.novelChatHistory(projectId) }
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val title = remember(projectId) { vm.project(projectId)?.title.orEmpty() }

    // Keep the newest message in view as history grows or the answer streams in.
    LaunchedEffect(history.size, streaming, generating) {
        val count = history.size + if (generating) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || generating) return
        vm.askNovel(projectId, q)
        input = ""
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    Column {
                        Text(tx(lang, "小说问答", "Ask the Novel"), style = MaterialTheme.typography.titleLarge)
                        if (title.isNotBlank()) {
                            Text(title, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = tx(lang, "清空", "Clear"))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(tx(lang, "问点什么…", "Ask anything…")) },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (generating) {
                        IconButton(onClick = { vm.stopAskNovel() }) {
                            Icon(Icons.Outlined.Stop, contentDescription = tx(lang, "停止", "Stop"),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = { send() }, enabled = input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = tx(lang, "发送", "Send"),
                                tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (history.isEmpty() && !generating) {
                item { EmptyState(lang) { input = it } }
            }

            items(history, key = { it.id }) { msg -> MessageBubble(msg, lang) }

            if (generating) {
                item {
                    MessageBubble(
                        NovelChatMessage(id = "streaming", role = "assistant", content = streaming),
                        lang,
                        showTypingWhenEmpty = true,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = tx(lang, "清空对话", "Clear conversation"),
            message = tx(lang, "确定清空与本小说的全部问答记录？", "Clear all Q&A history for this novel?"),
            confirmLabel = tx(lang, "清空", "Clear"),
            dismissLabel = tx(lang, "取消", "Cancel"),
            onConfirm = { vm.clearNovelChat(projectId) },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun MessageBubble(msg: NovelChatMessage, lang: String, showTypingWhenEmpty: Boolean = false) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 14.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                                 else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isUser) {
                    Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                } else if (msg.content.isBlank() && showTypingWhenEmpty) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tx(lang, "检索中…", "Retrieving…"), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    MarkdownText(msg.content)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(lang: String, onPick: (String) -> Unit) {
    val samples = if (lang == "en") listOf(
        "What realm is the protagonist at now?",
        "What is the relationship between the two leads?",
        "What major events have happened so far?",
    ) else listOf(
        "主角现在是什么境界？",
        "目前主要角色之间是什么关系？",
        "到目前为止发生了哪些重要事件？",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            tx(lang, "向 AI 提问，它会从当前版本的小说资料中检索作答。",
                "Ask the AI — it retrieves from the current version of your novel to answer."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        samples.forEach { s ->
            SuggestionChip(onClick = { onPick(s) }, label = { Text(s) })
        }
    }
}
