package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.ai.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompressionPolicyTest {
    @Test
    fun `safe threshold is inclusive and automatic compaction is attempted at most once`() {
        val atThreshold = prepared(usageRatio = AgentContextCompressionPolicy.SAFE_USAGE_RATIO)
        assertTrue(AgentContextCompressionPolicy.shouldAttempt(atThreshold, attempts = 0))
        assertFalse(AgentContextCompressionPolicy.shouldAttempt(atThreshold, attempts = 1))
    }

    @Test
    fun `truncation requests compaction below threshold while safe request does not`() {
        assertTrue(
            AgentContextCompressionPolicy.shouldAttempt(
                prepared(usageRatio = 0.2, historyTruncated = true),
                attempts = 0,
            ),
        )
        assertFalse(
            AgentContextCompressionPolicy.shouldAttempt(
                prepared(usageRatio = 0.81),
                attempts = 0,
            ),
        )
    }

    private fun prepared(
        usageRatio: Double,
        historyTruncated: Boolean = false,
    ) = AgentTranscriptRequestBudgeter.PreparedRequest(
        messages = listOf(ChatMessage("user", "test")),
        allocation = AgentTranscriptRequestBudgeter.Allocation(
            transcriptBudget = AgentTranscriptBudget(),
            inputBudgetTokens = 100,
            reservedTokens = 10,
            availableTranscriptChars = 90,
        ),
        transcript = AgentTranscriptBudgeter.FormatResult(
            text = "",
            historyTruncated = historyTruncated,
            contentTruncated = false,
        ),
        measurement = AgentTranscriptRequestBudgeter.Measurement(
            capacityTokens = 120,
            inputBudgetTokens = 100,
            fixedTokens = 10,
            historyTokens = 0,
            outputReserveTokens = 20,
            remainingTokens = 90,
            usageRatio = usageRatio,
            exceedsInputBudget = false,
        ),
    )
}
