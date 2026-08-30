package com.example.novelseek_ultra.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedChapterMutationTest {
    @Test
    fun `draft-only checkpoint behind final does not invalidate effective prose`() {
        assertFalse(
            DerivedChapterMutation.effectiveTextChanged(
                oldDraft = "old draft",
                oldFinal = "published",
                newDraft = "new draft",
                newFinal = "published",
            ),
        )
    }

    @Test
    fun `every effective prose transition invalidates`() {
        assertTrue(DerivedChapterMutation.effectiveTextChanged("one", "", "two", ""))
        assertTrue(DerivedChapterMutation.effectiveTextChanged("draft", "final one", "draft", "final two"))
        assertTrue(DerivedChapterMutation.effectiveTextChanged("fallback", "final", "fallback", ""))
        assertFalse(DerivedChapterMutation.effectiveTextChanged("same", "", "same", ""))
    }

    @Test
    fun `only title and order changes invalidate existing chapter metadata`() {
        val before = listOf(
            source("one", "One", 1),
            source("two", "Two", 2),
        )
        val after = listOf(
            source("one", "Renamed", 2),
            source("two", "Two", 1),
            source("new", "New", 3),
        )

        assertEquals(setOf("one", "two"), DerivedChapterMutation.changedMetadataIds(before, after))
        assertTrue(DerivedChapterMutation.changedMetadataIds(after, after).isEmpty())
    }

    @Test
    fun `historical target detection does not trust word count alone`() {
        val oldBackupWithMissingCount = listOf(
            DerivedChapterMutation.Presence(3, 0, "persisted draft"),
        )
        assertTrue(DerivedChapterMutation.hasWrittenAtOrAfter(3, oldBackupWithMissingCount))
        assertTrue(
            DerivedChapterMutation.hasWrittenAtOrAfter(
                2,
                listOf(DerivedChapterMutation.Presence(5, 10, "")),
            ),
        )
        assertFalse(
            DerivedChapterMutation.hasWrittenAtOrAfter(
                6,
                oldBackupWithMissingCount,
            ),
        )
    }

    private fun source(id: String, title: String, order: Int) =
        DerivedChapterMutation.Metadata(id, title, order)
}
