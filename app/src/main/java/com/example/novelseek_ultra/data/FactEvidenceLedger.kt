package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.FactEvidence
import com.example.novelseek_ultra.data.model.ChapterFactEvidenceBatch
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Pure, deterministic update and visibility rules for chapter-anchored fact evidence. */
internal object FactEvidenceLedger {
    data class Observation(
        val entityId: String? = null,
        val factType: String,
        val subject: String,
        val claim: String,
        val statusAfter: String = "open",
        val evidenceText: String = "",
    )

    /**
     * Replace the active evidence extracted from one exact chapter without deleting audit history.
     * Same source + same normalized claim revives the same id, so retries are idempotent.
     */
    fun replaceChapter(
        existing: List<ChapterFactEvidenceBatch>,
        chapterId: String,
        sourceHash: String,
        extractionInputHash: String,
        observations: List<Observation>,
    ): List<ChapterFactEvidenceBatch> {
        require(chapterId.isNotBlank()) { "Fact evidence chapter id is blank" }
        require(sourceHash.isNotBlank()) { "Fact evidence source hash is blank" }
        require(extractionInputHash.isNotBlank()) { "Fact evidence input hash is blank" }
        val normalized = observations.mapNotNull(::normalizeObservation)
            .distinctBy { observationKey(it) }
            .sortedWith(compareBy({ it.factType }, { it.subject }, { it.claim }, { it.statusAfter }))

        val updated = existing.map { batch ->
            if (batch.chapterId == chapterId && !batch.isStale) {
                batch.copy(isStale = true)
            } else {
                batch
            }
        }.toMutableList()

        val batchId = "fact-batch-${fingerprint(CONTRACT, chapterId, extractionInputHash).take(24)}"
        val next = ChapterFactEvidenceBatch(
            id = batchId,
            chapterId = chapterId,
            sourceHash = sourceHash,
            extractionInputHash = extractionInputHash,
            extractorContract = CONTRACT,
            facts = normalized.map { observation -> FactEvidence(
                id = evidenceId(chapterId, observation),
                entityId = observation.entityId,
                factType = observation.factType,
                subject = observation.subject,
                claim = observation.claim,
                statusAfter = observation.statusAfter,
                evidenceText = observation.evidenceText,
            ) },
            isStale = false,
        )
        val index = updated.indexOfFirst { it.id == batchId }
        if (index >= 0) updated[index] = next else updated += next

        // app_state.json must not grow forever after repeated edits. Keep every active batch plus
        // only the newest bounded stale history for this chapter; snapshots retain older versions.
        val staleForChapter = updated.withIndex()
            .filter { (_, batch) -> batch.chapterId == chapterId && batch.isStale }
            .takeLast(MAX_STALE_BATCHES_PER_CHAPTER)
            .map { it.index }
            .toSet()
        return updated.filterIndexed { batchIndex, batch ->
            batch.chapterId != chapterId || !batch.isStale || batchIndex in staleForChapter
        }
    }

    fun afterChapterChange(
        existing: List<ChapterFactEvidenceBatch>,
        chapterId: String,
    ): List<ChapterFactEvidenceBatch> = existing.map { batch ->
        if (batch.chapterId == chapterId) batch.copy(isStale = true) else batch
    }

    /** Permanent deletion/explicit forget must not leave a dangling chapter reference. */
    fun removeChapter(
        existing: List<ChapterFactEvidenceBatch>,
        chapterId: String,
    ): List<ChapterFactEvidenceBatch> = existing.filterNot { it.chapterId == chapterId }

    /** Resolve order through the live chapter table; copied order is display-only provenance. */
    fun visibleBefore(
        existing: List<ChapterFactEvidenceBatch>,
        targetChapterOrder: Int?,
        chapterOrdersById: Map<String, Int>,
    ): List<ChapterFactEvidenceBatch> = existing.filter { batch ->
        if (batch.isStale) return@filter false
        val liveOrder = chapterOrdersById[batch.chapterId] ?: return@filter false
        targetChapterOrder == null || liveOrder < targetChapterOrder
    }

    fun extractionInputHash(
        chapterId: String,
        chapterTitle: String,
        sourceHash: String,
        characterSignature: String,
    ): String = fingerprint(CONTRACT, chapterId, chapterTitle, sourceHash, characterSignature)

    fun isFresh(
        batch: ChapterFactEvidenceBatch,
        chapterId: String,
        chapterTitle: String,
        sourceHash: String,
        characterSignature: String,
    ): Boolean = !batch.isStale &&
        batch.chapterId == chapterId &&
        batch.sourceHash == sourceHash &&
        batch.extractorContract == CONTRACT &&
        batch.extractionInputHash == extractionInputHash(
            chapterId = chapterId,
            chapterTitle = chapterTitle,
            sourceHash = sourceHash,
            characterSignature = characterSignature,
        )

    /** Only a literal source substring may be rendered as evidence; invented model text is dropped. */
    fun verifiedExcerpt(sourceText: String, candidate: String, maxChars: Int = 160): String {
        val trimmed = candidate.trim()
        if (trimmed.isBlank() || !sourceText.contains(trimmed)) return ""
        return clipCodePoints(trimmed, maxChars)
    }

    private fun normalizeObservation(value: Observation): Observation? {
        val type = clipCodePoints(normalizeInline(value.factType), MAX_TYPE_CHARS)
        val subject = clipCodePoints(normalizeInline(value.subject), MAX_SUBJECT_CHARS)
        val claim = clipCodePoints(normalizeInline(value.claim), MAX_CLAIM_CHARS)
        if (type.isBlank() || subject.isBlank() || claim.isBlank()) return null
        val status = normalizeInline(value.statusAfter).ifBlank { "open" }
        return value.copy(
            entityId = value.entityId?.trim()?.takeIf { it.isNotBlank() },
            factType = type,
            subject = subject,
            claim = claim,
            statusAfter = status,
            evidenceText = clipCodePoints(value.evidenceText.trim(), MAX_EVIDENCE_CHARS),
        )
    }

    private fun observationKey(value: Observation): List<String> = listOf(
        value.factType,
        value.subject,
        value.claim,
        value.statusAfter,
        value.evidenceText,
    )

    private fun evidenceId(chapterId: String, value: Observation): String =
        "fact-${fingerprint(chapterId, *observationKey(value).toTypedArray()).take(24)}"

    private fun normalizeInline(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun clipCodePoints(value: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0 || value.isEmpty()) return ""
        val count = value.codePointCount(0, value.length)
        if (count <= maxCodePoints) return value
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
    }

    private fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }

    const val CONTRACT = "chapter_facts.v2"
    private const val MAX_TYPE_CHARS = 64
    private const val MAX_SUBJECT_CHARS = 128
    private const val MAX_CLAIM_CHARS = 512
    private const val MAX_EVIDENCE_CHARS = 160
    private const val MAX_STALE_BATCHES_PER_CHAPTER = 2
}
