package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile

/** Provider-bound request limits that must hold immediately before payload construction. */
object TextModelRequestPolicy {
    const val MIN_TEMPERATURE = 0.0
    const val MAX_TEMPERATURE = 2.0
    const val DEEPSEEK_V4_MAX_OUTPUT_TOKENS = 384_000

    fun isValidTemperature(value: Double?): Boolean =
        value != null && value.isFinite() && value in MIN_TEMPERATURE..MAX_TEMPERATURE

    /**
     * Rejects invalid scalar values and caps provider-specific limits before both budgeting and
     * serialization. This is deliberately repeated at the HTTP boundary even when the UI already
     * validates input, because profiles can also arrive through imports or legacy persisted state.
     */
    fun normalizeForRequest(config: TextModelConfig): TextModelConfig {
        require(isValidTemperature(config.temperature)) {
            "模型 temperature 必须是 0 到 2 之间的有限数值"
        }
        return if (isDirectDeepSeek(config)) {
            config.copy(
                maxOutputTokens = config.maxOutputTokens.coerceAtMost(
                    DEEPSEEK_V4_MAX_OUTPUT_TOKENS,
                ),
            )
        } else {
            config
        }
    }

    fun canPublishConnectionTestResult(
        testedProfile: TextModelProfile?,
        currentProfile: TextModelProfile?,
    ): Boolean = testedProfile == currentProfile
}
