package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportPayloadPreflightTest {

    @Test
    fun acceptsCompleteExternalPayloads() {
        val data = buildJsonObject {
            put("chapterBodies", JsonObject(mapOf(
                "chapter-1" to buildJsonObject { put("draft", "正文") },
            )))
            put("chapterIllustrations", JsonObject(mapOf("chapter-1" to JsonArray(emptyList()))))
            put("novelChats", JsonObject(mapOf("project-1" to JsonArray(emptyList()))))
            put("factEvidenceByProject", JsonObject(mapOf(
                "project-1" to JsonArray(listOf(buildJsonObject {
                    put("id", "batch-1")
                    put("chapterId", "chapter-1")
                    put("sourceHash", "hash-1")
                    put("facts", JsonArray(emptyList()))
                })),
            )))
        }

        BackupImportPayloadPreflight.validate(
            data,
            setOf("project-1"),
            setOf("chapter-1"),
            false,
            mapOf("project-1" to setOf("chapter-1")),
        )
    }

    @Test
    fun rejectsDanglingChapterBeforeWrites() {
        val data = buildJsonObject {
            put("chapterBodies", JsonObject(mapOf(
                "missing" to buildJsonObject { put("final", "正文") },
            )))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportPayloadPreflight.validate(data, emptySet(), setOf("chapter-1"), false)
        }
        assertTrue(error.message.orEmpty().contains("不存在的章节"))
    }

    @Test
    fun rejectsMalformedFileMapEntryBeforeWrites() {
        val data = buildJsonObject {
            put("chapterIllustrations", JsonObject(mapOf("chapter-1" to JsonPrimitive("bad"))))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportPayloadPreflight.validate(data, emptySet(), setOf("chapter-1"), false)
        }
        assertTrue(error.message.orEmpty().contains("必须是数组"))
    }

    @Test
    fun rejectsFactEvidenceAnchoredToAnotherProject() {
        val data = buildJsonObject {
            put("factEvidenceByProject", JsonObject(mapOf(
                "project-1" to JsonArray(listOf(buildJsonObject {
                    put("id", "batch-1")
                    put("chapterId", "chapter-2")
                    put("sourceHash", "hash-2")
                })),
            )))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportPayloadPreflight.validate(
                data = data,
                knownProjectIds = setOf("project-1", "project-2"),
                knownChapterIds = setOf("chapter-1", "chapter-2"),
                includeAppSettings = false,
                knownChapterIdsByProject = mapOf(
                    "project-1" to setOf("chapter-1"),
                    "project-2" to setOf("chapter-2"),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("不属于项目"))
    }

    @Test
    fun rejectsMalformedFactEvidenceBatchBeforeWrites() {
        val data = buildJsonObject {
            put("factEvidenceByProject", JsonObject(mapOf(
                "project-1" to JsonArray(listOf(buildJsonObject {
                    put("chapterId", "chapter-1")
                    put("sourceHash", "hash-1")
                })),
            )))
        }

        val error = assertThrows(BackupImportValidationException::class.java) {
            BackupImportPayloadPreflight.validate(
                data,
                setOf("project-1"),
                setOf("chapter-1"),
                false,
            )
        }
        assertTrue(error.message.orEmpty().contains("必须是非空字符串"))
    }
}
