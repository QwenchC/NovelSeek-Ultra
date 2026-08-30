package com.example.novelseek_ultra.data

/**
 * Pure classification for chapter mutations that can invalidate AI-derived state.
 * Keep this aligned with [DerivedSourceFingerprint]: prose, title and order are sources;
 * editor-only draft changes hidden behind a non-blank final are not.
 */
internal object DerivedChapterMutation {
    data class Metadata(
        val id: String,
        val title: String,
        val orderIndex: Int,
    )

    data class Presence(
        val orderIndex: Int,
        val wordCount: Int,
        val effectiveText: String,
    )

    fun effectiveText(draft: String, final: String): String = final.ifBlank { draft }

    fun effectiveTextChanged(
        oldDraft: String,
        oldFinal: String,
        newDraft: String,
        newFinal: String,
    ): Boolean = effectiveText(oldDraft, oldFinal) != effectiveText(newDraft, newFinal)

    fun changedMetadataIds(
        before: List<Metadata>,
        after: List<Metadata>,
    ): Set<String> {
        val beforeById = before.associateBy { it.id }
        return after.mapNotNullTo(linkedSetOf()) { current ->
            val previous = beforeById[current.id] ?: return@mapNotNullTo null
            current.id.takeIf {
                current.title != previous.title || current.orderIndex != previous.orderIndex
            }
        }
    }

    fun hasWrittenAtOrAfter(targetOrder: Int?, chapters: List<Presence>): Boolean =
        targetOrder != null && chapters.any { chapter ->
            chapter.orderIndex >= targetOrder &&
                (chapter.wordCount > 0 || chapter.effectiveText.isNotBlank())
        }
}
