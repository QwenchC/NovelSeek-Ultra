package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSecretSanitizerTest {

    @Test
    fun redactsEverySupportedCredentialWithoutChangingContent() {
        val input = buildJsonObject {
            put("projects", JsonArray(listOf(buildJsonObject { put("id", "project-1") })))
            put("textModelProfiles", JsonArray(listOf(buildJsonObject {
                put("id", "deepseek")
                put("apiKey", "profile-secret")
            })))
            put("textModelConfig", buildJsonObject {
                put("model", "deepseek-chat")
                put("apiKey", "active-secret")
            })
            put("embeddingConfig", buildJsonObject {
                put("model", "embedding")
                put("apiKey", "embedding-secret")
            })
            put("pollinationsKey", "image-secret")
        }

        val redacted = BackupSecretSanitizer.redact(input)

        assertEquals(
            "",
            redacted.getValue("textModelProfiles").jsonArray[0]
                .jsonObject.getValue("apiKey").jsonPrimitive.content,
        )
        assertEquals("", redacted.getValue("textModelConfig").jsonObject.getValue("apiKey").jsonPrimitive.content)
        assertEquals("", redacted.getValue("embeddingConfig").jsonObject.getValue("apiKey").jsonPrimitive.content)
        assertEquals("", redacted.getValue("pollinationsKey").jsonPrimitive.content)
        assertEquals("project-1", redacted.getValue("projects").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("deepseek-chat", redacted.getValue("textModelConfig").jsonObject.getValue("model").jsonPrimitive.content)
    }
}
