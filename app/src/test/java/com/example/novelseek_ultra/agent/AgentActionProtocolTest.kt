package com.example.novelseek_ultra.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionProtocolTest {
    @Test
    fun parsesBareActionWithNestedJsonArgs() {
        val result = AgentActionParser.parse(
            """
            {
              "thought": "  检查章节结构  ",
              "action": "inspect_chapter",
              "args": {
                "chapter": {"id": "chapter-1", "metadata": {"draft": true}},
                "paragraphs": ["含有 } 的正文", {"index": 2}],
                "count": 2
              }
            }
            """.trimIndent(),
        ).success()

        assertEquals("检查章节结构", result.thought)
        assertEquals("inspect_chapter", result.action)
        assertEquals(2, (result.args["count"] as JsonPrimitive).content.toInt())
        assertTrue(result.args["chapter"] is JsonObject)
        assertTrue(result.args["paragraphs"] is JsonArray)
    }

    @Test
    fun parsesOneOptionalJsonMarkdownFence() {
        val result = AgentActionParser.parse(
            """
            ```json
            {"thought":"下一步","action":"list_projects","args":{}}
            ```
            """.trimIndent(),
        ).success()

        assertEquals("list_projects", result.action)
        assertTrue(result.args.isEmpty())
    }

    @Test
    fun parsesOptionalDualEnginePlanProgress() {
        val result = AgentActionParser.parse(
            """{"action":"list_projects","args":{},"planStepId":"step-2","planStepDone":true}""",
        ).success()

        assertEquals("step-2", result.planStepId)
        assertTrue(result.planStepDone)
        assertFailureCode(
            """{"action":"list_projects","planStepId":"","planStepDone":true}""",
            AgentActionParseErrorCode.INVALID_PLAN_STEP_ID,
        )
        assertFailureCode(
            """{"action":"list_projects","planStepDone":"true"}""",
            AgentActionParseErrorCode.INVALID_PLAN_STEP_DONE,
        )
    }

    @Test
    fun parsesOneOptionalUnlabelledMarkdownFence() {
        val result = AgentActionParser.parse(
            """
            ```
            {"action":"list_projects"}
            ```
            """.trimIndent(),
        ).success()

        assertEquals("", result.thought)
        assertTrue(result.args.isEmpty())
    }

    @Test
    fun rejectsProseSurroundingJsonInsteadOfSalvagingBraces() {
        assertFailureCode(
            "Here is the requested action: {\"action\":\"list_projects\",\"args\":{}}",
            AgentActionParseErrorCode.EXTRANEOUS_CONTENT,
        )
        assertFailureCode(
            "{\"action\":\"list_projects\",\"args\":{}} Thanks!",
            AgentActionParseErrorCode.EXTRANEOUS_CONTENT,
        )
    }

    @Test
    fun rejectsMultipleTopLevelJsonObjects() {
        assertFailureCode(
            "{\"action\":\"first\",\"args\":{}} {\"action\":\"second\",\"args\":{}}",
            AgentActionParseErrorCode.MULTIPLE_JSON_OBJECTS,
        )
    }

    @Test
    fun rejectsMalformedOrMultipleMarkdownFences() {
        assertFailureCode(
            "```json\n{\"action\":\"first\"}\n```\n```json\n{\"action\":\"second\"}\n```",
            AgentActionParseErrorCode.MALFORMED_MARKDOWN_FENCE,
        )
        assertFailureCode(
            "```json\n{\"action\":\"first\"}",
            AgentActionParseErrorCode.MALFORMED_MARKDOWN_FENCE,
        )
        assertFailureCode(
            "prefix\n```json\n{\"action\":\"first\"}\n```",
            AgentActionParseErrorCode.MALFORMED_MARKDOWN_FENCE,
        )
    }

    @Test
    fun rejectsEmptyAndMissingActionSeparately() {
        assertFailureCode("   ", AgentActionParseErrorCode.EMPTY_RESPONSE)
        assertFailureCode("{}", AgentActionParseErrorCode.MISSING_ACTION)
        assertFailureCode("{\"action\":\"  \"}", AgentActionParseErrorCode.EMPTY_ACTION)
    }

    @Test
    fun rejectsNonStringActionAndThought() {
        assertFailureCode("{\"action\":7}", AgentActionParseErrorCode.INVALID_ACTION)
        assertFailureCode(
            "{\"thought\":{\"text\":\"no\"},\"action\":\"list_projects\"}",
            AgentActionParseErrorCode.INVALID_THOUGHT,
        )
    }

    @Test
    fun rejectsEveryNonObjectArgsShape() {
        listOf("[]", "null", "\"text\"", "4", "true").forEach { args ->
            assertFailureCode(
                "{\"action\":\"list_projects\",\"args\":$args}",
                AgentActionParseErrorCode.INVALID_ARGS,
            )
        }
    }

    @Test
    fun rejectsNonObjectRootAndMalformedJson() {
        assertFailureCode("[]", AgentActionParseErrorCode.ROOT_NOT_OBJECT)
        assertFailureCode("{\"action\":", AgentActionParseErrorCode.INVALID_JSON)
    }

    @Test
    fun parseErrorProducesSafeCorrectiveObservation() {
        val failure = AgentActionParser.parse("{}").failure()
        val feedback = failure.error.asModelObservation()

        assertTrue(feedback.contains("MISSING_ACTION"))
        assertTrue(feedback.contains("只输出一个 JSON 对象"))
        assertFalse(feedback.contains("{}{}"))
    }

    private fun assertFailureCode(raw: String, expected: AgentActionParseErrorCode) {
        assertEquals(expected, AgentActionParser.parse(raw).failure().error.code)
    }

    private fun AgentActionParseResult.success(): AgentAction = when (this) {
        is AgentActionParseResult.Success -> value
        is AgentActionParseResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun AgentActionParseResult.failure(): AgentActionParseResult.Failure = when (this) {
        is AgentActionParseResult.Failure -> this
        is AgentActionParseResult.Success -> throw AssertionError("Expected failure, got $value")
    }
}
