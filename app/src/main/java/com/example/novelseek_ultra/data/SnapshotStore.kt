package com.example.novelseek_ultra.data

import java.security.MessageDigest

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
        "containersByProject",
        "volumesByProject",
    )

    /** Default number of non-manual (auto / pre_ai) snapshots retained per project. */
    const val DEFAULT_RETENTION = 20

    /** Canonical text used for both KB embedding and content hashing: prefer final, fall back to draft. */
    fun canonicalChapterText(final: String, draft: String): String = final.ifBlank { draft }

    fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0f])
            }
        }
    }
}
