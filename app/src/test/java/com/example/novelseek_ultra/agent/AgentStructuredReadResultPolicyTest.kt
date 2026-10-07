package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.writing.TextRangeReader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentStructuredReadResultPolicyTest {
    @Test fun quotedFailurePhrasesInChapterAndOutlineAreSuccessfulData() {
        val observation = Json.encodeToString(TextRangeReader.read("他说：正文为空/过少，不代表无法审阅这封信。"))
        assertTrue(AgentToolOutcome.isSemanticFailure(observation))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("read_chapter", observation))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("get_outline", observation))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("read_chapter", Json.encodeToString(TextRangeReader.read(""))))
    }

    @Test fun searchMatchesMayContainFailurePhrasesAndEmptyPageIsStillSuccessful() {
        val source = TextRangeReader.Source("c", "无法审阅", "chapter", "正文为空/过少")
        val results = Json.encodeToString(TextRangeReader.search(listOf(source), "正文"))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("search_project_text", results))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("read_chapter", results))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("search_project_text",
            Json.encodeToString(TextRangeReader.search(listOf(source), "不存在"))))
    }

    @Test fun actualErrorsMalformedOrWrongShapeCannotMasqueradeAsSuccessfulRead() {
        for (tool in listOf("read_chapter", "get_outline", "search_project_text")) {
            for (response in listOf("执行出错：offset 必须是整数", "内容冲突：来源变化", "未找到章节", "{\"text\":", "{\"error\":\"失败\"}", "[]")) {
                assertTrue(AgentStructuredReadResultPolicy.isSemanticFailure(tool, response))
            }
        }
        assertTrue(AgentStructuredReadResultPolicy.isSemanticFailure("read_chapter",
            "{\"offset\":0,\"endOffset\":100,\"total\":100,\"nextOffset\":null,\"sourceHash\":\"${"a".repeat(64)}\",\"text\":\"短文\"}"))
        assertTrue(AgentStructuredReadResultPolicy.isSemanticFailure("search_project_text",
            "{\"offset\":0,\"total\":3,\"nextOffset\":null,\"query\":\"文字\",\"hits\":[]}"))
        assertTrue(AgentStructuredReadResultPolicy.isSemanticFailure("search_project_text",
            "{\"offset\":0,\"total\":1,\"nextOffset\":null,\"query\":\"文字\",\"hits\":[\"损坏结果\"]}"))
    }

    @Test fun legacyToolFailuresRetainTheirExistingMeaning() {
        assertTrue(AgentStructuredReadResultPolicy.isSemanticFailure("generate_chapter", "生成失败"))
        assertFalse(AgentStructuredReadResultPolicy.isSemanticFailure("list_chapters", "第一章"))
    }
}
