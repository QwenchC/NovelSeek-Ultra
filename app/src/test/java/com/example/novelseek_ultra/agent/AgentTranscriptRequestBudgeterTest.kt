package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.data.model.TextModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptRequestBudgeterTest {
    @Test
    fun `tight request space preserves low medium high ordering and final fit`() {
        val config = config(context = 12_000, output = 4_000)
        val fixed = fixedMessages(systemChars = 2_500, taskChars = 700)

        val allocations = listOf(
            AgentReasoningLevels.LOW,
            AgentReasoningLevels.MEDIUM,
            AgentReasoningLevels.HIGH,
        ).map { level ->
            val profile = AgentReasoningPolicy.forLevel(level)
            AgentTranscriptRequestBudgeter.allocate(
                config = config,
                preferredBudget = profile.executorTranscriptBudget,
                transcriptSharePercent = profile.transcriptSharePercent,
                fixedMessages = fixed,
            )
        }

        assertTrue(
            allocations[0].transcriptBudget.maxTotalChars <
                allocations[1].transcriptBudget.maxTotalChars,
        )
        assertTrue(
            allocations[1].transcriptBudget.maxTotalChars <
                allocations[2].transcriptBudget.maxTotalChars,
        )

        val high = allocations.last()
        val fittedMessages = fixed.dropLast(1) + fixed.last().copy(
            content = fixed.last().content.replace(
                EMPTY_TRANSCRIPT,
                "证".repeat(high.transcriptBudget.maxTotalChars),
            ),
        )
        assertTrue(
            PromptRequestBudgeter.estimateMessages(fittedMessages) <= high.inputBudgetTokens,
        )
    }

    @Test
    fun `larger fixed prompts and output reserves reduce transcript allowance`() {
        val profile = AgentReasoningPolicy.forLevel(AgentReasoningLevels.HIGH)
        fun allocate(context: Int, output: Int, systemChars: Int) =
            AgentTranscriptRequestBudgeter.allocate(
                config = config(context, output),
                preferredBudget = profile.executorTranscriptBudget,
                transcriptSharePercent = profile.transcriptSharePercent,
                fixedMessages = fixedMessages(systemChars, taskChars = 600),
            ).transcriptBudget.maxTotalChars

        val baseline = allocate(context = 16_000, output = 2_000, systemChars = 2_000)
        val largerSystem = allocate(context = 16_000, output = 2_000, systemChars = 5_000)
        val largerOutput = allocate(context = 16_000, output = 5_000, systemChars = 2_000)

        assertTrue(largerSystem < baseline)
        assertTrue(largerOutput < baseline)
    }

    @Test
    fun `planner and executor preferences are both capped by the same request envelope`() {
        val profile = AgentReasoningPolicy.forLevel(AgentReasoningLevels.HIGH)
        val config = config(context = 10_000, output = 3_000)
        val fixed = fixedMessages(systemChars = 2_000, taskChars = 600)

        listOf(
            profile.plannerTranscriptBudget,
            profile.executorTranscriptBudget,
        ).forEach { preferred ->
            val allocation = AgentTranscriptRequestBudgeter.allocate(
                config = config,
                preferredBudget = preferred,
                transcriptSharePercent = profile.transcriptSharePercent,
                fixedMessages = fixed,
            )

            assertTrue(
                allocation.transcriptBudget.maxTotalChars <=
                    allocation.availableTranscriptChars,
            )
            assertTrue(
                allocation.transcriptBudget.maxTotalChars <= preferred.maxTotalChars,
            )
        }
    }

    @Test
    fun `large windows retain the preferred high level ceiling`() {
        val profile = AgentReasoningPolicy.forLevel(AgentReasoningLevels.HIGH)
        val allocation = AgentTranscriptRequestBudgeter.allocate(
            config = config(context = 128_000, output = 8_000),
            preferredBudget = profile.executorTranscriptBudget,
            transcriptSharePercent = profile.transcriptSharePercent,
            fixedMessages = fixedMessages(systemChars = 1_000, taskChars = 300),
        )

        assertEquals(
            profile.executorTranscriptBudget.maxTotalChars,
            allocation.transcriptBudget.maxTotalChars,
        )
    }

    @Test
    fun `fixed required prompt over budget fails before transcript allocation`() {
        val profile = AgentReasoningPolicy.forLevel(AgentReasoningLevels.HIGH)

        assertThrows(PromptRequestBudgeter.BudgetExceededException::class.java) {
            AgentTranscriptRequestBudgeter.allocate(
                config = config(context = 4_096, output = 2_000),
                preferredBudget = profile.executorTranscriptBudget,
                transcriptSharePercent = profile.transcriptSharePercent,
                fixedMessages = fixedMessages(systemChars = 3_000, taskChars = 1_000),
            )
        }
    }

    @Test
    fun `prepared usage exactly matches final request estimator and output reserve`() {
        val config = config(context = 12_000, output = 2_000)
        val fixed = fixedMessages(systemChars = 600, taskChars = 200)
        val prepared = AgentTranscriptRequestBudgeter.prepare(
            config = config,
            preferredBudget = AgentTranscriptBudget(
                maxSteps = 20,
                maxTotalChars = 2_000,
                maxCharsPerStep = 1_000,
            ),
            transcriptSharePercent = 100,
            fixedMessages = fixed,
            replaySteps = listOf(
                AgentStep("u1", AgentStep.USER, "检查第三章"),
                AgentStep("m1", AgentStep.MESSAGE, "检查完成"),
            ),
            historyPrefix = "【本地压缩记忆】较早证据",
        ) { transcript ->
            fixed.dropLast(1) + fixed.last().copy(
                content = fixed.last().content.replace(EMPTY_TRANSCRIPT, transcript),
            )
        }

        val total = PromptRequestBudgeter.estimateMessages(prepared.messages)
        assertEquals(total, prepared.measurement.fixedTokens + prepared.measurement.historyTokens)
        assertEquals(
            prepared.measurement.inputBudgetTokens + prepared.measurement.outputReserveTokens,
            prepared.measurement.capacityTokens,
        )
        assertEquals(
            prepared.measurement.inputBudgetTokens - total,
            prepared.measurement.remainingTokens,
        )
        assertTrue(prepared.transcript.text.contains("【本地压缩记忆】较早证据"))
        assertTrue(prepared.transcript.text.contains("检查完成"))
        AgentTranscriptRequestBudgeter.validate(config, prepared.messages)
    }

    @Test
    fun `low level request clips oversized durable memory locally and keeps head and newest tail`() {
        val profile = AgentReasoningPolicy.forLevel(AgentReasoningLevels.LOW)
        val fixed = fixedMessages(systemChars = 400, taskChars = 100)
        val prefix = "MEMORY-HEADER-DIGEST-" + "旧".repeat(20_000) + "-LATEST-DENIAL-EVIDENCE"
        val prepared = AgentTranscriptRequestBudgeter.prepare(
            config = config(context = 8_000, output = 2_000),
            preferredBudget = profile.executorTranscriptBudget,
            transcriptSharePercent = profile.transcriptSharePercent,
            fixedMessages = fixed,
            replaySteps = listOf(AgentStep("tail", AgentStep.MESSAGE, "普通尾部")),
            historyPrefix = prefix,
        ) { transcript ->
            fixed.dropLast(1) + fixed.last().copy(
                content = fixed.last().content.replace(EMPTY_TRANSCRIPT, transcript),
            )
        }

        assertTrue(prepared.transcript.historyTruncated)
        assertTrue(prepared.transcript.contentTruncated)
        assertTrue(
            prepared.transcript.text.contains(
                AgentTranscriptRequestBudgeter.MEMORY_BUDGET_TRUNCATION_MARKER,
            ),
        )
        assertTrue(prepared.transcript.text.startsWith("MEMORY-HEADER"))
        assertTrue(prepared.transcript.text.contains("LATEST-DENIAL-EVIDENCE"))
        assertTrue(prepared.transcript.text.contains("普通尾部"))
        assertTrue(prepared.transcript.text.length <= prepared.allocation.transcriptBudget.maxTotalChars)
        AgentTranscriptRequestBudgeter.validate(config(context = 8_000, output = 2_000), prepared.messages)
    }

    private fun fixedMessages(systemChars: Int, taskChars: Int): List<ChatMessage> = listOf(
        ChatMessage("system", "系".repeat(systemChars)),
        ChatMessage("user", "任".repeat(taskChars) + EMPTY_TRANSCRIPT),
    )

    private fun config(context: Int, output: Int) = TextModelConfig(
        provider = "openai-compatible",
        contextWindowTokens = context,
        maxOutputTokens = output,
    )

    private companion object {
        const val EMPTY_TRANSCRIPT = "<TRANSCRIPT>"
    }
}
