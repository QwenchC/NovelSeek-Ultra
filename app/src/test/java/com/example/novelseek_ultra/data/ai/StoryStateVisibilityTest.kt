package com.example.novelseek_ultra.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryStateVisibilityTest {
    private val chapters = mapOf("past" to 2, "target" to 5, "future" to 8)

    @Test
    fun `source must precede target chapter`() {
        assertTrue(StoryStateVisibility.isVisibleBefore(5, "past", 99, chapters))
        assertFalse(StoryStateVisibility.isVisibleBefore(5, "target", 1, chapters))
        assertFalse(StoryStateVisibility.isVisibleBefore(5, "future", 1, chapters))
    }

    @Test
    fun `live order wins after chapter reordering`() {
        assertTrue(StoryStateVisibility.isVisibleBefore(5, "past", 9, chapters))
        assertFalse(StoryStateVisibility.isVisibleBefore(5, "future", 1, chapters))
    }

    @Test
    fun `deleted anchored source is hidden`() {
        assertFalse(StoryStateVisibility.isVisibleBefore(5, "deleted", 1, chapters))
    }

    @Test
    fun `legacy order and unanchored author state remain usable`() {
        assertTrue(StoryStateVisibility.isVisibleBefore(5, null, 4, chapters))
        assertFalse(StoryStateVisibility.isVisibleBefore(5, null, 5, chapters))
        assertTrue(StoryStateVisibility.isVisibleBefore(5, null, null, chapters))
        assertTrue(StoryStateVisibility.isVisibleBefore(null, "future", 8, chapters))
    }

    @Test
    fun futureOrTargetPayoffRetainsOnlyPreviouslyKnownIdentity() {
        assertEquals(
            StoryStateVisibility.EntityProjection.IDENTITY_ONLY,
            entityProjection(status = "paid_off", first = "past", last = "future"),
        )
        assertEquals(
            StoryStateVisibility.EntityProjection.IDENTITY_ONLY,
            entityProjection(status = "paid_off", first = "past", last = "target"),
        )
    }

    @Test
    fun pastAggregateUsesItsCurrentOpenOrPaidOffStatus() {
        assertEquals(
            StoryStateVisibility.EntityProjection.FULL,
            entityProjection(status = "open", first = "past", last = "past"),
        )
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            entityProjection(status = "paid_off", first = "past", last = "past"),
        )
    }

    @Test
    fun entityFirstSeenAtTargetOrInFutureIsHidden() {
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            entityProjection(status = "open", first = "target", last = "target"),
        )
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            entityProjection(status = "open", first = "future", last = "future"),
        )
    }

    @Test
    fun deletedAnchorsFailClosedWithoutDiscardingEarlierIdentity() {
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            entityProjection(status = "open", first = "deleted", last = "past"),
        )
        assertEquals(
            StoryStateVisibility.EntityProjection.IDENTITY_ONLY,
            entityProjection(status = "paid_off", first = "past", last = "deleted"),
        )
    }

    @Test
    fun unanchoredLegacyEntityIsHiddenHistoricallyButUsableForAppend() {
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            entityProjection(status = "open", first = null, last = null),
        )
        assertEquals(
            StoryStateVisibility.EntityProjection.FULL,
            entityProjection(
                status = "open",
                first = null,
                last = null,
                historicalTarget = false,
            ),
        )
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            entityProjection(
                status = "paid_off",
                first = null,
                last = null,
                historicalTarget = false,
            ),
        )
    }

    @Test
    fun liveOrderControlsAggregateProjectionAfterReordering() {
        val firstMovedForward = mapOf("first" to 7, "last" to 2)
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            StoryStateVisibility.entityProjectionBefore(
                targetChapterOrder = 5,
                historicalTarget = true,
                currentStatus = "open",
                firstSeenChapterId = "first",
                lastSeenChapterId = "last",
                chapterOrdersById = firstMovedForward,
            ),
        )

        val lastMovedForward = mapOf("first" to 2, "last" to 7)
        assertEquals(
            StoryStateVisibility.EntityProjection.IDENTITY_ONLY,
            StoryStateVisibility.entityProjectionBefore(
                targetChapterOrder = 5,
                historicalTarget = true,
                currentStatus = "paid_off",
                firstSeenChapterId = "first",
                lastSeenChapterId = "last",
                chapterOrdersById = lastMovedForward,
            ),
        )
    }

    @Test
    fun historicalProjectionWithoutTargetOrderFailsClosed() {
        assertEquals(
            StoryStateVisibility.EntityProjection.HIDDEN,
            StoryStateVisibility.entityProjectionBefore(
                targetChapterOrder = null,
                historicalTarget = true,
                currentStatus = "open",
                firstSeenChapterId = "past",
                lastSeenChapterId = "past",
                chapterOrdersById = chapters,
            ),
        )
    }

    @Test
    fun `arc progress is projected from assigned chapter window`() {
        assertEquals(
            "completed",
            arcStatus(current = "active", assigned = listOf(1, 2)),
        )
        assertEquals(
            "active",
            arcStatus(current = "completed", assigned = listOf(2, 5, 8)),
        )
        assertEquals(
            "active",
            arcStatus(current = "completed", assigned = listOf(5, 8)),
        )
        assertEquals(
            "upcoming",
            arcStatus(current = "completed", assigned = listOf(6, 8)),
        )
    }

    @Test
    fun `unassigned arcs use target arc order and append keeps current status`() {
        assertEquals(
            "completed",
            arcStatus(current = "upcoming", arcOrder = 1, targetArcOrder = 2),
        )
        assertEquals(
            "upcoming",
            arcStatus(current = "completed", arcOrder = 3, targetArcOrder = 2),
        )
        assertEquals(
            "ending",
            StoryStateVisibility.arcStatusBefore(
                targetChapterOrder = 5,
                historicalTarget = false,
                currentStatus = "ending",
                arcOrder = 3,
                targetArcOrder = 2,
                assignedChapterOrders = listOf(8),
            ),
        )
    }

    private fun entityProjection(
        status: String,
        first: String?,
        last: String?,
        historicalTarget: Boolean = true,
    ) = StoryStateVisibility.entityProjectionBefore(
        targetChapterOrder = 5,
        historicalTarget = historicalTarget,
        currentStatus = status,
        firstSeenChapterId = first,
        lastSeenChapterId = last,
        chapterOrdersById = chapters,
    )

    private fun arcStatus(
        current: String,
        assigned: List<Int> = emptyList(),
        arcOrder: Int = 1,
        targetArcOrder: Int? = 2,
    ) = StoryStateVisibility.arcStatusBefore(
        targetChapterOrder = 5,
        historicalTarget = true,
        currentStatus = current,
        arcOrder = arcOrder,
        targetArcOrder = targetArcOrder,
        assignedChapterOrders = assigned,
    )
}
