package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.ChapterFactEvidenceBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactEvidenceLedgerTest {
    @Test
    fun `successful empty extraction is retained as coverage`() {
        val result = FactEvidenceLedger.replaceChapter(
            existing = emptyList(),
            chapterId = "chapter-1",
            sourceHash = "hash-1",
            extractionInputHash = inputHash("chapter-1", "hash-1"),
            observations = emptyList(),
        )

        assertEquals(1, result.size)
        assertEquals("chapter-1", result.single().chapterId)
        assertEquals("hash-1", result.single().sourceHash)
        assertTrue(result.single().facts.isEmpty())
        assertFalse(result.single().isStale)
    }

    @Test
    fun `edited chapter stales old batch and same-source retry is idempotent`() {
        val first = FactEvidenceLedger.replaceChapter(
            existing = emptyList(),
            chapterId = "chapter-2",
            sourceHash = "hash-old",
            extractionInputHash = inputHash("chapter-2", "hash-old"),
            observations = listOf(observation("密信", "密信仍未拆开")),
        )
        val edited = FactEvidenceLedger.replaceChapter(
            existing = first,
            chapterId = "chapter-2",
            sourceHash = "hash-new",
            extractionInputHash = inputHash("chapter-2", "hash-new"),
            observations = listOf(observation("密信", "密信已经烧毁")),
        )
        val retried = FactEvidenceLedger.replaceChapter(
            existing = edited,
            chapterId = "chapter-2",
            sourceHash = "hash-new",
            extractionInputHash = inputHash("chapter-2", "hash-new"),
            observations = listOf(observation("密信", "密信已经烧毁")),
        )

        assertEquals(2, edited.size)
        assertTrue(edited.single { it.sourceHash == "hash-old" }.isStale)
        assertFalse(edited.single { it.sourceHash == "hash-new" }.isStale)
        assertEquals(edited, retried)
    }

    @Test
    fun `stale history is bounded while active evidence survives`() {
        var batches = emptyList<ChapterFactEvidenceBatch>()
        repeat(5) { revision ->
            batches = FactEvidenceLedger.replaceChapter(
                existing = batches,
                chapterId = "chapter-1",
                sourceHash = "hash-$revision",
                extractionInputHash = inputHash("chapter-1", "hash-$revision"),
                observations = listOf(observation("门", "状态-$revision")),
            )
        }

        assertEquals(3, batches.size)
        assertEquals(2, batches.count { it.isStale })
        assertEquals("hash-4", batches.single { !it.isStale }.sourceHash)
    }

    @Test
    fun `visibility uses live chapter order and hides deleted or stale sources`() {
        val chapterOne = batch("batch-1", "chapter-1")
        val chapterThree = batch("batch-3", "chapter-3")
        val deleted = batch("deleted", "chapter-deleted")
        val stale = batch("stale", "chapter-0", isStale = true)

        val visible = FactEvidenceLedger.visibleBefore(
            existing = listOf(chapterThree, deleted, chapterOne, stale),
            targetChapterOrder = 3,
            chapterOrdersById = mapOf("chapter-1" to 2, "chapter-3" to 4, "chapter-0" to 1),
        )

        assertEquals(listOf("batch-1"), visible.map { it.id })
    }

    @Test
    fun `permanent chapter removal drops active and stale evidence references`() {
        val remaining = FactEvidenceLedger.removeChapter(
            existing = listOf(
                batch("active", "chapter-delete"),
                batch("stale", "chapter-delete", isStale = true),
                batch("keep", "chapter-keep"),
            ),
            chapterId = "chapter-delete",
        )

        assertEquals(listOf("keep"), remaining.map { it.id })
    }

    @Test
    fun `only literal source excerpts are accepted and clipping remains literal`() {
        val source = "他把密信压在烛火上，火舌很快吞没了最后一个名字。"

        assertEquals(
            "密信压在烛火上",
            FactEvidenceLedger.verifiedExcerpt(source, "密信压在烛火上"),
        )
        assertEquals("", FactEvidenceLedger.verifiedExcerpt(source, "密信被完整保存"))
        assertEquals("他把密信", FactEvidenceLedger.verifiedExcerpt(source, source, maxChars = 4))
    }

    @Test
    fun `freshness covers title body characters and contract but not chapter order`() {
        val sourceHash = "body-hash"
        val inputHash = FactEvidenceLedger.extractionInputHash(
            chapterId = "chapter-1",
            chapterTitle = "第一章",
            sourceHash = sourceHash,
            characterSignature = "characters-a",
        )
        val current = ChapterFactEvidenceBatch(
            id = "batch",
            chapterId = "chapter-1",
            sourceHash = sourceHash,
            extractionInputHash = inputHash,
            extractorContract = FactEvidenceLedger.CONTRACT,
        )

        assertTrue(FactEvidenceLedger.isFresh(current, "chapter-1", "第一章", sourceHash, "characters-a"))
        assertFalse(FactEvidenceLedger.isFresh(current, "chapter-1", "改名", sourceHash, "characters-a"))
        assertFalse(FactEvidenceLedger.isFresh(current, "chapter-1", "第一章", "edited", "characters-a"))
        assertFalse(FactEvidenceLedger.isFresh(current, "chapter-1", "第一章", sourceHash, "characters-b"))
        assertFalse(
            FactEvidenceLedger.isFresh(
                current.copy(extractorContract = "chapter_facts.v1", extractionInputHash = ""),
                "chapter-1",
                "第一章",
                sourceHash,
                "characters-a",
            ),
        )
    }

    private fun observation(subject: String, claim: String) = FactEvidenceLedger.Observation(
        entityId = "entity-$subject",
        factType = "foreshadowing",
        subject = subject,
        claim = claim,
        evidenceText = "原文",
    )

    private fun inputHash(chapterId: String, sourceHash: String) =
        FactEvidenceLedger.extractionInputHash(chapterId, "标题", sourceHash, "characters")

    private fun batch(id: String, chapterId: String, isStale: Boolean = false) =
        ChapterFactEvidenceBatch(
            id = id,
            chapterId = chapterId,
            sourceHash = "hash-$id",
            isStale = isStale,
        )
}
