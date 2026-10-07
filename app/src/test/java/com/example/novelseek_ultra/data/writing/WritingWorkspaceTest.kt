package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.GenerationContextFingerprint
import com.example.novelseek_ultra.data.model.GenerationContextMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingWorkspaceTest {
    private val chapters = (1..3).map { Chapter("c$it", "p", "第${it}章", it) }
    private val current = chapters[1]

    @Test
    fun operationalPreferencesDoNotInvalidateStoryContentHash() {
        val original = WritingWorkspace(style = "克制", notes = listOf(StoryNote("n", text = "世界没有魔法")))
        val operational = original.copy(mode = "polish", maxRequestsPerRun = 64,
            planningProfileId = "planner", writingProfileId = "writer", reviewProfileId = "reviewer",
            extractionProfileId = "extractor")
        assertEquals(hash(original), hash(operational))
        assertNotEquals(hash(original), hash(original.copy(style = "轻快")))
        assertNotEquals(hash(original), hash(original.copy(notes = listOf(StoryNote("n", text = "世界有魔法")))))
    }

    @Test
    fun canonicalCardEncodingDistinguishesDelimiterCollisionAndIgnoresCardListOrder() {
        val first = StoryNote("n", subject = "a, text=b", text = "c")
        val second = StoryNote("n", subject = "a", text = "b, text=c")
        assertEquals(first.toString(), second.toString()) // Demonstrates why toString is not a fingerprint encoding.
        assertNotEquals(hash(WritingWorkspace(notes = listOf(first))), hash(WritingWorkspace(notes = listOf(second))))
        val another = StoryNote("z", text = "另一张卡片")
        assertEquals(hash(WritingWorkspace(notes = listOf(first, another))),
            hash(WritingWorkspace(notes = listOf(another, first))))
    }

    @Test
    fun factualAndBeliefCardsOnlyAppearAfterTheirSourceChapter() {
        val notes = listOf(
            StoryNote("past", "fact", text = "发现药方", sourceChapterId = "c1"),
            StoryNote("present", "fact", text = "本章才发生", sourceChapterId = "c2"),
            StoryNote("future", "fact", text = "下章才发生", sourceChapterId = "c3"),
            StoryNote("missing", "belief", text = "误认为导师无辜", sourceChapterId = "deleted"),
            StoryNote("canon", "canon", text = "作者知道真正凶手"),
            StoryNote("plan", "plan", text = "下章揭开真相"),
        )
        val selected = StoryNoteSelector.select(WritingWorkspace(notes = notes), current, chapters)
        assertEquals(setOf("past", "canon", "plan"), selected.includedIds.toSet())
        assertEquals(setOf("present", "future", "missing"), selected.excludedIds.toSet())
        assertTrue(selected.prompt.contains("作者设定（不代表角色已知）"))
        assertTrue(selected.prompt.contains("未来计划（不能当作已发生事实）"))
        assertFalse(selected.prompt.contains("下章才发生"))
    }

    @Test
    fun sourceBodyEditsInvalidateCardsAndUnboundLegacyCardsAreExcludedWhenHashesAreAvailable() {
        val oldHash = TextRangeReader.hash("原文中只发现药方")
        val notes = listOf(StoryNote("bound", "fact", text = "发现药方", sourceChapterId = "c1", sourceBodyHash = oldHash),
            StoryNote("legacy", "belief", text = "误认为导师无辜", sourceChapterId = "c1"))
        val original = StoryNoteSelector.select(WritingWorkspace(notes = notes), current, chapters,
            sourceHashes = mapOf("c1" to oldHash))
        assertEquals(listOf("bound"), original.includedIds)
        assertEquals(listOf("legacy"), original.excludedIds)
        val changed = StoryNoteSelector.select(WritingWorkspace(notes = notes), current, chapters,
            sourceHashes = mapOf("c1" to TextRangeReader.hash("原文已经改变")))
        assertTrue(changed.includedIds.isEmpty())
        assertEquals(setOf("bound", "legacy"), changed.excludedIds.toSet())
        assertTrue(changed.exclusionReasons.values.all { it.contains("正文已变化") })
    }

    @Test
    fun resolvedAndFutureForeshadowingAreExcludedButCurrentPayoffIsRequired() {
        val notes = listOf(
            StoryNote("due", "foreshadowing", text = "药方暗号", sourceChapterId = "c1", payoffChapterId = "c2"),
            StoryNote("resolved", "foreshadowing", text = "已回收的暗号", sourceChapterId = "c1", resolved = true),
            StoryNote("future", "foreshadowing", text = "下章才埋下", sourceChapterId = "c3"),
        )
        val selected = StoryNoteSelector.select(WritingWorkspace(notes = notes), current, chapters)
        assertEquals(listOf("due"), selected.includedIds)
        assertEquals(setOf("resolved", "future"), selected.excludedIds.toSet())
        assertTrue(selected.prompt.contains("本章应处理此伏笔"))
    }

    @Test
    fun characterKnowledgeMapsIdentifiersToNamesWithoutGivingOtherCharactersTheKnowledge() {
        val note = StoryNote("knowledge", "belief", text = "误认为导师无辜", sourceChapterId = "c1",
            knownByCharacterIds = listOf("hero", "mentor"))
        val selected = StoryNoteSelector.select(WritingWorkspace(notes = listOf(note)), current, chapters,
            characterNames = mapOf("hero" to "林澈", "mentor" to "导师"))
        assertTrue(selected.prompt.contains("知情角色：林澈, 导师"))
        assertTrue(selected.prompt.contains("其他角色不可直接获知"))
        assertTrue(selected.prompt.contains("角色的认知/误解，不代表客观事实"))
        val missing = StoryNoteSelector.select(WritingWorkspace(notes = listOf(note)), current, chapters,
            characterNames = mapOf("hero" to "林澈"))
        assertTrue(missing.includedIds.isEmpty())
        assertTrue(missing.prompt.isEmpty())
        assertTrue(missing.exclusionReasons["knowledge"]!!.contains("角色已删除"))
    }

    @Test
    fun requiredOverflowFailsWithoutTruncatingCanonOrScheduledPayoff() {
        listOf(StoryNote("canon", text = "世界设定".repeat(200)),
            StoryNote("payoff", "foreshadowing", text = "重要伏笔".repeat(200), payoffChapterId = "c2"))
            .forEach { note ->
                assertThrows(IllegalArgumentException::class.java) {
                    StoryNoteSelector.select(WritingWorkspace(notes = listOf(note)), current, chapters, maxChars = 64)
                }
            }
        val optional = StoryNoteSelector.select(WritingWorkspace(notes = listOf(
            StoryNote("optional", "plan", text = "可选计划".repeat(200)))), current, chapters, maxChars = 64)
        assertTrue(optional.includedIds.isEmpty())
        assertTrue(optional.prompt.isEmpty())
        assertTrue(optional.exclusionReasons["optional"]!!.contains("预算不足"))
    }

    @Test
    fun workspaceValidationRejectsUnboundFactsDuplicateCardsAndInvalidDigests() {
        assertThrows(IllegalArgumentException::class.java) {
            WritingWorkspace(notes = listOf(StoryNote("fact", "fact", text = "没有原文来源"))).validated()
        }
        val valid = StoryNote("note", text = "设定")
        assertThrows(IllegalArgumentException::class.java) { WritingWorkspace(notes = listOf(valid, valid)).validated() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sourceBodyHash = "not-a-digest").validated() }
    }

    private fun hash(workspace: WritingWorkspace) = GenerationContextFingerprint.capture("workspace_test",
        listOf(GenerationContextMaterial("workspace", workspace.contentFingerprintMaterial()))).fingerprint
}
