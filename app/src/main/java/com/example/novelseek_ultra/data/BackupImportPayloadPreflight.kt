package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validates every external-file payload and reference before backup import performs its first write. */
internal object BackupImportPayloadPreflight {

    fun validate(
        data: JsonObject,
        knownProjectIds: Set<String>,
        knownChapterIds: Set<String>,
        includeAppSettings: Boolean,
        knownChapterIdsByProject: Map<String, Set<String>> = emptyMap(),
    ) {
        validateMap(data, "chapterBodies") { chapterId, element ->
            if (chapterId !in knownChapterIds) reject("data.chapterBodies[$chapterId] 引用了不存在的章节")
            val body = element as? JsonObject
                ?: reject("data.chapterBodies[$chapterId] 必须是对象")
            validateOptionalString(body["draft"], "data.chapterBodies[$chapterId].draft")
            validateOptionalString(body["final"], "data.chapterBodies[$chapterId].final")
        }
        validateMap(data, "chapterIllustrations") { chapterId, element ->
            if (chapterId !in knownChapterIds) {
                reject("data.chapterIllustrations[$chapterId] 引用了不存在的章节")
            }
            val items = element as? JsonArray
                ?: reject("data.chapterIllustrations[$chapterId] 必须是数组")
            items.forEachIndexed { index, item ->
                if (item !is JsonObject) {
                    reject("data.chapterIllustrations[$chapterId][$index] 必须是对象")
                }
            }
        }
        validateMap(data, "novelChats") { projectId, element ->
            if (projectId !in knownProjectIds) reject("data.novelChats[$projectId] 引用了不存在的项目")
            val items = element as? JsonArray
                ?: reject("data.novelChats[$projectId] 必须是数组")
            items.forEachIndexed { index, item ->
                if (item !is JsonObject) reject("data.novelChats[$projectId][$index] 必须是对象")
            }
        }
        validateMap(data, "factEvidenceByProject") { projectId, element ->
            if (projectId !in knownProjectIds) {
                reject("data.factEvidenceByProject[$projectId] 引用了不存在的项目")
            }
            val batches = element as? JsonArray
                ?: reject("data.factEvidenceByProject[$projectId] 必须是数组")
            val seenBatchIds = linkedSetOf<String>()
            batches.forEachIndexed { index, item ->
                val path = "data.factEvidenceByProject[$projectId][$index]"
                val batch = item as? JsonObject ?: reject("$path 必须是对象")
                val batchId = requiredString(batch, "id", "$path.id")
                if (!seenBatchIds.add(batchId)) reject("$path.id 与同项目内其他批次重复")
                val chapterId = requiredString(batch, "chapterId", "$path.chapterId")
                if (chapterId !in knownChapterIds) reject("$path.chapterId 引用了不存在的章节")
                knownChapterIdsByProject[projectId]?.let { ownedChapterIds ->
                    if (chapterId !in ownedChapterIds) {
                        reject("$path.chapterId 不属于项目 $projectId")
                    }
                }
                requiredString(batch, "sourceHash", "$path.sourceHash")
                batch["facts"]?.let { facts ->
                    val array = facts as? JsonArray ?: reject("$path.facts 必须是数组")
                    array.forEachIndexed { factIndex, fact ->
                        if (fact !is JsonObject) reject("$path.facts[$factIndex] 必须是对象")
                    }
                }
            }
        }

        if (includeAppSettings) {
            validateMap(data, "agentSessions") { sessionId, element ->
                if (element !is JsonObject) reject("data.agentSessions[$sessionId] 必须是对象")
            }
            data["agentIndex"]?.let { index ->
                if (index !is JsonObject) reject("data.agentIndex 必须是对象")
            }
        }
    }

    private fun validateMap(
        data: JsonObject,
        field: String,
        validateEntry: (String, JsonElement) -> Unit,
    ) {
        val element = data[field] ?: return
        val map = element as? JsonObject ?: reject("data.$field 必须是对象")
        map.forEach(validateEntry)
    }

    private fun validateOptionalString(element: JsonElement?, path: String) {
        if (element == null || element is JsonNull) return
        val primitive = element as? JsonPrimitive ?: reject("$path 必须是字符串")
        if (!primitive.isString) reject("$path 必须是字符串")
    }

    private fun requiredString(obj: JsonObject, field: String, path: String): String {
        val primitive = obj[field] as? JsonPrimitive ?: reject("$path 必须是非空字符串")
        if (!primitive.isString || primitive.content.isBlank()) reject("$path 必须是非空字符串")
        return primitive.content
    }

    private fun reject(reason: String): Nothing =
        throw BackupImportValidationException("备份导入已拒绝：$reason")
}
