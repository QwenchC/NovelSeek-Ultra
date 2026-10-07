package com.example.novelseek_ultra.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterReviewDiffTest {
    @Test
    fun partialAdoptionKeepsUnselectedParagraphAndAllWhitespace() {
        val before = "开场。\n\n旧冲突。\n\n转折。\n\n旧结尾。\n"
        val after = "开场。\n\n新冲突。\n\n转折。\n\n新结尾。\n"
        val blocks = chapterReviewDiff(before, after)
        val changes = blocks.filter { it.changed }

        assertEquals(2, changes.size)
        assertEquals(before, assembleChapterReviewText(blocks, emptySet()))
        assertEquals(after, assembleChapterReviewText(blocks, changes.map { it.id }.toSet()))
        assertEquals("开场。\n\n新冲突。\n\n转折。\n\n旧结尾。\n", assembleChapterReviewText(blocks, setOf(changes.first().id)))
    }

    @Test
    fun additionsAndDeletionsCanBeAdoptedIndependently() {
        val before = "甲。\n要删除。\n乙。\n丙。\n"
        val after = "甲。\n乙。\n新增。\n丙。\n"
        val blocks = chapterReviewDiff(before, after)
        val deletion = blocks.single { it.changed && it.candidateText.isEmpty() }
        val insertion = blocks.single { it.changed && it.baselineText.isEmpty() }

        assertEquals("甲。\n乙。\n丙。\n", assembleChapterReviewText(blocks, setOf(deletion.id)))
        assertEquals("甲。\n要删除。\n乙。\n新增。\n丙。\n", assembleChapterReviewText(blocks, setOf(insertion.id)))
    }

    @Test
    fun repeatedAndReorderedParagraphsNeverLoseOrDuplicateText() {
        val before = "相同。\n甲。\n相同。\n乙。\n丙。\n"
        val after = "相同。\n丙。\n相同。\n甲。\n乙。\n"
        val blocks = chapterReviewDiff(before, after)

        assertEquals(before, blocks.joinToString("") { it.baselineText })
        assertEquals(after, blocks.joinToString("") { it.candidateText })
        assertEquals(before, assembleChapterReviewText(blocks, emptySet()))
        assertEquals(after, assembleChapterReviewText(blocks, blocks.map { it.id }.toSet()))
    }

    @Test
    fun emptyAndIdenticalBodiesHaveExactSemantics() {
        assertTrue(chapterReviewDiff("", "").isEmpty())
        val original = "正文。\n\n末尾保留\n"
        assertFalse(chapterReviewDiff(original, original).single().changed)
        val created = chapterReviewDiff("", original)
        assertEquals("", assembleChapterReviewText(created, emptySet()))
        assertEquals(original, assembleChapterReviewText(created, created.map { it.id }.toSet()))
    }

    @Test(timeout = 5_000)
    fun hugeManuscriptUsesBoundedGroupsAndRecoversBothVersions() {
        val before = buildString { repeat(35_000) { append("第 $it 段，正文与伏笔。\n") } }
        val after = before.replace("第 19999 段，正文与伏笔。", "第 19999 段，修改角色知情范围。")
        val blocks = chapterReviewDiff(before, after, maxParagraphs = 180, maxParagraphChars = 1_000)

        assertTrue(blocks.size <= 361)
        assertEquals(before, assembleChapterReviewText(blocks, emptySet()))
        assertEquals(after, assembleChapterReviewText(blocks, blocks.map { it.id }.toSet()))
    }

    @Test
    fun previewChunksAreLosslessAndUnicodeSafeIncludingLongSingleParagraph() {
        val text = "甲😀乙🌏\n".repeat(1_000) + "结尾😀"
        val chunks = chapterReviewTextChunks(text, maxChars = 7)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 7 })
        chunks.zipWithNext().forEach { (left, right) ->
            assertFalse(Character.isHighSurrogate(left.last()) && Character.isLowSurrogate(right.first()))
        }
        val blocks = chapterReviewDiff(text, "新开场😀\n$text", maxParagraphs = 10, maxParagraphChars = 11)
        blocks.forEach {
            if (it.baselineText.isNotEmpty()) assertFalse(Character.isLowSurrogate(it.baselineText.first()))
            if (it.candidateText.isNotEmpty()) assertFalse(Character.isLowSurrogate(it.candidateText.first()))
        }
        assertEquals(text, assembleChapterReviewText(blocks, emptySet()))
        assertEquals("新开场😀\n$text", assembleChapterReviewText(blocks, blocks.map { it.id }.toSet()))
    }
}
