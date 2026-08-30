package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.SummaryPayload
import com.example.novelseek_ultra.data.model.CharacterGrowthEntry
import com.example.novelseek_ultra.data.model.ContainerEntry
import com.example.novelseek_ultra.data.model.ContainerStore
import com.example.novelseek_ultra.data.model.EntityPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedStateInvalidationTest {
    @Test
    fun `changing one chapter stales that summary and every aggregate`() {
        val summaries = listOf(
            summary("target", "chapter", "chapter-2"),
            summary("other", "chapter", "chapter-1"),
            summary("arc", "arc", "arc-1"),
            summary("book", "book", "book-1"),
        )

        val result = DerivedStateInvalidation.summariesAfterChapterChange(summaries, "chapter-2")

        assertTrue(result.single { it.id == "target" }.isStale)
        assertFalse(result.single { it.id == "other" }.isStale)
        assertTrue(result.single { it.id == "arc" }.isStale)
        assertTrue(result.single { it.id == "book" }.isStale)
    }

    @Test
    fun `dirty chapter marker is deterministic and idempotent`() {
        assertEquals(
            listOf("chapter-1", "chapter-2"),
            DerivedStateInvalidation.staleChapterIdsAfterChange(
                listOf("chapter-1", "chapter-2", "chapter-1"),
                "chapter-2",
            ),
        )
    }

    @Test
    fun `old chapter-derived records are retained and staled while manual records stay fresh`() {
        val entities = listOf(
            EntityPayload("old", "event", "旧事实", firstSeenChapterId = "chapter-2"),
            EntityPayload("last", "event", "最近事实", lastSeenChapterId = "chapter-2"),
            EntityPayload("keep", "event", "保留事实", firstSeenChapterId = "chapter-1"),
        )
        val growth = listOf(
            CharacterGrowthEntry("ai", "旧成长", chapterId = "chapter-2"),
            CharacterGrowthEntry(
                "manual",
                "作者设定",
                chapterId = "chapter-2",
                manual = true,
                isStale = true,
            ),
            CharacterGrowthEntry("keep", "保留成长", chapterId = "chapter-1"),
        )
        val store = ContainerStore(
            entries = mapOf(
                "container" to mapOf(
                    "main" to listOf(
                        ContainerEntry("ai", "旧值", sourceChapterId = "chapter-2"),
                        ContainerEntry(
                            "manual",
                            "作者值",
                            sourceChapterId = "chapter-2",
                            manual = true,
                            isStale = true,
                        ),
                        ContainerEntry("keep", "保留值", sourceChapterId = "chapter-1"),
                    ),
                ),
            ),
        )

        val invalidatedEntities =
            DerivedStateInvalidation.entitiesAfterChapterChange(entities, "chapter-2")
        assertEquals(listOf("old", "last", "keep"), invalidatedEntities.map { it.id })
        assertTrue(invalidatedEntities.single { it.id == "old" }.isStale)
        assertTrue(invalidatedEntities.single { it.id == "last" }.isStale)
        assertFalse(invalidatedEntities.single { it.id == "keep" }.isStale)

        val invalidatedGrowth =
            DerivedStateInvalidation.growthAfterChapterChange(growth, "chapter-2")
        assertEquals(listOf("ai", "manual", "keep"), invalidatedGrowth.map { it.id })
        assertTrue(invalidatedGrowth.single { it.id == "ai" }.isStale)
        assertFalse(invalidatedGrowth.single { it.id == "manual" }.isStale)
        assertFalse(invalidatedGrowth.single { it.id == "keep" }.isStale)

        val invalidatedEntries = DerivedStateInvalidation.containersAfterChapterChange(
            store,
            "chapter-2",
        ).entries.getValue("container").getValue("main")
        assertEquals(listOf("ai", "manual", "keep"), invalidatedEntries.map { it.id })
        assertTrue(invalidatedEntries.single { it.id == "ai" }.isStale)
        assertFalse(invalidatedEntries.single { it.id == "manual" }.isStale)
        assertFalse(invalidatedEntries.single { it.id == "keep" }.isStale)
    }

    @Test
    fun `reapplying chapter invalidation is idempotent`() {
        val entities = listOf(
            EntityPayload("entity", "event", "事实", lastSeenChapterId = "chapter-2"),
        )
        val growth = listOf(
            CharacterGrowthEntry("growth", "成长", chapterId = "chapter-2"),
            CharacterGrowthEntry(
                "manual-growth",
                "作者设定",
                chapterId = "chapter-2",
                manual = true,
                isStale = true,
            ),
        )
        val containers = ContainerStore(
            entries = mapOf(
                "container" to mapOf(
                    "main" to listOf(
                        ContainerEntry("entry", "值", sourceChapterId = "chapter-2"),
                        ContainerEntry(
                            "manual-entry",
                            "作者值",
                            sourceChapterId = "chapter-2",
                            manual = true,
                            isStale = true,
                        ),
                    ),
                ),
            ),
        )

        val entitiesOnce = DerivedStateInvalidation.entitiesAfterChapterChange(entities, "chapter-2")
        val growthOnce = DerivedStateInvalidation.growthAfterChapterChange(growth, "chapter-2")
        val containersOnce =
            DerivedStateInvalidation.containersAfterChapterChange(containers, "chapter-2")

        assertEquals(
            entitiesOnce,
            DerivedStateInvalidation.entitiesAfterChapterChange(entitiesOnce, "chapter-2"),
        )
        assertEquals(
            growthOnce,
            DerivedStateInvalidation.growthAfterChapterChange(growthOnce, "chapter-2"),
        )
        assertEquals(
            containersOnce,
            DerivedStateInvalidation.containersAfterChapterChange(containersOnce, "chapter-2"),
        )
    }

    @Test
    fun `editing or deleting active growth preserves unrelated stale history`() {
        val entries = listOf(
            CharacterGrowthEntry("stale", "旧值", isStale = true),
            CharacterGrowthEntry("active", "当前值"),
        )

        val edited = DerivedStateInvalidation.updateLatestActiveGrowth(entries, "作者修订")
        assertEquals(listOf("stale", "active"), edited.map { it.id })
        assertTrue(edited.first().isStale)
        assertEquals("作者修订", edited.last().value)
        assertTrue(edited.last().manual)

        val deleted = DerivedStateInvalidation.deleteGrowthEntry(entries, "active")
        assertEquals(listOf("stale"), deleted.map { it.id })
        assertTrue(deleted.single().isStale)
    }

    private fun summary(id: String, scope: String, scopeId: String) = SummaryPayload(
        id = id,
        scopeType = scope,
        scopeId = scopeId,
        summaryText = id,
    )
}
