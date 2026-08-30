package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable

/**
 * Knowledge-base payloads. Mirrors PC `src/types/index.ts` (SummaryPayload / EntityPayload /
 * KbStats etc.) so the on-disk JSON is structurally identical and round-trips via the backup
 * bundle without a key remap.
 *
 * Storage layout on Android:
 *   - `summariesByProject`  → app_state.json
 *   - `entitiesByProject`   → app_state.json
 *   - Vector chunks         → `kb/<projectId>.json` (separate to keep app_state.json small)
 */

@Serializable
data class SummaryPayload(
    val id: String,
    val scopeType: String,         // "chapter" | "arc" | "book"
    val scopeId: String,           // chapterId / arcId / projectId
    val summaryText: String,
    val isStale: Boolean = false,
    val wordCount: Int = 0,
)

@Serializable
data class EntityPayload(
    val id: String,
    val entityType: String,        // "character_ref" | "foreshadowing" | "location" | "event" | "item"
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val summary: String = "",
    val status: String = "open",   // "open" | "paid_off" | "archived"
    val firstSeenChapterId: String? = null,
    val lastSeenChapterId: String? = null,
    val isStale: Boolean = false,
)

/**
 * One chapter-anchored claim extracted from committed prose.
 *
 * Unlike [EntityPayload], which is the latest aggregate view of an entity, this record never
 * overwrites an older claim. Re-extracting edited prose marks the old chapter evidence stale and
 * appends/revives the new deterministic claim, giving historical generation an auditable
 * "what was known before this chapter" ledger.
 */
@Serializable
data class FactEvidence(
    val id: String,
    val entityId: String? = null,
    val factType: String,
    val subject: String,
    val claim: String,
    val statusAfter: String = "open",
    /** Optional verbatim excerpt, retained only when it can be found in the source chapter. */
    val evidenceText: String = "",
)

/** One immutable extraction result, including a successful result containing zero facts. */
@Serializable
data class ChapterFactEvidenceBatch(
    val id: String,
    val chapterId: String,
    /** Digest of the exact effective chapter prose used by the extractor. */
    val sourceHash: String,
    /** Digest of every semantic extractor input; blank on legacy chapter_facts.v1 records. */
    val extractionInputHash: String = "",
    val extractorContract: String = "chapter_facts.v1",
    val facts: List<FactEvidence> = emptyList(),
    val isStale: Boolean = false,
)

/**
 * A single text chunk with its embedding vector. Stored per-project; cosine-similarity
 * retrieved at chapter-generation time when the user has KB enabled.
 */
@Serializable
data class KbChunk(
    val id: String,
    val sourceType: String,        // "chapter"
    val sourceId: String,          // chapterId
    val sourceTitle: String,
    val chunkIndex: Int,
    val text: String,
    val embeddingModel: String,
    val embedding: List<Float>,
)

@Serializable
data class KbStats(
    val totalChunks: Int,
    val totalSources: Int,
    val embeddingModels: List<String>,
)

object EntityTypes {
    const val CHARACTER = "character_ref"
    const val FORESHADOWING = "foreshadowing"
    const val LOCATION = "location"
    const val EVENT = "event"
    const val ITEM = "item"
    val ALL = listOf(CHARACTER, FORESHADOWING, LOCATION, EVENT, ITEM)
}

object EntityStatuses {
    const val OPEN = "open"
    const val PAID_OFF = "paid_off"
    const val ARCHIVED = "archived"
}

object SummaryScopes {
    const val CHAPTER = "chapter"
    const val ARC = "arc"
    const val BOOK = "book"
}
