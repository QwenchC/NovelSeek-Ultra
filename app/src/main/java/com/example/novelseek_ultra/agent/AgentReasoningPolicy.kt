package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentReasoningLevels

/**
 * Provider-neutral depth controls for the dual planner/executor pair.
 *
 * This intentionally does not mutate the text model's thinking switch: OpenAI-compatible
 * providers do not share one portable reasoning-effort parameter. Instead it changes the plan
 * bound, retained evidence and verification discipline for both roles.
 */
data class AgentReasoningProfile(
    val level: String,
    val plannerMaxSteps: Int,
    /** Share of request space left after fixed prompts; keeps the three depths distinct when tight. */
    val transcriptSharePercent: Int,
    val plannerTranscriptBudget: AgentTranscriptBudget,
    val executorTranscriptBudget: AgentTranscriptBudget,
    val plannerDirective: String,
    val executorDirective: String,
)

object AgentReasoningPolicy {
    fun forLevel(value: String?): AgentReasoningProfile = when (
        AgentReasoningLevels.normalize(value)
    ) {
        AgentReasoningLevels.LOW -> LOW
        AgentReasoningLevels.HIGH -> HIGH
        else -> MEDIUM
    }

    private val LOW = AgentReasoningProfile(
        level = AgentReasoningLevels.LOW,
        plannerMaxSteps = 6,
        transcriptSharePercent = 50,
        plannerTranscriptBudget = AgentTranscriptBudget(
            maxSteps = 18,
            maxTotalChars = 8_000,
            maxCharsPerStep = 2_000,
        ),
        executorTranscriptBudget = AgentTranscriptBudget(
            maxSteps = 36,
            maxTotalChars = 18_000,
            maxCharsPerStep = 4_000,
        ),
        plannerDirective =
            "采用直接、低开销的规划：只保留完成目标必需的依赖和验证点，不扩写可选工作。" +
                "简单任务优先少步骤；安全确认、候选审核和成功标准不得省略。",
        executorDirective =
            "优先选择满足当前步骤的最直接安全动作，减少重复查询和无收益调用；" +
                "仍必须用真实结果验证完成标准，不得降低确认与候选审核边界。",
    )

    private val MEDIUM = AgentReasoningProfile(
        level = AgentReasoningLevels.MEDIUM,
        plannerMaxSteps = 12,
        transcriptSharePercent = 75,
        plannerTranscriptBudget = AgentTranscriptBudget(
            maxSteps = 30,
            maxTotalChars = 12_000,
            maxCharsPerStep = 3_000,
        ),
        executorTranscriptBudget = AgentTranscriptBudget(),
        plannerDirective =
            "在执行成本、依赖关系和可验证性之间保持平衡；复杂目标拆成清晰阶段，" +
                "简单目标不要人为膨胀步骤。",
        executorDirective =
            "每次动作前核对当前步骤与已有状态，动作后依据工具结果验证完成标准；" +
                "遇到缺失信息或不确定结果时先查询或询问用户。",
    )

    private val HIGH = AgentReasoningProfile(
        level = AgentReasoningLevels.HIGH,
        plannerMaxSteps = 20,
        transcriptSharePercent = 100,
        plannerTranscriptBudget = AgentTranscriptBudget(
            maxSteps = 50,
            maxTotalChars = 24_000,
            maxCharsPerStep = 6_000,
        ),
        executorTranscriptBudget = AgentTranscriptBudget(
            maxSteps = 90,
            maxTotalChars = 48_000,
            maxCharsPerStep = 12_000,
        ),
        plannerDirective =
            "进行深度规划：核对前置依赖、项目不变量、成本与确认点，并为失败恢复和结果验证" +
                "保留可审计步骤；但简单任务仍应保持简洁。",
        executorDirective =
            "采用深度验证：动作前核对计划、实时状态和既有证据，优先可逆的小范围操作；" +
                "动作后逐项比对成功标准，必要时读取最新状态交叉验证，绝不凭推测完成步骤。",
    )
}
