package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.EntityPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EntityReconciliationTest {
    @Test
    fun `reextracting stale payoff preserves its original first seen anchor`() {
        val stale = entity(first = "chapter-2", last = "chapter-8", status = "paid_off")

        val repaired = EntityReconciliation.merge(
            current = stale,
            sourceChapterId = "chapter-8",
            aliases = listOf("旧约"),
            summary = "新的回收结果",
            extractedStatus = "open",
        )

        assertEquals("chapter-2", repaired.firstSeenChapterId)
        assertEquals("chapter-8", repaired.lastSeenChapterId)
        assertEquals("open", repaired.status)
        assertEquals("新的回收结果", repaired.summary)
        assertFalse(repaired.isStale)
    }

    @Test
    fun `reextracting first observation keeps unaffected later aggregate`() {
        val stale = entity(first = "chapter-2", last = "chapter-8", status = "paid_off")

        val repaired = EntityReconciliation.merge(
            current = stale,
            sourceChapterId = "chapter-2",
            aliases = emptyList(),
            summary = "早期描述",
            extractedStatus = "open",
        )

        assertEquals("chapter-2", repaired.firstSeenChapterId)
        assertEquals("chapter-8", repaired.lastSeenChapterId)
        assertEquals("paid_off", repaired.status)
        assertEquals("旧聚合", repaired.summary)
        assertFalse(repaired.isStale)
    }

    @Test
    fun `fresh match wins and stale match is repairable only from its anchor`() {
        val stale = entity(first = "chapter-2", last = "chapter-8")
        val fresh = stale.copy(id = "fresh", isStale = false)
        assertEquals(
            1,
            EntityReconciliation.matchIndex(
                listOf(stale, fresh), "foreshadowing", "誓约", "chapter-8",
            ),
        )
        assertEquals(
            -1,
            EntityReconciliation.matchIndex(
                listOf(stale), "foreshadowing", "誓约", "chapter-5",
            ),
        )
    }

    @Test
    fun `out of order completion keeps the chronologically latest aggregate`() {
        val chapterEightFirst = entity("chapter-8", "chapter-8")
            .copy(summary = "第八章状态", isStale = false)
        val reconciled = EntityReconciliation.reconcileWithEvidence(
            current = chapterEightFirst,
            evidence = listOf(
                EntityReconciliation.TimedEvidence("chapter-8", 8, "第八章状态", "paid_off"),
                EntityReconciliation.TimedEvidence("chapter-5", 5, "第五章状态", "open"),
            ),
            chapterOrdersById = mapOf("chapter-5" to 5, "chapter-8" to 8),
        )

        assertEquals("chapter-5", reconciled.firstSeenChapterId)
        assertEquals("chapter-8", reconciled.lastSeenChapterId)
        assertEquals("第八章状态", reconciled.summary)
        assertEquals("paid_off", reconciled.status)
    }

    @Test
    fun `partial ledger cannot overwrite a later legacy aggregate`() {
        val legacy = entity("chapter-2", "chapter-8")
            .copy(summary = "第八章旧聚合", status = "paid_off", isStale = false)
        val reconciled = EntityReconciliation.reconcileWithEvidence(
            current = legacy,
            evidence = listOf(
                EntityReconciliation.TimedEvidence("chapter-5", 5, "第五章重提取", "open"),
            ),
            chapterOrdersById = mapOf(
                "chapter-2" to 2,
                "chapter-5" to 5,
                "chapter-8" to 8,
            ),
        )

        assertEquals("chapter-8", reconciled.lastSeenChapterId)
        assertEquals("第八章旧聚合", reconciled.summary)
        assertEquals("paid_off", reconciled.status)
    }

    @Test
    fun `removing latest anchor falls back to remaining verified evidence`() {
        val current = entity("chapter-2", "chapter-8")
            .copy(summary = "第八章状态", status = "paid_off", isStale = true)
        val remaining = EntityReconciliation.afterChapterRemoval(
            current = current,
            removedChapterId = "chapter-8",
            remainingEvidence = listOf(
                EntityReconciliation.TimedEvidence("chapter-2", 2, "第二章埋下伏笔", "open"),
            ),
            // Explicit forget does not delete the chapter, so its live order is still present.
            chapterOrdersById = mapOf("chapter-2" to 2, "chapter-8" to 8),
        )

        requireNotNull(remaining)
        assertEquals("chapter-2", remaining.firstSeenChapterId)
        assertEquals("chapter-2", remaining.lastSeenChapterId)
        assertEquals("第二章埋下伏笔", remaining.summary)
        assertEquals("open", remaining.status)
        assertFalse(remaining.isStale)
    }

    @Test
    fun `removal without verified evidence hides an unverifiable legacy aggregate`() {
        val current = entity("chapter-2", "chapter-8")
            .copy(summary = "第八章的不可验证状态", status = "paid_off", isStale = false)

        val remaining = EntityReconciliation.afterChapterRemoval(
            current = current,
            removedChapterId = "chapter-8",
            remainingEvidence = emptyList(),
            chapterOrdersById = mapOf("chapter-2" to 2, "chapter-8" to 8),
        )

        requireNotNull(remaining)
        assertEquals("chapter-2", remaining.firstSeenChapterId)
        assertEquals("chapter-2", remaining.lastSeenChapterId)
        assertEquals("", remaining.summary)
        assertEquals("open", remaining.status)
        assertEquals(true, remaining.isStale)
    }

    private fun entity(
        first: String,
        last: String,
        status: String = "open",
    ) = EntityPayload(
        id = "stale",
        entityType = "foreshadowing",
        canonicalName = "誓约",
        summary = "旧聚合",
        status = status,
        firstSeenChapterId = first,
        lastSeenChapterId = last,
        isStale = true,
    )
}
