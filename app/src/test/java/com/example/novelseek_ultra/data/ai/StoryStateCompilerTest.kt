package com.example.novelseek_ultra.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryStateCompilerTest {
    @Test
    fun `compilation is deterministic regardless of source collection order`() {
        val input = sampleInput()
        val first = StoryStateCompiler.compile(input)
        val reordered = StoryStateCompiler.compile(
            input.copy(
                arcs = input.arcs.reversed(),
                summaries = input.summaries.reversed(),
                commitments = input.commitments.reversed(),
                evidence = input.evidence.reversed(),
                characters = input.characters.reversed(),
                trackedState = input.trackedState.reversed(),
                recentChapters = input.recentChapters.reversed(),
                retrievedMemory = input.retrievedMemory.reversed(),
            ),
        )

        assertEquals(first.prompt, reordered.prompt)
        assertEquals(first.fingerprint, reordered.fingerprint)
        assertTrue(first.fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.prompt.indexOf("【剧情弧线状态】") < first.prompt.indexOf("【叙事摘要】"))
        assertTrue(first.prompt.indexOf("【叙事摘要】") < first.prompt.indexOf("【未闭合剧情承诺与事实】"))
        assertTrue(first.prompt.indexOf("【未闭合剧情承诺与事实】") < first.prompt.indexOf("【逐章事实证据】"))
        assertTrue(first.prompt.indexOf("【逐章事实证据】") < first.prompt.indexOf("【角色当前状态】"))
    }

    @Test
    fun `compiler enforces a total prompt budget and reports truncated sections`() {
        val huge = "长状态".repeat(8_000)
        val input = sampleInput().copy(
            summaries = (1..8).map {
                StoryStateCompiler.SummaryState("chapter", "chapter-$it", "$it-$huge")
            },
            commitments = (1..40).map {
                StoryStateCompiler.CommitmentState("thread-$it", "foreshadowing", "伏笔$it", huge)
            },
            trackedState = (1..40).map {
                StoryStateCompiler.TrackedState("container-$it", "追踪$it", "block", "全局", huge)
            },
        )

        val compiled = StoryStateCompiler.compile(input)

        assertTrue(compiled.prompt.length <= StoryStateCompiler.MAX_PROMPT_CHARS)
        assertTrue(compiled.truncatedSections.isNotEmpty())
        assertTrue(compiled.prompt.contains("[truncated]"))
        assertTrue(compiled.prompt.contains("【最近章节已发生事实】"))
        assertTrue(compiled.prompt.contains("【长程相关记忆】"))
    }

    @Test
    fun `large early sections cannot starve recent consequences or retrieved memory`() {
        val huge = "早期状态".repeat(10_000)
        val compiled = StoryStateCompiler.compile(
            sampleInput().copy(
                arcs = listOf(StoryStateCompiler.ArcState("arc", "巨大弧线", huge, "active", 1)),
                summaries = listOf(StoryStateCompiler.SummaryState("book", "book", huge)),
                commitments = listOf(
                    StoryStateCompiler.CommitmentState("thread", "foreshadowing", "巨大伏笔", huge),
                ),
            ),
        )

        assertTrue(compiled.prompt.length <= StoryStateCompiler.MAX_PROMPT_CHARS)
        assertTrue(compiled.prompt.contains("【最近章节已发生事实】"))
        assertTrue(compiled.prompt.contains("主角负伤退入城中"))
        assertTrue(compiled.prompt.contains("【长程相关记忆】"))
        assertTrue(compiled.prompt.contains("密信首次出现"))
    }

    @Test
    fun `evidence overflow keeps newest complete records instead of partial old facts`() {
        val evidence = (1..30).map { order ->
            StoryStateCompiler.EvidenceState(
                entityId = "entity-$order",
                type = "event",
                subject = "事实$order",
                claim = ("第${order}章事实内容").repeat(30),
                statusAfter = "open",
                sourceChapterId = "chapter-$order",
                sourceChapterOrder = order,
                sourceChapterTitle = "章节$order",
            )
        }
        val compiled = StoryStateCompiler.compile(
            StoryStateCompiler.Input(language = "zh", evidence = evidence),
        )
        val section = compiled.prompt.substringAfter("【逐章事实证据】\n")

        assertTrue(section.contains("第30章《章节30》"))
        assertFalse(section.contains("第1章《章节1》"))
        assertTrue(section.lineSequence().all {
            it == "[earlier records truncated]" || it.startsWith("- [")
        })
        assertTrue("fact_evidence" in compiled.truncatedSections)
    }

    @Test
    fun `duplicate logical keys still compile deterministically`() {
        val first = sampleInput().copy(
            commitments = listOf(
                StoryStateCompiler.CommitmentState("same", "event", "同名事件", "后发生"),
                StoryStateCompiler.CommitmentState("same", "event", "同名事件", "先发生"),
            ),
        )

        assertEquals(
            StoryStateCompiler.compile(first).prompt,
            StoryStateCompiler.compile(first.copy(commitments = first.commitments.reversed())).prompt,
        )
    }

    @Test
    fun `recent consequences prefer summaries and retain only the latest three chapters`() {
        val chapters = (1..5).map { order ->
            StoryStateCompiler.RecentChapterState(
                chapterId = "chapter-$order",
                order = order,
                title = "标题$order",
                summary = if (order == 5) "第五章结构化后果" else null,
                bodyTail = "BODY-TAIL-$order",
            )
        }

        val compiled = StoryStateCompiler.compile(
            StoryStateCompiler.Input(language = "zh", recentChapters = chapters),
        )

        assertFalse(compiled.prompt.contains("BODY-TAIL-1"))
        assertFalse(compiled.prompt.contains("BODY-TAIL-2"))
        assertTrue(compiled.prompt.contains("BODY-TAIL-3"))
        assertTrue(compiled.prompt.contains("第五章结构化后果"))
        assertFalse(compiled.prompt.contains("BODY-TAIL-5"))
    }

    @Test
    fun `meaningful state change changes the compiler fingerprint`() {
        val first = StoryStateCompiler.compile(sampleInput())
        val changed = StoryStateCompiler.compile(
            sampleInput().copy(
                commitments = sampleInput().commitments.map {
                    it.copy(summary = it.summary + " 已推进")
                },
            ),
        )

        assertNotEquals(first.fingerprint, changed.fingerprint)
    }

    @Test
    fun `chapter evidence retains provenance and exact excerpt`() {
        val compiled = StoryStateCompiler.compile(sampleInput())

        assertTrue(compiled.prompt.contains("第1章《第一章》"))
        assertTrue(compiled.prompt.contains("密信仍未拆开"))
        assertTrue(compiled.prompt.contains("原文证据：“他把密信压在枕下”"))
        assertEquals(2, compiled.sourceCounts.getValue("fact_evidence"))
    }

    @Test
    fun `english compilation uses stable english section contracts`() {
        val compiled = StoryStateCompiler.compile(sampleInput().copy(language = "en"))

        assertTrue(compiled.prompt.startsWith("[Story arc state]"))
        assertTrue(compiled.prompt.contains("[Open story commitments and facts]"))
        assertTrue(compiled.prompt.contains("[Chapter fact evidence]"))
        assertFalse(compiled.prompt.contains("【"))
        assertEquals(StoryStateCompiler.CONTRACT, compiled.contract)
    }

    @Test
    fun `stale summaries are never rendered as authoritative state`() {
        val compiled = StoryStateCompiler.compile(
            sampleInput().copy(
                summaries = listOf(
                    StoryStateCompiler.SummaryState("book", "fresh", "有效摘要"),
                    StoryStateCompiler.SummaryState("arc", "stale", "过期剧透", stale = true),
                ),
            ),
        )

        assertTrue(compiled.prompt.contains("有效摘要"))
        assertFalse(compiled.prompt.contains("过期剧透"))
    }

    private fun sampleInput() = StoryStateCompiler.Input(
        language = "zh",
        arcs = listOf(
            StoryStateCompiler.ArcState("arc-2", "后续弧", "尚未开始", "upcoming", 2),
            StoryStateCompiler.ArcState("arc-1", "当前弧", "核心冲突", "active", 1),
        ),
        summaries = listOf(
            StoryStateCompiler.SummaryState("arc", "arc-1", "弧线推进摘要"),
            StoryStateCompiler.SummaryState("book", "project-1", "全书摘要"),
        ),
        commitments = listOf(
            StoryStateCompiler.CommitmentState("item-1", "item", "断剑", "仍由主角持有"),
            StoryStateCompiler.CommitmentState("thread-1", "foreshadowing", "密信", "来源尚未揭晓"),
        ),
        evidence = listOf(
            StoryStateCompiler.EvidenceState(
                entityId = "thread-1",
                type = "foreshadowing",
                subject = "密信",
                claim = "密信仍未拆开",
                statusAfter = "open",
                sourceChapterId = "chapter-1",
                sourceChapterOrder = 1,
                sourceChapterTitle = "第一章",
                evidenceText = "他把密信压在枕下",
            ),
            StoryStateCompiler.EvidenceState(
                type = "event",
                subject = "封城",
                claim = "北门已经关闭",
                statusAfter = "resolved",
                sourceChapterId = "chapter-2",
                sourceChapterOrder = 2,
                sourceChapterTitle = "第二章",
            ),
        ),
        characters = listOf(
            StoryStateCompiler.CharacterState("char-2", "乙", "已经离队", 4),
            StoryStateCompiler.CharacterState("char-1", "甲", "负伤但仍在城中", 5),
        ),
        trackedState = listOf(
            StoryStateCompiler.TrackedState("container-2", "物品", "char-1", "甲", "持有断剑"),
            StoryStateCompiler.TrackedState("container-1", "关系", "char-2", "乙", "暂时敌对"),
        ),
        recentChapters = listOf(
            StoryStateCompiler.RecentChapterState("chapter-4", 4, "旧约", bodyTail = "旧约已破裂"),
            StoryStateCompiler.RecentChapterState("chapter-5", 5, "夜袭", summary = "主角负伤退入城中"),
        ),
        retrievedMemory = listOf(
            StoryStateCompiler.MemoryState(2, "chapter-2", "第二章", 0, "城门曾被封印"),
            StoryStateCompiler.MemoryState(1, "chapter-1", "第一章", 1, "密信首次出现"),
        ),
    )
}
