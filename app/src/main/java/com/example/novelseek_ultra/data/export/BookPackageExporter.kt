package com.example.novelseek_ultra.data.export

import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Text-only publication packages. Writes one document at a time and leaves the caller's stream open. */
object BookPackageExporter {
    data class Chapter(val title: String, val body: String)
    data class Book(
        val title: String,
        val author: String? = null,
        val chapters: List<Chapter>,
        val outline: String? = null,
        val language: String = "zh-CN",
        val identifier: String = "urn:uuid:${UUID.randomUUID()}",
    )

    fun exportEpub(output: OutputStream, book: Book) {
        val language = book.language.takeIf { it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) } ?: "zh-CN"
        val documents = buildList {
            add("title.xhtml" to Chapter(book.title.ifBlank { "Untitled" }, book.author.orEmpty()))
            if (!book.outline.isNullOrBlank()) add("outline.xhtml" to Chapter("大纲 / Outline", book.outline))
            book.chapters.forEachIndexed { index, chapter -> add("chapter-${index + 1}.xhtml" to chapter) }
        }
        packageZip(output) { zip ->
            // OCF requires the first entry to be uncompressed, with no extra fields or BOM.
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mime) }.value
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; this.crc = crc
            })
            zip.write(mime); zip.closeEntry()
            zip.xml("META-INF/container.xml") { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            }
            zip.xml("EPUB/package.opf") { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="$language"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                writer.write("<dc:identifier id=\"book-id\">${xml(book.identifier)}</dc:identifier><dc:title>${xml(book.title.ifBlank { "Untitled" })}</dc:title><dc:language>$language</dc:language>")
                if (!book.author.isNullOrBlank()) writer.write("<dc:creator>${xml(book.author)}</dc:creator>")
                writer.write("<meta property=\"dcterms:modified\">${utcNow()}</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                documents.forEachIndexed { index, (path, _) -> writer.write("<item id=\"doc-$index\" href=\"$path\" media-type=\"application/xhtml+xml\"/>") }
                writer.write("</manifest><spine>")
                documents.indices.forEach { writer.write("<itemref idref=\"doc-$it\"/>") }
                writer.write("</spine></package>")
            }
            zip.xml("EPUB/nav.xhtml") { writer ->
                writer.write(xhtmlStart("目录 / Contents", language))
                writer.write("<nav epub:type=\"toc\" id=\"toc\"><h1>目录 / Contents</h1><ol>")
                documents.forEach { (path, chapter) -> writer.write("<li><a href=\"$path\">${xml(chapter.title.ifBlank { "Untitled" })}</a></li>") }
                writer.write("</ol></nav></body></html>")
            }
            documents.forEach { (path, chapter) ->
                zip.xml("EPUB/$path") { writer ->
                    writer.write(xhtmlStart(chapter.title, language))
                    writer.write("<h1>${xml(chapter.title)}</h1>")
                    chapter.body.lineSequence().forEach { writer.write("<p>${xml(it)}</p>") }
                    writer.write("</body></html>")
                }
            }
        }
    }

    fun exportDocx(output: OutputStream, book: Book) {
        packageZip(output) { zip ->
            zip.xml("[Content_Types].xml") { it.write("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""") }
            zip.xml("_rels/.rels") { it.write("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="core" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""") }
            zip.xml("word/_rels/document.xml.rels") { it.write("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""") }
            zip.xml("docProps/core.xml") { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""")
                writer.write("<dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author.orEmpty())}</dc:creator><dcterms:modified xsi:type=\"dcterms:W3CDTF\">${utcNow()}</dcterms:modified></cp:coreProperties>")
            }
            zip.xml("word/styles.xml") { it.write("""<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="SimSun"/><w:sz w:val="24"/><w:lang w:val="zh-CN" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:spacing w:after="120" w:line="360" w:lineRule="auto"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>""") }
            zip.xml("word/document.xml") { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
                wordParagraph(writer, book.title.ifBlank { "Untitled" }, "Title")
                if (!book.author.isNullOrBlank()) wordParagraph(writer, book.author)
                if (!book.outline.isNullOrBlank()) {
                    wordParagraph(writer, "大纲 / Outline", "Heading1", pageBreak = true)
                    book.outline.lineSequence().forEach { wordParagraph(writer, it) }
                }
                book.chapters.forEach { chapter ->
                    wordParagraph(writer, chapter.title, "Heading1", pageBreak = true)
                    chapter.body.lineSequence().forEach { wordParagraph(writer, it) }
                }
                writer.write("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
            }
        }
    }

    private fun wordParagraph(writer: Writer, text: String, style: String = "Normal", pageBreak: Boolean = false) {
        writer.write("<w:p><w:pPr><w:pStyle w:val=\"$style\"/>")
        if (pageBreak) writer.write("<w:pageBreakBefore/>")
        writer.write("</w:pPr><w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>")
    }

    private fun xhtmlStart(title: String, language: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language" lang="$language"><head><title>${xml(title.ifBlank { "Untitled" })}</title><style>body { line-height: 1.7; } p { white-space: pre-wrap; margin: 0.6em 0; } h1 { font-size: 1.6em; }</style></head><body>"""

    private fun packageZip(output: OutputStream, write: (ZipOutputStream) -> Unit) {
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun close() { flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
        }).use(write)
    }

    private fun ZipOutputStream.xml(path: String, write: (Writer) -> Unit) {
        putNextEntry(ZipEntry(path))
        val writer = OutputStreamWriter(this, Charsets.UTF_8)
        write(writer); writer.flush(); closeEntry()
    }

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** Escape XML 1.0 while replacing forbidden controls and unpaired UTF-16 surrogates. */
    internal fun xml(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val code = Character.codePointAt(value, index)
            when (code) {
                38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;")
                34 -> append("&quot;"); 39 -> append("&apos;")
                else -> if (code == 9 || code == 10 || code == 13 || code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF) appendCodePoint(code) else append('\uFFFD')
            }
            index += Character.charCount(code)
        }
    }
}
