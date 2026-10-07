package com.example.novelseek_ultra.data.writing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuscriptImporterTest {
    @Test
    fun splitsPlainTextChineseChapterTitlesWithoutSpacesAndEnglishTitles() {
        val result = ManuscriptImporter.preview("第一章相逢\n林澈推开药铺木门。\n第2章追踪\n药商消失在人群。\nChapter 3: Return\nHe returned.")
        assertEquals(listOf("第一章相逢", "第2章追踪", "Chapter 3: Return"), result.chapters.map { it.title })
        assertEquals(listOf("林澈推开药铺木门。", "药商消失在人群。", "He returned."), result.chapters.map { it.body })
    }

    @Test
    fun recognizesMarkdownAndNormalizesBomAndMixedNewlinesWithoutChangingProse() {
        val result = ManuscriptImporter.preview("\uFEFF# 开篇\r\n第一行😀\r第二行\n## 下一场\r\n下一场正文\n### 收尾\n最后一行")
        assertEquals(listOf("开篇", "下一场", "收尾"), result.chapters.map { it.title })
        assertEquals("第一行😀\n第二行", result.chapters.first().body)
        assertTrue(result.chapters.none { it.body.contains('\r') || it.body.contains('\uFEFF') })
    }

    @Test
    fun preservesPrefaceAndTreatsUnheadedManuscriptAsOneChapter() {
        val result = ManuscriptImporter.preview("作者前言\n这是一段导言。\n\n第一章初见\n正文")
        assertEquals(listOf("序章", "第一章初见"), result.chapters.map { it.title })
        assertEquals("作者前言\n这是一段导言。", result.chapters.first().body)
        assertEquals(listOf(ImportedChapter("序章", "没有章节标题的完整正文。")),
            ManuscriptImporter.preview("没有章节标题的完整正文。").chapters)
    }

    @Test
    fun skipsEmptyHeadingOnlyChaptersAndUsesLatestHeadingForFollowingBody() {
        val result = ManuscriptImporter.preview("第一章\n\n第二章空标题前的正文\n真正的正文\n第三章\n\n")
        assertEquals(listOf(ImportedChapter("第二章空标题前的正文", "真正的正文")), result.chapters)
        assertThrows(IllegalArgumentException::class.java) { ManuscriptImporter.preview("第一章\n\n第二章\n") }
    }

    @Test
    fun rejectsBlankInputsAndOverlargeManuscriptBeforeSplitting() {
        listOf("", " \r\n\t", "\uFEFF\r\n").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { ManuscriptImporter.preview(input) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManuscriptImporter.preview("x".repeat(ManuscriptImporter.MAX_CHARACTERS + 1))
        }
    }

    @Test
    fun chapterCountLimitIsEnforcedWithoutSilentlyDroppingChapters() {
        val text = (1..2_001).joinToString("\n") { "第${it}章\n正文" }
        assertThrows(IllegalArgumentException::class.java) { ManuscriptImporter.preview(text) }
        assertEquals(2_000, ManuscriptImporter.preview((1..2_000).joinToString("\n") { "第${it}章\n正文" }).chapters.size)
    }

    @Test
    fun preservesIndentationAndDoesNotInterpretHtmlOrInlineChapterMentions() {
        val text = "第一章起点\n  他提到了第三章中的传言。\n<script>alert('text')</script>\n尾段。"
        val result = ManuscriptImporter.preview(text)
        assertEquals(1, result.chapters.size)
        assertEquals("  他提到了第三章中的传言。\n<script>alert('text')</script>\n尾段。", result.chapters.single().body)
    }
}
