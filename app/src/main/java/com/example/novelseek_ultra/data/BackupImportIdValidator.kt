package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BackupImportValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Rejects untrusted IDs before backup data can influence a private-storage path. */
internal object BackupImportIdValidator {
    private const val MAX_ID_LENGTH = 128

    fun validate(data: JsonObject, includeAppSettings: Boolean = true) {
        validateProjects(data)
        validateProjectMaps(data)
        validateChapters(data)
        validateMapKeys(data, "chapterBodies", "章节")
        validateMapKeys(data, "chapterIllustrations", "章节")
        validateMapKeys(data, "promoByChapter", "章节")
        validateMapKeys(data, "novelChats", "项目")
        validateFolders(data)
        if (includeAppSettings) {
            validateAgentSessions(data)
            validateAgentIndex(data)
            validateOptionalId(
                data["lastListenProjectId"],
                "data.lastListenProjectId",
                "项目",
                allowBlank = true,
            )
        }
    }

    private fun validateProjects(data: JsonObject) {
        val seen = mutableSetOf<String>()
        (data["projects"] as? JsonArray)?.forEachIndexed { index, element ->
            val project = element as? JsonObject ?: reject("data.projects[$index] 必须是对象")
            val id = validateRequiredId(project["id"], "data.projects[$index].id", "项目")
            if (!seen.add(id)) reject("data.projects[$index].id 与备份内其他项目 ID 重复")
        }
    }

    private fun validateProjectMaps(data: JsonObject) {
        data.forEach { (field, element) ->
            if (!field.endsWith("ByProject")) return@forEach
            (element as? JsonObject)?.keys?.forEach { projectId ->
                requireSafeStorageId(projectId, "data.$field[$projectId]", "项目")
            }
        }
    }

    private fun validateChapters(data: JsonObject) {
        val chaptersByProject = data["chaptersByProject"] as? JsonObject ?: return
        val seenChapterIds = mutableSetOf<String>()
        chaptersByProject.forEach { (projectId, element) ->
            requireSafeStorageId(projectId, "data.chaptersByProject[$projectId]", "项目")
            (element as? JsonArray)?.forEachIndexed { index, chapterElement ->
                val chapter = chapterElement as? JsonObject
                    ?: reject("data.chaptersByProject[$projectId][$index] 必须是对象")
                val chapterId = validateRequiredId(
                    chapter["id"],
                    "data.chaptersByProject[$projectId][$index].id",
                    "章节",
                )
                if (!seenChapterIds.add(chapterId)) {
                    reject("data.chaptersByProject[$projectId][$index].id 与备份内其他章节 ID 重复")
                }
                val embeddedProjectId = validateOptionalId(
                    chapter["project_id"] ?: chapter["projectId"],
                    "data.chaptersByProject[$projectId][$index].project_id",
                    "项目",
                )
                if (embeddedProjectId != null && embeddedProjectId != projectId) {
                    reject("data.chaptersByProject[$projectId][$index].project_id 与所属项目不一致")
                }
            }
        }
    }

    private fun validateFolders(data: JsonObject) {
        (data["folders"] as? JsonArray)?.forEachIndexed { folderIndex, element ->
            val folder = element as? JsonObject ?: return@forEachIndexed
            (folder["projectIds"] as? JsonArray)?.forEachIndexed { idIndex, id ->
                validateRequiredId(id, "data.folders[$folderIndex].projectIds[$idIndex]", "项目")
            }
        }
    }

    private fun validateAgentSessions(data: JsonObject) {
        (data["agentSessions"] as? JsonObject)?.forEach { (sessionId, element) ->
            requireSafeAgentSessionId(sessionId, "data.agentSessions[$sessionId]")
            val session = element as? JsonObject ?: return@forEach
            val embedded = validateOptionalId(
                session["id"],
                "data.agentSessions[$sessionId].id",
                "智能体会话",
            )
            if (embedded != null && embedded != sessionId) {
                reject("data.agentSessions[$sessionId].id 与对象键不一致")
            }
        }
    }

    private fun validateAgentIndex(data: JsonObject) {
        val index = data["agentIndex"] ?: return
        val obj = index as? JsonObject ?: reject("data.agentIndex 必须是对象")
        validateOptionalAgentSessionId(obj["currentId"], "data.agentIndex.currentId")
        val items = obj["items"] ?: return
        val array = items as? JsonArray ?: reject("data.agentIndex.items 必须是数组")
        val seen = mutableSetOf<String>()
        array.forEachIndexed { itemIndex, element ->
            val item = element as? JsonObject
                ?: reject("data.agentIndex.items[$itemIndex] 必须是对象")
            val id = validateOptionalAgentSessionId(
                item["id"],
                "data.agentIndex.items[$itemIndex].id",
            ) ?: reject("data.agentIndex.items[$itemIndex].id 缺少智能体会话 ID")
            if (!seen.add(id)) reject("data.agentIndex.items[$itemIndex].id 与其他会话 ID 重复")
        }
    }

    private fun validateMapKeys(data: JsonObject, field: String, kind: String) {
        (data[field] as? JsonObject)?.keys?.forEach { id ->
            requireSafeStorageId(id, "data.$field[$id]", kind)
        }
    }

    private fun validateRequiredId(element: JsonElement?, path: String, kind: String): String =
        validateOptionalId(element, path, kind) ?: reject("$path 缺少$kind ID")

    private fun validateOptionalId(
        element: JsonElement?,
        path: String,
        kind: String,
        allowBlank: Boolean = false,
    ): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: reject("$path 的$kind ID 必须是字符串")
        if (!primitive.isString) reject("$path 的$kind ID 必须是字符串")
        val id = primitive.content
        if (allowBlank && id.isBlank()) return id
        requireSafeStorageId(id, path, kind)
        return id
    }

    private fun requireSafeStorageId(id: String, path: String, kind: String) {
        val windowsStem = id.substringBefore('.').uppercase()
        val windowsReserved = windowsStem in setOf("CON", "PRN", "AUX", "NUL") ||
            windowsStem.matches(Regex("COM[1-9]|LPT[1-9]"))
        val unsafe = id.isBlank() ||
            id.length > MAX_ID_LENGTH ||
            id != id.trim() ||
            id == "." || id == ".." ||
            windowsReserved ||
            id.any { it == '/' || it == '\\' || it == ':' || it.code == 0 || it.isISOControl() }
        if (unsafe) {
            reject(
                "$path 含有不安全的$kind ID；ID 必须是单个非空路径片段，" +
                    "长度不超过 $MAX_ID_LENGTH，且不能包含首尾空白、斜杠、反斜杠、" +
                    "冒号、控制字符或 Windows 保留名称",
            )
        }
    }

    private fun validateOptionalAgentSessionId(element: JsonElement?, path: String): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: reject("$path 的智能体会话 ID 必须是字符串")
        if (!primitive.isString) reject("$path 的智能体会话 ID 必须是字符串")
        val id = primitive.content
        requireSafeAgentSessionId(id, path)
        return id
    }

    private fun requireSafeAgentSessionId(id: String, path: String) {
        requireSafeStorageId(id, path, "智能体会话")
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            reject("$path 含有应用无法加载的智能体会话 ID；仅允许字母、数字、连字符和下划线")
        }
    }

    private fun reject(reason: String): Nothing =
        throw BackupImportValidationException("备份导入已拒绝：$reason")
}
