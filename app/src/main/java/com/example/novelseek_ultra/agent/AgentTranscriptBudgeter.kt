package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep

/** Character and step limits used when replaying an agent transcript to a model. */
data class AgentTranscriptBudget(
    val maxSteps: Int = 60,
    val maxTotalChars: Int = 32_000,
    val maxCharsPerStep: Int = 8_000,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be greater than zero" }
        require(maxTotalChars > 0) { "maxTotalChars must be greater than zero" }
        require(maxCharsPerStep > 0) { "maxCharsPerStep must be greater than zero" }
    }
}

/**
 * Formats persisted [AgentStep]s for model replay without allowing one large tool result to crowd
 * out newer instructions. Limits are measured in UTF-16 characters, matching [String.length].
 *
 * Newest steps are selected first, then restored to chronological order in the returned text.
 * Truncation never cuts between the high and low surrogate of a Unicode code point.
 */
object AgentTranscriptBudgeter {
    const val CONTENT_TRUNCATION_MARKER = "…（内容已截断）"
    const val HISTORY_TRUNCATION_LABEL = "历史已截断"

    data class FormatResult(
        val text: String,
        /** True when one or more older replay entries did not fit this request. */
        val historyTruncated: Boolean,
        /** True when at least one retained entry was clipped inside its body. */
        val contentTruncated: Boolean,
    )

    fun formatDetailed(
        steps: List<AgentStep>,
        budget: AgentTranscriptBudget = AgentTranscriptBudget(),
    ): FormatResult {
        val text = format(steps, budget)
        return FormatResult(
            text = text,
            historyTruncated = text.contains("【$HISTORY_TRUNCATION_LABEL】"),
            contentTruncated = text.contains(CONTENT_TRUNCATION_MARKER),
        )
    }

    /**
     * Render request-critical steps without either per-step or aggregate clipping.
     *
     * Callers must account for this text as fixed prompt content. If it cannot fit the selected
     * model, request validation fails explicitly instead of silently discarding active-plan or
     * review-gate evidence.
     */
    fun formatRequired(steps: List<AgentStep>): String =
        steps.joinToString("\n") { step -> renderStep(step, Int.MAX_VALUE) }

    fun format(
        steps: List<AgentStep>,
        budget: AgentTranscriptBudget = AgentTranscriptBudget(),
    ): String {
        if (steps.isEmpty()) return ""

        val candidates = steps.takeLast(budget.maxSteps).map { step ->
            RenderedStep(
                step = step,
                line = renderStep(step, budget.maxCharsPerStep),
            )
        }
        val allCandidates = candidates.joinToString("\n") { it.line }
        val historyWasStepLimited = candidates.size < steps.size

        if (!historyWasStepLimited && allCandidates.length <= budget.maxTotalChars) {
            return allCandidates
        }

        // A history marker is reserved before adding steps so transcript trimming is never silent.
        val keptNewestFirst = mutableListOf<RenderedStep>()
        for (candidate in candidates.asReversed()) {
            val proposed = keptNewestFirst + candidate
            val omittedCount = steps.size - proposed.size
            val marker = historyMarker(omittedCount)
            val proposedLinesLength = proposed.sumOf { it.line.length } + (proposed.size - 1)
            val required = marker.length + 1 + proposedLinesLength
            if (required <= budget.maxTotalChars) {
                keptNewestFirst += candidate
            } else {
                break
            }
        }

        if (keptNewestFirst.isEmpty()) {
            return renderLatestWithinTotalBudget(steps.last(), steps.size - 1, budget)
        }

        val keptChronological = keptNewestFirst.asReversed()
        val omittedCount = steps.size - keptChronological.size
        return buildString {
            append(historyMarker(omittedCount))
            append('\n')
            append(keptChronological.joinToString("\n") { it.line })
        }
    }

    private fun renderLatestWithinTotalBudget(
        latest: AgentStep,
        omittedCount: Int,
        budget: AgentTranscriptBudget,
    ): String {
        val marker = historyMarker(omittedCount)
        val availableForStep = budget.maxTotalChars - marker.length - 1
        if (availableForStep <= 0) {
            return truncateWithMarker(marker, budget.maxTotalChars, CONTENT_TRUNCATION_MARKER)
        }

        val line = renderStep(latest, budget.maxCharsPerStep)
        return marker + "\n" + truncateWithMarker(
            value = line,
            maxChars = availableForStep,
            marker = CONTENT_TRUNCATION_MARKER,
        )
    }

    private fun renderStep(step: AgentStep, maxCharsPerStep: Int): String {
        val body = truncateWithMarker(
            value = step.text,
            maxChars = maxCharsPerStep,
            marker = CONTENT_TRUNCATION_MARKER,
        )
        return "【${label(step)}】$body"
    }

    private fun label(step: AgentStep): String = when (step.type) {
        AgentStep.USER -> "用户指令"
        AgentStep.THOUGHT -> "你的思考"
        AgentStep.ACTION -> buildString {
            append("你执行的动作")
            if (step.id.isNotBlank()) append("[id=${step.id}]")
            if (step.tool.isNotBlank()) append("[${step.tool}]")
            if (step.actionStatus.isNotBlank()) append("[状态=${step.actionStatus}]")
            if (step.planId.isNotBlank() || step.planStepId.isNotBlank()) {
                append("[计划=${step.planId.ifBlank { "legacy" }}/${step.planStepId.ifBlank { "?" }}]")
            }
            if (step.resolvedProjectId.isNotBlank()) append("[项目=${step.resolvedProjectId}]")
        }
        AgentStep.OBSERVATION -> resultLabel("结果", step)
        AgentStep.MESSAGE -> "你的回复"
        AgentStep.QUESTION -> resultLabel("你向用户提问", step)
        AgentStep.ANSWER -> resultLabel("用户回答", step)
        AgentStep.ERROR -> resultLabel("错误", step)
        AgentStep.PLAN -> "规划智能体"
        AgentStep.CONTEXT_SUMMARY -> "已压缩会话记忆"
        else -> step.type
    }

    private fun resultLabel(prefix: String, step: AgentStep): String = buildString {
        append(prefix)
        if (step.id.isNotBlank()) append("[id=${step.id}]")
        if (step.resultForActionId.isNotBlank()) append("[动作=${step.resultForActionId}]")
        if (step.resultKind.isNotBlank()) append("[证据=${step.resultKind}]")
    }

    private fun historyMarker(omittedCount: Int): String =
        "【$HISTORY_TRUNCATION_LABEL】已省略 ${omittedCount.coerceAtLeast(0)} 条较早记录"

    private fun truncateWithMarker(value: String, maxChars: Int, marker: String): String {
        if (value.length <= maxChars) return value
        if (maxChars <= marker.length) return safePrefix(marker, maxChars)

        val prefix = safePrefix(value, maxChars - marker.length)
        return prefix + marker
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

    private data class RenderedStep(val step: AgentStep, val line: String)
}
