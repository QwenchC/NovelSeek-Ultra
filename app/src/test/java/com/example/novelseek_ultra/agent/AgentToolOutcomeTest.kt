package com.example.novelseek_ultra.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolOutcomeTest {
    @Test
    fun recognizesKnownLegacyFailureMessages() {
        listOf(
            "",
            "缺少 chapterId",
            "无聚焦项目",
            "项目不存在",
            "未找到章节",
            "未找到第 3 段（共 2 段）",
            "未配置文本模型",
            "插入失败（参考章节不存在）",
            "章节生成失败",
            "paragraphIndex 需≥1",
            "engine 仅支持 pollinations / comfyui",
            "执行出错：timeout",
            "内容冲突：章节已被修改或删除",
            "角色导入失败：大纲中未提取到可导入角色",
            "角色创建失败：项目已删除或同名角色已存在",
            "副本创建失败：项目已删除",
            "该章暂无正文",
            "第3章正文为空/过少，已跳过",
            "（暂无章节，无法审阅）",
            "保存失败",
            "审阅失败",
            "（审阅失败）",
            "（检索失败）",
            "（未找到结果或搜索不可用）",
            "细化失败",
            "润色失败",
            "境界解析失败",
            "境界解析失败（需为 JSON 数组）",
            "未找到子境界：金丹后期",
            "子境界名称不唯一，请同时指定唯一的 realm：后期",
            "未产生任何变化：项目字段与当前值相同",
            "已生成 0 个副本",
            "已在副本生成 0 条弧线",
            "已为弧线规划并创建 0 个章节",
            "章节摘要：成功 0，失败 0",
            "知识库重建：0 章 / 0 块 / 失败 0",
            "快照回退失败：未找到快照",
        ).forEach { message ->
            assertTrue("Expected semantic failure: $message", AgentToolOutcome.isSemanticFailure(message))
        }
    }

    @Test
    fun keepsEmptyCollectionsIdempotentResultsAndContentAsSuccess() {
        listOf(
            "（暂无项目）",
            "（无章节）",
            "已有推文，已跳过",
            "已更新章节",
            "未找到真相，是这一章刻意保留的悬念。",
        ).forEach { message ->
            assertFalse("Expected semantic success: $message", AgentToolOutcome.isSemanticFailure(message))
        }
    }

    @Test
    fun countedBatchFailuresAreFailuresButZeroFailuresAreSuccess() {
        listOf(
            "章节摘要：成功 0，失败 4",
            "章节摘要：成功 8，失败 1",
            "知识库重建：0 章 / 0 块 / 失败 4（provider timeout）",
            "知识库重建：0 章 / 0 块 / 失败 4（provider\ntimeout）",
            "知识库重建：8 章 / 42 块 / 失败 1",
        ).forEach { message ->
            assertTrue("Expected counted failure: $message", AgentToolOutcome.isSemanticFailure(message))
        }

        listOf(
            "章节摘要：成功 8，失败 0",
            "知识库重建：8 章 / 42 块 / 失败 0",
        ).forEach { message ->
            assertFalse("Expected successful batch result: $message", AgentToolOutcome.isSemanticFailure(message))
        }
    }
}
