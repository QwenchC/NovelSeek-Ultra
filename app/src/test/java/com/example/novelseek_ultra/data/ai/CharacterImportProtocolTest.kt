package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.TextModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterImportProtocolTest {
    @Test
    fun parsesOnlyCompleteWrapperObject() {
        val parsed = CharacterImportProtocol.parseCompleteJson(
            """{"characters":[{"name":"林澈","gender":"男","isProtagonist":true,"role":"主角","personality":"冷静而克制","motivation":"寻找失踪的家人","background":"边城医馆学徒","appearance":"黑发灰衣"}]}""",
        )

        assertEquals(1, parsed.size)
        assertEquals("林澈", parsed.single().name)
        assertTrue(parsed.single().isProtagonist)
    }

    @Test
    fun rejectsProseFenceLooseArrayAndPipeFallback() {
        val validObject = """{"characters":[]}"""
        val invalid = listOf(
            "说明：$validObject",
            "```json\n$validObject\n```",
            "[]",
            "林澈 | 男 | 主角 | 冷静",
        )

        invalid.forEach { text ->
            assertTrue(
                "Expected strict rejection for $text",
                runCatching { CharacterImportProtocol.parseCompleteJson(text) }.isFailure,
            )
        }
    }

    @Test
    fun splitBatchesStayWithinBudgetAndCarryUnicodeSafeTailOverlap() {
        val outline = (1..24).joinToString("\n") { index ->
            "## 角色$index 😀\n身份：第${index}位守门人；动机：保护城池与家人。"
        }
        val tokenBudget = 48
        val batches = CharacterImportProtocol.splitOutline(outline, tokenBudget)

        assertTrue(batches.size > 1)
        assertTrue(batches.all { PromptRequestBudgeter.estimateText(it) <= tokenBudget })
        batches.zipWithNext().forEach { (previous, next) ->
            assertTrue(longestSuffixPrefix(previous, next) > 0)
        }
        assertTrue(batches.none(::hasUnpairedSurrogate))
    }

    @Test
    fun derivedBatchBudgetKeepsEveryRenderedRequestValid() {
        val config = TextModelConfig(
            provider = "custom",
            contextWindowTokens = 4_096,
            maxOutputTokens = 1_024,
        )
        val prefix = listOf(
            ChatMessage("system", Prompts.charsFromOutlineSystem("zh")),
            ChatMessage("system", Prompts.charsFromOutlineContext("练气、筑基、金丹", "zh")),
        )
        val fixed = prefix + ChatMessage(
            "user",
            Prompts.charsFromOutlineUser("", "zh", 9_999, 9_999),
        )
        val budget = CharacterImportProtocol.outlineBatchTokenBudget(config, fixed)
        val outline = (1..120).joinToString("\n") { "角色$it：身份、关系、动机与过往。" }
        val batches = CharacterImportProtocol.splitOutline(outline, budget)

        batches.forEachIndexed { index, batch ->
            val messages = prefix + ChatMessage(
                "user",
                Prompts.charsFromOutlineUser(batch, "zh", index + 1, batches.size),
            )
            PromptRequestBudgeter.validate(config, messages)
        }
    }

    @Test
    fun duplicateNamesMergeDeterministicallyInFirstSeenOrder() {
        fun character(name: String, role: String, personality: String, protagonist: Boolean = false) =
            Character(id = "", name = name, role = role, personality = personality, isProtagonist = protagonist)

        val merged = CharacterImportProtocol.mergeByName(
            batches = listOf(
                listOf(character("Alice", "ally", "calm"), character("Bob", "mentor", "strict")),
                listOf(character(" alice ", "trusted ally", "calm and observant", protagonist = true)),
            ),
            idPrefix = "import-fixed",
        )

        assertEquals(listOf("Alice", "Bob"), merged.map { it.name })
        assertEquals(listOf("import-fixed-0", "import-fixed-1"), merged.map { it.id })
        assertEquals("trusted ally", merged.first().role)
        assertEquals("calm and observant", merged.first().personality)
        assertTrue(merged.first().isProtagonist)
    }

    private fun longestSuffixPrefix(left: String, right: String): Int {
        for (length in minOf(left.length, right.length) downTo 1) {
            if (left.endsWith(right.substring(0, length))) return length
        }
        return 0
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                java.lang.Character.isHighSurrogate(char) -> {
                    if (index + 1 >= value.length ||
                        !java.lang.Character.isLowSurrogate(value[index + 1])
                    ) return true
                    index += 2
                }
                java.lang.Character.isLowSurrogate(char) -> return true
                else -> index++
            }
        }
        return false
    }
}
