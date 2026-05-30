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
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.ai.EdgeTtsService
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.util.tx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(vm: AppViewModel) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()                 // re-read chapters when project updates
    val projects by vm.projects.collectAsState()
    val ab = vm.audiobook

    val curPid by ab.projectId.collectAsState()
    var picking by remember { mutableStateOf(false) }

    // Restore last-listened project on first entry.
    LaunchedEffect(Unit) {
        if (ab.projectId.value == null) vm.lastListenProjectId()?.let { ab.selectProject(it) }
    }
    // Pause + save progress whenever the listen screen leaves composition (tab switch / background).
    DisposableEffect(Unit) { onDispose { ab.pauseAndSave() } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(tx(lang, "听书", "Listen"), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (curPid != null) {
                        TextButton(onClick = { picking = true }) {
                            Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "切换", "Switch"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val pid = curPid
        if (pid == null || picking) {
            ProjectPicker(
                lang = lang,
                projects = projects,
                novelType = { vm.novelType(it) },
                modifier = Modifier.padding(padding),
                onPick = { ab.selectProject(it); picking = false },
            )
        } else {
            PlayerBody(vm = vm, projectId = pid, lang = lang, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun ProjectPicker(
    lang: String,
    projects: List<com.example.novelseek_ultra.data.model.Project>,
    novelType: (String) -> String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    if (projects.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(tx(lang, "还没有小说项目", "No novels yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(tx(lang, "选择要收听的小说", "Pick a novel to listen to"),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        items(projects, key = { it.id }) { p ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        val typeLabel = if (novelType(p.id) == "long") tx(lang, "长篇", "Long") else tx(lang, "短篇", "Short")
                        Text("$typeLabel · ${p.current_word_count} ${tx(lang, "字", "words")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBody(vm: AppViewModel, projectId: String, lang: String, modifier: Modifier = Modifier) {
    val ab = vm.audiobook
    val state by vm.state.collectAsState()
    val project = remember(state, projectId) { vm.project(projectId) }
    val chapters = remember(state, projectId) { vm.chapters(projectId).sortedBy { it.order_index } }
    val curChapterId by ab.chapterId.collectAsState()
    val isPlaying by ab.isPlaying.collectAsState()
    val preparing by ab.preparing.collectAsState()
    val segIndex by ab.segmentIndex.collectAsState()
    val segCount by ab.segmentCount.collectAsState()
    val status by ab.status.collectAsState()
    val voice by ab.voice.collectAsState()
    val rate by ab.rate.collectAsState()

    val curChapter = chapters.firstOrNull { it.id == curChapterId }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(project?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            curChapter?.let { "第${it.order_index}章 ${it.title}" } ?: tx(lang, "未选择章节", "No chapter"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (segCount > 0) {
            Text(tx(lang, "段落 ${segIndex + 1}/$segCount", "Segment ${segIndex + 1}/$segCount"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Per-chapter segment progress: a horizontal slider with one tick per segment. Drag to seek.
        if (segCount > 1) {
            var dragging by remember(curChapterId) { mutableStateOf<Float?>(null) }
            val maxV = (segCount - 1).toFloat()
            Slider(
                value = (dragging ?: segIndex.toFloat()).coerceIn(0f, maxV),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { ab.seekSegment(it.toInt()) }
                    dragging = null
                },
                valueRange = 0f..maxV,
                steps = (segCount - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        // ── transport controls ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ab.prevChapter() }) {
                Icon(Icons.Outlined.SkipPrevious, contentDescription = tx(lang, "上一章", "Previous"), modifier = Modifier.size(32.dp))
            }
            FilledIconButton(onClick = { ab.toggle() }, modifier = Modifier.size(64.dp)) {
                when {
                    preparing -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                    isPlaying -> Icon(Icons.Outlined.Pause, contentDescription = tx(lang, "暂停", "Pause"), modifier = Modifier.size(32.dp))
                    else -> Icon(Icons.Outlined.PlayArrow, contentDescription = tx(lang, "播放", "Play"), modifier = Modifier.size(32.dp))
                }
            }
            IconButton(onClick = { ab.nextChapter() }) {
                Icon(Icons.Outlined.SkipNext, contentDescription = tx(lang, "下一章", "Next"), modifier = Modifier.size(32.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        // ── voice + speed ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceDropdown(voice = voice, onPick = { ab.setVoice(it) }, lang = lang)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val speeds = listOf(-25 to tx(lang, "慢", "Slow"), 0 to tx(lang, "正常", "Normal"),
                25 to tx(lang, "快", "Fast"), 50 to tx(lang, "更快", "Faster"))
            speeds.forEach { (value, label) ->
                FilterChip(selected = rate == value, onClick = { ab.setRate(value) }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(tx(lang, "章节", "Chapters"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(chapters, key = { it.id }) { ch ->
                val isCurrent = ch.id == curChapterId
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { ab.playChapter(ch.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                         else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("第${ch.order_index}章 ${ch.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        if (isCurrent && isPlaying) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceDropdown(voice: String, onPick: (String) -> Unit, lang: String) {
    var expanded by remember { mutableStateOf(false) }
    val label = EdgeTtsService.VOICES.firstOrNull { it.first == voice }?.second ?: voice
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(tx(lang, "音色：", "Voice: ") + label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EdgeTtsService.VOICES.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(id); expanded = false })
            }
        }
    }
}
