package com.example.novelseek_ultra.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.OutputStream

/**
 * Rich PDF export via PdfBox-Android. Produces:
 *   - page 1: full-bleed cover image + book title in a white, black-bordered, shadowed box near
 *     the top-center
 *   - table of contents (page 2+) with clickable hyperlinks + PDF bookmarks
 *   - one chapter per page-break: bold title → promo banner → light summary (smaller) → body
 *   - Song/serif CJK body via a bundled TTF; kinsoku line-breaking (no punctuation at line start)
 *   - page numbers from the TOC onward (cover unnumbered)
 *
 * CJK rendering requires a bundled TrueType font at [FONT_ASSET]. If it's missing or anything
 * fails, [export] returns false and the caller falls back to the native (text+image, no links)
 * renderer. This is intentional: PdfBox needs an embedded glyph font for Chinese — the system
 * font Android's native PdfDocument uses isn't available here.
 */
object PdfExporter {

    /** Place a CJK serif (宋体) TrueType font here: app/src/main/assets/fonts/cjk-serif.ttf */
    const val FONT_ASSET = "fonts/cjk-serif.ttf"

    data class Illu(val anchorIndex: Int, val base64: String)
    data class Chap(
        val number: Int,          // shown as "第n章" in heading + TOC
        val title: String,
        val body: String,
        val promoBase64: String?,
        val summary: String?,
        val illus: List<Illu>,
    )

    // A4 @ 72dpi-ish points.
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private const val MARGIN = 48f
    private const val BODY_SIZE = 12.5f
    private const val SUMMARY_SIZE = 10.5f          // slightly smaller than body, per spec
    private const val TITLE_SIZE = 17f
    private const val COVER_TITLE_SIZE = 40f        // big cover title
    private const val TOC_SIZE = 12.5f
    private const val LINE_MUL = 1.6f
    private const val CJK_INDENT = "　　"   // two full-width spaces for paragraph indent

    // 行首禁则：这些标点不能出现在行首（出现则挤到上一行末尾）。
    private val NO_LINE_START =
        "，。、；：！？”’）】》」』%‰°·…—".toSet()

    private val CONTENT_W get() = PAGE_W - 2 * MARGIN

    /** True if the bundled CJK font asset exists — lets the caller pick the renderer up front. */
    fun fontAvailable(context: Context): Boolean =
        runCatching { context.assets.open(FONT_ASSET).use { true } }.getOrDefault(false)

    /** Returns true on success. False → caller should fall back to the native renderer. */
    fun export(
        context: Context,
        out: OutputStream,
        title: String,
        author: String?,
        coverBase64: String?,
        outline: String?,
        chapters: List<Chap>,
    ): Boolean {
        // Font is mandatory for CJK. Missing → signal fallback (don't crash).
        val fontBytes = runCatching { context.assets.open(FONT_ASSET).use { it.readBytes() } }.getOrNull()
            ?: return false

        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument().use { doc ->
                val font = PDType0Font.load(doc, fontBytes.inputStream(), true)
                val b = Builder(doc, font)
                b.render(title, author, coverBase64, outline, chapters)
                doc.save(out)
            }
            true
        } catch (_: Throwable) {
            // Any PdfBox/API/glyph failure → fall back to native renderer.
            false
        }
    }

    // ── internal renderer ──────────────────────────────────────────────────────

    private class Builder(val doc: PDDocument, val font: PDType0Font) {
        private val pages = ArrayList<PDPage>()
        private var cs: PDPageContentStream? = null
        private var yTop = MARGIN          // distance from the top edge of the current page

        fun render(
            title: String,
            author: String?,
            coverBase64: String?,
            outline: String?,
            chapters: List<Chap>,
        ) {
            // 1) Cover (document index 0, unnumbered).
            drawCoverPage(title, author, coverBase64)
            closeStream()

            // 2) Reserve TOC page slots so chapter pages get correct absolute indices.
            val tocPageCount = computeTocPageCount(chapters.size)
            val tocPages = ArrayList<PDPage>()
            repeat(tocPageCount) {
                val p = PDPage(PDRectangle(PAGE_W, PAGE_H))
                doc.addPage(p); pages.add(p); tocPages.add(p)
            }

            // 3) Chapters — each starts a fresh page. Record first-page index per chapter.
            val chapterFirstPage = ArrayList<PDPage>(chapters.size)
            val chapterPageNo = ArrayList<Int>(chapters.size)
            if (!outline.isNullOrBlank()) {
                newPage()
                textLine("Outline", TITLE_SIZE, bold = true)
                yTop += 4f
                paragraph(outline, BODY_SIZE)
                closeStream()
            }
            chapters.forEach { ch ->
                newPage()
                chapterFirstPage.add(currentPage())
                chapterPageNo.add(pages.size - 1)   // absolute index == displayed number
                drawChapter(ch)
                closeStream()
            }

            // 4) Draw the reserved TOC pages now that destinations + page numbers are known.
            drawToc(tocPages, chapters, chapterFirstPage, chapterPageNo)

            // 5) Bookmarks (PDF outline).
            addBookmarks(chapters, chapterFirstPage)

            // 6) Page numbers (skip cover at index 0; numbering starts at 1 on the first TOC page).
            drawPageNumbers()
        }

        // ── page plumbing ───────────────────────────────────────────────────────

        private fun currentPage() = pages.last()

        private fun newPage(): PDPage {
            closeStream()
            val p = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(p); pages.add(p)
            cs = PDPageContentStream(doc, p)
            yTop = MARGIN
            return p
        }

        private fun closeStream() { cs?.close(); cs = null }

        private fun ensure(h: Float) { if (yTop + h > PAGE_H - MARGIN) newPage() }

        // ── text ─────────────────────────────────────────────────────────────────

        /** Width of [s] at [size] in points. Caller must pass font-encodable text. */
        private fun widthOf(s: String, size: Float): Float =
            font.getStringWidth(s) / 1000f * size

        /** Drop glyphs the font can't encode (replace with space) to avoid showText crashes. */
        private fun sanitize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                if (ch == '\n' || ch == '\r' || ch == '\t') { sb.append(' '); continue }
                try { font.getStringWidth(ch.toString()); sb.append(ch) }
                catch (_: Throwable) { sb.append(' ') }
            }
            return sb.toString()
        }

        /** Greedy wrap with kinsoku: a forbidden-at-start char is pulled back onto the prev line. */
        private fun wrap(text: String, size: Float, maxWidth: Float): List<String> {
            val lines = ArrayList<String>()
            val sb = StringBuilder()
            var w = 0f
            for (ch in text) {
                val cw = widthOf(ch.toString(), size)
                if (w + cw > maxWidth && sb.isNotEmpty()) {
                    if (ch in NO_LINE_START) {
                        sb.append(ch); lines.add(sb.toString()); sb.setLength(0); w = 0f
                    } else {
                        lines.add(sb.toString()); sb.setLength(0); sb.append(ch); w = cw
                    }
                } else { sb.append(ch); w += cw }
            }
            if (sb.isNotEmpty()) lines.add(sb.toString())
            return lines
        }

        /** Draw a single pre-wrapped line at the current cursor. */
        private fun textLine(raw: String, size: Float, gray: Float = 0f, bold: Boolean = false) {
            val s = sanitize(raw)
            val lineH = size * LINE_MUL
            ensure(lineH)
            val c = cs!!
            val baseline = PAGE_H - (yTop + size)
            c.beginText()
            c.setNonStrokingColor(gray, gray, gray)
            c.setFont(font, size)
            c.newLineAtOffset(MARGIN, baseline)
            c.showText(s)
            c.endText()
            if (bold) {
                // faux-bold: redraw with a sub-pixel horizontal offset
                c.beginText()
                c.setNonStrokingColor(gray, gray, gray)
                c.setFont(font, size)
                c.newLineAtOffset(MARGIN + 0.4f, baseline)
                c.showText(s)
                c.endText()
            }
            yTop += lineH
        }

        /** Wrap + draw a whole paragraph, then add paragraph spacing. */
        private fun paragraph(raw: String, size: Float, gray: Float = 0f) {
            val clean = sanitize(raw)
            wrap(clean, size, CONTENT_W).forEach { line ->
                val lineH = size * LINE_MUL
                ensure(lineH)
                val c = cs!!
                val baseline = PAGE_H - (yTop + size)
                c.beginText()
                c.setNonStrokingColor(gray, gray, gray)
                c.setFont(font, size)
                c.newLineAtOffset(MARGIN, baseline)
                c.showText(line)
                c.endText()
                yTop += lineH
            }
            yTop += size * 0.5f
        }

        // ── images ─────────────────────────────────────────────────────────────

        private fun drawImageBlock(bmp: Bitmap, gapAfter: Float = 8f) {
            if (bmp.width <= 0 || bmp.height <= 0) return
            var drawW = CONTENT_W
            var drawH = bmp.height.toFloat() * CONTENT_W / bmp.width
            val maxH = PAGE_H - 2 * MARGIN
            if (drawH > maxH) { drawH = maxH; drawW = bmp.width.toFloat() * maxH / bmp.height }
            ensure(drawH)
            val img: PDImageXObject = LosslessFactory.createFromImage(doc, bmp)
            val left = MARGIN + (CONTENT_W - drawW) / 2
            val bottom = PAGE_H - (yTop + drawH)
            cs!!.drawImage(img, left, bottom, drawW, drawH)
            yTop += drawH + gapAfter
        }

        // ── cover ────────────────────────────────────────────────────────────────

        private fun drawCoverPage(title: String, author: String?, coverBase64: String?) {
            val p = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(p); pages.add(p)
            cs = PDPageContentStream(doc, p)
            val c = cs!!

            // Full-bleed cover image (center-cropped to page aspect to avoid distortion).
            coverBase64?.let { decode(it) }?.let { src ->
                val cropped = centerCrop(src, PAGE_W.toInt(), PAGE_H.toInt())
                val img = LosslessFactory.createFromImage(doc, cropped)
                c.drawImage(img, 0f, 0f, PAGE_W, PAGE_H)
            }

            // Title: the GLYPHS themselves are white-filled with a black outline + drop shadow
            // (no box). Big font, wrapped, centered near the top. Implemented by stacking several
            // black offset copies (outline halo) + a gray shadow copy under a white center copy —
            // avoids depending on PDF text rendering-mode (FILL_STROKE) APIs.
            val titleLines = wrap(sanitize(title), COVER_TITLE_SIZE, CONTENT_W)
            val centerX = PAGE_W / 2
            var topY = MARGIN + 40f
            titleLines.forEach { line ->
                val baseline = PAGE_H - (topY + COVER_TITLE_SIZE)
                coverTitleLine(c, line, COVER_TITLE_SIZE, centerX, baseline)
                topY += COVER_TITLE_SIZE * 1.3f
            }

            // Author under the title (optional), white with a soft shadow for legibility.
            if (!author.isNullOrBlank()) {
                val a = sanitize(author)
                val aw = font.getStringWidth(a) / 1000f * 16f
                val ab = PAGE_H - (topY + 16f) - 8f
                c.beginText(); c.setNonStrokingColor(0.2f, 0.2f, 0.2f); c.setFont(font, 16f)
                c.newLineAtOffset((PAGE_W - aw) / 2 + 1.5f, ab - 1.5f); c.showText(a); c.endText()
                c.beginText(); c.setNonStrokingColor(1f, 1f, 1f); c.setFont(font, 16f)
                c.newLineAtOffset((PAGE_W - aw) / 2, ab); c.showText(a); c.endText()
            }
        }

        /** Draw one centered cover-title line: gray shadow + black outline halo + white center. */
        private fun coverTitleLine(
            c: PDPageContentStream, line: String, size: Float, centerX: Float, baseline: Float,
        ) {
            val w = font.getStringWidth(line) / 1000f * size
            val x = centerX - w / 2
            fun stamp(dx: Float, dy: Float, gray: Float) {
                c.beginText(); c.setNonStrokingColor(gray, gray, gray); c.setFont(font, size)
                c.newLineAtOffset(x + dx, baseline + dy); c.showText(line); c.endText()
            }
            stamp(3f, -3f, 0.35f)                 // drop shadow
            val t = size * 0.04f                  // outline thickness
            for (dx in floatArrayOf(-t, 0f, t)) for (dy in floatArrayOf(-t, 0f, t)) {
                if (dx == 0f && dy == 0f) continue
                stamp(dx, dy, 0f)                 // black outline halo
            }
            stamp(0f, 0f, 1f)                     // white fill center
        }

        // ── chapter body ───────────────────────────────────────────────────────

        private fun drawChapter(ch: Chap) {
            // Bold title with "第n章" prefix (first line).
            textLine("第${ch.number}章　${ch.title}", TITLE_SIZE, bold = true)
            yTop += 4f
            // Promo banner.
            ch.promoBase64?.let { decode(it) }?.let { drawImageBlock(it) }
            // Promo summary — light gray, smaller than body.
            ch.summary?.takeIf { it.isNotBlank() }?.let {
                paragraph(it, SUMMARY_SIZE, gray = 0.45f)
            }
            // Body + inline illustrations anchored per paragraph (1-based).
            // Chinese paragraphs get a two-full-width-space first-line indent.
            val paras = splitParagraphs(ch.body)
            if (paras.isEmpty()) {
                paragraph(indentIfChinese(ch.body), BODY_SIZE)
            } else {
                val byAnchor = ch.illus.groupBy { it.anchorIndex }
                paras.forEachIndexed { idx, para ->
                    paragraph(indentIfChinese(para), BODY_SIZE)
                    byAnchor[idx + 1]?.forEach { ill ->
                        decode(ill.base64)?.let { drawImageBlock(it) }
                    }
                }
            }
        }

        /** Prepend two full-width spaces to paragraphs containing CJK text (first-line indent). */
        private fun indentIfChinese(s: String): String =
            if (s.any { it.code in 0x4E00..0x9FFF }) "$CJK_INDENT$s" else s

        // ── table of contents ─────────────────────────────────────────────────

        private fun drawToc(
            tocPages: List<PDPage>,
            chapters: List<Chap>,
            firstPages: List<PDPage>,
            pageNos: List<Int>,
        ) {
            if (tocPages.isEmpty()) return
            var tocIdx = 0
            var stream = PDPageContentStream(doc, tocPages[tocIdx])
            yTop = MARGIN
            // header on first TOC page
            run {
                val c = stream
                val header = "目录"
                c.beginText(); c.setNonStrokingColor(0f, 0f, 0f); c.setFont(font, TITLE_SIZE)
                c.newLineAtOffset(MARGIN, PAGE_H - (yTop + TITLE_SIZE)); c.showText(header); c.endText()
                c.beginText(); c.setNonStrokingColor(0f, 0f, 0f); c.setFont(font, TITLE_SIZE)
                c.newLineAtOffset(MARGIN + 0.4f, PAGE_H - (yTop + TITLE_SIZE)); c.showText(header); c.endText()
                yTop += TITLE_SIZE * LINE_MUL + 6f
            }
            val lineH = TOC_SIZE * LINE_MUL
            chapters.forEachIndexed { i, ch ->
                if (yTop + lineH > PAGE_H - MARGIN) {
                    stream.close()
                    tocIdx += 1
                    if (tocIdx >= tocPages.size) return  // safety
                    stream = PDPageContentStream(doc, tocPages[tocIdx])
                    yTop = MARGIN
                }
                val numStr = pageNos[i].toString()
                val titleStr = sanitize("第${ch.number}章　${ch.title}")
                val numW = font.getStringWidth(numStr) / 1000f * TOC_SIZE
                // truncate title to leave room for the page number on the right
                val maxTitleW = CONTENT_W - numW - 18f
                val shown = truncateToWidth(titleStr, TOC_SIZE, maxTitleW)
                val baseline = PAGE_H - (yTop + TOC_SIZE)
                stream.beginText(); stream.setNonStrokingColor(0.1f, 0.1f, 0.1f); stream.setFont(font, TOC_SIZE)
                stream.newLineAtOffset(MARGIN, baseline); stream.showText(shown); stream.endText()
                stream.beginText(); stream.setFont(font, TOC_SIZE)
                stream.newLineAtOffset(PAGE_W - MARGIN - numW, baseline); stream.showText(numStr); stream.endText()

                // clickable link over the whole row → chapter first page
                val link = PDAnnotationLink()
                // borderStyle (setBorderStyle) takes the style dict; `border` is a raw COSArray.
                link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                link.rectangle = PDRectangle(MARGIN, PAGE_H - (yTop + lineH), CONTENT_W, lineH)
                val dest = PDPageFitWidthDestination().apply { page = firstPages[i] }
                link.action = PDActionGoTo().apply { destination = dest }
                tocPages[tocIdx].annotations.add(link)

                yTop += lineH
            }
            stream.close()
        }

        private fun truncateToWidth(s: String, size: Float, maxW: Float): String {
            if (widthOf(s, size) <= maxW) return s
            val ell = "…"
            val ellW = widthOf(ell, size)
            val sb = StringBuilder()
            var w = 0f
            for (ch in s) {
                val cw = widthOf(ch.toString(), size)
                if (w + cw + ellW > maxW) break
                sb.append(ch); w += cw
            }
            return sb.append(ell).toString()
        }

        // ── bookmarks + page numbers ────────────────────────────────────────────

        private fun addBookmarks(chapters: List<Chap>, firstPages: List<PDPage>) {
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            chapters.forEachIndexed { i, ch ->
                val item = PDOutlineItem()
                item.title = ch.title
                item.destination = PDPageFitWidthDestination().apply { page = firstPages[i] }
                outline.addLast(item)
            }
            outline.openNode()
        }

        private fun drawPageNumbers() {
            pages.forEachIndexed { i, p ->
                if (i == 0) return@forEachIndexed   // cover unnumbered
                val label = i.toString()            // first TOC page shows "1"
                val w = font.getStringWidth(label) / 1000f * 9f
                val c = PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true)
                c.beginText(); c.setNonStrokingColor(0.4f, 0.4f, 0.4f); c.setFont(font, 9f)
                c.newLineAtOffset(PAGE_W / 2 - w / 2, MARGIN * 0.5f)
                c.showText(label); c.endText(); c.close()
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun computeTocPageCount(entries: Int): Int {
        if (entries <= 0) return 1
        val usable = PAGE_H - 2 * MARGIN
        val lineH = TOC_SIZE * LINE_MUL
        val headerH = TITLE_SIZE * LINE_MUL + 6f
        val firstPageRows = ((usable - headerH) / lineH).toInt().coerceAtLeast(1)
        val restPageRows = (usable / lineH).toInt().coerceAtLeast(1)
        if (entries <= firstPageRows) return 1
        val remaining = entries - firstPageRows
        return 1 + Math.ceil(remaining.toDouble() / restPageRows).toInt()
    }

    private fun splitParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun decode(b64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun centerCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        val srcRatio = src.width.toFloat() / src.height
        val tgtRatio = targetW.toFloat() / targetH
        val cw: Int; val chh: Int
        if (srcRatio > tgtRatio) {
            chh = src.height
            cw = (src.height * tgtRatio).toInt().coerceIn(1, src.width)
        } else {
            cw = src.width
            chh = (src.width / tgtRatio).toInt().coerceIn(1, src.height)
        }
        val x = ((src.width - cw) / 2).coerceAtLeast(0)
        val y = ((src.height - chh) / 2).coerceAtLeast(0)
        return runCatching { Bitmap.createBitmap(src, x, y, cw, chh) }.getOrDefault(src)
    }
}
