package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit allow-list redaction used by the default backup path. */
internal object BackupSecretSanitizer {

    fun redact(data: JsonObject): JsonObject {
        val sanitized = data.toMutableMap()
        (data["textModelProfiles"] as? JsonArray)?.let { profiles ->
            sanitized["textModelProfiles"] = JsonArray(profiles.map { entry ->
                val profile = entry as? JsonObject ?: return@map entry
                JsonObject(profile.toMutableMap().apply { put("apiKey", JsonPrimitive("")) })
            })
        }
        redactObjectField(data, sanitized, "textModelConfig")
        redactObjectField(data, sanitized, "embeddingConfig")
        // Keep the field for PC/Android settings-shape compatibility, but never its value.
        sanitized["pollinationsKey"] = JsonPrimitive("")
        return JsonObject(sanitized)
    }

    private fun redactObjectField(
        source: JsonObject,
        destination: MutableMap<String, kotlinx.serialization.json.JsonElement>,
        field: String,
    ) {
        val obj = source[field] as? JsonObject ?: return
        destination[field] = JsonObject(obj.toMutableMap().apply {
            put("apiKey", JsonPrimitive(""))
        })
    }
}
