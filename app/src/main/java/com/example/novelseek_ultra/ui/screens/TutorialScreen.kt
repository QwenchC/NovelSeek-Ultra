package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.ui.components.MarkdownText
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.launch

/**
 * In-app illustrated tutorial: renders the bundled `assets/tutorial/tutorial.md` (extracted from the
 * project README's "使用教程" section). A small block-level parser handles headings, paragraphs/lists,
 * fenced code blocks (shown verbatim — so format samples aren't mis-rendered as headings), tables, a
 * clickable table-of-contents (anchor → scroll), images (Coil, loaded from assets) and rules. The
 * whole page is wrapped in a SelectionContainer so users can select & copy text.
 */
private sealed class Blk {
    data class Heading(val level: Int, val text: String, val anchor: String) : Blk()
    data class Para(val md: String) : Blk()
    data class Code(val text: String) : Blk()
    data class Table(val header: List<String>, val rows: List<List<String>>) : Blk()
    data class Img(val path: String) : Blk()
    data class Toc(val entries: List<Pair<String, String>>) : Blk()  // (label, anchor)
    object Rule : Blk()
}

private val IMG_LINE = Regex("""^\s*!\[[^\]]*]\(([^)]+)\)\s*$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val TOC_LINE = Regex("""^(?:\d+[.、]|[-*])\s*\[([^\]]+)]\(#([^)]+)\)$""")
private val SEP_LINE = Regex("""^\|?[\s:|-]*-[\s:|-]*\|?$""")
private val HR = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

/** GitHub-flavored heading anchor: lowercase, drop punctuation, spaces → '-'. Matches the README TOC links. */
private fun slug(text: String): String =
    text.lowercase()
        .replace(Regex("""[^\p{L}\p{N}\s-]"""), "")
        .trim()
        .replace(Regex("""\s+"""), "-")

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private fun parseTutorial(md: String): List<Blk> {
    val out = ArrayList<Blk>()
    val lines = md.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    val tocBuf = ArrayList<Pair<String, String>>()
    fun flushPara() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) out.add(Blk.Para(t))
        para.setLength(0)
    }
    fun flushToc() {
        if (tocBuf.isNotEmpty()) { out.add(Blk.Toc(ArrayList(tocBuf))); tocBuf.clear() }
    }
    var i = 0
    var skippedTitle = false
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block — collect verbatim, do NOT parse markdown inside.
        if (trimmed.startsWith("```")) {
            flushPara(); flushToc()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) { sb.append(lines[i]).append("\n"); i++ }
            i++ // skip closing fence
            out.add(Blk.Code(sb.toString().trimEnd('\n')))
            continue
        }
        // Image.
        val img = IMG_LINE.find(line)
        if (img != null) {
            flushPara(); flushToc(); out.add(Blk.Img(img.groupValues[1].trim())); i++; continue
        }
        // TOC link line — accumulate consecutive ones into a single clickable block.
        val tocLink = TOC_LINE.find(trimmed)
        if (tocLink != null) {
            flushPara(); tocBuf.add(tocLink.groupValues[1].trim() to tocLink.groupValues[2].trim()); i++; continue
        }
        flushToc()  // any non-TOC line ends a TOC block
        // Table: a `|...|` header followed by a `|---|` separator.
        if (trimmed.startsWith("|") && i + 1 < lines.size && SEP_LINE.matches(lines[i + 1].trim())) {
            flushPara()
            val header = splitRow(trimmed)
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(splitRow(lines[i].trim())); i++ }
            out.add(Blk.Table(header, rows))
            continue
        }
        // Heading.
        val h = HEADING.find(line)
        if (h != null) {
            flushPara()
            val level = h.groupValues[1].length
            val text = h.groupValues[2].trim()
            // Drop the leading "## 使用教程" — the top bar already shows it.
            if (!skippedTitle && level <= 2 && text.contains("使用教程")) { skippedTitle = true; i++; continue }
            out.add(Blk.Heading(level, text, slug(text)))
            i++; continue
        }
        // Horizontal rule.
        if (HR.matches(trimmed)) { flushPara(); out.add(Blk.Rule); i++; continue }
        // Blank line → paragraph break.
        if (trimmed.isEmpty()) { flushPara(); i++; continue }
        // Normal text (strip blockquote markers for cleaner display).
        para.append(if (trimmed.startsWith(">")) trimmed.removePrefix(">").trim() else line).append("\n")
        i++
    }
    flushPara(); flushToc()
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(vm: AppViewModel, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val context = LocalContext.current
    val blocks = remember {
        val md = runCatching {
            context.assets.open("tutorial/tutorial.md").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        parseTutorial(md)
    }
    // anchor → list index, so TOC entries can scroll to their heading.
    val anchorIndex = remember(blocks) {
        buildMap { blocks.forEachIndexed { idx, b -> if (b is Blk.Heading) put(b.anchor, idx) } }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text(tx(lang, "使用教程", "Tutorial"), style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
        if (blocks.isEmpty()) {
            Text(
                tx(lang, "教程内容暂不可用。", "Tutorial content is unavailable."),
                modifier = Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(blocks) { _, blk ->
                    when (blk) {
                        is Blk.Heading -> HeadingBlock(blk)
                        is Blk.Para -> MarkdownText(blk.md, modifier = Modifier.fillMaxWidth())
                        is Blk.Code -> CodeBlock(blk.text)
                        is Blk.Table -> TableBlock(blk)
                        is Blk.Toc -> TocBlock(blk, lang) { anchor ->
                            anchorIndex[anchor]?.let { target -> scope.launch { listState.animateScrollToItem(target) } }
                        }
                        is Blk.Img -> AsyncImage(
                            model = "file:///android_asset/tutorial/${blk.path}",
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        is Blk.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(h: Blk.Heading) {
    val style = when (h.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleLarge
        4 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = h.text,
        style = style.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (h.level <= 3) 10.dp else 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun CodeBlock(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableBlock(t: Blk.Table) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            t.header.forEach { cell ->
                Text(
                    cell,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        t.rows.forEach { row ->
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth()) {
                // Pad short rows so columns stay aligned with the header.
                val cells = if (row.size >= t.header.size) row
                            else row + List(t.header.size - row.size) { "" }
                cells.take(t.header.size.coerceAtLeast(1)).forEach { cell ->
                    Text(
                        cell,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TocBlock(toc: Blk.Toc, lang: String, onJump: (anchor: String) -> Unit) {
    // Clicking an entry scrolls to its section. Wrapped in DisableSelection so taps don't fight the
    // page-wide text selection.
    DisableSelection {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            toc.entries.forEach { (label, anchor) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(anchor) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
