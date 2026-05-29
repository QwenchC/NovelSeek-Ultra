package com.example.novelseek_ultra.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.example.novelseek_ultra.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.export.PdfExporter
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExportFormat(val label: String, val mime: String, val ext: String) {
    Txt("TXT", "text/plain", "txt"),
    Markdown("Markdown", "text/markdown", "md"),
    Pdf("PDF", "application/pdf", "pdf"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = vm.project(projectId)
    val chapters = remember(projectId) { vm.chapters(projectId).sortedBy { it.order_index } }

    var format by remember { mutableStateOf(ExportFormat.Pdf) }
    var includeOutline by remember { mutableStateOf(false) }
    var preferFinal by remember { mutableStateOf(true) }
    // PDF-only image toggles (mirror the PC export): novel cover, per-chapter promo banner,
    // and inline paragraph illustrations. Default on.
    var includeCover by remember { mutableStateOf(true) }
    var includePromo by remember { mutableStateOf(true) }
    var includeIllustrations by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }

    val createDoc = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(format.mime),
    ) { uri: Uri? ->
        if (uri == null || project == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = tx(lang, "正在导出…", "Exporting…")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when (format) {
                        ExportFormat.Txt -> writeTextFile(
                            context, uri,
                            buildPlainText(project.title, project.author,
                                if (includeOutline) vm.outlineText(projectId) else null,
                                chapters.map { ch ->
                                    val b = vm.chapterBody(ch.id)
                                    ch.title to if (preferFinal) b.final.ifBlank { b.draft } else b.draft
                                })
                        )
                        ExportFormat.Markdown -> writeTextFile(
                            context, uri,
                            buildMarkdown(project.title, project.author,
                                if (includeOutline) vm.outlineText(projectId) else null,
                                chapters.map { ch ->
                                    val b = vm.chapterBody(ch.id)
                                    ch.title to if (preferFinal) b.final.ifBlank { b.draft } else b.draft
                                })
                        )
                        ExportFormat.Pdf -> {
                            // Resolve the project's default cover (or first cover) as base64.
                            val coverB64 = if (includeCover) {
                                val covers = vm.getCoverImages(projectId)
                                val def = project.default_cover_id
                                (if (def != null) covers.find { it.id == def } else covers.firstOrNull())
                                    ?.imageBase64
                            } else null
                            val outlineText = if (includeOutline) vm.outlineText(projectId) else null

                            if (PdfExporter.fontAvailable(context)) {
                                // Rich path: cover + clickable TOC + bookmarks + Song font + kinsoku.
                                val chaps = chapters.map { ch ->
                                    val b = vm.chapterBody(ch.id)
                                    val body = if (preferFinal) b.final.ifBlank { b.draft } else b.draft
                                    val promoItem = if (includePromo) vm.getChapterPromo(ch.id) else null
                                    val ills = if (includeIllustrations)
                                        vm.chapterIllustrations(ch.id).map { PdfExporter.Illu(it.anchorIndex, it.imageBase64) }
                                    else emptyList()
                                    PdfExporter.Chap(
                                        number = ch.order_index,
                                        title = ch.title,
                                        body = body,
                                        promoBase64 = promoItem?.imageBase64,
                                        summary = promoItem?.summary,
                                        illus = ills,
                                    )
                                }
                                val richOk = context.contentResolver.openOutputStream(uri, "w")?.use { os ->
                                    PdfExporter.export(context, os, project.title, project.author, coverB64, outlineText, chaps)
                                } ?: false
                                if (!richOk) {
                                    // Font present but PdfBox failed → native fallback.
                                    writePdfFile(
                                        context, uri, project.title, project.author, outlineText,
                                        coverB64, nativePdfChapters(vm, chapters, preferFinal, includePromo, includeIllustrations),
                                    )
                                }
                            } else {
                                // No bundled CJK font → native renderer (text+image, no links).
                                writePdfFile(
                                    context, uri, project.title, project.author, outlineText,
                                    coverB64, nativePdfChapters(vm, chapters, preferFinal, includePromo, includeIllustrations),
                                )
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            status = when {
                !ok -> tx(lang, "导出失败", "Export failed")
                format == ExportFormat.Pdf && !PdfExporter.fontAvailable(context) -> tx(lang,
                    "导出完成（未找到内置中文字体，已用基础排版导出：无封面标题框/目录超链接。请放置字体后重试，见下方说明）。",
                    "Done (no bundled CJK font — exported with the basic layout: no cover title box / TOC links. Add the font and retry, see note below).")
                else -> tx(lang, "导出完成", "Export complete")
            }
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                title = { Text(tx(lang, "导出", "Export"), style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx(lang, "导出格式", "Format"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportFormat.values().forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                    }
                }
            } }

            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx(lang, "选项", "Options"), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeOutline, onCheckedChange = { includeOutline = it })
                    Text(tx(lang, "包含大纲作为前言", "Include outline as foreword"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = preferFinal, onCheckedChange = { preferFinal = it })
                    Text(tx(lang, "优先使用「正文」（取消则使用「草稿」）", "Prefer 'final' text (uncheck for 'draft')"))
                }

                // ── PDF-only image options ──────────────────────────────
                if (format == ExportFormat.Pdf) {
                    Text(
                        tx(lang, "PDF 图片（仅 PDF 格式生效）", "PDF images (PDF format only)"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeCover, onCheckedChange = { includeCover = it })
                        Text(tx(lang, "封面（书籍首页）", "Cover (title page)"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includePromo, onCheckedChange = { includePromo = it })
                        Text(tx(lang, "章节推文图（章首横幅）", "Chapter promo (header banner)"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeIllustrations, onCheckedChange = { includeIllustrations = it })
                        Text(tx(lang, "段落插图（按锚点插入）", "Paragraph illustrations (inline)"))
                    }
                    // Font status: clickable-TOC / cover-title-box / bookmarks need a bundled CJK font.
                    val fontOk = remember { PdfExporter.fontAvailable(context) }
                    if (fontOk) {
                        Text(
                            tx(lang, "✓ 已检测到内置中文字体：完整排版（满页封面+标题框、可点目录、书签、宋体、页码）。",
                                "✓ Bundled CJK font found: full layout (cover title box, clickable TOC, bookmarks, Song font, page numbers)."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            tx(lang,
                                "⚠ 未找到内置中文字体，PDF 将用基础排版（无封面标题框 / 目录超链接 / 书签）。\n" +
                                    "要启用完整排版：把一个中文宋体 TrueType 字体重命名为 cjk-serif.ttf，放到\n" +
                                    "app/src/main/assets/fonts/ 目录下，重新编译即可。",
                                "⚠ No bundled CJK font — PDF uses the basic layout (no cover title box / TOC links / bookmarks).\n" +
                                    "To enable the full layout: drop a Chinese Song TrueType font renamed to cjk-serif.ttf into\n" +
                                    "app/src/main/assets/fonts/ and rebuild."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Text(
                    tx(lang, "章节数：${chapters.size}", "Chapters: ${chapters.size}"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } }

            FilledTonalButton(
                onClick = {
                    val safeTitle = project.title.replace(Regex("[^\\p{L}\\p{N}\\-_ ]"), "_")
                    createDoc.launch("$safeTitle.${format.ext}")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tx(lang, "保存到…", "Save to…"))
            }

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ── Renderers ──────────────────────────────────────────────────────────

private fun buildPlainText(title: String, author: String?, outline: String?, chapters: List<Pair<String, String>>): String =
    buildString {
        appendLine(title); if (!author.isNullOrBlank()) appendLine(author); appendLine()
        if (!outline.isNullOrBlank()) { appendLine("【大纲 / Outline】"); appendLine(outline); appendLine() }
        chapters.forEachIndexed { i, (t, body) ->
            appendLine("${i + 1}. $t"); appendLine(); appendLine(body); appendLine()
        }
    }

private fun buildMarkdown(title: String, author: String?, outline: String?, chapters: List<Pair<String, String>>): String =
    buildString {
        appendLine("# $title"); if (!author.isNullOrBlank()) appendLine("*$author*"); appendLine()
        if (!outline.isNullOrBlank()) { appendLine("## Outline"); appendLine(); appendLine(outline); appendLine() }
        chapters.forEach { (t, body) -> appendLine("## $t"); appendLine(); appendLine(body); appendLine() }
    }

private fun writeTextFile(ctx: Context, uri: Uri, content: String) {
    ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(content.toByteArray()) }
}

private data class PdfIllustration(val anchorIndex: Int, val base64: String)
private data class PdfChapter(
    val title: String,
    val body: String,
    val promoBase64: String?,
    val illustrations: List<PdfIllustration>,
)

/** Build the native-renderer chapter list (used when the bundled CJK font is unavailable). */
private fun nativePdfChapters(
    vm: AppViewModel,
    chapters: List<Chapter>,
    preferFinal: Boolean,
    includePromo: Boolean,
    includeIllustrations: Boolean,
): List<PdfChapter> = chapters.map { ch ->
    val b = vm.chapterBody(ch.id)
    val body = if (preferFinal) b.final.ifBlank { b.draft } else b.draft
    val promo = if (includePromo) vm.getChapterPromo(ch.id)?.imageBase64 else null
    val ills = if (includeIllustrations)
        vm.chapterIllustrations(ch.id).map { PdfIllustration(it.anchorIndex, it.imageBase64) }
    else emptyList()
    PdfChapter(ch.title, body, promo, ills)
}

/** Split body into paragraphs identically to the editor (blank-line delimited), 1-based anchors. */
private fun splitParagraphsForPdf(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isEmpty()) return emptyList()
    return normalized.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Render a paginated A4-ish PDF using Android's native PdfDocument, now with images:
 *   - project cover on its own first page
 *   - per-chapter promo banner above the chapter heading
 *   - paragraph illustrations inserted right after their anchor paragraph (1-based)
 *
 * Base64 → Bitmap decoding happens here (this runs on Dispatchers.IO at the call site).
 */
private fun writePdfFile(
    ctx: Context, uri: Uri,
    title: String, author: String?,
    outline: String?,
    coverBase64: String?,
    chapters: List<PdfChapter>,
) {
    val pageWidth = 595   // points (A4-ish at 72dpi)
    val pageHeight = 842
    val margin = 48
    val lineHeight = 18
    val maxWidth = pageWidth - 2 * margin

    val pdf = PdfDocument()
    val paint = Paint().apply { textSize = 13f }
    val titlePaint = Paint().apply { textSize = 22f; isFakeBoldText = true }
    val headingPaint = Paint().apply { textSize = 17f; isFakeBoldText = true }

    var pageNum = 1
    var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
    var canvas = page.canvas
    var y = margin

    fun finishPage() {
        pdf.finishPage(page)
        pageNum += 1
        page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        canvas = page.canvas
        y = margin
    }

    fun drawLine(text: String, p: Paint) {
        if (y + lineHeight > pageHeight - margin) finishPage()
        canvas.drawText(text, margin.toFloat(), y.toFloat(), p)
        y += (p.textSize + 4).toInt()
    }

    fun wrapAndDraw(body: String, p: Paint = paint) {
        body.split("\n").forEach { rawLine ->
            if (rawLine.isBlank()) { y += lineHeight; return@forEach }
            var line = ""
            for (ch in rawLine) {
                val candidate = line + ch
                if (p.measureText(candidate) > maxWidth) {
                    drawLine(line, p); line = ch.toString()
                } else line = candidate
            }
            if (line.isNotEmpty()) drawLine(line, p)
        }
    }

    // Scale a bitmap to fit the text column width (and cap at the printable page height),
    // paginating first if it doesn't fit the remaining space. Centered horizontally.
    fun drawImage(bmp: Bitmap, gapAfter: Int = 8) {
        if (bmp.width <= 0 || bmp.height <= 0) return
        var drawW = maxWidth
        var drawH = (bmp.height.toFloat() * maxWidth / bmp.width).toInt()
        val maxH = pageHeight - 2 * margin
        if (drawH > maxH) {
            drawH = maxH
            drawW = (bmp.width.toFloat() * maxH / bmp.height).toInt()
        }
        if (y + drawH > pageHeight - margin) finishPage()
        val left = margin + (maxWidth - drawW) / 2
        canvas.drawBitmap(bmp, null, Rect(left, y, left + drawW, y + drawH), null)
        y += drawH + gapAfter
    }

    // ── Cover page ──────────────────────────────────────────────────────
    coverBase64?.let { base64ToBitmap(it) }?.let { bmp ->
        drawImage(bmp)
        drawLine(title, titlePaint)
        if (!author.isNullOrBlank()) drawLine(author, paint)
        finishPage()
    } ?: run {
        drawLine(title, titlePaint)
        if (!author.isNullOrBlank()) drawLine(author, paint)
        y += lineHeight
    }

    if (!outline.isNullOrBlank()) {
        drawLine("Outline", headingPaint)
        wrapAndDraw(outline)
        finishPage()
    }

    chapters.forEachIndexed { i, ch ->
        // Promo banner above the chapter heading.
        ch.promoBase64?.let { base64ToBitmap(it) }?.let { drawImage(it) }

        drawLine("${i + 1}. ${ch.title}", headingPaint)
        y += 6

        val paragraphs = splitParagraphsForPdf(ch.body)
        if (paragraphs.isEmpty()) {
            wrapAndDraw(ch.body)
        } else {
            val byAnchor = ch.illustrations.groupBy { it.anchorIndex }
            paragraphs.forEachIndexed { idx, para ->
                wrapAndDraw(para)
                y += 4
                // anchorIndex is 1-based → paragraph at list index idx is anchor idx+1
                byAnchor[idx + 1]?.forEach { ill ->
                    base64ToBitmap(ill.base64)?.let { drawImage(it) }
                }
            }
        }
        finishPage()
    }
    pdf.finishPage(page)

    ctx.contentResolver.openOutputStream(uri, "w")?.use { pdf.writeTo(it) }
    pdf.close()
}
