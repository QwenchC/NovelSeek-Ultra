package com.example.novelseek_ultra.data.ai

/**
 * Temporal visibility policy for derived story state.
 *
 * A chapter may be generated after later chapters already exist (for example after inserting or
 * revising an old chapter). Derived values anchored to the target or a later chapter must not be
 * presented as facts that have already happened. Live chapter order wins over the order copied into
 * an old derived record, so chapter reordering remains safe. An anchored record whose source chapter
 * was deleted is hidden instead of becoming an untraceable fact.
 */
internal object StoryStateVisibility {
    enum class EntityProjection {
        FULL,
        IDENTITY_ONLY,
        HIDDEN,
    }

    fun isVisibleBefore(
        targetChapterOrder: Int?,
        sourceChapterId: String?,
        sourceChapterOrder: Int?,
        chapterOrdersById: Map<String, Int>,
    ): Boolean {
        if (targetChapterOrder == null) return true
        if (sourceChapterId != null) {
            val liveOrder = chapterOrdersById[sourceChapterId] ?: return false
            return liveOrder < targetChapterOrder
        }
        return sourceChapterOrder?.let { it < targetChapterOrder } ?: true
    }

    /**
     * Conservatively projects one aggregate entity into the state visible before a target chapter.
     *
     * EntityPayload stores only the first and latest observation, so a latest observation at the
     * target or in the future may have overwritten both the summary and paid-off status that were
     * true at the target boundary. In that case the identity is retained as an open commitment,
     * while future-derived details are discarded by the caller. A missing first anchor cannot prove
     * that the entity existed at a historical target and is therefore hidden. For a genuine append
     * target, unanchored legacy/current author state remains usable.
     *
     * Chapter ids always resolve through [chapterOrdersById]. A copied historical order can never
     * override the live order, and a deleted anchored chapter is treated as missing evidence.
     */
    fun entityProjectionBefore(
        targetChapterOrder: Int?,
        historicalTarget: Boolean,
        currentStatus: String,
        firstSeenChapterId: String?,
        lastSeenChapterId: String?,
        chapterOrdersById: Map<String, Int>,
    ): EntityProjection {
        if (historicalTarget && targetChapterOrder == null) return EntityProjection.HIDDEN

        if (firstSeenChapterId == null) {
            if (historicalTarget) return EntityProjection.HIDDEN
        } else {
            val firstOrder = chapterOrdersById[firstSeenChapterId]
                ?: return EntityProjection.HIDDEN
            if (targetChapterOrder != null && firstOrder >= targetChapterOrder) {
                return EntityProjection.HIDDEN
            }
        }

        if (lastSeenChapterId == null) {
            if (historicalTarget) return EntityProjection.IDENTITY_ONLY
        } else {
            val lastOrder = chapterOrdersById[lastSeenChapterId]
                ?: return EntityProjection.IDENTITY_ONLY
            if (targetChapterOrder != null && lastOrder >= targetChapterOrder) {
                return EntityProjection.IDENTITY_ONLY
            }
        }

        return if (currentStatus.equals("open", ignoreCase = true)) {
            EntityProjection.FULL
        } else {
            EntityProjection.HIDDEN
        }
    }

    /** Projects mutable global arc progress onto the boundary immediately before the target. */
    fun arcStatusBefore(
        targetChapterOrder: Int?,
        historicalTarget: Boolean,
        currentStatus: String,
        arcOrder: Int,
        targetArcOrder: Int?,
        assignedChapterOrders: List<Int>,
    ): String {
        if (!historicalTarget || targetChapterOrder == null) return currentStatus
        if (assignedChapterOrders.isNotEmpty()) {
            val first = assignedChapterOrders.minOrNull() ?: return "upcoming"
            val last = assignedChapterOrders.maxOrNull() ?: return "upcoming"
            return when {
                targetChapterOrder in assignedChapterOrders -> "active"
                last < targetChapterOrder -> "completed"
                first > targetChapterOrder -> "upcoming"
                else -> "active"
            }
        }
        return when {
            targetArcOrder == null -> "upcoming"
            arcOrder < targetArcOrder -> "completed"
            arcOrder == targetArcOrder -> "active"
            else -> "upcoming"
        }
    }
}
