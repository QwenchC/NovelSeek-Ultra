package com.example.novelseek_ultra.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Safety policy for arguments whose omission could otherwise turn an overwrite into data loss.
 *
 * Most legacy tools validate their own arguments when they run. These checks deliberately happen
 * before confirmation/auto-approval as well, so a malformed model action is never presented as a
 * legitimate destructive operation.
 */
internal object AgentToolArgumentPolicy {
    private data class PatchSchema(
        val strings: Set<String> = emptySet(),
        val integers: Set<String> = emptySet(),
        val booleans: Set<String> = emptySet(),
    )

    private val nonEmptyTextOverwriteTools = setOf(
        "set_world_setting",
        "set_timeline",
        "set_outline",
        "set_chapter_body",
    )

    private val unconditionalConfirmationTools = setOf(
        "delete_project",
        "restore_snapshot",
    )

    private val patchSchemas = mapOf(
        "update_arc" to PatchSchema(
            strings = setOf("title", "summary", "status"),
            integers = setOf("chapterCount"),
        ),
        "update_volume" to PatchSchema(strings = setOf("name", "description", "realmPlan")),
        "update_chapter" to PatchSchema(strings = setOf("title", "goal", "conflict")),
        "update_character" to PatchSchema(
            strings = setOf(
                "name", "gender", "role", "personality", "background", "motivation",
                "appearance", "realm", "subRealm",
            ),
            booleans = setOf("isProtagonist"),
        ),
        "update_project" to PatchSchema(
            strings = setOf("title", "genre", "description", "status"),
            integers = setOf("targetWordCount"),
        ),
        "update_container" to PatchSchema(
            strings = setOf("name"),
            booleans = setOf(
                "autoUpdate", "affectsGeneration", "affectsVolumeGeneration", "affectsArcGeneration",
            ),
        ),
        "set_kb_features" to PatchSchema(
            booleans = setOf("knowledgeBase", "summaries", "entities"),
        ),
    )

    fun validationError(toolName: String, args: JsonObject): String? = when {
        toolName in nonEmptyTextOverwriteTools && nonBlankString(args, "text") == null ->
            "缺少 text（必须显式提供非空字符串；已拒绝覆盖）"

        toolName == "replace_in_chapter" && explicitString(args, "replace") == null ->
            "缺少 replace（必须显式提供字符串；空字符串表示删除）"

        toolName in patchSchemas && !hasUsablePatch(args, checkNotNull(patchSchemas[toolName])) ->
            "缺少可更新字段（已拒绝无变化操作）"

        else -> null
    }

    /** Empty/blank replacement is an explicit deletion and must bypass project auto-approval. */
    fun requiresAlwaysConfirmation(toolName: String, args: JsonObject): Boolean =
        toolName in unconditionalConfirmationTools ||
            (toolName == "replace_in_chapter" && explicitString(args, "replace")?.isBlank() == true)

    fun alwaysRequiresConfirmationByName(toolName: String): Boolean =
        toolName in unconditionalConfirmationTools

    fun nonBlankString(args: JsonObject, key: String): String? =
        explicitString(args, key)?.takeIf { it.isNotBlank() }

    /** Distinguishes an explicitly supplied empty string from a missing/null/non-string value. */
    fun explicitString(args: JsonObject, key: String): String? {
        val primitive = args[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull
    }

    fun integer(args: JsonObject, key: String): Int? {
        val primitive = args[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.toIntOrNull()
    }

    fun boolean(args: JsonObject, key: String): Boolean? {
        val primitive = args[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return primitive.booleanOrNull
        return when (primitive.contentOrNull?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private fun hasUsablePatch(args: JsonObject, schema: PatchSchema): Boolean =
        schema.strings.any { nonBlankString(args, it) != null } ||
            schema.integers.any { integer(args, it) != null } ||
            schema.booleans.any { boolean(args, it) != null }
}
