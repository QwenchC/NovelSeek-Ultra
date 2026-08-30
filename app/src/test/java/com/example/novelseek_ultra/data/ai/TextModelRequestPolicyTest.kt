package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextModelRequestPolicyTest {

    @Test
    fun temperatureValidationRejectsNonFiniteAndOutOfRangeValues() {
        listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, -0.01, 2.01).forEach {
            assertFalse(TextModelRequestPolicy.isValidTemperature(it))
        }
        assertTrue(TextModelRequestPolicy.isValidTemperature(0.0))
        assertTrue(TextModelRequestPolicy.isValidTemperature(2.0))
    }

    @Test
    fun deepSeekOutputLimitIsNormalizedButCustomProviderIsUnchanged() {
        val oversized = TextModelConfig(
            provider = "deepseek",
            apiUrl = "https://proxy.example/v1",
            maxOutputTokens = 900_000,
        )
        assertEquals(
            TextModelRequestPolicy.DEEPSEEK_V4_MAX_OUTPUT_TOKENS,
            TextModelRequestPolicy.normalizeForRequest(oversized).maxOutputTokens,
        )
        assertEquals(
            900_000,
            TextModelRequestPolicy.normalizeForRequest(
                oversized.copy(provider = "custom"),
            ).maxOutputTokens,
        )
    }

    @Test
    fun connectionResultIsRejectedAfterProfileSwitchOrEdit() {
        val tested = profile(id = "a", model = "model-a")
        assertTrue(TextModelRequestPolicy.canPublishConnectionTestResult(tested, tested.copy()))
        assertFalse(
            TextModelRequestPolicy.canPublishConnectionTestResult(
                tested,
                profile(id = "b", model = "model-a"),
            ),
        )
        assertFalse(
            TextModelRequestPolicy.canPublishConnectionTestResult(
                tested,
                tested.copy(model = "model-b"),
            ),
        )
    }

    private fun profile(id: String, model: String) = TextModelProfile(
        id = id,
        name = id,
        provider = "custom",
        apiUrl = "https://example.com/v1",
        model = model,
    )
}
