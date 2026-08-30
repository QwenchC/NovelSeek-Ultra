package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentSessionMemory
import com.example.novelseek_ultra.data.model.AgentStep
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Deterministic, model-free compaction for the prompt replay layer.
 *
 * The durable [AgentStep] list is never mutated. A compacted prefix is represented by a bounded
 * prompt summary and the full tail remains verbatim, so recovery and evidence checks keep using the
 * original audit chain. Only a continuous terminal prefix can advance the boundary.
 */
internal object AgentContextCompressor {
    const val SUMMARY_VERSION = 1
    const val MAX_SUMMARY_CHARS = 12_000
    const val MIN_REPLAY_TAIL_STEPS = 16

    fun normalizeMemory(
        steps: List<AgentStep>,
        memory: AgentSessionMemory,
        sensitiveTools: Set<String> = emptySet(),
    ): AgentSessionMemory {
        val sanitized = memory.sanitized()
        if (!hasUniqueStepIds(steps)) return AgentSessionMemory()
        val boundary = boundaryIndex(steps, sanitized)
        val prefix = if (boundary >= 0) steps.subList(0, boundary + 1) else emptyList()
        val prefixDigest = if (prefix.isNotEmpty()) fingerprint(prefix) else ""
        val prefixMatches = boundary >= 0 &&
            sanitized.summaryVersion == SUMMARY_VERSION &&
            sanitized.compactedPrefixDigest.length == SHA_256_HEX_LENGTH &&
            prefixDigest == sanitized.compactedPrefixDigest &&
            summarize(prefix, sensitiveTools, prefixDigest) == sanitized.summary
        return if (!prefixMatches || sanitized.summary.isBlank()) {
            AgentSessionMemory()
        } else {
            sanitized.copy(compressedStepCount = boundary + 1)
        }
    }

    /** Full-fidelity tail only; callers reserve [AgentSessionMemory.summary] separately. */
    fun replaySteps(
        steps: List<AgentStep>,
        memory: AgentSessionMemory,
        sensitiveTools: Set<String> = emptySet(),
    ): List<AgentStep> {
        val normalized = normalizeMemory(steps, memory, sensitiveTools)
        val boundary = boundaryIndex(steps, normalized)
        if (boundary < 0) return steps
        return steps.drop(boundary + 1)
    }

    /** Returns null when no additional safe prefix is currently eligible. */
    fun compact(
        steps: List<AgentStep>,
        memory: AgentSessionMemory,
        activePlan: AgentPlan?,
        pendingReview: AgentPendingReview?,
        hasPendingPrompt: Boolean,
        sensitiveTools: Set<String>,
        compressedAt: String,
    ): AgentSessionMemory? {
        if (steps.size <= MIN_REPLAY_TAIL_STEPS) return null
        if (!hasUniqueStepIds(steps)) return null
        val normalized = normalizeMemory(steps, memory, sensitiveTools)
        val previousBoundary = boundaryIndex(steps, normalized)
        val protectedFrom = earliestProtectedIndex(
            steps = steps,
            activePlan = activePlan,
            pendingReview = pendingReview,
            hasPendingPrompt = hasPendingPrompt,
        )
        val nextBoundary = causallyClosedBoundary(steps, protectedFrom - 1)
        if (nextBoundary <= previousBoundary || nextBoundary !in steps.indices) return null

        val compacted = steps.subList(0, nextBoundary + 1)
        val prefixDigest = fingerprint(compacted)
        return AgentSessionMemory(
            summaryVersion = SUMMARY_VERSION,
            summary = summarize(compacted, sensitiveTools, prefixDigest),
            compactedThroughStepId = steps[nextBoundary].id,
            compactedPrefixDigest = prefixDigest,
            compressionCount = saturatedIncrement(normalized.compressionCount),
            compressedStepCount = nextBoundary + 1,
            lastCompressedAt = compressedAt.takeIf { it.isNotBlank() },
        )
    }

    private fun earliestProtectedIndex(
        steps: List<AgentStep>,
        activePlan: AgentPlan?,
        pendingReview: AgentPendingReview?,
        hasPendingPrompt: Boolean,
    ): Int {
        val protected = mutableListOf((steps.size - MIN_REPLAY_TAIL_STEPS).coerceAtLeast(0))

        steps.indexOfLast { it.type == AgentStep.USER }
            .takeIf { it >= 0 }
            ?.let(protected::add)

        activePlan?.takeUnless { it.isComplete }?.sourceCommandId
            ?.takeIf { it.isNotBlank() }
            ?.let { sourceId -> steps.indexOfFirst { it.id == sourceId } }
            ?.takeIf { it >= 0 }
            ?.let(protected::add)

        val planId = activePlan?.takeUnless { it.isComplete }?.planId.orEmpty()
        if (planId.isNotBlank()) {
            val planActionIds = steps.asSequence()
                .filter { it.type == AgentStep.ACTION && it.planId == planId }
                .map { it.id }
                .toSet()
            steps.indices.firstOrNull { index ->
                val step = steps[index]
                step.planId == planId || step.resultForActionId in planActionIds
            }?.let(protected::add)
        }

        pendingReview?.actionId?.let { actionId ->
            steps.indices.firstOrNull { index ->
                val step = steps[index]
                step.id == actionId || step.resultForActionId == actionId
            }?.let(protected::add)
        }

        steps.indices.firstOrNull { index ->
            val step = steps[index]
            step.type == AgentStep.ACTION && step.actionStatus in NON_TERMINAL_ACTION_STATUSES
        }?.let(protected::add)

        if (hasPendingPrompt) {
            steps.indexOfLast { step ->
                step.type == AgentStep.QUESTION ||
                    (step.type == AgentStep.ACTION &&
                        step.actionStatus == AgentStep.ACTION_PROPOSED)
            }.takeIf { it >= 0 }?.let(protected::add)
        }

        return protected.minOrNull()?.coerceIn(0, steps.size) ?: 0
    }

    /** Never split an action from a later (or malformed earlier) result/answer/image bundle. */
    private fun causallyClosedBoundary(steps: List<AgentStep>, proposedBoundary: Int): Int {
        var boundary = proposedBoundary
        val actionIndexes = steps.mapIndexedNotNull { index, step ->
            step.id.takeIf { step.type == AgentStep.ACTION && it.isNotBlank() }?.let { it to index }
        }.toMap()
        var changed: Boolean
        do {
            changed = false
            steps.forEachIndexed { resultIndex, result ->
                val actionIndex = actionIndexes[result.resultForActionId] ?: return@forEachIndexed
                val first = minOf(actionIndex, resultIndex)
                val last = maxOf(actionIndex, resultIndex)
                if (first <= boundary && last > boundary) {
                    boundary = first - 1
                    changed = true
                }
            }
            steps.forEachIndexed { questionIndex, question ->
                if (question.type != AgentStep.QUESTION) return@forEachIndexed
                val nextQuestion = steps.indexOfFirstFrom(questionIndex + 1) {
                    it.type == AgentStep.QUESTION
                }.takeIf { it >= 0 } ?: steps.size
                val answerIndex = steps.indexOfFirstFrom(questionIndex + 1) {
                    it.type == AgentStep.ANSWER
                }.takeIf { it in (questionIndex + 1) until nextQuestion } ?: return@forEachIndexed
                if (questionIndex <= boundary && answerIndex > boundary) {
                    boundary = questionIndex - 1
                    changed = true
                }
            }
        } while (changed && boundary >= 0)
        return boundary
    }

    private fun List<AgentStep>.indexOfFirstFrom(
        startIndex: Int,
        predicate: (AgentStep) -> Boolean,
    ): Int {
        for (index in startIndex.coerceAtLeast(0) until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun hasUniqueStepIds(steps: List<AgentStep>): Boolean =
        steps.all { it.id.isNotBlank() } && steps.map { it.id }.distinct().size == steps.size

    private fun summarize(
        steps: List<AgentStep>,
        sensitiveTools: Set<String>,
        prefixDigest: String,
    ): String {
        val userGoals = steps.filter { it.type == AgentStep.USER }.takeLast(MAX_GOALS)
        val actions = steps.filter { it.type == AgentStep.ACTION }
        val sensitiveActions = actions.filter { it.tool in sensitiveTools }
        val auditActions = (actions.takeLast(MAX_AUDIT_ACTIONS) +
            sensitiveActions.takeLast(MAX_SENSITIVE_ACTIONS))
            .distinctBy { it.id }
            .sortedBy { action -> steps.indexOfFirst { it.id == action.id } }
        val otherKeySteps = steps.filter {
            it.type in setOf(
                AgentStep.PLAN,
                AgentStep.MESSAGE,
                AgentStep.ERROR,
                AgentStep.QUESTION,
                AgentStep.ANSWER,
            )
        }.takeLast(MAX_OTHER_KEY_STEPS)

        val header = buildString {
            appendLine("【本地压缩记忆 v$SUMMARY_VERSION】")
            appendLine("已压缩 ${steps.size} 条较早记录；原始执行链仍保留，可按动作 ID 追溯。")
            appendLine("审计指纹 SHA-256：$prefixDigest")
        }.trimEnd()

        val goalBlocks = userGoals.map { step ->
            "- id=${step.id}：${compactText(step.text, MAX_GOAL_CHARS)}"
        }
        val auditBlocks = auditActions.map { action ->
            buildString {
                append("- action=${action.id} tool=${action.tool.ifBlank { "unknown" }}")
                if (action.tool in sensitiveTools) append(" sensitive=true")
                append(" status=${action.actionStatus.ifBlank { "legacy" }}")
                if (action.planId.isNotBlank()) append(" plan=${action.planId}/${action.planStepId}")
                if (action.resolvedProjectId.isNotBlank()) append(" project=${action.resolvedProjectId}")
                append("：${compactText(action.text, MAX_ACTION_CHARS)}")
                steps.filter { it.resultForActionId == action.id }
                    .takeLast(MAX_RESULTS_PER_ACTION)
                    .forEach { result ->
                        appendLine()
                        append(
                            "  evidence=${result.id} type=${result.type} " +
                                "kind=${result.resultKind.ifBlank { "legacy" }}：" +
                                compactText(result.text, MAX_EVIDENCE_CHARS),
                        )
                    }
            }
        }
        val otherBlocks = otherKeySteps.map { step ->
            "- id=${step.id} type=${step.type}：${compactText(step.text, MAX_OTHER_CHARS)}"
        }

        val sections = listOfNotNull(
            boundedSection("较早用户目标", goalBlocks, GOAL_SECTION_CHARS),
            boundedSection("动作与证据索引", auditBlocks, AUDIT_SECTION_CHARS),
            boundedSection("较早关键沟通", otherBlocks, OTHER_SECTION_CHARS),
        )
        return truncateSafely(
            (listOf(header) + sections).joinToString("\n"),
            MAX_SUMMARY_CHARS,
        )
    }

    /** Select newest blocks first, then restore their original chronological order. */
    private fun boundedSection(title: String, blocks: List<String>, maxChars: Int): String? {
        if (blocks.isEmpty()) return null
        val heading = "【$title】"
        val available = (maxChars - heading.length - 1).coerceAtLeast(1)
        val newestFirst = mutableListOf<String>()
        var used = 0
        for (block in blocks.asReversed()) {
            val extra = block.length + if (newestFirst.isEmpty()) 0 else 1
            if (used + extra > available) break
            newestFirst += block
            used += extra
        }
        if (newestFirst.isEmpty()) {
            newestFirst += truncateSafely(blocks.last(), available)
        }
        val omitted = blocks.size - newestFirst.size
        return buildString {
            appendLine(heading)
            if (omitted > 0) appendLine("（更早 $omitted 项仅保留在审计链与指纹中）")
            append(newestFirst.asReversed().joinToString("\n"))
        }
    }

    private fun fingerprint(steps: List<AgentStep>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        steps.forEach { step ->
            listOf(
                step.id,
                step.type,
                step.text,
                step.tool,
                step.createdAt,
                step.image,
                step.argsJson,
                step.actionStatus,
                step.planStepId,
                step.planId,
                step.resolvedProjectId,
                step.resultForActionId,
                step.resultKind,
            ).forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun compactText(value: String, maxChars: Int): String =
        truncateSafely(value.replace(Regex("\\s+"), " ").trim(), maxChars)

    private fun truncateSafely(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val marker = "…（压缩截断）"
        var end = (maxChars - marker.length).coerceAtLeast(0)
        if (
            end in 1 until value.length &&
            value[end - 1].isHighSurrogate() &&
            value[end].isLowSurrogate()
        ) {
            end--
        }
        return value.substring(0, end) + marker.take(maxChars - end)
    }

    private fun boundaryIndex(steps: List<AgentStep>, memory: AgentSessionMemory): Int =
        memory.compactedThroughStepId
            .takeIf { it.isNotBlank() }
            ?.let { id -> steps.indexOfFirst { it.id == id } }
            ?: -1

    private fun saturatedIncrement(value: Int): Int =
        if (value == Int.MAX_VALUE) Int.MAX_VALUE else value + 1

    private val NON_TERMINAL_ACTION_STATUSES = setOf(
        AgentStep.ACTION_PROPOSED,
        AgentStep.ACTION_RUNNING,
        AgentStep.ACTION_AWAITING_REVIEW,
        AgentStep.ACTION_INTERRUPTED,
    )
    private const val MAX_GOALS = 4
    private const val MAX_AUDIT_ACTIONS = 24
    private const val MAX_SENSITIVE_ACTIONS = 24
    private const val MAX_RESULTS_PER_ACTION = 2
    private const val MAX_OTHER_KEY_STEPS = 16
    private const val MAX_GOAL_CHARS = 1_200
    private const val MAX_ACTION_CHARS = 320
    private const val MAX_EVIDENCE_CHARS = 480
    private const val MAX_OTHER_CHARS = 600
    private const val GOAL_SECTION_CHARS = 3_000
    private const val AUDIT_SECTION_CHARS = 6_000
    private const val OTHER_SECTION_CHARS = 2_000
    private const val SHA_256_HEX_LENGTH = 64
}
