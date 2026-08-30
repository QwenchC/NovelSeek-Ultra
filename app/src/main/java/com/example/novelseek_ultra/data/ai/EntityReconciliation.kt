package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.EntityPayload

/** Repairs one aggregate entity after re-extracting a chapter without discarding safe anchors. */
internal object EntityReconciliation {
    data class TimedEvidence(
        val chapterId: String,
        val chapterOrder: Int,
        val summary: String,
        val statusAfter: String,
    )

    fun matchIndex(
        existing: List<EntityPayload>,
        entityType: String,
        canonicalName: String,
        sourceChapterId: String,
    ): Int {
        fun EntityPayload.matches(): Boolean =
            this.entityType == entityType &&
                (this.canonicalName == canonicalName || canonicalName in aliases)

        val fresh = existing.indexOfFirst { !it.isStale && it.matches() }
        if (fresh >= 0) return fresh
        return existing.indexOfFirst {
            it.isStale && it.matches() &&
                (it.firstSeenChapterId == sourceChapterId || it.lastSeenChapterId == sourceChapterId)
        }
    }

    fun merge(
        current: EntityPayload,
        sourceChapterId: String,
        aliases: List<String>,
        summary: String,
        extractedStatus: String,
    ): EntityPayload {
        val repairingStale = current.isStale
        val sourceWasLatest = current.lastSeenChapterId == null ||
            current.lastSeenChapterId == sourceChapterId
        val keepLaterAggregate = repairingStale &&
            current.firstSeenChapterId == sourceChapterId && !sourceWasLatest
        val normalizedStatus = extractedStatus
            .takeIf { it == "open" || it == "paid_off" || it == "archived" }
            ?: current.status

        val nextSummary = if (keepLaterAggregate) {
            current.summary
        } else {
            summary.takeIf { it.isNotBlank() } ?: current.summary
        }
        val nextStatus = when {
            keepLaterAggregate -> current.status
            repairingStale -> normalizedStatus
            normalizedStatus == "paid_off" && current.status == "open" -> "paid_off"
            else -> current.status
        }
        val nextLastSeen = if (keepLaterAggregate) current.lastSeenChapterId else sourceChapterId

        return current.copy(
            aliases = (current.aliases + aliases)
                .filter { it.isNotBlank() && it != current.canonicalName }
                .distinct(),
            summary = nextSummary,
            status = nextStatus,
            firstSeenChapterId = current.firstSeenChapterId ?: sourceChapterId,
            lastSeenChapterId = nextLastSeen,
            isStale = false,
        )
    }

    /**
     * Rebuild temporal anchors from fresh chapter evidence. A partially migrated old project may
     * have a later aggregate without ledger coverage; in that case the later aggregate wins.
     */
    fun reconcileWithEvidence(
        current: EntityPayload,
        evidence: List<TimedEvidence>,
        chapterOrdersById: Map<String, Int>,
    ): EntityPayload {
        if (evidence.isEmpty()) return current
        val ordered = evidence.sortedWith(
            compareBy<TimedEvidence>({ it.chapterOrder }, { it.chapterId }, { it.summary }),
        )
        val earliest = ordered.first()
        val latest = ordered.last()
        val currentFirstOrder = current.firstSeenChapterId?.let(chapterOrdersById::get)
        val currentLastOrder = current.lastSeenChapterId?.let(chapterOrdersById::get)
        val replaceFirst = current.firstSeenChapterId == null ||
            currentFirstOrder == null ||
            earliest.chapterOrder < currentFirstOrder
        val replaceLatest = current.lastSeenChapterId == null ||
            currentLastOrder == null ||
            latest.chapterOrder >= currentLastOrder
        val latestStatus = latest.statusAfter.takeIf {
            it == "open" || it == "paid_off" || it == "archived"
        } ?: current.status

        return current.copy(
            summary = if (replaceLatest && latest.summary.isNotBlank()) latest.summary else current.summary,
            status = if (replaceLatest) latestStatus else current.status,
            firstSeenChapterId = if (replaceFirst) earliest.chapterId else current.firstSeenChapterId,
            lastSeenChapterId = if (replaceLatest) latest.chapterId else current.lastSeenChapterId,
        )
    }

    fun afterChapterRemoval(
        current: EntityPayload,
        removedChapterId: String,
        remainingEvidence: List<TimedEvidence>,
        chapterOrdersById: Map<String, Int>,
    ): EntityPayload? {
        val affected = current.firstSeenChapterId == removedChapterId ||
            current.lastSeenChapterId == removedChapterId
        if (!affected) return current
        val survivingFirst = current.firstSeenChapterId
            ?.takeIf { it != removedChapterId && it in chapterOrdersById }
        val survivingLast = current.lastSeenChapterId
            ?.takeIf { it != removedChapterId && it in chapterOrdersById }
        val withoutRemovedAnchor = current.copy(
            firstSeenChapterId = survivingFirst,
            lastSeenChapterId = survivingLast,
            isStale = false,
        )
        if (remainingEvidence.isNotEmpty()) {
            return reconcileWithEvidence(
                current = withoutRemovedAnchor,
                evidence = remainingEvidence,
                chapterOrdersById = chapterOrdersById,
            )
        }
        val survivingAnchor = survivingFirst ?: survivingLast ?: return null
        // The aggregate carries only its latest summary/status, so after removing an anchor there
        // is no proof that those values came from the surviving legacy anchor. Retain identity for
        // a future repair, but hide it from generation until verified evidence is extracted again.
        return current.copy(
            summary = "",
            status = "open",
            firstSeenChapterId = survivingFirst ?: survivingAnchor,
            lastSeenChapterId = survivingLast ?: survivingAnchor,
            isStale = true,
        )
    }
}
