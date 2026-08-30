package com.example.novelseek_ultra.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanProtocolTest {
    @Test
    fun parsesNestedPlanAndInjectsRuntimeMetadata() {
        val plan = AgentPlanParser.parse(
            raw = """
                {
                  "summary": " 先检查，再修改 ",
                  "steps": [
                    {
                      "id": " inspect ",
                      "goal": " 阅读当前章节 ",
                      "successCriteria": " 找出至少一个结构问题 ",
                      "suggestedTools": [" read_chapter ", "list_characters"]
                    },
                    {
                      "id": "revise",
                      "goal": "局部修订正文"
                    }
                  ]
                }
            """.trimIndent(),
            createdAt = 1234L,
            sourceCommandId = " command-7 ",
        ).success()

        assertEquals("先检查，再修改", plan.summary)
        assertEquals(1234L, plan.createdAt)
        assertEquals("command-7", plan.sourceCommandId)
        assertEquals(2, plan.steps.size)
        assertEquals("inspect", plan.steps[0].id)
        assertEquals("找出至少一个结构问题", plan.steps[0].successCriteria)
        assertEquals(listOf("read_chapter", "list_characters"), plan.steps[0].suggestedTools)
        assertEquals(AgentPlanStepStatus.PENDING, plan.steps[0].status)
        assertEquals(AgentPlanStepStatus.PENDING, plan.steps[1].status)
    }

    @Test
    fun parsesOneOptionalWholeMarkdownFence() {
        val plan = AgentPlanParser.parse(
            """
                ```json
                {"summary":"检查","steps":[{"id":"one","goal":"读取项目"}]}
                ```
            """.trimIndent(),
            createdAt = 1L,
        ).success()

        assertEquals("one", plan.steps.single().id)
    }

    @Test
    fun rejectsMalformedMultipleOrProseWrappedOutput() {
        assertFailureCode(
            "{\"summary\":\"a\",\"steps\":[{\"id\":\"1\",\"goal\":\"g\"}]",
            AgentPlanParseErrorCode.INVALID_JSON,
        )
        assertFailureCode(
            "{\"summary\":\"a\",\"steps\":[{\"id\":\"1\",\"goal\":\"g\"}]} " +
                "{\"summary\":\"b\",\"steps\":[{\"id\":\"2\",\"goal\":\"h\"}]}",
            AgentPlanParseErrorCode.MULTIPLE_JSON_OBJECTS,
        )
        assertFailureCode(
            "计划如下：{\"summary\":\"a\",\"steps\":[{\"id\":\"1\",\"goal\":\"g\"}]}",
            AgentPlanParseErrorCode.EXTRANEOUS_CONTENT,
        )
        assertFailureCode(
            "```json\n{\"summary\":\"a\",\"steps\":[{\"id\":\"1\",\"goal\":\"g\"}]}\n```\n尾注",
            AgentPlanParseErrorCode.MALFORMED_MARKDOWN_FENCE,
        )
    }

    @Test
    fun rejectsDuplicateBlankIdsAndBlankGoals() {
        assertFailureCode(
            """{"summary":"a","steps":[{"id":"same","goal":"g"},{"id":" same ","goal":"h"}]}""",
            AgentPlanParseErrorCode.DUPLICATE_STEP_ID,
        )
        assertFailureCode(
            """{"summary":"a","steps":[{"id":" ","goal":"g"}]}""",
            AgentPlanParseErrorCode.INVALID_STEP_ID,
        )
        assertFailureCode(
            """{"summary":"a","steps":[{"id":"1","goal":"   "}]}""",
            AgentPlanParseErrorCode.INVALID_STEP_GOAL,
        )
    }

    @Test
    fun rejectsEmptyOrOversizedPlan() {
        assertFailureCode(
            """{"summary":"a","steps":[]}""",
            AgentPlanParseErrorCode.INVALID_PLAN_SIZE,
        )
        val steps = (1..21).joinToString(",") { index ->
            "{\"id\":\"$index\",\"goal\":\"goal $index\"}"
        }
        assertFailureCode(
            "{\"summary\":\"a\",\"steps\":[$steps]}",
            AgentPlanParseErrorCode.INVALID_PLAN_SIZE,
        )
    }

    @Test
    fun rejectsPlannerSuppliedRuntimeStatus() {
        assertFailureCode(
            """{"summary":"a","steps":[{"id":"1","goal":"g","status":"done"}]}""",
            AgentPlanParseErrorCode.INVALID_STATUS,
        )
        assertFailureCode(
            """{"summary":"a","steps":[{"id":"1","goal":"g","status":1}]}""",
            AgentPlanParseErrorCode.INVALID_STATUS,
        )
    }

    @Test
    fun serializesStatusesUsingStableProtocolNames() {
        val encoded = Json { encodeDefaults = true }.encodeToString(
            AgentPlan(
                summary = "test",
                steps = AgentPlanStepStatus.entries.mapIndexed { index, status ->
                    AgentPlanStep(id = "$index", goal = "goal", status = status)
                },
                createdAt = 9L,
            ),
        )

        assertTrue(encoded.contains("\"pending\""))
        assertTrue(encoded.contains("\"in_progress\""))
        assertTrue(encoded.contains("\"completed\""))
        assertTrue(encoded.contains("\"blocked\""))
    }

    @Test
    fun updatesStepStatusImmutably() {
        val original = AgentPlan(
            summary = "test",
            steps = listOf(
                AgentPlanStep("one", "first"),
                AgentPlanStep("two", "second"),
            ),
            createdAt = 1L,
        )

        val updated = original.withStepStatus("two", AgentPlanStepStatus.COMPLETED)

        assertNotSame(original, updated)
        assertEquals(AgentPlanStepStatus.PENDING, original.steps[1].status)
        assertEquals(AgentPlanStepStatus.COMPLETED, updated.steps[1].status)
        assertEquals(original.steps[0], updated.steps[0])
    }

    @Test
    fun parseErrorObservationIsBoundedAndDoesNotEchoRawResponse() {
        val raw = "SECRET-UNTRUSTED " + "x".repeat(10_000) + " {}"
        val failure = AgentPlanParser.parse(raw).failure()
        val observation = failure.error.asModelObservation()

        assertEquals(AgentPlanParseErrorCode.EXTRANEOUS_CONTENT, failure.error.code)
        assertFalse(observation.contains("SECRET-UNTRUSTED"))
        assertTrue(observation.length < 1_000)
    }

    private fun assertFailureCode(raw: String, expected: AgentPlanParseErrorCode) {
        assertEquals(expected, AgentPlanParser.parse(raw, createdAt = 1L).failure().error.code)
    }

    private fun AgentPlanParseResult.success(): AgentPlan = when (this) {
        is AgentPlanParseResult.Success -> value
        is AgentPlanParseResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun AgentPlanParseResult.failure(): AgentPlanParseResult.Failure = when (this) {
        is AgentPlanParseResult.Failure -> this
        is AgentPlanParseResult.Success -> throw AssertionError("Expected failure, got $value")
    }
}
