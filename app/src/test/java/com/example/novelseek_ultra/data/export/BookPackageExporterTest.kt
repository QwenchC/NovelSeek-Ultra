package com.example.novelseek_ultra.data.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document

class BookPackageExporterTest {
    private val book = BookPackageExporter.Book(
        title = "风与雪 <第二部>", author = "甲 & 乙", outline = "起点\n终点",
        chapters = listOf(
            BookPackageExporter.Chapter("第 1 章：约定", "  林晓说：\"出发吧。\"\n\n远处😀，风停了。 & < >"),
            BookPackageExporter.Chapter("第 2 章", "未完待续\u0000\uD800"),
        ), identifier = "urn:uuid:00000000-0000-0000-0000-000000000001",
    )

    @Test fun epubHasStoredFirstMimeAndResolvableManifestNavigationAndSpine() {
        val bytes = ByteArrayOutputStream().also { BookPackageExporter.exportEpub(it, book) }.toByteArray()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry
            assertEquals("mimetype", entry.name)
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.UTF_8))
            assertTrue(entry.extra == null || entry.extra.isEmpty())
        }
        val files = unzip(bytes)
        val container = parse(files.getValue("META-INF/container.xml"))
        val rootfile = container.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container", "rootfile").item(0)
        assertEquals("EPUB/package.opf", rootfile.attributes.getNamedItem("full-path").nodeValue)
        val opf = parse(files.getValue("EPUB/package.opf"))
        assertEquals(book.title, opf.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0).textContent)
        val manifest = opf.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "item")
        val ids = mutableSetOf<String>()
        for (index in 0 until manifest.length) {
            val node = manifest.item(index)
            ids += node.attributes.getNamedItem("id").nodeValue
            assertTrue(files.containsKey("EPUB/" + node.attributes.getNamedItem("href").nodeValue))
        }
        val spine = opf.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "itemref")
        assertEquals(4, spine.length)
        for (index in 0 until spine.length) assertTrue(ids.contains(spine.item(index).attributes.getNamedItem("idref").nodeValue))
        val nav = parse(files.getValue("EPUB/nav.xhtml"))
        assertEquals(4, nav.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "a").length)
        files.filterKeys { it.endsWith(".xhtml") }.values.forEach { parse(it) }
        assertTrue(parse(files.getValue("EPUB/chapter-1.xhtml")).documentElement.textContent.contains("远处😀，风停了。 & < >"))
    }

    @Test fun docxHasValidPartRelationshipsAndPreservesChineseAndParagraphWhitespace() {
        val files = unzip(ByteArrayOutputStream().also { BookPackageExporter.exportDocx(it, book) }.toByteArray())
        files.values.forEach { parse(it) }
        assertTrue(files.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")))
        val rels = parse(files.getValue("_rels/.rels"))
        val relationships = rels.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship")
        for (index in 0 until relationships.length) assertTrue(files.containsKey(relationships.item(index).attributes.getNamedItem("Target").nodeValue))
        val document = parse(files.getValue("word/document.xml"))
        val text = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
        assertEquals(book.title, text.item(0).textContent)
        assertTrue((0 until text.length).map { text.item(it).textContent }.contains("  林晓说：\"出发吧。\""))
        assertTrue(document.documentElement.textContent.contains("远处😀，风停了。 & < >"))
        assertTrue(document.documentElement.textContent.contains("未完待续\uFFFD\uFFFD"))
        val xmlText = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xmlText.contains("xml:space=\"preserve\""))
        assertEquals(3, document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "pageBreakBefore").length)
    }

    @Test fun exportersKeepCallerStreamOpenAndEscapeUnsafeXml() {
        class TrackingStream : ByteArrayOutputStream() {
            var wasClosed = false
            override fun close() { wasClosed = true; super.close() }
        }
        val stream = TrackingStream()
        BookPackageExporter.exportEpub(stream, book)
        assertFalse(stream.wasClosed)
        val stream2 = TrackingStream()
        BookPackageExporter.exportDocx(stream2, book)
        assertFalse(stream2.wasClosed)
        assertEquals("&lt;&gt;&amp;&quot;&apos;\uFFFD😀", BookPackageExporter.xml("<>&\"'\u0001😀"))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private fun parse(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}
