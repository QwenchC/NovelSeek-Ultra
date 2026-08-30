package com.example.novelseek_ultra.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolArgumentPolicyTest {
    @Test
    fun `overwrite tools reject missing blank and non-string text`() {
        val tools = listOf("set_world_setting", "set_timeline", "set_outline", "set_chapter_body")
        tools.forEach { tool ->
            assertNotNull(AgentToolArgumentPolicy.validationError(tool, buildJsonObject {}))
            assertNotNull(
                AgentToolArgumentPolicy.validationError(
                    tool,
                    buildJsonObject { put("text", "   ") },
                ),
            )
            assertNotNull(
                AgentToolArgumentPolicy.validationError(
                    tool,
                    Json.parseToJsonElement("{\"text\":123}").jsonObject,
                ),
            )
            assertNull(
                AgentToolArgumentPolicy.validationError(
                    tool,
                    buildJsonObject { put("text", "保留内容") },
                ),
            )
        }
    }

    @Test
    fun `chapter replacement distinguishes omission from explicit deletion`() {
        val missing = buildJsonObject {}
        val nonString = Json.parseToJsonElement("{\"replace\":false}").jsonObject
        val deletion = buildJsonObject { put("replace", "") }
        val replacement = buildJsonObject { put("replace", "新文本") }

        assertNotNull(AgentToolArgumentPolicy.validationError("replace_in_chapter", missing))
        assertNotNull(AgentToolArgumentPolicy.validationError("replace_in_chapter", nonString))
        assertNull(AgentToolArgumentPolicy.validationError("replace_in_chapter", deletion))
        assertTrue(AgentToolArgumentPolicy.requiresAlwaysConfirmation("replace_in_chapter", deletion))
        assertFalse(AgentToolArgumentPolicy.requiresAlwaysConfirmation("replace_in_chapter", replacement))
    }

    @Test
    fun `project deletion and restore always require confirmation`() {
        val args = buildJsonObject {}
        assertTrue(AgentToolArgumentPolicy.requiresAlwaysConfirmation("delete_project", args))
        assertTrue(AgentToolArgumentPolicy.requiresAlwaysConfirmation("restore_snapshot", args))
    }

    @Test
    fun `update tools reject an empty patch and accept a typed patch`() {
        val cases = listOf(
            "update_arc" to buildJsonObject { put("title", "新弧线") },
            "update_volume" to buildJsonObject { put("name", "新副本") },
            "update_chapter" to buildJsonObject { put("goal", "新目标") },
            "update_character" to buildJsonObject { put("isProtagonist", true) },
            "update_project" to buildJsonObject { put("targetWordCount", 100_000) },
            "update_container" to buildJsonObject { put("autoUpdate", false) },
            "set_kb_features" to buildJsonObject { put("knowledgeBase", true) },
        )

        cases.forEach { (tool, validArgs) ->
            assertNotNull(AgentToolArgumentPolicy.validationError(tool, buildJsonObject {}))
            assertNull(AgentToolArgumentPolicy.validationError(tool, validArgs))
        }
    }
}
