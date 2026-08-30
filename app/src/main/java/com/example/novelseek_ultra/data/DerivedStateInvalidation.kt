package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.SummaryPayload
import com.example.novelseek_ultra.data.model.CharacterGrowthEntry
import com.example.novelseek_ultra.data.model.ContainerStore
import com.example.novelseek_ultra.data.model.EntityPayload

/** Pure invalidation rules shared by repository transactions and JVM tests. */
internal object DerivedStateInvalidation {
    fun summariesAfterChapterChange(
        summaries: List<SummaryPayload>,
        chapterId: String,
    ): List<SummaryPayload> = summaries.map { summary ->
        if (summary.scopeType != "chapter" || summary.scopeId == chapterId) {
            summary.copy(isStale = true)
        } else {
            summary
        }
    }

    fun staleChapterIdsAfterChange(existing: List<String>, chapterId: String): List<String> =
        (existing + chapterId).distinct()

    fun entitiesAfterChapterChange(
        entities: List<EntityPayload>,
        chapterId: String,
    ): List<EntityPayload> = entities.map { entity ->
        if (entity.firstSeenChapterId == chapterId || entity.lastSeenChapterId == chapterId) {
            entity.copy(isStale = true)
        } else {
            entity
        }
    }

    fun growthAfterChapterChange(
        entries: List<CharacterGrowthEntry>,
        chapterId: String,
    ): List<CharacterGrowthEntry> = entries.map { entry ->
        when {
            entry.manual -> entry.copy(isStale = false)
            entry.chapterId == chapterId -> entry.copy(isStale = true)
            else -> entry
        }
    }

    fun updateLatestActiveGrowth(
        entries: List<CharacterGrowthEntry>,
        value: String,
    ): List<CharacterGrowthEntry> {
        val index = entries.indexOfLast { !it.isStale }
        if (index < 0) return entries
        return entries.toMutableList().also { updated ->
            updated[index] = updated[index].copy(value = value, manual = true, isStale = false)
        }
    }

    fun deleteGrowthEntry(
        entries: List<CharacterGrowthEntry>,
        entryId: String,
    ): List<CharacterGrowthEntry> = entries.filterNot { it.id == entryId }

    fun containersAfterChapterChange(
        store: ContainerStore,
        chapterId: String,
    ): ContainerStore = store.copy(
        entries = store.entries.mapValues { (_, blocks) ->
            blocks.mapValues { (_, chain) ->
                chain.map { entry ->
                    when {
                        entry.manual -> entry.copy(isStale = false)
                        entry.sourceChapterId == chapterId -> entry.copy(isStale = true)
                        else -> entry
                    }
                }
            }
        },
    )
}
