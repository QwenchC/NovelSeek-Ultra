package com.example.novelseek_ultra.data

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure helpers for project snapshots. State/file IO lives in [AppRepository] (it owns the state
 * flow and filesDir); this object holds only the side-effect-free logic so it stays testable.
 */
object SnapshotStore {

    /**
     * Per-project `*ByProject` maps that belong to a single project and are sliced into a snapshot.
     * `promoByChapter` is intentionally absent — it is keyed by chapterId, so it is captured per
     * chapter (ProjectSnapshot.promos) rather than by project id.
     */
    val PROJECT_KEYED_FIELDS = listOf(
        "novelTypeByProject",
        "plotArcsByProject",
        "charactersByProject",
        "worldSettingByProject",
        "timelineByProject",
        "longNovelOutlineByProject",
        "characterRelationshipsByProject",
        "characterEventsByProject",
        "cultivationRealmsByProject",
        "characterRealmEventsByProject",
        "summariesByProject",
        "entitiesByProject",
        "factEvidenceByProject",
        "containersByProject",
        "volumesByProject",
        "characterGrowthByProject",
    )

    /** Default number of non-manual (auto / pre_ai) snapshots retained per project. */
    const val DEFAULT_RETENTION = 20

    /** Canonical text used for both KB embedding and content hashing: prefer final, fall back to draft. */
    fun canonicalChapterText(final: String, draft: String): String = final.ifBlank { draft }

    fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    /**
     * Stable signature of every restorable payload field. Map/object keys are sorted so a storage
     * ordering change alone does not create a snapshot, while metadata, draft, illustration, and
     * promo-only changes are still preserved by auto snapshots.
     */
    fun contentSignature(
        project: JsonObject,
        chapters: JsonArray,
        chapterBodies: Map<String, JsonObject>,
        illustrations: Map<String, JsonArray>,
        promos: Map<String, JsonObject>,
        projectMaps: JsonObject,
        chapterHashes: Map<String, String>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun add(label: String, value: String) {
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(valueBytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(valueBytes)
        }
        fun <T : JsonElement> addJsonMap(label: String, values: Map<String, T>) {
            values.entries.sortedBy { it.key }.forEach { (key, value) ->
                add("$label-key", key)
                add("$label-value", canonicalJson(value))
            }
        }

        add("project", canonicalJson(project))
        add("chapters", canonicalJson(chapters))
        addJsonMap("body", chapterBodies)
        add("project-maps", canonicalJson(projectMaps))
        addJsonMap("illustration", illustrations)
        addJsonMap("promo", promos)
        chapterHashes.entries.sortedBy { it.key }.forEach { (key, value) ->
            add("chapter-hash-key", key)
            add("chapter-hash-value", value)
        }
        return digest.digest().toHex()
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
            "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> element.toString()
    }

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (b in this@toHex) {
                val v = b.toInt() and 0xff
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0f])
            }
        }
}
