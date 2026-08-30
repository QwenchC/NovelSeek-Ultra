package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelProfile
import com.example.novelseek_ultra.data.model.TextModelThinkingModes

/** Narrow migration for the retired official DeepSeek profile; custom aliases remain untouched. */
internal object TextModelMigration {
    data class Result(
        val profiles: List<TextModelProfile>,
        val config: TextModelConfig?,
    )

    fun migrate(
        profiles: List<TextModelProfile>,
        activeProfileId: String?,
        config: TextModelConfig?,
    ): Result {
        var activeProfileIsOfficialBuiltIn = false
        val migratedProfiles = profiles.map { profile ->
            if (profile.id == activeProfileId && isOfficialBuiltInProfile(profile)) {
                activeProfileIsOfficialBuiltIn = true
            }
            if (isRetiredOfficialProfile(profile)) {
                profile.copy(
                    model = CURRENT_MODEL,
                    thinkingMode = migratedThinkingMode(profile.model),
                )
            } else {
                profile
            }
        }
        val migratedConfig = if (
            activeProfileIsOfficialBuiltIn && config != null && isRetiredOfficialConfig(config)
        ) {
            config.copy(
                model = CURRENT_MODEL,
                thinkingMode = migratedThinkingMode(config.model),
            )
        } else {
            config
        }
        return Result(migratedProfiles, migratedConfig)
    }

    private fun isOfficialBuiltInProfile(profile: TextModelProfile): Boolean =
        profile.id == "deepseek" &&
            profile.builtIn &&
            profile.provider.trim().equals("deepseek", ignoreCase = true) &&
            isOfficialEndpoint(profile.apiUrl)

    private fun isRetiredOfficialProfile(profile: TextModelProfile): Boolean =
        isOfficialBuiltInProfile(profile) && profile.model.trim() in RETIRED_MODELS

    private fun isRetiredOfficialConfig(config: TextModelConfig): Boolean =
        config.provider.trim().equals("deepseek", ignoreCase = true) &&
            config.model.trim() in RETIRED_MODELS &&
            isOfficialEndpoint(config.apiUrl)

    private fun isOfficialEndpoint(value: String): Boolean =
        value.trim().lowercase().trimEnd('/').let { endpoint ->
            endpoint == "https://api.deepseek.com" || endpoint == "https://api.deepseek.com/v1"
        }

    private fun migratedThinkingMode(model: String): String =
        if (model.trim() == RETIRED_REASONER_MODEL) {
            TextModelThinkingModes.ENABLED
        } else {
            TextModelThinkingModes.DISABLED
        }

    const val CURRENT_MODEL = "deepseek-v4-flash"
    private const val RETIRED_CHAT_MODEL = "deepseek-chat"
    private const val RETIRED_REASONER_MODEL = "deepseek-reasoner"
    private val RETIRED_MODELS = setOf(RETIRED_CHAT_MODEL, RETIRED_REASONER_MODEL)
}
