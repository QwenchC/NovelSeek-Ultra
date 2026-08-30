package com.example.novelseek_ultra.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TextModelConfigSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy configs receive conservative request budget defaults`() {
        val config = json.decodeFromString<TextModelConfig>(
            """{"provider":"custom","model":"legacy-model"}""",
        )
        val profile = json.decodeFromString<TextModelProfile>(
            """{"id":"legacy","name":"Legacy","provider":"custom"}""",
        )

        assertEquals(64_000, config.contextWindowTokens)
        assertEquals(8_000, config.maxOutputTokens)
        assertEquals(TextModelThinkingModes.AUTO, config.thinkingMode)
        assertEquals(64_000, profile.contextWindowTokens)
        assertEquals(8_000, profile.maxOutputTokens)
        assertEquals(TextModelThinkingModes.AUTO, profile.thinkingMode)
    }

    @Test
    fun `custom request limits survive persistence round trip`() {
        val profile = TextModelProfile(
            id = "custom",
            name = "Custom",
            provider = "deepseek",
            contextWindowTokens = 128_000,
            maxOutputTokens = 12_000,
            thinkingMode = TextModelThinkingModes.DISABLED,
        )

        assertEquals(
            profile,
            json.decodeFromString<TextModelProfile>(json.encodeToString(profile)),
        )
    }
}
