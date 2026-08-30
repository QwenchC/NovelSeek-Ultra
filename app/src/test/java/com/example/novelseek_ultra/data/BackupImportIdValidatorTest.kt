package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportIdValidatorTest {

    @Test
    fun acceptsSafeStorageBackedIds() {
        val data = backup(
            projectId = "project_一-123",
            chapterId = "chapter-456",
        )

        BackupImportIdValidator.validate(data)
    }

    @Test
    fun rejectsTraversalInProjectIdBeforeImportWrites() {
        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(backup(projectId = "../../escape"))
        }

        assertTrue(error.message.orEmpty().contains("data.projects[0].id"))
    }

    @Test
    fun rejectsWindowsNormalizedTraversalSegment() {
        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(backup(projectId = ".. "))
        }

        assertTrue(error.message.orEmpty().contains("data.projects[0].id"))
    }

    @Test
    fun rejectsTraversalInChapterBodyKey() {
        val data = backup().let { valid ->
            JsonObject(valid + ("chapterBodies" to JsonObject(mapOf("..\\secret" to JsonObject(emptyMap())))))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(data)
        }

        assertTrue(error.message.orEmpty().contains("data.chapterBodies"))
    }

    @Test
    fun rejectsMismatchedEmbeddedProjectId() {
        val chapter = buildJsonObject {
            put("id", "chapter-safe")
            put("project_id", "different-project")
        }
        val data = buildJsonObject {
            put("chaptersByProject", JsonObject(mapOf("project-safe" to JsonArray(listOf(chapter)))))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(data)
        }

        assertTrue(error.message.orEmpty().contains("与所属项目不一致"))
    }

    @Test
    fun rejectsNonStringId() {
        val data = buildJsonObject {
            put("projects", JsonArray(listOf(buildJsonObject { put("id", 42) })))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(data)
        }

        assertEquals(true, error.message.orEmpty().contains("必须是字符串"))
    }

    @Test
    fun rejectsUnsafeAgentIndexItemId() {
        val data = buildJsonObject {
            put("agentIndex", buildJsonObject {
                put("items", JsonArray(listOf(buildJsonObject { put("id", "../session") })))
            })
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(data)
        }
        assertTrue(error.message.orEmpty().contains("data.agentIndex.items[0].id"))
    }

    @Test
    fun rejectsAgentSessionIdThatStorageCanWriteButRuntimeCannotLoad() {
        val data = buildJsonObject {
            put("agentSessions", JsonObject(mapOf(
                "session.with.dot" to buildJsonObject { put("id", "session.with.dot") },
            )))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(data)
        }
        assertTrue(error.message.orEmpty().contains("仅允许字母、数字"))
    }

    @Test
    fun projectOnlyImportIgnoresUnselectedAgentSettings() {
        val data = buildJsonObject {
            put("agentSessions", JsonObject(mapOf(
                "../broken" to buildJsonObject { put("id", "../broken") },
            )))
        }

        BackupImportIdValidator.validate(data, includeAppSettings = false)
    }

    @Test
    fun rejectsDuplicateChapterIdsThatWouldShareOneBodyFile() {
        val chapter1 = buildJsonObject {
            put("id", "same-chapter")
            put("project_id", "project-1")
        }
        val chapter2 = buildJsonObject {
            put("id", "same-chapter")
            put("project_id", "project-2")
        }
        val data = buildJsonObject {
            put("chaptersByProject", JsonObject(mapOf(
                "project-1" to JsonArray(listOf(chapter1)),
                "project-2" to JsonArray(listOf(chapter2)),
            )))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportIdValidator.validate(data)
        }
        assertTrue(error.message.orEmpty().contains("章节 ID 重复"))
    }

    private fun backup(
        projectId: String = "project-safe",
        chapterId: String = "chapter-safe",
    ): JsonObject {
        val chapter = buildJsonObject {
            put("id", chapterId)
            put("project_id", projectId)
        }
        return buildJsonObject {
            put("projects", JsonArray(listOf(buildJsonObject { put("id", projectId) })))
            put("chaptersByProject", JsonObject(mapOf(projectId to JsonArray(listOf(chapter)))))
            put("chapterBodies", JsonObject(mapOf(chapterId to JsonObject(emptyMap()))))
            put("chapterIllustrations", JsonObject(mapOf(chapterId to JsonArray(emptyList()))))
            put("novelChats", JsonObject(mapOf(projectId to JsonArray(emptyList()))))
            put("promoByChapter", JsonObject(mapOf(chapterId to JsonObject(emptyMap()))))
        }
    }
}
