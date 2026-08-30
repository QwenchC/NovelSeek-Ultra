package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.model.TextModelThinkingModes
import org.junit.Assert.assertEquals
import org.junit.Test

class TextModelMigrationTest {
    @Test
    fun `retired official built in profile and its active config migrate to v4 non thinking`() {
        val profile = TextModelProfile(
            id = "deepseek",
            name = "DeepSeek",
            provider = "deepseek",
            apiUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            builtIn = true,
        )
        val config = TextModelConfig(
            provider = "deepseek",
            apiUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )

        val result = TextModelMigration.migrate(listOf(profile), "deepseek", config)

        assertEquals("deepseek-v4-flash", result.profiles.single().model)
        assertEquals(TextModelThinkingModes.DISABLED, result.profiles.single().thinkingMode)
        assertEquals("deepseek-v4-flash", result.config?.model)
        assertEquals(TextModelThinkingModes.DISABLED, result.config?.thinkingMode)
    }

    @Test
    fun `custom legacy alias is never rewritten`() {
        val custom = TextModelProfile(
            id = "custom",
            name = "Proxy",
            provider = "deepseek",
            apiUrl = "https://proxy.example/v1",
            model = "deepseek-chat",
            builtIn = false,
        )

        val result = TextModelMigration.migrate(listOf(custom), "custom", null)

        assertEquals(custom, result.profiles.single())
    }

    @Test
    fun `retired official reasoner migrates with thinking enabled`() {
        val profile = TextModelProfile(
            id = "deepseek",
            name = "DeepSeek",
            provider = " DeepSeek ",
            apiUrl = "https://api.deepseek.com",
            model = " deepseek-reasoner ",
            builtIn = true,
        )
        val config = TextModelConfig(
            provider = "DeepSeek ",
            apiUrl = "https://api.deepseek.com",
            model = "deepseek-reasoner ",
        )

        val result = TextModelMigration.migrate(listOf(profile), "deepseek", config)

        assertEquals("deepseek-v4-flash", result.profiles.single().model)
        assertEquals(TextModelThinkingModes.ENABLED, result.profiles.single().thinkingMode)
        assertEquals(TextModelThinkingModes.ENABLED, result.config?.thinkingMode)
    }

    @Test
    fun `stale active config migrates after official built in profile already reached v4`() {
        val profile = TextModelProfile(
            id = "deepseek",
            name = "DeepSeek",
            provider = "deepseek",
            apiUrl = "https://api.deepseek.com/v1",
            model = TextModelMigration.CURRENT_MODEL,
            builtIn = true,
        )
        val staleConfig = TextModelConfig(
            provider = "deepseek",
            apiUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        )

        val result = TextModelMigration.migrate(listOf(profile), "deepseek", staleConfig)

        assertEquals(profile, result.profiles.single())
        assertEquals(TextModelMigration.CURRENT_MODEL, result.config?.model)
        assertEquals(TextModelThinkingModes.DISABLED, result.config?.thinkingMode)
    }
}
