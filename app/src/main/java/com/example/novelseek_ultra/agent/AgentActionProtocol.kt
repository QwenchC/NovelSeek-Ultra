package com.example.novelseek_ultra.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The single action emitted by the text model for one agent turn.
 *
 * Keeping this protocol independent from Android and the controller makes model output validation
 * deterministic and cheap to unit-test. [args] deliberately remains a JSON object because each
 * registered tool owns a different argument schema.
 */
@Serializable
data class AgentAction(
    val thought: String = "",
    val action: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val planStepId: String? = null,
    val planStepDone: Boolean = false,
)

/** A stable, machine-readable reason why model output was rejected. */
@Serializable
enum class AgentActionParseErrorCode {
    EMPTY_RESPONSE,
    MALFORMED_MARKDOWN_FENCE,
    MULTIPLE_JSON_OBJECTS,
    EXTRANEOUS_CONTENT,
    INVALID_JSON,
    ROOT_NOT_OBJECT,
    MISSING_ACTION,
    INVALID_ACTION,
    EMPTY_ACTION,
    INVALID_THOUGHT,
    INVALID_ARGS,
    INVALID_PLAN_STEP_ID,
    INVALID_PLAN_STEP_DONE,
}

/**
 * Structured parse failure that can be logged as-is and converted into a corrective model
 * observation without echoing the (potentially very large or untrusted) original response.
 */
@Serializable
data class AgentActionParseError(
    val code: AgentActionParseErrorCode,
    val detail: String,
) {
    fun asModelObservation(): String =
        "动作格式错误（${code.name}）：$detail。" +
            "请只输出一个 JSON 对象，格式为 " +
            "{\"thought\":\"简短说明\",\"action\":\"工具名\",\"args\":{}}，不要附加其他文字。"
}

sealed interface AgentActionParseResult {
    data class Success(val value: AgentAction) : AgentActionParseResult
    data class Failure(val error: AgentActionParseError) : AgentActionParseResult
}

/** Strict parser for the model-facing agent action protocol. */
object AgentActionParser {
    private val json = Json

    // A single conventional Markdown fence is tolerated for provider compatibility. The whole
    // response must be the fenced block; prose before/after it is never discarded.
    private val fencedBlock = Regex(
        pattern = """\A```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```[ \t]*\z""",
        option = RegexOption.IGNORE_CASE,
    )
    private val standaloneFenceLine = Regex(
        pattern = """(?m)^[ \t]*```(?:json)?[ \t]*$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parse(raw: String): AgentActionParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return failure(AgentActionParseErrorCode.EMPTY_RESPONSE, "模型返回了空内容")
        }

        val candidate = when {
            trimmed.startsWith("```") || trimmed.endsWith("```") -> {
                val match = fencedBlock.matchEntire(trimmed)
                    ?: return failure(
                        AgentActionParseErrorCode.MALFORMED_MARKDOWN_FENCE,
                        "仅允许包裹整个 JSON 的一组 ``` 或 ```json 围栏",
                    )
                match.groupValues[1].trim().also {
                    if (it.isEmpty()) {
                        return failure(
                            AgentActionParseErrorCode.EMPTY_RESPONSE,
                            "Markdown 围栏内没有 JSON 内容",
                        )
                    }
                    if (standaloneFenceLine.containsMatchIn(it)) {
                        return failure(
                            AgentActionParseErrorCode.MALFORMED_MARKDOWN_FENCE,
                            "仅允许一组 Markdown JSON 围栏",
                        )
                    }
                }
            }
            else -> trimmed
        }

        // Do not revive malformed output by taking the substring between the first and last brace.
        // Detect a complete first root object followed by anything else before decoding.
        if (candidate.startsWith('{')) {
            val rootEnd = matchingRootObjectEnd(candidate)
            if (rootEnd != null && candidate.substring(rootEnd + 1).isNotBlank()) {
                val remainder = candidate.substring(rootEnd + 1).trimStart()
                val code = if (remainder.startsWith('{')) {
                    AgentActionParseErrorCode.MULTIPLE_JSON_OBJECTS
                } else {
                    AgentActionParseErrorCode.EXTRANEOUS_CONTENT
                }
                val detail = if (code == AgentActionParseErrorCode.MULTIPLE_JSON_OBJECTS) {
                    "一次响应中包含多个顶层 JSON 对象"
                } else {
                    "完整 JSON 对象之后仍有额外内容"
                }
                return failure(code, detail)
            }
        }

        val element = runCatching { json.parseToJsonElement(candidate) }.getOrElse {
            val code = if (!candidate.startsWith('{') && candidate.contains('{')) {
                AgentActionParseErrorCode.EXTRANEOUS_CONTENT
            } else {
                AgentActionParseErrorCode.INVALID_JSON
            }
            val detail = if (code == AgentActionParseErrorCode.EXTRANEOUS_CONTENT) {
                "JSON 对象前后存在说明文字或其他内容"
            } else {
                "内容不是一个完整、合法的 JSON 值"
            }
            return failure(code, detail)
        }

        val obj = element as? JsonObject
            ?: return failure(
                AgentActionParseErrorCode.ROOT_NOT_OBJECT,
                "顶层 JSON 必须是对象，不能是数组、字符串、数字、布尔值或 null",
            )

        val thought = when (val value = obj["thought"]) {
            null -> ""
            is JsonPrimitive -> {
                if (!value.isString) {
                    return failure(
                        AgentActionParseErrorCode.INVALID_THOUGHT,
                        "thought 存在时必须是字符串",
                    )
                }
                value.content.trim()
            }
            else -> return failure(
                AgentActionParseErrorCode.INVALID_THOUGHT,
                "thought 存在时必须是字符串",
            )
        }

        val actionElement = obj["action"]
            ?: return failure(
                AgentActionParseErrorCode.MISSING_ACTION,
                "缺少必填字段 action",
            )
        val actionPrimitive = actionElement as? JsonPrimitive
            ?: return failure(
                AgentActionParseErrorCode.INVALID_ACTION,
                "action 必须是字符串",
            )
        if (!actionPrimitive.isString) {
            return failure(AgentActionParseErrorCode.INVALID_ACTION, "action 必须是字符串")
        }
        val action = actionPrimitive.content.trim()
        if (action.isEmpty()) {
            return failure(AgentActionParseErrorCode.EMPTY_ACTION, "action 不能为空或仅包含空白")
        }

        val args = when (val value = obj["args"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> value
            else -> return failure(
                AgentActionParseErrorCode.INVALID_ARGS,
                "args 存在时必须是 JSON 对象",
            )
        }

        val planStepId = when (val value = obj["planStepId"]) {
            null -> null
            is JsonPrimitive -> {
                if (!value.isString || value.content.isBlank()) {
                    return failure(
                        AgentActionParseErrorCode.INVALID_PLAN_STEP_ID,
                        "planStepId 存在时必须是非空字符串",
                    )
                }
                value.content.trim()
            }
            else -> return failure(
                AgentActionParseErrorCode.INVALID_PLAN_STEP_ID,
                "planStepId 存在时必须是非空字符串",
            )
        }
        val planStepDone = when (val value = obj["planStepDone"]) {
            null -> false
            is JsonPrimitive -> {
                if (value.isString) {
                    return failure(
                        AgentActionParseErrorCode.INVALID_PLAN_STEP_DONE,
                        "planStepDone 存在时必须是布尔值",
                    )
                }
                value.booleanOrNull ?: return failure(
                    AgentActionParseErrorCode.INVALID_PLAN_STEP_DONE,
                    "planStepDone 存在时必须是布尔值",
                )
            }
            else -> return failure(
                AgentActionParseErrorCode.INVALID_PLAN_STEP_DONE,
                "planStepDone 存在时必须是布尔值",
            )
        }

        return AgentActionParseResult.Success(
            AgentAction(thought, action, args, planStepId, planStepDone),
        )
    }

    /**
     * Returns the index of the closing brace for a root object, respecting nested arrays/objects
     * and escaped quotes. A null result means the candidate is incomplete or structurally invalid;
     * the JSON decoder will provide the final rejection in that case.
     */
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
        code: AgentActionParseErrorCode,
        detail: String,
    ): AgentActionParseResult.Failure =
        AgentActionParseResult.Failure(AgentActionParseError(code, detail))
}
