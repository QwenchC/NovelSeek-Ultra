package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig

/**
 * Deterministic last-mile request budget guard for OpenAI-compatible chat payloads.
 *
 * Prompt builders put slow-changing/cacheable context first and the immediate task last. Semantic
 * sections receive fixed shares before rendering; the final guard never mutates an already-rendered
 * request. [prepare] remains only as a deterministic fallback for non-authoritative utility calls.
 * The estimator is deliberately conservative and does not pretend to be a provider's exact tokenizer.
 */
internal object PromptRequestBudgeter {
    const val CONTRACT = "request_budget.v1"

    data class Result(
        val messages: List<ChatMessage>,
        val estimatedInputTokens: Int,
        val inputBudgetTokens: Int,
        val maxOutputTokens: Int,
        val truncatedMessageIndexes: List<Int>,
    )

    enum class TrimPolicy { HEAD, TAIL, MIDDLE }

    data class Section(
        val id: String,
        val content: String?,
        val weight: Int = 1,
        val required: Boolean = false,
        val trimPolicy: TrimPolicy = TrimPolicy.HEAD,
    )

    data class SectionAllocation(
        val values: Map<String, String?>,
        val inputBudgetTokens: Int,
        val reservedTokens: Int,
        val truncatedSectionIds: List<String>,
    )

    class BudgetExceededException(message: String) : IllegalArgumentException(message)

    /**
     * Allocate the request's optional semantic blocks before the final prompt string is rendered.
     * Required material is never silently truncated; if it cannot fit, no HTTP request should run.
     */
    fun allocateSections(
        config: TextModelConfig,
        systemPrompt: String,
        requiredTaskText: String,
        sections: List<Section>,
    ): SectionAllocation {
        require(sections.map { it.id }.all { it.isNotBlank() }) { "Prompt section id is blank" }
        require(sections.map { it.id }.distinct().size == sections.size) {
            "Prompt section ids are not unique"
        }
        require(sections.all { it.weight > 0 }) { "Prompt section weight must be positive" }

        val normalized = sections.map { it.copy(content = it.content?.let(::normalize)) }
        val inputBudget = inputBudgetTokens(config)
        val baseCost = estimateText(normalize(systemPrompt)) +
            estimateText(normalize(requiredTaskText)) + STRUCTURED_ENVELOPE_TOKENS
        val requiredCost = normalized.filter { it.required }
            .sumOf { estimateText(it.content.orEmpty()) }
        if (baseCost + requiredCost > inputBudget) {
            throw BudgetExceededException(
                "请求中的必需内容超过模型上下文预算：需要约 ${baseCost + requiredCost} tokens，" +
                    "可用 $inputBudget tokens。请提高模型上下文上限，或改用分段生成/修订。",
            )
        }

        val optionalSections = normalized.filter { !it.required }
        val optional = optionalSections.filter { !it.content.isNullOrBlank() }
        val actualOptionalBudget = inputBudget - baseCost - requiredCost
        val values = linkedMapOf<String, String?>()
        normalized.filter { it.required }.forEach { values[it.id] = it.content }
        val allocations = mutableMapOf<String, Int>()
        // Include empty optional sections in the denominator and never redistribute their share.
        // A dynamic block appearing/disappearing must not alter an earlier stable block's bytes.
        val totalWeight = optionalSections.sumOf { it.weight }.coerceAtLeast(1)
        val fixedOptionalPool = inputBudget * OPTIONAL_POOL_PERCENT / 100
        optionalSections.forEach { section ->
            allocations[section.id] =
                (fixedOptionalPool.toLong() * section.weight / totalWeight).toInt()
        }
        // Ordinary task-length changes are absorbed by the fixed required slot. If required prose
        // grows beyond that slot, reclaim optional capacity from the dynamic tail first so the
        // cacheable world/timeline prefix remains byte-stable for as long as possible.
        var overflow = (allocations.values.sum() - actualOptionalBudget).coerceAtLeast(0)
        optionalSections.asReversed().forEach { section ->
            if (overflow <= 0) return@forEach
            val current = allocations.getValue(section.id)
            val reduction = minOf(current, overflow)
            allocations[section.id] = current - reduction
            overflow -= reduction
        }

        val truncated = mutableListOf<String>()
        optional.forEach { section ->
            val content = section.content.orEmpty()
            val allowance = allocations.getValue(section.id)
            val fitted = fitToTokens(content, allowance, section.trimPolicy)
            if (fitted != content) truncated += section.id
            values[section.id] = fitted.takeIf { it.isNotBlank() }
        }
        normalized.filter { it.id !in values }.forEach { values[it.id] = null }
        return SectionAllocation(
            values = values,
            inputBudgetTokens = inputBudget,
            reservedTokens = baseCost + requiredCost,
            truncatedSectionIds = truncated,
        )
    }

    /** Final non-mutating request guard. Over-budget requests fail before any network call. */
    fun validate(config: TextModelConfig, messages: List<ChatMessage>): Result {
        val normalized = messages.map { it.copy(content = normalize(it.content)) }
        val estimated = estimateMessages(normalized)
        val budget = inputBudgetTokens(config)
        if (estimated > budget) {
            throw BudgetExceededException(
                "完整提示词超过模型上下文预算：需要约 $estimated tokens，可用 $budget tokens。" +
                    "请提高模型上下文上限或减少必需输入。",
            )
        }
        return Result(
            messages = normalized,
            estimatedInputTokens = estimated,
            inputBudgetTokens = budget,
            maxOutputTokens = maxOutputTokens(config),
            truncatedMessageIndexes = emptyList(),
        )
    }

    fun prepare(config: TextModelConfig, messages: List<ChatMessage>): Result {
        val normalized = messages.map { message ->
            message.copy(content = normalize(message.content))
        }.toMutableList()
        val inputBudget = inputBudgetTokens(config)
        val outputBudget = maxOutputTokens(config)
        val truncated = linkedSetOf<Int>()

        while (estimateMessages(normalized) > inputBudget) {
            val over = estimateMessages(normalized) - inputBudget
            val candidate = normalized.indices
                .map { index ->
                    val contentTokens = estimateText(normalized[index].content)
                    val minimum = minimumContentTokens(normalized, index)
                    Triple(index, contentTokens, (contentTokens - minimum).coerceAtLeast(0))
                }
                .filter { it.third > 0 }
                .maxWithOrNull(compareBy<Triple<Int, Int, Int>>({ it.third }, { -it.first }))
                ?: break
            val index = candidate.first
            val target = (candidate.second - over - CLIP_MARGIN_TOKENS)
                .coerceAtLeast(minimumContentTokens(normalized, index))
            val clipped = clipMiddle(normalized[index].content, target, normalized[index].role)
            if (clipped == normalized[index].content) break
            normalized[index] = normalized[index].copy(content = clipped)
            truncated += index
        }

        // A very small custom context window can cross the preferred minima. Keep enforcing the
        // hard upper bound while retaining both ends of the largest remaining message.
        while (estimateMessages(normalized) > inputBudget && normalized.isNotEmpty()) {
            val index = normalized.indices.maxByOrNull { estimateText(normalized[it].content) } ?: break
            val current = estimateText(normalized[index].content)
            val target = (current - (estimateMessages(normalized) - inputBudget) - CLIP_MARGIN_TOKENS)
                .coerceAtLeast(16)
            val clipped = clipMiddle(normalized[index].content, target, normalized[index].role)
            if (clipped == normalized[index].content) break
            normalized[index] = normalized[index].copy(content = clipped)
            truncated += index
        }

        return Result(
            messages = normalized,
            estimatedInputTokens = estimateMessages(normalized),
            inputBudgetTokens = inputBudget,
            maxOutputTokens = outputBudget,
            truncatedMessageIndexes = truncated.toList(),
        )
    }

    fun estimateMessages(messages: List<ChatMessage>): Int = REQUEST_OVERHEAD_TOKENS +
        messages.sumOf { MESSAGE_OVERHEAD_TOKENS + estimateText(it.role) + estimateText(it.content) }

    fun estimateText(value: String): Int {
        var units = 0L
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            units += when {
                codePoint <= 0x7f && Character.isLetterOrDigit(codePoint) -> 2L
                codePoint <= 0x7f && Character.isWhitespace(codePoint) -> 2L
                codePoint <= 0x7f -> 3L
                codePoint > 0xffff -> 12L
                else -> 6L
            }
            index += Character.charCount(codePoint)
        }
        return ((units + TOKEN_UNITS - 1) / TOKEN_UNITS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun inputBudgetTokens(config: TextModelConfig): Int {
        val context = config.contextWindowTokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)
        return context - maxOutputTokens(config) - SAFETY_TOKENS
    }

    fun maxOutputTokens(config: TextModelConfig): Int {
        val context = config.contextWindowTokens.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)
        val upper = (context - MIN_INPUT_TOKENS - SAFETY_TOKENS).coerceAtLeast(MIN_OUTPUT_TOKENS)
        return config.maxOutputTokens.coerceIn(MIN_OUTPUT_TOKENS, upper)
    }

    private fun minimumContentTokens(messages: List<ChatMessage>, index: Int): Int = when {
        index == messages.lastIndex -> LAST_MESSAGE_MIN_TOKENS
        messages[index].role == "system" -> SYSTEM_MIN_TOKENS
        else -> OTHER_MESSAGE_MIN_TOKENS
    }

    private fun clipMiddle(value: String, tokenBudget: Int, role: String): String {
        if (estimateText(value) <= tokenBudget) return value
        if (tokenBudget <= estimateText(TRUNCATION_MARK) + 4) return takePrefix(value, tokenBudget)
        val available = tokenBudget - estimateText(TRUNCATION_MARK)
        val headRatio = when (role) {
            "system" -> 0.90
            "assistant" -> 0.60
            else -> 0.80
        }
        val headBudget = (available * headRatio).toInt().coerceAtLeast(1)
        val tailBudget = (available - headBudget).coerceAtLeast(1)
        val head = takePrefix(value, headBudget).trimEnd()
        val tail = takeSuffix(value, tailBudget).trimStart()
        return "$head$TRUNCATION_MARK$tail"
    }

    private fun fitToTokens(value: String, tokenBudget: Int, policy: TrimPolicy): String {
        if (tokenBudget <= 0) return ""
        if (estimateText(value) <= tokenBudget) return value
        val markerCost = estimateText(TRUNCATION_MARK)
        if (tokenBudget <= markerCost + 4) return takePrefix(value, tokenBudget)
        return when (policy) {
            TrimPolicy.HEAD -> takePrefix(value, tokenBudget - markerCost).trimEnd() + TRUNCATION_MARK.trimEnd()
            TrimPolicy.TAIL -> TRUNCATION_MARK.trimStart() + takeSuffix(value, tokenBudget - markerCost).trimStart()
            TrimPolicy.MIDDLE -> clipMiddle(value, tokenBudget, "user")
        }
    }

    private fun takePrefix(value: String, tokenBudget: Int): String {
        var low = 0
        var high = value.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            val safeMid = safePrefixBoundary(value, mid)
            if (estimateText(value.substring(0, safeMid)) <= tokenBudget) low = mid else high = mid - 1
        }
        return value.substring(0, safePrefixBoundary(value, low))
    }

    private fun takeSuffix(value: String, tokenBudget: Int): String {
        var low = 0
        var high = value.length
        while (low < high) {
            val length = (low + high + 1) ushr 1
            val start = safeSuffixBoundary(value, value.length - length)
            if (estimateText(value.substring(start)) <= tokenBudget) low = length else high = length - 1
        }
        return value.substring(safeSuffixBoundary(value, value.length - low))
    }

    private fun safePrefixBoundary(value: String, index: Int): Int = when {
        index in 1 until value.length && Character.isHighSurrogate(value[index - 1]) &&
            Character.isLowSurrogate(value[index]) -> index - 1
        else -> index.coerceIn(0, value.length)
    }

    private fun safeSuffixBoundary(value: String, index: Int): Int = when {
        index in 1 until value.length && Character.isLowSurrogate(value[index]) &&
            Character.isHighSurrogate(value[index - 1]) -> index + 1
        else -> index.coerceIn(0, value.length)
    }

    private fun normalize(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trimEnd()

    private const val TOKEN_UNITS = 6L
    private const val MIN_CONTEXT_TOKENS = 4_096
    private const val MAX_CONTEXT_TOKENS = 1_000_000
    private const val MIN_INPUT_TOKENS = 2_048
    private const val MIN_OUTPUT_TOKENS = 256
    private const val SAFETY_TOKENS = 512
    private const val REQUEST_OVERHEAD_TOKENS = 4
    private const val MESSAGE_OVERHEAD_TOKENS = 6
    private const val SYSTEM_MIN_TOKENS = 256
    private const val LAST_MESSAGE_MIN_TOKENS = 768
    private const val OTHER_MESSAGE_MIN_TOKENS = 128
    private const val CLIP_MARGIN_TOKENS = 8
    private const val STRUCTURED_ENVELOPE_TOKENS = 384
    private const val OPTIONAL_POOL_PERCENT = 70
    private const val TRUNCATION_MARK = "\n… [request context truncated by $CONTRACT] …\n"
}
