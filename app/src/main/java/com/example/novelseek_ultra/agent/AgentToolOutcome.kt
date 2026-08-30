package com.example.novelseek_ultra.agent

/**
 * The legacy tool registry returns human-readable strings rather than a typed result. Keep the
 * conservative, well-known validation failures from being recorded as successful actions.
 */
object AgentToolOutcome {
    private val knownNotFound = Regex(
        """^未找到(?:章节|角色|副本|弧线|容器|封面|境界|该 profile|第 .+ 段)""",
    )
    private val countedFailureResults = listOf(
        Regex("""^章节摘要：成功 \d+，失败 (\d+)$"""),
        Regex("""^知识库重建：\d+ 章 / \d+ 块 / 失败 (\d+)[\s\S]*$"""),
    )
    private val zeroProductionResults = listOf(
        Regex("""^已生成 0 个副本$"""),
        Regex("""^已在副本生成 0 条弧线$"""),
        Regex("""^已为弧线规划并创建 0 个章节$"""),
        Regex("""^章节摘要：成功 0，失败 0$"""),
        Regex("""^知识库重建：0 章 / 0 块 / 失败 0$"""),
    )
    private val exactFailures = setOf(
        "审阅失败",
        "（审阅失败）",
        "（检索失败）",
        "（未找到结果或搜索不可用）",
        "细化失败",
        "润色失败",
        "境界解析失败",
    )

    fun isSemanticFailure(message: String): Boolean {
        val value = message.trim()
        if (value.isEmpty()) return true
        val hasCountedFailure = countedFailureResults.any { pattern ->
            pattern.matchEntire(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { it > 0 } == true
        }
        return hasCountedFailure ||
            value in exactFailures ||
            zeroProductionResults.any { it.matches(value) } ||
            value.startsWith("执行出错：") ||
            value.startsWith("内容冲突：") ||
            value.startsWith("角色导入失败：") ||
            value.startsWith("角色创建失败：") ||
            value.startsWith("副本创建失败：") ||
            value.startsWith("缺少 ") ||
            value == "无聚焦项目" ||
            value == "项目不存在" ||
            knownNotFound.containsMatchIn(value) ||
            value.startsWith("未找到子境界") ||
            value.startsWith("子境界名称不唯一") ||
            value.startsWith("未产生任何变化") ||
            value.startsWith("境界解析失败") ||
            value.startsWith("未在正文中找到该片段") ||
            value.startsWith("未配置") ||
            value.startsWith("插入失败") ||
            value.startsWith("无 prompt ") ||
            value.startsWith("该角色暂无外貌描述") ||
            value == "该章暂无正文" ||
            value.contains("正文为空/过少") ||
            value.contains("无法审阅") ||
            value.startsWith("paragraphIndex 需") ||
            value.startsWith("position 需") ||
            value.startsWith("engine 仅支持") ||
            value == "保存失败" ||
            value.startsWith("生成失败") ||
            value.startsWith("推文生成失败") ||
            value.startsWith("快照回退失败") ||
            (value.length <= 80 && value.endsWith("生成失败"))
    }
}
