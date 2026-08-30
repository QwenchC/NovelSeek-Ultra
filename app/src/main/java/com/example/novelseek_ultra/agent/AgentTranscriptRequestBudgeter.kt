package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import com.example.novelseek_ultra.data.ai.TextModelRequestPolicy
import com.example.novelseek_ultra.data.model.TextModelConfig

/**
 * Fits the optional agent transcript after every fixed request component has been rendered.
 *
 * System instructions, tool documentation, the active plan, current state and current user task are
 * represented by [fixedMessages] and are never trimmed here. The returned character cap is safe for
 * [PromptRequestBudgeter]'s conservative estimator: one UTF-16 character can add at most one
 * estimated token (a supplementary code point occupies two UTF-16 characters and adds two tokens).
 */
internal object AgentTranscriptRequestBudgeter {
    const val MEMORY_BUDGET_TRUNCATION_MARKER = "…（压缩记忆已按本次模型预算节选）…"

    data class Allocation(
        val transcriptBudget: AgentTranscriptBudget,
        val inputBudgetTokens: Int,
        val reservedTokens: Int,
        val availableTranscriptChars: Int,
    )

    data class Measurement(
        val capacityTokens: Int,
        val inputBudgetTokens: Int,
        val fixedTokens: Int,
        val historyTokens: Int,
        val outputReserveTokens: Int,
        val remainingTokens: Int,
        val usageRatio: Double,
        val exceedsInputBudget: Boolean,
    )

    data class PreparedRequest(
        val messages: List<ChatMessage>,
        val allocation: Allocation,
        val transcript: AgentTranscriptBudgeter.FormatResult,
        val measurement: Measurement,
    )

    fun allocate(
        config: TextModelConfig,
        preferredBudget: AgentTranscriptBudget,
        transcriptSharePercent: Int,
        fixedMessages: List<ChatMessage>,
    ): Allocation {
        require(transcriptSharePercent in 1..100) {
            "transcriptSharePercent must be between 1 and 100"
        }
        require(fixedMessages.isNotEmpty()) { "fixedMessages must not be empty" }

        val requestConfig = TextModelRequestPolicy.normalizeForRequest(config)
        val inputBudget = PromptRequestBudgeter.inputBudgetTokens(requestConfig)
        val reserved = PromptRequestBudgeter.estimateMessages(fixedMessages)
        if (reserved >= inputBudget) {
            throw PromptRequestBudgeter.BudgetExceededException(
                "智能体的系统指令、工具文档、计划、当前状态与当前任务超过模型上下文预算：" +
                    "需要约 $reserved tokens，可用 $inputBudget tokens。请提高模型上下文上限。",
            )
        }
        val available = inputBudget - reserved
        val depthAllowance = (
            available.toLong() * transcriptSharePercent / 100
        ).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val maxTotalChars = minOf(preferredBudget.maxTotalChars, depthAllowance)

        return Allocation(
            transcriptBudget = preferredBudget.copy(
                maxTotalChars = maxTotalChars,
                maxCharsPerStep = minOf(preferredBudget.maxCharsPerStep, maxTotalChars),
            ),
            inputBudgetTokens = inputBudget,
            reservedTokens = reserved,
            availableTranscriptChars = available,
        )
    }

    /** Build and measure the exact messages that will also pass through the HTTP boundary guard. */
    fun prepare(
        config: TextModelConfig,
        preferredBudget: AgentTranscriptBudget,
        transcriptSharePercent: Int,
        fixedMessages: List<ChatMessage>,
        replaySteps: List<com.example.novelseek_ultra.data.model.AgentStep>,
        historyPrefix: String? = null,
        renderMessages: (String) -> List<ChatMessage>,
    ): PreparedRequest {
        val allocation = allocate(
            config = config,
            preferredBudget = preferredBudget,
            transcriptSharePercent = transcriptSharePercent,
            fixedMessages = fixedMessages,
        )
        val rawPrefix = historyPrefix?.trim().orEmpty()
        val hasPrefixAndTail = rawPrefix.isNotEmpty() && replaySteps.isNotEmpty()
        val separatorChars = if (hasPrefixAndTail) 2 else 0
        // A summary's newest entry is still older than every raw replay step. When both exist,
        // reserve at least half of the optional-history envelope for the full-fidelity recent tail.
        val recentTailReserve = if (hasPrefixAndTail) {
            allocation.transcriptBudget.maxTotalChars / 2
        } else {
            0
        }
        val prefixBudget = (
            allocation.transcriptBudget.maxTotalChars - separatorChars - recentTailReserve
            ).coerceAtLeast(0)
        // Memory is optional request history, not fixed safety state. A memory produced under a
        // larger model is clipped only for this request, with both its version/digest header and
        // newest evidence tail retained. The durable summary remains byte-for-byte unchanged.
        val prefix = fitMemoryPrefix(rawPrefix, prefixBudget)
        val prefixWasTruncated = prefix != rawPrefix
        val prefixChars = prefix.length + if (prefix.isNotEmpty() && replaySteps.isNotEmpty()) 2 else 0
        val remainingChars = allocation.transcriptBudget.maxTotalChars - prefixChars
        val tail = if (remainingChars <= 0) {
            AgentTranscriptBudgeter.FormatResult(
                text = "",
                historyTruncated = replaySteps.isNotEmpty(),
                contentTruncated = false,
            )
        } else {
            AgentTranscriptBudgeter.formatDetailed(
                replaySteps,
                allocation.transcriptBudget.copy(
                    maxTotalChars = remainingChars,
                    maxCharsPerStep = minOf(
                        allocation.transcriptBudget.maxCharsPerStep,
                        remainingChars,
                    ),
                ),
            )
        }
        val history = listOf(prefix, tail.text)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val transcript = tail.copy(
            text = history,
            historyTruncated = tail.historyTruncated || prefixWasTruncated,
            contentTruncated = tail.contentTruncated || prefixWasTruncated,
        )
        val messages = renderMessages(transcript.text)
        return PreparedRequest(
            messages = messages,
            allocation = allocation,
            transcript = transcript,
            measurement = measure(config, fixedMessages, messages),
        )
    }

    /** Uses exactly [PromptRequestBudgeter]'s estimator and normalized provider request limits. */
    fun measure(
        config: TextModelConfig,
        fixedMessages: List<ChatMessage>,
        messages: List<ChatMessage>,
    ): Measurement {
        val requestConfig = TextModelRequestPolicy.normalizeForRequest(config)
        val inputBudget = PromptRequestBudgeter.inputBudgetTokens(requestConfig)
        val outputReserve = PromptRequestBudgeter.maxOutputTokens(requestConfig)
        val fixed = PromptRequestBudgeter.estimateMessages(fixedMessages)
        val total = PromptRequestBudgeter.estimateMessages(messages)
        val history = (total - fixed).coerceAtLeast(0)
        val remaining = (inputBudget - total).coerceAtLeast(0)
        return Measurement(
            capacityTokens = saturatedAdd(inputBudget, outputReserve),
            inputBudgetTokens = inputBudget,
            fixedTokens = fixed,
            historyTokens = history,
            outputReserveTokens = outputReserve,
            remainingTokens = remaining,
            usageRatio = if (inputBudget > 0) total.toDouble() / inputBudget.toDouble() else 1.0,
            exceedsInputBudget = total > inputBudget,
        )
    }

    /** Repeat the same non-mutating last-mile validation before handing a request to AiService. */
    fun validate(config: TextModelConfig, messages: List<ChatMessage>) {
        val requestConfig = TextModelRequestPolicy.normalizeForRequest(config)
        PromptRequestBudgeter.validate(requestConfig, messages)
    }

    private fun saturatedAdd(left: Int, right: Int): Int =
        if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right

    private fun fitMemoryPrefix(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        if (maxChars <= 0) return ""
        if (maxChars <= MEMORY_BUDGET_TRUNCATION_MARKER.length) {
            return MEMORY_BUDGET_TRUNCATION_MARKER.take(maxChars)
        }
        val payload = maxChars - MEMORY_BUDGET_TRUNCATION_MARKER.length
        val headChars = payload / 3
        val tailChars = payload - headChars
        return safePrefix(value, headChars) +
            MEMORY_BUDGET_TRUNCATION_MARKER +
            safeSuffix(value, tailChars)
    }

    private fun safePrefix(value: String, maxChars: Int): String {
        var end = maxChars.coerceIn(0, value.length)
        if (
            end in 1 until value.length &&
            value[end - 1].isHighSurrogate() &&
            value[end].isLowSurrogate()
        ) {
            end--
        }
        return value.substring(0, end)
    }

    private fun safeSuffix(value: String, maxChars: Int): String {
        var start = (value.length - maxChars.coerceAtLeast(0)).coerceIn(0, value.length)
        if (
            start in 1 until value.length &&
            value[start - 1].isHighSurrogate() &&
            value[start].isLowSurrogate()
        ) {
            start++
        }
        return value.substring(start)
    }
}
