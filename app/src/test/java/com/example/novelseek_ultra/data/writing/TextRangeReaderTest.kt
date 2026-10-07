package com.example.novelseek_ultra.data.writing

import org.junit.Assert.*
import org.junit.Test

class TextRangeReaderTest {
    @Test fun paginationReassemblesLargeChapterWithoutSplittingEmoji() {
        val text = "前文😀𠮷后文\n".repeat(3_000)
        val pages = mutableListOf<TextRangeReader.Range>()
        var offset: Int? = 0
        while (offset != null) {
            val page = TextRangeReader.read(text, offset, 7)
            pages += page
            assertFalse(page.text.first().isLowSurrogate())
            assertFalse(page.text.last().isHighSurrogate())
            offset = page.nextOffset
        }
        assertEquals(text, pages.joinToString("") { it.text })
        assertEquals(text.length, pages.last().endOffset)
        assertEquals(1, pages.map { it.sourceHash }.distinct().size)
    }

    @Test fun oneCharacterPageCanAdvanceOverEmojiAndArbitraryOffsetsAreAdjusted() {
        val first = TextRangeReader.read("😀后", 0, 1)
        assertEquals("😀", first.text)
        assertEquals(2, first.nextOffset)
        assertEquals(0, TextRangeReader.read("😀后", 1, 1).offset)
        assertNull(TextRangeReader.read("", 0, 1).nextOffset)
    }

    @Test fun rejectsInvalidRangeRatherThanSilentlyClamping() {
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.read("正文", -1) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.read("正文", 3) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.read("正文", 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.read("正文", 0, 12_001) }
    }

    @Test fun searchPaginatesAcrossOutlineAndChaptersAndKeepsExactOffsets() {
        val sources = listOf(
            TextRangeReader.Source("outline", "大纲", "outline", "线索在此，线索在彼"),
            TextRangeReader.Source("chapter-8", "第八章", "chapter", "😀林晓发现线索😀，之后又见线索"),
        )
        val first = TextRangeReader.search(sources, "线索", limit = 3, contextLength = 1)
        assertEquals(4, first.total)
        assertEquals(3, first.nextOffset)
        assertEquals("chapter-8", first.hits.last().sourceId)
        assertEquals("现线索😀", first.hits.last().context)
        val next = TextRangeReader.search(sources, "线索", offset = first.nextOffset!!, limit = 3)
        assertEquals(1, next.hits.size)
        assertNull(next.nextOffset)
        (first.hits + next.hits).forEach { hit ->
            val source = sources.first { it.id == hit.sourceId }
            assertEquals("线索", source.text.substring(hit.offset, hit.endOffset))
            assertEquals(TextRangeReader.hash(source.text), hit.sourceHash)
        }
    }

    @Test fun searchIsLiteralAndCanIgnoreCaseAndHashesChangeWithSource() {
        val source = TextRangeReader.Source("1", "1", "chapter", "ABC a.c abc")
        assertEquals(2, TextRangeReader.search(listOf(source), "abc", ignoreCase = true).total)
        assertEquals(1, TextRangeReader.search(listOf(source), "a.c").total)
        assertNotEquals(TextRangeReader.hash(source.text), TextRangeReader.hash(source.text + "!"))
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.search(listOf(source), " ") }
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.search(listOf(source), "abc", limit = 31) }
        assertThrows(IllegalArgumentException::class.java) { TextRangeReader.search(listOf(source), "\uDE00") }
    }

    @Test fun millionMatchesAcrossThousandLazySourcesRemainBoundedToRequestedPage() {
        var loaded = 0
        val sources = sequence {
            repeat(1_000) { index ->
                loaded++
                yield(TextRangeReader.Source("chapter-$index", "章节 $index", "chapter", "字".repeat(1_000)))
            }
        }.asIterable()
        val page = TextRangeReader.search(sources, "字", limit = 30)
        assertEquals(1_000, loaded)
        assertEquals(1_000_000, page.total)
        assertEquals(30, page.hits.size)
        assertEquals(30, page.nextOffset)
        assertTrue(page.hits.all { it.context.length <= 241 })
    }

    @Test fun emojiSearchContextAndMatchOffsetsContainWholeCodePoints() {
        val text = "😀😀线索😀😀"
        val source = TextRangeReader.Source("c", "c", "chapter", text)
        val page = TextRangeReader.search(listOf(source), "线索", contextLength = 1)
        assertEquals("😀线索😀", page.hits.single().context)
        val emoji = TextRangeReader.search(listOf(source), "😀", contextLength = 0)
        assertEquals(listOf(0, 2, 6, 8), emoji.hits.map { it.offset })
        assertTrue(emoji.hits.all { it.context == "😀" })
    }
}
