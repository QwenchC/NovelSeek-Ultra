package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Project-level version control ("snapshots"). A snapshot bundles the full content of ONE novel
 * project at a point in time — think `git commit` of a single project. Stored on disk as:
 *
 *   filesDir/versions/{projectId}/
 *     ├── index.json            ← List<SnapshotMeta>, newest first
 *     └── {snapshotId}.json     ← one [ProjectSnapshot]
 *
 * KB vector chunks (kb/{projectId}.json) are deliberately NOT embedded in the snapshot — they are
 * the heaviest data and are derived (re-indexable). Instead each chapter's content hash is stored
 * so that on restore we can detect exactly which chapters' vectors went stale and rebuild only
 * those. See AppRepository.restoreSnapshot / reconcileKbAfterRestore.
 */
@Serializable
data class SnapshotMeta(
    val id: String,
    val projectId: String,
    val createdAt: String,
    val label: String = "",
    val trigger: String = TRIGGER_MANUAL,   // manual | pre_ai | auto
    val wordCount: Int = 0,
    val chapterCount: Int = 0,
    val sizeBytes: Long = 0,
    /** sha1 over chapter list + per-chapter content hashes + project maps; used for dedup. */
    val contentSig: String = "",
) {
    companion object {
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_PRE_AI = "pre_ai"
        const val TRIGGER_AUTO = "auto"
    }
}

/** Full content payload of one snapshot. `project`/`chapters`/maps are kept as raw JSON so they
 *  round-trip exactly with the live store shape (no key remapping). */
@Serializable
data class ProjectSnapshot(
    val meta: SnapshotMeta,
    val project: JsonObject,
    val chapters: JsonArray,
    val chapterBodies: Map<String, JsonObject> = emptyMap(),   // chapterId -> ChapterBody
    val illustrations: Map<String, JsonArray> = emptyMap(),    // chapterId -> [Illustration]
    val promos: Map<String, JsonObject> = emptyMap(),          // chapterId -> ChapterPromo
    val projectMaps: JsonObject = JsonObject(emptyMap()),      // *ByProject slices for this project
    /** chapterId -> sha1(canonical chapter text) at snapshot time. Drives stale-KB detection. */
    val chapterHashes: Map<String, String> = emptyMap(),
)

/** Outcome of a restore — drives the "knowledge base needs rebuild" banner. */
data class RestoreResult(
    val staleChapterIds: List<String> = emptyList(),
    val prunedOrphanChunks: Int = 0,
)
