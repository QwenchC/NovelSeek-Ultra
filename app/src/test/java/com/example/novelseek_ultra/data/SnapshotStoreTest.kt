package com.example.novelseek_ultra.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotStoreTest {
    private fun signature(
        title: String = "旧标题",
        draft: String = "草稿",
        illustrations: Map<String, JsonArray> = emptyMap(),
        promos: Map<String, JsonObject> = emptyMap(),
        projectMaps: JsonObject = buildJsonObject {
            put("world", buildJsonObject { put("name", "世界") })
        },
    ): String = SnapshotStore.contentSignature(
        project = buildJsonObject { put("title", title); put("id", "p1") },
        chapters = buildJsonArray { add(buildJsonObject { put("id", "c1") }) },
        chapterBodies = mapOf("c1" to buildJsonObject { put("draft", draft); put("final", "定稿") }),
        illustrations = illustrations,
        promos = promos,
        projectMaps = projectMaps,
        chapterHashes = mapOf("c1" to "canonical-final-hash"),
    )

    @Test
    fun `metadata draft illustration and promo changes affect signature`() {
        val base = signature()
        val illustration = mapOf(
            "c1" to buildJsonArray { add(buildJsonObject { put("url", "cover-v2.png") }) },
        )
        val promo = mapOf("c1" to buildJsonObject { put("text", "新推文") })

        assertNotEquals(base, signature(title = "新标题"))
        assertNotEquals(base, signature(draft = "新草稿"))
        assertNotEquals(base, signature(illustrations = illustration))
        assertNotEquals(base, signature(promos = promo))
    }

    @Test
    fun `map and json object insertion order do not affect signature`() {
        fun makeBody(id: String) = buildJsonObject { put("final", id); put("draft", "") }
        val first = SnapshotStore.contentSignature(
            project = buildJsonObject { put("title", "书"); put("id", "p1") },
            chapters = buildJsonArray {},
            chapterBodies = linkedMapOf("b" to makeBody("b"), "a" to makeBody("a")),
            illustrations = emptyMap(),
            promos = emptyMap(),
            projectMaps = buildJsonObject { put("z", 1); put("a", 2) },
            chapterHashes = linkedMapOf("b" to "2", "a" to "1"),
        )
        val second = SnapshotStore.contentSignature(
            project = buildJsonObject { put("id", "p1"); put("title", "书") },
            chapters = buildJsonArray {},
            chapterBodies = linkedMapOf("a" to makeBody("a"), "b" to makeBody("b")),
            illustrations = emptyMap(),
            promos = emptyMap(),
            projectMaps = buildJsonObject { put("a", 2); put("z", 1) },
            chapterHashes = linkedMapOf("a" to "1", "b" to "2"),
        )

        assertEquals(first, second)
    }

    @Test
    fun `fact evidence participates in snapshot capture and dedup signature`() {
        assertTrue("factEvidenceByProject" in SnapshotStore.PROJECT_KEYED_FIELDS)
        val withEvidence = buildJsonObject {
            put("world", buildJsonObject { put("name", "世界") })
            put("factEvidenceByProject", JsonArray(listOf(buildJsonObject {
                put("id", "batch-1")
                put("chapterId", "c1")
                put("sourceHash", "hash-1")
            })))
        }

        assertNotEquals(signature(), signature(projectMaps = withEvidence))
    }
}
