package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.novelseek_ultra.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.PlotArc
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx

// ── Parsed data types ─────────────────────────────────────────────────────────

private data class ParsedArc(val title: String, val summary: String)

internal data class ParsedChapter(
    val title: String,        // full heading e.g. "第1章：引子"
    val plot: String = "",
    val keyScene: String = "",
)

private data class ParsedChar(
    val name: String,
    val role: String = "",
    val personality: String = "",
    val motivation: String = "",
    val background: String = "",
)

private data class ParsedOutlineSections(
    val worldSetting: String?,
    val timeline: String?,
    val arcs: List<ParsedArc>,
    val chars: List<ParsedChar>,
    val shortChapters: List<ParsedChapter> = emptyList(),
)

private data class SaveSelection(
    val overwriteWorld: Boolean = true,
    val overwriteTimeline: Boolean = true,
    val overwriteArcs: Boolean = true,
    val importChars: Boolean = true,
    val importChapters: Boolean = true,
)

// ── Section extraction helpers ────────────────────────────────────────────────

private fun extractOutlineSection(text: String, vararg headers: String): String? {
    for (header in headers) {
        val escaped = Regex.escape(header)
        val rx = Regex(
            "(?:^|\\n)#{1,3}\\s*$escaped[^\\n]*\\n([\\s\\S]*?)(?=\\n#{1,3}|\\z)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val match = rx.find(text) ?: continue
        val body = match.groupValues[1].trim()
        if (body.isNotEmpty()) return body
    }
    return null
}

private fun extractCharField(block: String, vararg keys: String): String {
    for (key in keys) {
        val re = Regex(
            "(?:^|\\n)\\s*[-*]?\\s*\\*{0,2}${Regex.escape(key)}\\*{0,2}\\s*[：:][ \\t]*(.*?)(?=\\n|\$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val hit = re.find(block) ?: continue
        val v = hit.groupValues[1].trim().trimStart('*').trimEnd('*').trim()
        if (v.isNotBlank()) return v
    }
    return ""
}

// Extracts a single-line field value from a chapter block (e.g. **情节**：...)
private fun extractChapterField(block: String, vararg keys: String): String {
    for (key in keys) {
        val re = Regex(
            "[-*]?\\s*\\*{0,2}${Regex.escape(key)}\\*{0,2}\\s*[：:﹕][ \\t]*(.+?)[ \\t]*$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
        val hit = re.find(block) ?: continue
        val v = hit.groupValues[1].trim()
        if (v.isNotBlank()) return v
    }
    return ""
}

/** Parse strict chapter entries from a short-novel outline. Also used by ProjectScreen. */
internal fun parseShortChapters(outline: String): List<ParsedChapter> {
    val results = mutableListOf<ParsedChapter>()
    // Extract only the chapter-outline section to avoid false matches from ## headings elsewhere
    val sectionBody = Regex(
        "##\\s+(?:章节大纲|Chapter Outline)[\\s\\S]*?(?=\\n##\\s|\\z)",
        RegexOption.IGNORE_CASE,
    ).find(outline)?.value ?: outline

    Regex(
        "###\\s+(.+?)[ \\t]*(?:\\n|\$)([\\s\\S]*?)(?=\\n###|\\z)",
        RegexOption.IGNORE_CASE,
    ).findAll(sectionBody).forEach { m ->
        // Strip any residual "第N章：" / "Chapter N:" prefix the model may add despite instructions
        val rawTitle = m.groupValues[1].trim()
        val title = rawTitle
            .replace(Regex("^(?:第\\s*\\d+\\s*[章节][：:﹕]?|Chapter\\s+\\d+[：:﹕]?)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { rawTitle }
        val body = m.groupValues[2]
        if (title.isBlank()) return@forEach
        results += ParsedChapter(
            title = title,
            plot = extractChapterField(body, "情节", "Plot"),
            keyScene = extractChapterField(body, "关键场景", "Key Scene"),
        )
    }
    return results
}

private fun parseOutlineSections(outline: String, isLong: Boolean = false): ParsedOutlineSections {
    val worldSetting = extractOutlineSection(outline, "世界观概述", "世界观设定", "World Overview", "World Setting")
    val timeline = extractOutlineSection(outline, "时间线", "故事时间线", "Timeline", "Story Timeline")

    // Parse arcs
    val arcs = mutableListOf<ParsedArc>()
    Regex("#{2,3}\\s+弧线[^：:\\n]+?[：:]\\s*(.+?)(?:\\n|\$)([\\s\\S]*?)(?=\\n#{2,3}|\\z)")
        .findAll(outline)
        .forEach { m -> arcs += ParsedArc(m.groupValues[1].trim(), m.groupValues[2].trim()) }
    if (arcs.isEmpty()) {
        Regex("#{2,3}\\s+Arc\\s+\\d+[:：]?\\s*(.+?)(?:\\n|\$)([\\s\\S]*?)(?=\\n#{2,3}|\\z)", RegexOption.IGNORE_CASE)
            .findAll(outline)
            .forEach { m -> arcs += ParsedArc(m.groupValues[1].trim(), m.groupValues[2].trim()) }
    }

    // Parse characters
    val chars = mutableListOf<ParsedChar>()
    val charSection = extractOutlineSection(outline, "核心人物设定", "人物设定", "主要角色", "Core Characters", "Main Characters")
    if (charSection != null) {
        val headRe = Regex(
            "#{2,3}\\s+([^\\n#（(【\\[]{1,25})(?:[（(【\\[][^\\n）)】\\]]{0,30}[）)】\\]])?(?:\\n|\$)([\\s\\S]*?)(?=\\n#{2,3}|\\z)",
        )
        headRe.findAll(charSection).forEach { m ->
            val name = m.groupValues[1].trim()
            if (name.isBlank()) return@forEach
            val block = m.groupValues[2]
            chars += ParsedChar(
                name = name,
                role = extractCharField(block, "身份", "身份定位", "定位", "Role", "Occupation"),
                personality = extractCharField(block, "性格", "性格特点", "Personality"),
                motivation = extractCharField(block, "动机", "核心动机", "Motivation", "Goal"),
                background = extractCharField(block, "背景", "人物弧线", "弧线成长", "Background", "Arc"),
            )
        }
        if (chars.isEmpty()) {
            Regex("^\\d+[.、]\\s*\\*{0,2}([^\\n*（(]{1,25})\\*{0,2}", RegexOption.MULTILINE)
                .findAll(charSection)
                .mapNotNull { it.groupValues[1].trim().takeIf { n -> n.isNotBlank() } }
                .take(12)
                .forEach { name -> chars += ParsedChar(name = name) }
        }
    }

    return ParsedOutlineSections(
        worldSetting = worldSetting,
        timeline = timeline,
        arcs = arcs.take(10),
        chars = chars.take(12),
        shortChapters = if (!isLong) parseShortChapters(outline) else emptyList(),
    )
}

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(vm: AppViewModel, projectId: String, isLong: Boolean = false, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val project = remember(state, projectId) { vm.project(projectId) }
    val isGenerating by vm.isGenerating.collectAsState()
    val streaming by vm.streamingText.collectAsState()

    var outline by remember(projectId) { mutableStateOf(vm.outlineText(projectId)) }
    var world by remember(projectId) { mutableStateOf(vm.worldSetting(projectId)) }
    var timeline by remember(projectId) { mutableStateOf(vm.timeline(projectId)) }
    var requirements by remember { mutableStateOf("") }
    var chapterCount by remember { mutableFloatStateOf(20f) }
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(streaming) {
        if (isGenerating && streaming.isNotEmpty()) outline = streaming
    }

    Scaffold(
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text(tx(lang, "大纲", "Outline"), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(
                        onClick = {
                            if (outline.isBlank()) {
                                vm.setWorldSetting(projectId, world)
                                vm.setTimeline(projectId, timeline)
                                vm.showStatus(tx(lang, "已保存", "Saved"))
                            } else {
                                showSaveDialog = true
                            }
                        },
                        enabled = !isGenerating,
                    ) { Icon(Icons.Outlined.Save, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── World setting ──
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx(lang, "世界观", "World Setting"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = world, onValueChange = { world = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    placeholder = { Text(tx(lang, "描述故事所处的世界", "Describe the world your story takes place in")) },
                )
            } }

            // ── Timeline ──
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx(lang, "时间线", "Timeline"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = timeline, onValueChange = { timeline = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    placeholder = { Text(tx(lang, "关键事件、年表等", "Key events, chronology, …")) },
                )
            } }

            // ── AI generator ──
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx(lang, "AI 生成大纲", "AI Outline Generator"), style = MaterialTheme.typography.titleMedium)
                if (!isLong) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tx(lang, "目标章节数：", "Target chapters: "))
                        Text("${chapterCount.toInt()}")
                    }
                    Slider(value = chapterCount, onValueChange = { chapterCount = it }, valueRange = 3f..80f)
                }
                OutlinedTextField(
                    value = requirements, onValueChange = { requirements = it },
                    label = { Text(tx(lang, "额外要求（可选）", "Extra requirements (optional)")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (project != null) {
                                vm.generateOutline(
                                    projectId = projectId,
                                    title = project.title,
                                    genre = project.genre.orEmpty(),
                                    description = project.description.orEmpty(),
                                    chapterCount = if (isLong) null else chapterCount.toInt(),
                                    requirements = requirements.ifBlank { null },
                                    appendToExisting = false,
                                    isLong = isLong,
                                    onComplete = { generated -> outline = generated },
                                )
                            }
                        },
                        enabled = !isGenerating,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tx(lang, "生成", "Generate"))
                    }
                    // 续写 — same full prompt (title/genre/desc/world/timeline/arcs/chars/realm/
                    // requirements/structure spec all preserved); the model is told to pick up
                    // from where the existing outline ends instead of rewriting from scratch.
                    FilledTonalButton(
                        onClick = {
                            if (project != null) {
                                vm.generateOutline(
                                    projectId = projectId,
                                    title = project.title,
                                    genre = project.genre.orEmpty(),
                                    description = project.description.orEmpty(),
                                    chapterCount = if (isLong) null else chapterCount.toInt(),
                                    requirements = requirements.ifBlank { null },
                                    appendToExisting = false,
                                    isLong = isLong,
                                    continueFromExisting = true,
                                    onComplete = { generated -> outline = generated },
                                )
                            }
                        },
                        enabled = !isGenerating && outline.isNotBlank(),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tx(lang, "续写", "Continue"))
                    }
                    if (isGenerating) {
                        FilledTonalButton(onClick = { vm.stopGenerating() }) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(tx(lang, "停止", "Stop"))
                        }
                    }
                }
            } }

            // ── Outline body ──
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx(lang, "大纲正文", "Outline"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = outline, onValueChange = { outline = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    placeholder = { Text(tx(lang, "AI 生成的大纲会显示在这里，也可以手动编辑。",
                        "AI-generated outline will appear here. You can also edit manually.")) },
                )
                Text(
                    "${outline.length} ${tx(lang, "字符", "chars")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } }
            Spacer(Modifier.height(48.dp))
        }

        // ── Save dialog ───────────────────────────────────────────────────────
        if (showSaveDialog) {
            val parsed = remember(outline) { parseOutlineSections(outline, isLong) }
            SaveOutlineDialog(
                lang = lang,
                isLong = isLong,
                parsed = parsed,
                currentWorld = world,
                currentTimeline = timeline,
                existingArcCount = vm.plotArcs(projectId).size,
                existingCharCount = vm.characters(projectId).size,
                existingChapterCount = vm.chapters(projectId).size,
                onConfirm = { sel ->
                    vm.setOutlineText(projectId, outline)
                    if (sel.overwriteWorld && parsed.worldSetting != null) {
                        world = parsed.worldSetting
                        vm.setWorldSetting(projectId, parsed.worldSetting)
                    } else {
                        vm.setWorldSetting(projectId, world)
                    }
                    if (sel.overwriteTimeline && parsed.timeline != null) {
                        timeline = parsed.timeline
                        vm.setTimeline(projectId, parsed.timeline)
                    } else {
                        vm.setTimeline(projectId, timeline)
                    }
                    if (isLong && sel.overwriteArcs && parsed.arcs.isNotEmpty()) {
                        val ts = System.currentTimeMillis()
                        vm.setPlotArcs(projectId, parsed.arcs.mapIndexed { i, arc ->
                            PlotArc(id = "arc-$ts-$i", title = arc.title, summary = arc.summary,
                                order = i + 1, status = "upcoming")
                        })
                    }
                    if (sel.importChars && parsed.chars.isNotEmpty()) {
                        val existing = vm.characters(projectId)
                        val existingNames = existing.map { it.name }.toSet()
                        val ts = System.currentTimeMillis()
                        val newChars = parsed.chars
                            .filter { it.name !in existingNames }
                            .mapIndexed { i, pc ->
                                Character(id = "char-outline-$ts-$i", name = pc.name,
                                    role = pc.role, personality = pc.personality,
                                    motivation = pc.motivation, background = pc.background)
                            }
                        if (newChars.isNotEmpty()) vm.setCharacters(projectId, existing + newChars)
                    }
                    if (!isLong && sel.importChapters && parsed.shortChapters.isNotEmpty()) {
                        val ts = System.currentTimeMillis()
                        parsed.shortChapters.forEachIndexed { i, pc ->
                            val goal = buildString {
                                if (pc.plot.isNotBlank()) append(pc.plot)
                                if (pc.keyScene.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append(tx(lang, "关键场景：", "Key scene: "))
                                    append(pc.keyScene)
                                }
                            }.ifBlank { null }
                            vm.addChapter(projectId, pc.title, goal)
                        }
                    }
                    vm.showStatus(tx(lang, "已保存", "Saved"))
                    showSaveDialog = false
                },
                onDismiss = { showSaveDialog = false },
            )
        }
    }
}

// ── Save dialog ───────────────────────────────────────────────────────────────

@Composable
private fun SaveOutlineDialog(
    lang: String,
    isLong: Boolean,
    parsed: ParsedOutlineSections,
    currentWorld: String,
    currentTimeline: String,
    existingArcCount: Int,
    existingCharCount: Int,
    existingChapterCount: Int,
    onConfirm: (SaveSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var sel by remember { mutableStateOf(SaveSelection()) }
    val hasWorld = parsed.worldSetting != null
    val hasTimeline = parsed.timeline != null
    val hasArcs = parsed.arcs.isNotEmpty()
    val hasChars = parsed.chars.isNotEmpty()
    val hasChapters = !isLong && parsed.shortChapters.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "保存生成的大纲", "Save Generated Outline")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    tx(lang, "AI大纲文本将始终保存。勾选需要同步覆盖的内容：",
                        "AI outline text is always saved. Check items to also overwrite:"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Fixed row — outline always saved
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(tx(lang, "AI大纲文本（始终保存）", "AI Outline Text (always saved)"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (hasWorld) SaveSectionRow(
                    checked = sel.overwriteWorld,
                    onCheckedChange = { sel = sel.copy(overwriteWorld = it) },
                    label = tx(lang, "覆盖世界观设定", "Overwrite World Setting"),
                    warning = if (currentWorld.isNotBlank()) tx(lang, "⚠ 当前已有世界观设定，将被替换", "⚠ Existing world setting will be replaced") else null,
                    preview = parsed.worldSetting?.take(120),
                    lang = lang,
                )
                if (hasTimeline) SaveSectionRow(
                    checked = sel.overwriteTimeline,
                    onCheckedChange = { sel = sel.copy(overwriteTimeline = it) },
                    label = tx(lang, "覆盖时间线", "Overwrite Timeline"),
                    warning = if (currentTimeline.isNotBlank()) tx(lang, "⚠ 当前已有时间线内容，将被替换", "⚠ Existing timeline will be replaced") else null,
                    preview = parsed.timeline?.take(120),
                    lang = lang,
                )
                if (isLong && hasArcs) SaveSectionRow(
                    checked = sel.overwriteArcs,
                    onCheckedChange = { sel = sel.copy(overwriteArcs = it) },
                    label = tx(lang, "覆盖剧情弧线（替换为 ${parsed.arcs.size} 个新弧线）",
                        "Overwrite Plot Arcs (replace with ${parsed.arcs.size} new arcs)"),
                    warning = if (existingArcCount > 0) tx(lang, "⚠ 当前已有 $existingArcCount 个弧线，将全部替换",
                        "⚠ $existingArcCount existing arcs will be replaced") else null,
                    customContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            parsed.arcs.take(6).forEachIndexed { i, arc ->
                                Text("${i + 1}. ${arc.title}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (parsed.arcs.size > 6) Text(
                                tx(lang, "…还有 ${parsed.arcs.size - 6} 个弧线", "…and ${parsed.arcs.size - 6} more arcs"),
                                style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    lang = lang,
                )
                if (hasChars) SaveSectionRow(
                    checked = sel.importChars,
                    onCheckedChange = { sel = sel.copy(importChars = it) },
                    label = tx(lang, "导入角色到角色管理（${parsed.chars.size} 个新角色）",
                        "Import Characters (${parsed.chars.size} new characters)"),
                    warning = if (existingCharCount > 0) tx(lang, "⚠ 已有 $existingCharCount 个角色，新角色将追加（不覆盖已有角色）",
                        "⚠ $existingCharCount existing characters — new ones will be appended") else null,
                    customContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            parsed.chars.take(6).forEach { c ->
                                Text(if (c.role.isNotBlank()) "${c.name}·${c.role.take(5)}" else c.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (parsed.chars.size > 6) Text("+${parsed.chars.size - 6}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    lang = lang,
                )
                if (hasChapters) SaveSectionRow(
                    checked = sel.importChapters,
                    onCheckedChange = { sel = sel.copy(importChapters = it) },
                    label = tx(lang, "导入空白章节（${parsed.shortChapters.size} 章）",
                        "Import Blank Chapters (${parsed.shortChapters.size} chapters)"),
                    warning = if (existingChapterCount > 0) tx(lang,
                        "⚠ 已有 $existingChapterCount 个章节，新章节将追加",
                        "⚠ $existingChapterCount existing chapters — new ones will be appended") else null,
                    customContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            parsed.shortChapters.take(5).forEach { c ->
                                Text(c.title, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (parsed.shortChapters.size > 5) Text(
                                tx(lang, "…还有 ${parsed.shortChapters.size - 5} 章", "…and ${parsed.shortChapters.size - 5} more"),
                                style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    lang = lang,
                )
                if (!hasWorld && !hasTimeline && !(isLong && hasArcs) && !hasChars && !hasChapters) {
                    Text(
                        tx(lang, "未能从大纲中识别出可解析的结构化内容，仅保存大纲文本。",
                            "No parseable structured content found — only outline text will be saved."),
                        style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sel) }) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(tx(lang, "确认保存", "Confirm Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}

@Composable
private fun SaveSectionRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    warning: String?,
    preview: String? = null,
    customContent: (@Composable () -> Unit)? = null,
    lang: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (warning != null) Text(warning, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
                if (preview != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text("${tx(lang, "新内容：", "New: ")}$preview…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                customContent?.invoke()
            }
        }
    }
}
