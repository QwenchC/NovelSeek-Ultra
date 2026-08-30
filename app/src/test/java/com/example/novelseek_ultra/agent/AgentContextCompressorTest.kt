package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentSessionMemory
import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompressorTest {
    @Test
    fun `compaction keeps the full audit chain and replaces only replay prefix`() {
        val steps = baseSteps(50).toMutableList().also {
            it[30] = step(30, AgentStep.USER, "最新完整指令")
        }.toList()
        val original = steps.map { it.copy() }

        val memory = compact(steps)

        assertNotNull(memory)
        assertEquals("s29", memory!!.compactedThroughStepId)
        assertEquals(original, steps)
        assertEquals(steps.drop(30), AgentContextCompressor.replaySteps(steps, memory))
    }

    @Test
    fun `active plan source and evidence remain outside compacted prefix`() {
        val steps = baseSteps(50).toMutableList().also {
            it[12] = step(12, AgentStep.USER, "计划源指令")
            it[18] = step(18, AgentStep.ACTION, "读取", tool = "read_chapter").copy(
                actionStatus = AgentStep.ACTION_SUCCEEDED,
                planId = "plan-1",
                planStepId = "p1",
            )
            it[19] = step(19, AgentStep.OBSERVATION, "证据").copy(
                resultForActionId = "s18",
                resultKind = AgentStep.RESULT_COMMITTED,
            )
        }.toList()
        val plan = AgentPlan(
            summary = "检查并续写",
            steps = listOf(
                AgentPlanStep("p1", "检查", status = AgentPlanStepStatus.IN_PROGRESS),
            ),
            createdAt = 1L,
            sourceCommandId = "s12",
            planId = "plan-1",
        )

        val memory = compact(steps, activePlan = plan)

        assertNotNull(memory)
        assertTrue(boundary(steps, memory!!) < 12)
        val replay = AgentContextCompressor.replaySteps(steps, memory)
        assertTrue(replay.any { it.id == "s12" })
        assertTrue(replay.any { it.id == "s18" })
        assertTrue(replay.any { it.id == "s19" })
    }

    @Test
    fun `nonterminal interrupted review and unanswered question each stop the boundary`() {
        fun boundaryFor(
            changed: (MutableList<AgentStep>) -> Unit,
            pendingReview: AgentPendingReview? = null,
            pendingPrompt: Boolean = false,
        ): Int {
            val steps = baseSteps(50).toMutableList().also(changed).toList()
            return boundary(
                steps,
                compact(
                    steps,
                    pendingReview = pendingReview,
                    hasPendingPrompt = pendingPrompt,
                )!!,
            )
        }

        assertTrue(boundaryFor({ it[14] = step(14, AgentStep.USER, "最新指令") }) < 14)
        assertTrue(
            boundaryFor({
                it[10] = step(10, AgentStep.ACTION, "运行中").copy(
                    actionStatus = AgentStep.ACTION_RUNNING,
                )
            }) < 10,
        )
        assertTrue(
            boundaryFor({
                it[9] = step(9, AgentStep.ACTION, "被中断").copy(
                    actionStatus = AgentStep.ACTION_INTERRUPTED,
                )
            }) < 9,
        )
        val review = AgentPendingReview("p", "c", "r", "candidate", "s8")
        assertTrue(
            boundaryFor(
                changed = {
                    it[8] = step(8, AgentStep.ACTION, "待审核").copy(
                        actionStatus = AgentStep.ACTION_SUCCEEDED,
                    )
                    it[20] = step(20, AgentStep.OBSERVATION, "候选稿").copy(
                        resultForActionId = "s8",
                        resultKind = AgentStep.RESULT_PENDING_REVIEW,
                    )
                },
                pendingReview = review,
            ) < 8,
        )
        assertTrue(
            boundaryFor(
                changed = { it[11] = step(11, AgentStep.QUESTION, "要继续吗？") },
                pendingPrompt = true,
            ) < 11,
        )
    }

    @Test
    fun `causal action result and question answer bundles are never split`() {
        val actionSteps = baseSteps(50).toMutableList().also {
            it[25] = step(25, AgentStep.ACTION, "动作").copy(
                actionStatus = AgentStep.ACTION_SUCCEEDED,
            )
            it[36] = step(36, AgentStep.OBSERVATION, "迟到结果").copy(
                resultForActionId = "s25",
                resultKind = AgentStep.RESULT_COMMITTED,
            )
        }.toList()
        val actionMemory = compact(actionSteps)!!
        assertTrue(boundary(actionSteps, actionMemory) < 25)
        assertTrue(AgentContextCompressor.replaySteps(actionSteps, actionMemory).any { it.id == "s25" })

        val qaSteps = baseSteps(50).toMutableList().also {
            it[30] = step(30, AgentStep.QUESTION, "确认范围？")
            it[37] = step(37, AgentStep.ANSWER, "只改第三章")
        }.toList()
        val qaMemory = compact(qaSteps)!!
        assertTrue(boundary(qaSteps, qaMemory) < 30)
        val replay = AgentContextCompressor.replaySteps(qaSteps, qaMemory)
        assertTrue(replay.any { it.id == "s30" })
        assertTrue(replay.any { it.id == "s37" })
    }

    @Test
    fun `digest invalidates memory after any represented step changes`() {
        val steps = baseSteps(50)
        val memory = compact(steps)!!
        val changed = steps.toMutableList().also {
            it[0] = it[0].copy(resultKind = AgentStep.RESULT_COMMITTED)
        }

        assertEquals(
            AgentSessionMemory(),
            AgentContextCompressor.normalizeMemory(changed, memory),
        )
    }

    @Test
    fun `repeated compaction advances boundary and saturates bounded summary safely`() {
        val firstSteps = baseSteps(55).toMutableList().also {
            it[20] = step(20, AgentStep.USER, "第一目标")
        }.toList()
        val first = compact(firstSteps)!!
        val expanded = baseSteps(90).toMutableList().also {
            it[20] = step(20, AgentStep.USER, "第一目标")
            it[60] = step(60, AgentStep.USER, "第二目标")
            for (index in 21 until 60) {
                if (index != 20) it[index] = it[index].copy(text = "😀".repeat(1_000))
            }
        }.toList()

        val second = compact(expanded, memory = first)!!

        assertTrue(boundary(expanded, second) > boundary(firstSteps, first))
        assertEquals(2, second.compressionCount)
        assertTrue(second.summary.length <= AgentContextCompressor.MAX_SUMMARY_CHARS)
        assertFalse(hasUnpairedSurrogate(second.summary))
    }

    @Test
    fun `duplicate persistent step ids disable compression`() {
        val steps = baseSteps(30).toMutableList().also { it[1] = it[1].copy(id = "s0") }
        assertNull(compact(steps))
    }

    @Test
    fun `completed plan no longer pins old source while newest sensitive audit survives quotas`() {
        val completedPlanSteps = baseSteps(60).toMutableList().also {
            it[12] = step(12, AgentStep.USER, "旧计划目标")
            it[45] = step(45, AgentStep.USER, "当前目标")
        }.toList()
        val completedPlan = AgentPlan(
            summary = "已完成",
            steps = listOf(
                AgentPlanStep("p1", "完成", status = AgentPlanStepStatus.COMPLETED),
            ),
            createdAt = 1L,
            sourceCommandId = "s12",
            planId = "old-plan",
        )
        val completedMemory = compact(completedPlanSteps, activePlan = completedPlan)!!
        assertTrue(boundary(completedPlanSteps, completedMemory) > 12)

        val auditSteps = buildList {
            repeat(40) { index ->
                add(
                    AgentStep(
                        id = "delete-$index",
                        type = AgentStep.ACTION,
                        text = "删除范围 $index",
                        tool = "delete_project",
                        actionStatus = if (index == 39) {
                            AgentStep.ACTION_DENIED
                        } else {
                            AgentStep.ACTION_SUCCEEDED
                        },
                    ),
                )
                add(
                    AgentStep(
                        id = "decision-$index",
                        type = AgentStep.OBSERVATION,
                        text = if (index == 39) "用户拒绝删除" else "已提交",
                        resultForActionId = "delete-$index",
                        resultKind = AgentStep.RESULT_COMMITTED,
                    ),
                )
            }
            repeat(16) { add(step(100 + it, AgentStep.MESSAGE, "尾部$it")) }
        }
        val auditMemory = compact(auditSteps)!!
        assertTrue(auditMemory.summary.contains("action=delete-39"))
        assertTrue(auditMemory.summary.contains("evidence=decision-39"))
        assertTrue(auditMemory.summary.contains("用户拒绝删除"))
    }

    private fun compact(
        steps: List<AgentStep>,
        memory: AgentSessionMemory = AgentSessionMemory(),
        activePlan: AgentPlan? = null,
        pendingReview: AgentPendingReview? = null,
        hasPendingPrompt: Boolean = false,
    ): AgentSessionMemory? = AgentContextCompressor.compact(
        steps = steps,
        memory = memory,
        activePlan = activePlan,
        pendingReview = pendingReview,
        hasPendingPrompt = hasPendingPrompt,
        sensitiveTools = setOf("delete_project"),
        compressedAt = "2026-08-30T12:00:00Z",
    )

    private fun baseSteps(count: Int): List<AgentStep> =
        (0 until count).map { step(it, AgentStep.MESSAGE, "记录$it") }

    private fun step(
        index: Int,
        type: String,
        text: String,
        tool: String = "",
    ) = AgentStep(id = "s$index", type = type, text = text, tool = tool)

    private fun boundary(steps: List<AgentStep>, memory: AgentSessionMemory): Int =
        steps.indexOfFirst { it.id == memory.compactedThroughStepId }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            when {
                value[index].isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                    index += 2
                }
                value[index].isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }
}
