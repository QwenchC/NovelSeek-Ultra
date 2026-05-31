package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable

/**
 * "容器" (Container) — a flexible, optionally AI-evolved knowledge store attached to a project.
 *
 * A container is partitioned into BLOCKS by its [type]:
 *   - by_character: one block per character (blockKey = characterId)
 *   - by_chapter:   one block per chapter   (blockKey = chapterId)
 *   - single:       one block               (blockKey = "main")
 *
 * Blocks are NOT stored explicitly — they are derived live from the project's current characters /
 * chapters, so adding a character/chapter automatically yields a new (empty) block. Each block holds
 * a CHAIN of [ContainerEntry] values (oldest → newest); the newest is user-editable. When
 * [autoUpdatePerChapter] is on, saving the latest chapter asks the AI to append a new entry (or skip
 * if nothing changed). When [affectsGeneration] is on, the latest values are injected into chapter
 * generation as soft guidance.
 *
 * The whole container store rides inside `containersByProject` (app_state), so version snapshots and
 * restores cover it automatically — each branch keeps its own container history.
 */
@Serializable
data class Container(
    val id: String,
    val name: String,
    val type: String,                       // by_character | by_chapter | single
    val autoUpdatePerChapter: Boolean = false,
    val affectsGeneration: Boolean = false,
    val affectsVolumeGeneration: Boolean = false,   // inject latest values into 副本 generation
    val affectsArcGeneration: Boolean = false,      // inject latest values into 剧情弧线 generation
    val createdAt: String = "",
) {
    companion object {
        const val BY_CHARACTER = "by_character"
        const val BY_CHAPTER = "by_chapter"
        const val SINGLE = "single"
        const val SINGLE_BLOCK_KEY = "main"
    }
}

/** One value in a block's chain. [sourceChapter*] records which chapter produced an AI update. */
@Serializable
data class ContainerEntry(
    val id: String,
    val value: String,
    val sourceChapterId: String? = null,
    val sourceChapterOrder: Int? = null,
    val sourceChapterTitle: String? = null,
    val createdAt: String = "",
    val manual: Boolean = false,
)

/** Persisted shape of one project's containers (the value of `containersByProject[projectId]`). */
@Serializable
data class ContainerStore(
    val containers: List<Container> = emptyList(),
    // containerId -> (blockKey -> chain of entries, oldest first)
    val entries: Map<String, Map<String, List<ContainerEntry>>> = emptyMap(),
)
