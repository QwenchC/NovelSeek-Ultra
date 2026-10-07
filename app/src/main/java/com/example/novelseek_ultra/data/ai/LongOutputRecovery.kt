package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** The syntax that a continuation must finish without restarting the previous response. */
internal enum class LongOutputFormat {
    MARKDOWN,
    JSON_OBJECT,
}

/**
 * Collects a long response atomically and resumes only the machine-classified output-limit case.
 *
 * Callers receive a replace-style cumulative preview. They must not append that preview to their
 * own buffer; doing so would duplicate all earlier chunks when a continuation request starts.
 */
internal object LongOutputRecovery {
    const val DEFAULT_MAX_REQUESTS = 4

    class ExhaustedException(message: String) : IllegalStateException(message)

    suspend fun collect(
        config: TextModelConfig,
        initialMessages: List<ChatMessage>,
        format: LongOutputFormat,
        taskLabel: String,
        language: String,
        maxRequests: Int = DEFAULT_MAX_REQUESTS,
        request: (List<ChatMessage>) -> Flow<AiService.StreamEvent>,
        onCumulative: suspend (String) -> Unit = {},
    ): String {
        require(maxRequests > 0) { "maxRequests must be positive" }
        val requestConfig = TextModelRequestPolicy.normalizeForRequest(config)
        var accumulated = ""
        var messages = PromptRequestBudgeter.validate(requestConfig, initialMessages).messages

        repeat(maxRequests) { requestIndex ->
            currentCoroutineContext().ensureActive()
            val segment = StringBuilder()
            var lastPreviewLength = -1
            try {
                request(messages).collectCompleted(
                    onDelta = { delta ->
                        segment.append(delta)
                        // Rebuilding and overlap-scanning the complete text for every tiny token
                        // becomes quadratic on phone CPUs. Publish the first delta immediately,
                        // then bounded character batches; the completed segment is always
                        // published below.
                        if (
                            lastPreviewLength < 0 ||
                            segment.length - lastPreviewLength >= PREVIEW_UPDATE_INTERVAL_CHARS
                        ) {
                            onCumulative(mergeOverlap(accumulated, segment.toString()))
                            lastPreviewLength = segment.length
                        }
                    },
                )
                accumulated = mergeOverlap(accumulated, segment.toString())
                if (accumulated.isBlank()) {
                    throw ExhaustedException("$taskLabel 未返回任何内容，已取消保存；请检查模型配置后重试。")
                }
                onCumulative(accumulated)
                return accumulated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AiStreamCompletionException) {
                if (failure.kind != AiService.StreamFailureKind.OUTPUT_LIMIT) throw failure

                val beforeLength = accumulated.length
                accumulated = mergeOverlap(accumulated, segment.toString())
                if (accumulated.length <= beforeLength) {
                    throw ExhaustedException(
                        "$taskLabel 达到模型输出上限后没有产生可续写的新内容，已取消保存；" +
                            "请提高设置中的“最大输出 Tokens”或缩小生成范围。",
                    )
                }
                onCumulative(accumulated)
                if (requestIndex == maxRequests - 1) {
                    throw ExhaustedException(
                        "$taskLabel 连续 $maxRequests 段都达到模型输出上限，结果仍不完整，已取消保存；" +
                            "请提高设置中的“最大输出 Tokens”或缩小生成范围后重试。",
                    )
                }
                messages = continuationMessages(
                    config = requestConfig,
                    initialMessages = initialMessages,
                    accumulated = accumulated,
                    format = format,
                    language = language,
                )
            }
        }
        error("unreachable")
    }

    /** Merge an exact repeated prefix emitted by a provider when it resumes from a cutoff. */
    fun mergeOverlap(previous: String, continuation: String): String {
        if (previous.isEmpty()) return continuation
        if (continuation.isEmpty()) return previous
        val max = minOf(previous.length, continuation.length, MAX_OVERLAP_CHARS)
        val pattern = continuation.substring(0, max)
        val prefix = IntArray(pattern.length)
        for (index in 1 until pattern.length) {
            var matched = prefix[index - 1]
            while (matched > 0 && pattern[index] != pattern[matched]) {
                matched = prefix[matched - 1]
            }
            if (pattern[index] == pattern[matched]) matched++
            prefix[index] = matched
        }

        var matched = 0
        val tailStart = (previous.length - max).coerceAtLeast(0)
        for (index in tailStart until previous.length) {
            val char = previous[index]
            while (matched > 0 && char != pattern[matched]) matched = prefix[matched - 1]
            if (char == pattern[matched]) matched++
            if (matched == pattern.length && index != previous.lastIndex) {
                matched = prefix[matched - 1]
            }
        }
        val overlap = matched.takeIf { it >= MIN_OVERLAP_CHARS } ?: 0
        return previous + continuation.substring(overlap)
    }

    internal fun continuationMessages(
        config: TextModelConfig,
        initialMessages: List<ChatMessage>,
        accumulated: String,
        format: LongOutputFormat,
        language: String,
    ): List<ChatMessage> {
        val instruction = continuationInstruction(format, language)
        val base = initialMessages
        val emptyContext = base + ChatMessage("assistant", "") + ChatMessage("user", instruction)
        if (
            PromptRequestBudgeter.estimateMessages(emptyContext) <=
            PromptRequestBudgeter.inputBudgetTokens(config)
        ) {
            val tail = largestFittingSuffix(config, base, accumulated, instruction)
            val candidate = base + ChatMessage("assistant", tail) + ChatMessage("user", instruction)
            if (
                tail == accumulated ||
                PromptRequestBudgeter.estimateText(tail) >= MIN_CONTINUATION_TAIL_TOKENS
            ) {
                return PromptRequestBudgeter.validate(config, candidate).messages
            }
        }

        throw PromptRequestBudgeter.BudgetExceededException(
            "续写请求无法保留足够的截断点上下文，继续生成可能重复或错接，已取消保存；" +
                "请提高模型上下文上限、降低最大输出 Tokens 或缩小生成范围。",
        )
    }

    private fun largestFittingSuffix(
        config: TextModelConfig,
        base: List<ChatMessage>,
        accumulated: String,
        instruction: String,
    ): String {
        val budget = PromptRequestBudgeter.inputBudgetTokens(config)
        var low = 0
        var high = accumulated.length
        while (low < high) {
            val length = (low + high + 1) ushr 1
            val start = safeSuffixBoundary(accumulated, accumulated.length - length)
            val tail = accumulated.substring(start)
            val candidate = base + ChatMessage("assistant", tail) + ChatMessage("user", instruction)
            if (PromptRequestBudgeter.estimateMessages(candidate) <= budget) {
                low = length
            } else {
                high = length - 1
            }
        }
        return accumulated.substring(safeSuffixBoundary(accumulated, accumulated.length - low))
    }

    private fun safeSuffixBoundary(value: String, index: Int): Int = when {
        index in 1 until value.length && Character.isLowSurrogate(value[index]) &&
            Character.isHighSurrogate(value[index - 1]) -> index + 1
        else -> index.coerceIn(0, value.length)
    }

    private fun continuationInstruction(format: LongOutputFormat, language: String): String {
        val english = language == "en"
        return when {
            format == LongOutputFormat.JSON_OBJECT && english ->
                "The previous assistant response hit the output limit. Continue from the exact next " +
                    "character until the SAME JSON object is complete. Output continuation bytes only: " +
                    "no restart, Markdown, code fence, explanation, or apology. The preceding assistant " +
                    "message may contain only the retained tail of the truncated response."
            format == LongOutputFormat.JSON_OBJECT ->
                "上一条 assistant 输出达到长度上限。请从截断处的下一个字符继续，直到完成同一个 JSON 对象。" +
                    "只输出续接字符：不要重头输出，不要 Markdown、代码块、解释或道歉。上一条 assistant 消息可能只保留了截断输出的末尾。"
            english ->
                "The previous assistant response hit the output limit. Continue from the exact next " +
                    "character and finish the same document. Output new continuation text only: no " +
                    "restart, recap, greeting, explanation, or apology. The preceding assistant message " +
                    "may contain only the retained tail of the truncated response."
            else ->
                "上一条 assistant 输出达到长度上限。请从截断处的下一个字符继续并完成同一份文档。" +
                    "只输出新的续写内容：不要重头输出、回顾、寒暄、解释或道歉。上一条 assistant 消息可能只保留了截断输出的末尾。"
        }
    }

    private const val MIN_CONTINUATION_TAIL_TOKENS = 128
    private const val MIN_OVERLAP_CHARS = 12
    private const val MAX_OVERLAP_CHARS = 32_768
    private const val PREVIEW_UPDATE_INTERVAL_CHARS = 96
}
