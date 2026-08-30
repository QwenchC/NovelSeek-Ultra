package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptBudgeterTest {
    @Test
    fun `huge tool output is capped and explicitly marked`() {
        val steps = listOf(
            step(AgentStep.USER, "读取这一章"),
            step(AgentStep.OBSERVATION, "章".repeat(20_000)),
            step(AgentStep.USER, "不要改正文，只总结冲突"),
        )

        val transcript = AgentTranscriptBudgeter.format(
            steps,
            AgentTranscriptBudget(
                maxSteps = 20,
                maxTotalChars = 400,
                maxCharsPerStep = 80,
            ),
        )

        assertTrue(transcript.length <= 400)
        assertTrue(transcript.contains(AgentTranscriptBudgeter.CONTENT_TRUNCATION_MARKER))
        assertTrue(transcript.contains("【用户指令】不要改正文，只总结冲突"))
    }

    @Test
    fun `newest user instruction survives total budget and output stays chronological`() {
        val steps = listOf(
            step(AgentStep.USER, "最早指令"),
            step(AgentStep.OBSERVATION, "旧工具输出".repeat(2_000)),
            step(AgentStep.MESSAGE, "中间回复"),
            step(AgentStep.USER, "最新指令：停止生成，先汇报当前进度"),
        )

        val transcript = AgentTranscriptBudgeter.format(
            steps,
            AgentTranscriptBudget(
                maxSteps = 4,
                maxTotalChars = 85,
                maxCharsPerStep = 2_000,
            ),
        )

        assertTrue(transcript.length <= 85)
        assertTrue(transcript.contains("【历史已截断】"))
        assertTrue(transcript.contains("最新指令：停止生成，先汇报当前进度"))
        assertFalse(transcript.contains("最早指令"))
        val replyIndex = transcript.indexOf("中间回复")
        val latestIndex = transcript.indexOf("最新指令")
        if (replyIndex >= 0) assertTrue(replyIndex < latestIndex)
    }

    @Test
    fun `action renders tool and status while labels match controller`() {
        val steps = listOf(
            step(AgentStep.THOUGHT, "先检查", id = "1"),
            AgentStep(
                id = "2",
                type = AgentStep.ACTION,
                text = "{\"chapter_id\":\"c1\"}",
                tool = "read_chapter",
                actionStatus = AgentStep.ACTION_SUCCEEDED,
            ),
            step(AgentStep.OBSERVATION, "读取完成", id = "3"),
            step(AgentStep.QUESTION, "是否继续？", id = "4"),
            step(AgentStep.ANSWER, "继续", id = "5"),
            step(AgentStep.ERROR, "示例错误", id = "6"),
        )

        val transcript = AgentTranscriptBudgeter.format(steps)

        assertEquals(
            listOf(
                "【你的思考】先检查",
                "【你执行的动作[id=2][read_chapter][状态=succeeded]】{\"chapter_id\":\"c1\"}",
                "【结果[id=3]】读取完成",
                "【你向用户提问[id=4]】是否继续？",
                "【用户回答[id=5]】继续",
                "【错误[id=6]】示例错误",
            ).joinToString("\n"),
            transcript,
        )
    }

    @Test
    fun `truncation never leaves an unpaired surrogate`() {
        val transcript = AgentTranscriptBudgeter.format(
            listOf(step(AgentStep.OBSERVATION, "abc😀def".repeat(3))),
            AgentTranscriptBudget(
                maxSteps = 1,
                maxTotalChars = 100,
                // Leaves four UTF-16 code units before the marker, exactly between 😀's pair.
                maxCharsPerStep = AgentTranscriptBudgeter.CONTENT_TRUNCATION_MARKER.length + 4,
            ),
        )

        assertTrue(transcript.contains(AgentTranscriptBudgeter.CONTENT_TRUNCATION_MARKER))
        assertFalse(hasUnpairedSurrogate(transcript))
    }

    @Test
    fun `max steps keeps newest entries in chronological order and marks omission`() {
        val transcript = AgentTranscriptBudgeter.format(
            (1..5).map { step(AgentStep.MESSAGE, "回复$it", id = it.toString()) },
            AgentTranscriptBudget(
                maxSteps = 2,
                maxTotalChars = 200,
                maxCharsPerStep = 100,
            ),
        )

        assertTrue(transcript.contains("已省略 3 条较早记录"))
        assertFalse(transcript.contains("回复3"))
        assertTrue(transcript.indexOf("回复4") < transcript.indexOf("回复5"))
    }

    @Test
    fun `required evidence is never clipped and remains traceable`() {
        val body = "证据".repeat(10_000)
        val transcript = AgentTranscriptBudgeter.formatRequired(
            listOf(
                AgentStep(
                    id = "action-9",
                    type = AgentStep.ACTION,
                    text = "写入候选稿",
                    tool = "generate_chapter",
                    actionStatus = AgentStep.ACTION_AWAITING_REVIEW,
                    planId = "plan-2",
                    planStepId = "p3",
                    resolvedProjectId = "project-1",
                ),
                AgentStep(
                    id = "result-9",
                    type = AgentStep.OBSERVATION,
                    text = body,
                    resultForActionId = "action-9",
                    resultKind = AgentStep.RESULT_PENDING_REVIEW,
                ),
            ),
        )

        assertTrue(transcript.contains("[id=action-9]"))
        assertTrue(transcript.contains("[计划=plan-2/p3]"))
        assertTrue(transcript.contains("[项目=project-1]"))
        assertTrue(transcript.contains("[动作=action-9]"))
        assertTrue(transcript.contains("[证据=pending_review]"))
        assertTrue(transcript.endsWith(body))
        assertFalse(transcript.contains(AgentTranscriptBudgeter.CONTENT_TRUNCATION_MARKER))
    }

    private fun step(type: String, text: String, id: String = "step") = AgentStep(
        id = id,
        type = type,
        text = text,
    )

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                    index += 2
                }
                char.isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }
}
