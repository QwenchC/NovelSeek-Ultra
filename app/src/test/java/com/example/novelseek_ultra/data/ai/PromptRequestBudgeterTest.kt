package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRequestBudgeterTest {
    private val smallConfig = TextModelConfig(
        contextWindowTokens = 4_096,
        maxOutputTokens = 512,
    )

    @Test
    fun `structured allocation uses fixed semantic shares and fits optional blocks`() {
        val huge = "世界规则".repeat(2_000)
        val first = allocation(huge, target = "副本一约束".repeat(500))
        val second = allocation(huge, target = "副本二约束".repeat(20))

        assertEquals(first.values.getValue("world"), second.values.getValue("world"))
        assertTrue(first.truncatedSectionIds.contains("world"))
        assertTrue(
            PromptRequestBudgeter.estimateText(first.values.getValue("world").orEmpty()) <
                PromptRequestBudgeter.estimateText(huge),
        )
        assertEquals("作者草稿不可裁剪", first.values.getValue("draft"))
    }

    @Test
    fun `an absent dynamic section cannot change an earlier stable allocation`() {
        val hugeWorld = "稳定世界规则".repeat(2_000)
        val withTarget = allocation(hugeWorld, target = "动态目标".repeat(500))
        val withoutTarget = allocation(hugeWorld, target = "")

        assertEquals(
            withTarget.values.getValue("world"),
            withoutTarget.values.getValue("world"),
        )
        assertEquals(null, withoutTarget.values.getValue("target"))
    }

    @Test
    fun `ordinary task length changes are absorbed without moving the world prefix`() {
        val hugeWorld = "稳定世界规则".repeat(2_000)
        val shortTask = allocation(hugeWorld, target = "目标", requiredTask = "写本章")
        val longerTask = allocation(
            hugeWorld,
            target = "目标",
            requiredTask = "本章标题目标冲突与节拍要求".repeat(30),
        )

        assertEquals(
            shortTask.values.getValue("world"),
            longerTask.values.getValue("world"),
        )
    }

    @Test
    fun `first beat without prose and later beat with full tail keep early cache allocations`() {
        val sections = listOf(
            PromptRequestBudgeter.Section("world", "稳定世界".repeat(2_000), weight = 25),
            PromptRequestBudgeter.Section("timeline", "稳定时间线".repeat(2_000), weight = 10),
            PromptRequestBudgeter.Section("characters", "角色资料".repeat(2_000), weight = 15),
            PromptRequestBudgeter.Section("story", "故事状态".repeat(2_000), weight = 35),
            PromptRequestBudgeter.Section("target", "当前约束".repeat(2_000), weight = 7),
            PromptRequestBudgeter.Section("chapter_list", null, weight = 8),
            PromptRequestBudgeter.Section("draft", "作者草稿", required = true),
        )
        fun allocate(writtenTail: String?) = PromptRequestBudgeter.allocateSections(
            config = smallConfig.copy(maxOutputTokens = 1_024),
            systemPrompt = "分段写作规则",
            requiredTaskText = listOfNotNull(
                "完整蓝图",
                "当前节拍",
                writtenTail,
                "beat=1/7",
            ).joinToString("\n"),
            sections = sections,
        )

        val first = allocate(null)
        val later = allocate("已写正文".repeat(375))

        assertEquals(first.values.getValue("world"), later.values.getValue("world"))
        assertEquals(first.values.getValue("timeline"), later.values.getValue("timeline"))
    }

    @Test
    fun `required prose that cannot fit fails instead of being truncated`() {
        val error = assertThrows(PromptRequestBudgeter.BudgetExceededException::class.java) {
            PromptRequestBudgeter.allocateSections(
                config = smallConfig,
                systemPrompt = "系统规则",
                requiredTaskText = "整章原文".repeat(10_000),
                sections = emptyList(),
            )
        }

        assertTrue(error.message.orEmpty().contains("必需内容"))
    }

    @Test
    fun `final guard rejects an oversized complete request`() {
        assertThrows(PromptRequestBudgeter.BudgetExceededException::class.java) {
            PromptRequestBudgeter.validate(
                smallConfig,
                listOf(ChatMessage("user", "超长请求".repeat(10_000))),
            )
        }
    }

    @Test
    fun `fallback clipping is deterministic keeps task suffix and never splits emoji`() {
        val task = "最终任务：继续写完本章🙂"
        val messages = listOf(
            ChatMessage("system", "规则"),
            ChatMessage("user", "稳定前缀🙂" + "中间资料".repeat(10_000) + task),
        )

        val first = PromptRequestBudgeter.prepare(smallConfig, messages)
        val second = PromptRequestBudgeter.prepare(smallConfig, messages)
        val content = first.messages.last().content

        assertEquals(first, second)
        assertTrue(first.estimatedInputTokens <= first.inputBudgetTokens)
        assertTrue(content.startsWith("稳定前缀🙂"))
        assertTrue(content.endsWith(task))
        assertFalse(content.anyIndexed { index, ch ->
            Character.isHighSurrogate(ch) &&
                (index == content.lastIndex || !Character.isLowSurrogate(content[index + 1]))
        })
    }

    @Test
    fun `configured output is clamped so a minimum input budget always remains`() {
        val config = TextModelConfig(contextWindowTokens = 4_096, maxOutputTokens = 99_999)

        assertEquals(1_536, PromptRequestBudgeter.maxOutputTokens(config))
        assertEquals(2_048, PromptRequestBudgeter.inputBudgetTokens(config))
    }

    private fun allocation(
        world: String,
        target: String,
        requiredTask: String = "写当前章节",
    ) =
        PromptRequestBudgeter.allocateSections(
            config = smallConfig,
            systemPrompt = "系统规则",
            requiredTaskText = requiredTask,
            sections = listOf(
                PromptRequestBudgeter.Section("world", world, weight = 60),
                PromptRequestBudgeter.Section("story", "故事状态".repeat(1_000), weight = 30),
                PromptRequestBudgeter.Section("target", target, weight = 10),
                PromptRequestBudgeter.Section("draft", "作者草稿不可裁剪", required = true),
            ),
        )

    private inline fun String.anyIndexed(predicate: (Int, Char) -> Boolean): Boolean {
        forEachIndexed { index, char -> if (predicate(index, char)) return true }
        return false
    }
}
