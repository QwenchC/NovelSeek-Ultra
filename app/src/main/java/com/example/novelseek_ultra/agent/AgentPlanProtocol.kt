package com.example.novelseek_ultra.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lifecycle state of one step in a planner/executor run. */
@Serializable
enum class AgentPlanStepStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("blocked")
    BLOCKED,
}

/** A small, independently executable unit proposed by the planner agent. */
@Serializable
data class AgentPlanStep(
    val id: String,
    val goal: String,
    val successCriteria: String? = null,
    val suggestedTools: List<String> = emptyList(),
    val status: AgentPlanStepStatus = AgentPlanStepStatus.PENDING,
)

/**
 * A validated hand-off from the read-only planner to the tool-using executor.
 *
 * [createdAt] and [sourceCommandId] are runtime metadata. They are supplied by the caller instead
 * of trusting a model to create them.
 */
@Serializable
data class AgentPlan(
    val summary: String,
    val steps: List<AgentPlanStep>,
    val createdAt: Long,
    val sourceCommandId: String = "",
    /** Runtime-owned identity used to bind action evidence to this exact plan across restarts. */
    val planId: String = "",
) {
    /** Returns a new plan; the original plan and all unaffected steps remain unchanged. */
    fun withStepStatus(stepId: String, status: AgentPlanStepStatus): AgentPlan {
        val normalizedId = stepId.trim()
        require(normalizedId.isNotEmpty()) { "stepId must not be blank" }
        require(steps.any { it.id == normalizedId }) { "Unknown plan step id" }
        return copy(
            steps = steps.map { step ->
                if (step.id == normalizedId) step.copy(status = status) else step
            },
        )
    }

    val currentStep: AgentPlanStep?
        get() = steps.firstOrNull { it.status == AgentPlanStepStatus.IN_PROGRESS }

    val isComplete: Boolean
        get() = steps.all { it.status == AgentPlanStepStatus.COMPLETED }

    /** Starts the next pending step unless another step is already in progress. */
    fun beginNextPendingStep(): AgentPlan {
        if (currentStep != null) return this
        val next = steps.firstOrNull { it.status == AgentPlanStepStatus.PENDING } ?: return this
        return withStepStatus(next.id, AgentPlanStepStatus.IN_PROGRESS)
    }

    /** Completes only the current step, then advances in plan order. */
    fun completeCurrentStepAndAdvance(expectedStepId: String): AgentPlan {
        val current = currentStep ?: error("No plan step is in progress")
        require(current.id == expectedStepId.trim()) { "Only the current plan step can be completed" }
        return withStepStatus(current.id, AgentPlanStepStatus.COMPLETED).beginNextPendingStep()
    }

    fun blockCurrentStep(expectedStepId: String): AgentPlan {
        val current = currentStep ?: error("No plan step is in progress")
        require(current.id == expectedStepId.trim()) { "Only the current plan step can be blocked" }
        return withStepStatus(current.id, AgentPlanStepStatus.BLOCKED)
    }

    fun asPromptText(): String = buildString {
        appendLine("计划摘要：$summary")
        steps.forEach { step ->
            append("- [")
            append(step.status.name.lowercase())
            append("] ")
            append(step.id)
            append("：")
            append(step.goal)
            step.successCriteria?.let { append("；完成标准：").append(it) }
            if (step.suggestedTools.isNotEmpty()) {
                append("；建议工具：").append(step.suggestedTools.joinToString(", "))
            }
            appendLine()
        }
    }.trim()
}

/** The compact schema sent to a planner model. Runtime metadata and default status are omitted. */
object AgentPlannerOutputFormat {
    const val INSTRUCTION: String =
        "只输出一个 JSON 对象：" +
            "{\"summary\":\"一句话计划\",\"steps\":[" +
            "{\"id\":\"step-1\",\"goal\":\"单一目标\"," +
            "\"successCriteria\":\"完成标志\",\"suggestedTools\":[\"tool_name\"]}]}。" +
            "步骤数必须为 1 到 20；id 必须唯一。不要输出 Markdown、解释文字、createdAt 或 sourceCommandId。"
}

/** Stable, machine-readable reasons why a planner response was rejected. */
@Serializable
enum class AgentPlanParseErrorCode {
    EMPTY_RESPONSE,
    MALFORMED_MARKDOWN_FENCE,
    MULTIPLE_JSON_OBJECTS,
    EXTRANEOUS_CONTENT,
    INVALID_JSON,
    ROOT_NOT_OBJECT,
    UNKNOWN_FIELD,
    MISSING_SUMMARY,
    INVALID_SUMMARY,
    MISSING_STEPS,
    INVALID_STEPS,
    INVALID_PLAN_SIZE,
    INVALID_STEP,
    MISSING_STEP_ID,
    INVALID_STEP_ID,
    DUPLICATE_STEP_ID,
    MISSING_STEP_GOAL,
    INVALID_STEP_GOAL,
    INVALID_SUCCESS_CRITERIA,
    INVALID_SUGGESTED_TOOLS,
    INVALID_STATUS,
}

/** A bounded parse error that never echoes the model's full, potentially untrusted response. */
@Serializable
data class AgentPlanParseError(
    val code: AgentPlanParseErrorCode,
    val detail: String,
    val stepIndex: Int? = null,
) {
    fun asModelObservation(): String =
        "计划格式错误（${code.name}）：$detail。${AgentPlannerOutputFormat.INSTRUCTION}"
}

sealed interface AgentPlanParseResult {
    data class Success(val value: AgentPlan) : AgentPlanParseResult
    data class Failure(val error: AgentPlanParseError) : AgentPlanParseResult
}

/** Strict parser for the planner-facing JSON protocol. */
object AgentPlanParser {
    private val json = Json
    private val topLevelFields = setOf("summary", "steps")
    private val stepFields = setOf("id", "goal", "successCriteria", "suggestedTools", "status")
    private val stepIdPattern = Regex("""[A-Za-z0-9_-]{1,64}""")

    // Provider compatibility: tolerate one optional fence only when it wraps the entire response.
    private val fencedBlock = Regex(
        pattern = """\A```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```[ \t]*\z""",
        option = RegexOption.IGNORE_CASE,
    )
    private val standaloneFenceLine = Regex(
        pattern = """(?m)^[ \t]*```(?:json)?[ \t]*$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parse(
        raw: String,
        createdAt: Long = System.currentTimeMillis(),
        sourceCommandId: String = "",
    ): AgentPlanParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return failure(AgentPlanParseErrorCode.EMPTY_RESPONSE, "规划器返回了空内容")
        }

        val candidate = unwrapOptionalFence(trimmed) ?: return fenceFailure(trimmed)

        if (candidate.startsWith('{')) {
            val rootEnd = matchingRootObjectEnd(candidate)
            if (rootEnd != null && candidate.substring(rootEnd + 1).isNotBlank()) {
                val remainder = candidate.substring(rootEnd + 1).trimStart()
                return if (remainder.startsWith('{')) {
                    failure(
                        AgentPlanParseErrorCode.MULTIPLE_JSON_OBJECTS,
                        "一次响应中包含多个顶层 JSON 对象",
                    )
                } else {
                    failure(
                        AgentPlanParseErrorCode.EXTRANEOUS_CONTENT,
                        "完整 JSON 对象之后仍有额外内容",
                    )
                }
            }
        }

        val element = runCatching { json.parseToJsonElement(candidate) }.getOrElse {
            return if (!candidate.startsWith('{') && candidate.contains('{')) {
                failure(
                    AgentPlanParseErrorCode.EXTRANEOUS_CONTENT,
                    "JSON 对象前后存在说明文字或其他内容",
                )
            } else {
                failure(AgentPlanParseErrorCode.INVALID_JSON, "内容不是一个完整、合法的 JSON 值")
            }
        }

        val obj = element as? JsonObject
            ?: return failure(AgentPlanParseErrorCode.ROOT_NOT_OBJECT, "顶层 JSON 必须是对象")
        if (obj.keys.any { it !in topLevelFields }) {
            return failure(AgentPlanParseErrorCode.UNKNOWN_FIELD, "顶层对象包含协议未定义的字段")
        }

        val summaryElement = obj["summary"]
            ?: return failure(AgentPlanParseErrorCode.MISSING_SUMMARY, "缺少必填字段 summary")
        val summary = summaryElement.stringContentOrNull()?.trim()
            ?: return failure(AgentPlanParseErrorCode.INVALID_SUMMARY, "summary 必须是字符串")
        if (summary.isEmpty()) {
            return failure(AgentPlanParseErrorCode.INVALID_SUMMARY, "summary 不能为空或仅包含空白")
        }
        if (summary.length > 500) {
            return failure(AgentPlanParseErrorCode.INVALID_SUMMARY, "summary 不能超过 500 个字符")
        }

        val stepsElement = obj["steps"]
            ?: return failure(AgentPlanParseErrorCode.MISSING_STEPS, "缺少必填字段 steps")
        val stepElements = stepsElement as? JsonArray
            ?: return failure(AgentPlanParseErrorCode.INVALID_STEPS, "steps 必须是 JSON 数组")
        if (stepElements.size !in 1..20) {
            return failure(AgentPlanParseErrorCode.INVALID_PLAN_SIZE, "steps 数量必须为 1 到 20")
        }

        val ids = mutableSetOf<String>()
        val steps = ArrayList<AgentPlanStep>(stepElements.size)
        stepElements.forEachIndexed { index, stepElement ->
            val stepObject = stepElement as? JsonObject
                ?: return failure(
                    AgentPlanParseErrorCode.INVALID_STEP,
                    "steps[$index] 必须是 JSON 对象",
                    index,
                )
            if (stepObject.keys.any { it !in stepFields }) {
                return failure(
                    AgentPlanParseErrorCode.UNKNOWN_FIELD,
                    "steps[$index] 包含协议未定义的字段",
                    index,
                )
            }
            if ("status" in stepObject) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_STATUS,
                    "steps[$index].status 是运行时字段，规划器不得提供",
                    index,
                )
            }

            val idElement = stepObject["id"]
                ?: return failure(
                    AgentPlanParseErrorCode.MISSING_STEP_ID,
                    "steps[$index] 缺少必填字段 id",
                    index,
                )
            val id = idElement.stringContentOrNull()?.trim()
                ?: return failure(
                    AgentPlanParseErrorCode.INVALID_STEP_ID,
                    "steps[$index].id 必须是字符串",
                    index,
                )
            if (id.isEmpty()) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_STEP_ID,
                    "steps[$index].id 不能为空或仅包含空白",
                    index,
                )
            }
            if (!stepIdPattern.matches(id)) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_STEP_ID,
                    "steps[$index].id 只能包含字母、数字、下划线和连字符，且最长 64 字符",
                    index,
                )
            }
            if (!ids.add(id)) {
                return failure(
                    AgentPlanParseErrorCode.DUPLICATE_STEP_ID,
                    "steps[$index].id 与较早步骤重复",
                    index,
                )
            }

            val goalElement = stepObject["goal"]
                ?: return failure(
                    AgentPlanParseErrorCode.MISSING_STEP_GOAL,
                    "steps[$index] 缺少必填字段 goal",
                    index,
                )
            val goal = goalElement.stringContentOrNull()?.trim()
                ?: return failure(
                    AgentPlanParseErrorCode.INVALID_STEP_GOAL,
                    "steps[$index].goal 必须是字符串",
                    index,
                )
            if (goal.isEmpty()) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_STEP_GOAL,
                    "steps[$index].goal 不能为空或仅包含空白",
                    index,
                )
            }
            if (goal.length > 1_000) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_STEP_GOAL,
                    "steps[$index].goal 不能超过 1000 个字符",
                    index,
                )
            }

            val successCriteria = when (val value = stepObject["successCriteria"]) {
                null, JsonNull -> null
                else -> value.stringContentOrNull()?.trim()
                    ?: return failure(
                        AgentPlanParseErrorCode.INVALID_SUCCESS_CRITERIA,
                        "steps[$index].successCriteria 必须是字符串或 null",
                        index,
                    )
            }
            if (successCriteria != null && successCriteria.isEmpty()) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_SUCCESS_CRITERIA,
                    "steps[$index].successCriteria 不能仅包含空白",
                    index,
                )
            }
            if (successCriteria != null && successCriteria.length > 1_000) {
                return failure(
                    AgentPlanParseErrorCode.INVALID_SUCCESS_CRITERIA,
                    "steps[$index].successCriteria 不能超过 1000 个字符",
                    index,
                )
            }

            val suggestedToolsResult = parseSuggestedTools(stepObject["suggestedTools"], index)
            val suggestedTools = suggestedToolsResult.first
                ?: return suggestedToolsResult.second
                    ?: failure(
                        AgentPlanParseErrorCode.INVALID_SUGGESTED_TOOLS,
                        "steps[$index].suggestedTools 格式无效",
                        index,
                    )

            steps += AgentPlanStep(
                id = id,
                goal = goal,
                successCriteria = successCriteria,
                suggestedTools = suggestedTools,
                status = AgentPlanStepStatus.PENDING,
            )
        }

        return AgentPlanParseResult.Success(
            AgentPlan(
                summary = summary,
                steps = steps,
                createdAt = createdAt,
                sourceCommandId = sourceCommandId.trim(),
            ),
        )
    }

    private fun parseSuggestedTools(
        value: kotlinx.serialization.json.JsonElement?,
        index: Int,
    ): Pair<List<String>?, AgentPlanParseResult.Failure?> {
        if (value == null || value === JsonNull) return emptyList<String>() to null
        val array = value as? JsonArray
            ?: return invalidSuggestedTools(index, "steps[$index].suggestedTools 必须是字符串数组")
        if (array.size > 16) {
            return invalidSuggestedTools(index, "steps[$index].suggestedTools 最多包含 16 个工具")
        }
        val result = ArrayList<String>(array.size)
        array.forEach { element ->
            val tool = element.stringContentOrNull()?.trim()
                ?: return invalidSuggestedTools(index, "steps[$index].suggestedTools 只能包含字符串")
            if (tool.isEmpty() || tool.length > 64) {
                return invalidSuggestedTools(index, "steps[$index].suggestedTools 包含空名称或超长名称")
            }
            result += tool
        }
        return result.distinct() to null
    }

    private fun invalidSuggestedTools(
        index: Int,
        detail: String,
    ): Pair<List<String>?, AgentPlanParseResult.Failure?> =
        null to failure(AgentPlanParseErrorCode.INVALID_SUGGESTED_TOOLS, detail, index)

    private fun kotlinx.serialization.json.JsonElement.stringContentOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content
    }

    /** Returns null only for a malformed fence; unfenced content is returned unchanged. */
    private fun unwrapOptionalFence(trimmed: String): String? {
        if (!trimmed.startsWith("```") && !trimmed.endsWith("```")) return trimmed
        val match = fencedBlock.matchEntire(trimmed) ?: return null
        val inside = match.groupValues[1].trim()
        if (inside.isEmpty() || standaloneFenceLine.containsMatchIn(inside)) return null
        return inside
    }

    private fun fenceFailure(trimmed: String): AgentPlanParseResult.Failure {
        val match = fencedBlock.matchEntire(trimmed)
        val emptyFence = match != null && match.groupValues[1].trim().isEmpty()
        return if (emptyFence) {
            failure(AgentPlanParseErrorCode.EMPTY_RESPONSE, "Markdown 围栏内没有 JSON 内容")
        } else {
            failure(
                AgentPlanParseErrorCode.MALFORMED_MARKDOWN_FENCE,
                "仅允许包裹整个 JSON 的一组 ``` 或 ```json 围栏",
            )
        }
    }

    /** Finds the end of one root object while respecting nested structures and escaped quotes. */
    private fun matchingRootObjectEnd(value: String): Int? {
        if (!value.startsWith('{')) return null
        val expectedClosers = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        value.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (char) {
                '"' -> inString = true
                '{' -> expectedClosers.addLast('}')
                '[' -> expectedClosers.addLast(']')
                '}', ']' -> {
                    if (expectedClosers.removeLastOrNull() != char) return null
                    if (expectedClosers.isEmpty()) return index
                }
            }
        }
        return null
    }

    private fun failure(
        code: AgentPlanParseErrorCode,
        detail: String,
        stepIndex: Int? = null,
    ): AgentPlanParseResult.Failure =
        AgentPlanParseResult.Failure(AgentPlanParseError(code, detail, stepIndex))
}
