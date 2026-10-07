package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptsTest {
    @Test
    fun plannerIsReadOnlyAndDoesNotExposeRuntimeStatusInItsExample() {
        val prompt = AgentPrompts.plannerSystem("- read_chapter: 读取章节")

        assertTrue(prompt.contains("你不能调用任何工具"))
        assertTrue(prompt.contains("不能把任何步骤标记为已完成、进行中或阻塞"))
        assertTrue(prompt.contains("suggestedTools"))
        assertTrue(prompt.contains("read_chapter"))
        assertFalse(prompt.contains("\"status\":"))
    }

    @Test
    fun dualExecutorUsesASeparateVerifiedStepCompletionAction() {
        val prompt = AgentPrompts.dualExecutorSystem(
            toolDocs = "- read_chapter: 读取章节",
            plan = "当前步骤 p1：读取并核对章节",
        )

        assertTrue(prompt.contains("\"action\":\"complete_plan_step\""))
        assertTrue(prompt.contains("\"planStepId\":\"p1\""))
        assertTrue(prompt.contains("\"summary\":\"已验证的完成结果与证据摘要\""))
        assertTrue(prompt.contains("不要输出或依赖 planStepDone"))
        assertFalse(prompt.contains("\"planStepDone\":"))
        assertTrue(prompt.contains("所有计划步骤均已完成后"))
    }

    @Test
    fun classicPromptKeepsItsOriginalActionProtocol() {
        val prompt = AgentPrompts.system("- read_chapter: 读取章节")

        assertTrue(
            prompt.contains(
                "{\"thought\":\"一句话说明你这步要做什么\",\"action\":\"工具名\",\"args\":{...}}",
            ),
        )
        assertFalse(prompt.contains("complete_plan_step"))
        assertFalse(prompt.contains("planStepId"))
        assertFalse(prompt.contains("推理级别"))
    }

    @Test
    fun reasoningLevelChangesBothPlannerAndExecutorDepthWithoutChangingJsonProtocol() {
        val lowPlanner = AgentPrompts.plannerSystem(
            "- read_chapter: 读取章节",
            AgentReasoningLevels.LOW,
        )
        val highPlanner = AgentPrompts.plannerSystem(
            "- read_chapter: 读取章节",
            AgentReasoningLevels.HIGH,
        )
        val highExecutor = AgentPrompts.dualExecutorSystem(
            toolDocs = "- read_chapter: 读取章节",
            plan = "当前步骤 p1：读取并核对章节",
            reasoningLevel = AgentReasoningLevels.HIGH,
        )

        assertTrue(lowPlanner.contains("【推理级别：low】"))
        assertTrue(lowPlanner.contains("1–6 个"))
        assertTrue(highPlanner.contains("【推理级别：high】"))
        assertTrue(highPlanner.contains("1–20 个"))
        assertTrue(highExecutor.contains("【推理级别：high】"))
        assertTrue(highExecutor.contains("逐项比对成功标准"))
        assertTrue(highExecutor.contains("\"action\":\"complete_plan_step\""))
        assertTrue(highExecutor.contains("pending_review"))
    }

    @Test
    fun chapterBodyToolsOnlyCreateCandidatesUntilUserAcceptance() {
        val prompt = AgentPrompts.system("- generate_chapter: 生成章节")

        listOf(
            "generate_chapter",
            "revise_chapter",
            "replace_in_chapter",
            "edit_paragraph",
            "set_chapter_body",
        ).forEach { tool -> assertTrue("missing candidate boundary for $tool", prompt.contains(tool)) }
        assertTrue(prompt.contains("都只生成待审核候选稿"))
        assertTrue(prompt.contains("pending_review"))
        assertTrue(prompt.contains("不得声称正文"))
        assertTrue(prompt.contains("不得继续调用依赖新正文"))
        assertTrue(prompt.contains("accepted / committed"))
        assertTrue(prompt.contains("rejected、source_changed"))
    }

    @Test
    fun plannerRequiresAcceptedOfficialBodyInChapterStepSuccessCriteria() {
        val prompt = AgentPrompts.plannerSystem("- generate_chapter: 生成章节")

        assertTrue(prompt.contains("候选已被用户接受并成为正式正文"))
        assertTrue(prompt.contains("仅生成 pending_review 候选不算步骤完成"))
    }

    @Test
    fun dualExecutorCannotCompleteOrContinueFromPendingReviewCandidate() {
        val prompt = AgentPrompts.dualExecutorSystem(
            toolDocs = "- generate_chapter: 生成章节",
            plan = "当前步骤 p1：生成并采用章节",
        )

        assertTrue(prompt.contains("正文工具返回 pending_review 时"))
        assertTrue(prompt.contains("不得输出 complete_plan_step"))
        assertTrue(prompt.contains("不得执行依赖新正文的后续步骤"))
        assertTrue(prompt.contains("只有看到 accepted / committed"))
    }

    @Test
    fun systemPromptRoutesLongContentThroughDedicatedGenerationTools() {
        val prompt = AgentPrompts.system("- generate_outline\n- generate_chapter")

        assertTrue(prompt.contains("不要把整篇大纲、整章正文或大批角色档案内联进动作 JSON"))
        assertTrue(prompt.contains("generate_outline"))
        assertTrue(prompt.contains("generate_chapter"))
        assertTrue(prompt.contains("避免动作 JSON 因输出上限被截断"))
    }
}
