package com.example.novelseek_ultra.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsCacheLayoutTest {

    @Test
    fun chapterPromptKeepsStableProjectContextBeforeChapterSpecificContext() {
        val first = chapterPrompt(
            chapterTitle = "Chapter One",
            chapterList = "CHAPTER_LIST_ONE",
            world = "WORLD_RULE_A\r\nWORLD_RULE_B  ",
        )
        val second = chapterPrompt(
            chapterTitle = "Chapter Two",
            chapterList = "CHAPTER_LIST_TWO",
            world = "WORLD_RULE_A\nWORLD_RULE_B",
        )

        val firstStablePrefix = first.substringBefore("[Compiled Story State v2")
        val secondStablePrefix = second.substringBefore("[Compiled Story State v2")

        assertEquals(firstStablePrefix, secondStablePrefix)
        assertTrue(first.indexOf("WORLD_RULE_A") < first.indexOf("CHAPTER_LIST_ONE"))
        assertTrue(first.indexOf("TIMELINE_TOKEN") < first.indexOf("CHAPTER_LIST_ONE"))
        assertTrue(first.indexOf("CHARACTER_TOKEN") < first.indexOf("CHAPTER_LIST_ONE"))
        assertTrue(first.contains("[Compiled Story State v2 — authoritative continuity state]"))
        assertTrue(first.indexOf("DYNAMIC_KB_Chapter One") < first.indexOf("CHAPTER_LIST_ONE"))
        assertTrue(first.indexOf("DYNAMIC_KB_Chapter One") < first.indexOf("TARGET_Chapter One"))
        assertTrue(first.indexOf("TARGET_Chapter One") < first.indexOf("CHAPTER_LIST_ONE"))
        assertTrue(first.indexOf("DYNAMIC_KB_Chapter One") < first.indexOf("Write this chapter"))
        assertEquals(1, Regex("CHARACTER_TOKEN").findAll(first).count())
    }

    @Test
    fun changingOnlyTargetConstraintsPreservesTheStoryStatePrefix() {
        val first = chapterPrompt(
            chapterTitle = "Shared Chapter",
            chapterList = "CHAPTER_LIST_ONE",
            world = "WORLD_STABLE",
            targetConstraints = "TARGET_VOLUME_ONE",
        )
        val second = chapterPrompt(
            chapterTitle = "Shared Chapter",
            chapterList = "CHAPTER_LIST_TWO",
            world = "WORLD_STABLE",
            targetConstraints = "TARGET_VOLUME_TWO",
        )

        val boundary = "[Target chapter constraints"
        assertEquals(first.substringBefore(boundary), second.substringBefore(boundary))
        assertTrue(first.indexOf("DYNAMIC_KB_Shared Chapter") < first.indexOf("TARGET_VOLUME_ONE"))
    }

    @Test
    fun blueprintPromptKeepsStableProjectContextBeforeChangingMemory() {
        val prompt = Prompts.chapterBlueprintUser(
            chapterTitle = "Chapter One",
            outlineGoal = "Goal",
            conflict = "Conflict",
            prevSummary = "PREVIOUS_DYNAMIC",
            currentContent = null,
            chapterList = "CHAPTER_LIST_DYNAMIC",
            charactersInfo = "CHARACTER_STABLE",
            worldSetting = "WORLD_STABLE",
            timeline = "TIMELINE_STABLE",
            kbAugmentation = "KB_DYNAMIC",
            targetConstraints = "TARGET_DYNAMIC",
            draftReference = null,
            targetWords = 2_000,
            isContinuation = false,
            language = "en",
        )

        assertTrue(prompt.indexOf("WORLD_STABLE") < prompt.indexOf("CHAPTER_LIST_DYNAMIC"))
        assertTrue(prompt.indexOf("TIMELINE_STABLE") < prompt.indexOf("KB_DYNAMIC"))
        assertTrue(prompt.indexOf("CHARACTER_STABLE") < prompt.indexOf("PREVIOUS_DYNAMIC"))
        assertTrue(prompt.contains("[Compiled Story State v2]"))
        assertTrue(prompt.indexOf("KB_DYNAMIC") < prompt.indexOf("TARGET_DYNAMIC"))
        assertTrue(prompt.indexOf("TARGET_DYNAMIC") < prompt.indexOf("CHAPTER_LIST_DYNAMIC"))
        assertTrue(prompt.indexOf("KB_DYNAMIC") < prompt.indexOf("CHAPTER_LIST_DYNAMIC"))
        assertTrue(prompt.indexOf("KB_DYNAMIC") < prompt.indexOf("Plan the beats for this chapter"))
    }

    @Test
    fun segmentPromptsShareNormalizedPrefixUntilWrittenProseChanges() {
        val first = segmentPrompt(
            beatIndex = 1,
            currentBeat = "BEAT_ONE",
            writtenTail = "TAIL_ONE",
            world = "WORLD_A\r\nWORLD_B  ",
        )
        val second = segmentPrompt(
            beatIndex = 2,
            currentBeat = "BEAT_TWO",
            writtenTail = "TAIL_TWO",
            world = "WORLD_A\nWORLD_B",
        )

        val dynamicBoundary = "[Prose written so far"
        assertEquals(first.substringBefore(dynamicBoundary), second.substringBefore(dynamicBoundary))
        assertTrue(first.indexOf("SEGMENT_STATE") < first.indexOf("FULL_BLUEPRINT"))
        assertTrue(first.indexOf("FULL_BLUEPRINT") < first.indexOf("TAIL_ONE"))
        assertTrue(first.indexOf("TAIL_ONE") < first.indexOf("BEAT_ONE"))
    }

    @Test
    fun firstBeatWithoutTailAndLaterBeatShareEverythingThroughTheBlueprint() {
        val first = segmentPrompt(
            beatIndex = 1,
            currentBeat = "BEAT_ONE",
            writtenTail = null,
            world = "WORLD_STABLE",
        )
        val later = segmentPrompt(
            beatIndex = 2,
            currentBeat = "BEAT_TWO",
            writtenTail = "TAIL_LATER".repeat(150),
            world = "WORLD_STABLE",
        )

        val firstBoundary = first.indexOf("Chapter: Chapter One")
        val laterBoundary = later.indexOf("[Prose written so far")
        assertTrue(firstBoundary > 0)
        assertTrue(laterBoundary > 0)
        assertEquals(first.substring(0, firstBoundary), later.substring(0, laterBoundary))
    }

    private fun chapterPrompt(
        chapterTitle: String,
        chapterList: String,
        world: String,
        targetConstraints: String = "TARGET_$chapterTitle",
    ): String = Prompts.chapterUser(
        chapterTitle = chapterTitle,
        outlineGoal = "DYNAMIC_GOAL_$chapterTitle",
        conflict = "DYNAMIC_CONFLICT",
        prevSummary = "DYNAMIC_PREVIOUS",
        currentContent = null,
        chapterList = chapterList,
        charactersInfo = "CHARACTER_TOKEN",
        worldSetting = world,
        timeline = "TIMELINE_TOKEN",
        targetWords = 2_000,
        isContinuation = false,
        language = "en",
        kbAugmentation = "DYNAMIC_KB_$chapterTitle",
        targetConstraints = targetConstraints,
        draftReference = null,
    )

    private fun segmentPrompt(
        beatIndex: Int,
        currentBeat: String,
        writtenTail: String?,
        world: String,
    ): String = Prompts.chapterSegmentUser(
        chapterTitle = "Chapter One",
        blueprint = "FULL_BLUEPRINT\r\nSECOND_LINE  ",
        currentBeat = currentBeat,
        beatIndex = beatIndex,
        beatTotal = 2,
        writtenTail = writtenTail,
        charactersInfo = "CHARACTER_STABLE",
        worldSetting = world,
        timeline = "TIMELINE_STABLE",
        kbAugmentation = "SEGMENT_STATE",
        targetConstraints = "SEGMENT_TARGET",
        draftReference = "DRAFT_STABLE",
        targetWords = 1_000,
        language = "en",
    )
}
