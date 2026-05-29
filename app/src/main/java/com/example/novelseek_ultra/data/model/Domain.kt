package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable

/**
 * Domain model mirrors PC `src/types/index.ts` and `src/store/index.ts`. Field names follow the PC
 * JSON shape (snake_case for Project/Chapter, camelCase for everything else) so that backup files
 * round-trip without any key remapping.
 */

typealias UiLanguage = String  // "zh" | "en"

@Serializable
data class Project(
    val id: String,
    val title: String,
    val author: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val language: String? = null,
    val target_word_count: Int? = null,
    val current_word_count: Int = 0,
    val status: String = "draft",        // draft | in_progress | completed
    val created_at: String = "",
    val updated_at: String = "",
    val cover_images: String? = null,
    val default_cover_id: String? = null,
)

@Serializable
data class ProjectFolder(
    val id: String,
    val name: String,
    val emoji: String,
    val projectIds: List<String> = emptyList(),
)

@Serializable
data class Chapter(
    val id: String,
    val project_id: String,
    val title: String,
    val order_index: Int,
    val outline_goal: String? = null,
    val conflict: String? = null,
    val twist: String? = null,
    val cliffhanger: String? = null,
    val word_count: Int = 0,
    val status: String = "draft",
    val created_at: String = "",
    val updated_at: String = "",
)

@Serializable
data class Character(
    val id: String,
    val name: String,
    val gender: String = "",
    val role: String = "",
    val personality: String = "",
    val background: String = "",
    val motivation: String = "",
    val appearance: String = "",
    val portraitBase64: String? = null,
    val portraitPrompt: String? = null,
    val isProtagonist: Boolean = false,
    val currentRealmId: String? = null,
    val currentSubRealmId: String? = null,
)

@Serializable
data class ChapterPromo(
    val imagePrompt: String,
    val summary: String,
    val imageBase64: String? = null,
)

@Serializable
data class CoverImageConfig(
    val model: String = "zimage",
    val style: String = "",
    val width: Int = 1080,
    val height: Int = 1920,
)

@Serializable
data class CoverImageItem(
    val id: String,
    val name: String,
    val imageBase64: String,
    val prompt: String? = null,
    val createdAt: String? = null,
    val config: CoverImageConfig? = null,
)

/**
 * Inline illustration anchored to a paragraph inside a chapter's body text. Mirrors PC's
 * `Illustration` interface in `src/pages/EditorPage.tsx` so JSON shape is structurally identical.
 *   - anchorIndex is 1-based, points at the paragraph the picture renders under.
 *   - paragraphIndices remembers the original selection used to seed the AI prompt (so the user
 *     can see which paragraphs the picture summarised).
 *   - imageBase64 is raw PNG/JPEG bytes encoded as Base64 (NO_WRAP). Stored separately per
 *     chapter to keep app_state.json small — see AppRepository.chapterIllustrations().
 */
@Serializable
data class Illustration(
    val id: String,
    val anchorIndex: Int,
    val paragraphIndices: List<Int> = emptyList(),
    val prompt: String = "",
    val imageBase64: String,
    val createdAt: String = "",
)

@Serializable
data class PlotArc(
    val id: String,
    val title: String,
    val summary: String,
    val order: Int,
    val status: String,                  // upcoming | active | ending | completed
    val chaptersUntilEnd: Int? = null,
    val chapterCount: Int = 0,
    val miniOutline: String? = null,
    val builtChapterIds: List<String>? = null,
)

@Serializable
data class CharacterRelationship(
    val id: String,
    val fromCharId: String,
    val toCharId: String,
    val type: String,
    val description: String,
)

@Serializable
data class CharacterEvent(
    val id: String,
    val characterId: String,
    val arcId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val title: String,
    val description: String,
)

@Serializable
data class CultivationSubRealm(
    val id: String,
    val order: Int,
    val name: String,
    val description: String? = null,
)

@Serializable
data class CultivationRealm(
    val id: String,
    val order: Int,
    val name: String,
    val description: String? = null,
    val subRealms: List<CultivationSubRealm>? = null,
)

@Serializable
data class CharacterRealmEvent(
    val id: String,
    val characterId: String,
    val realmId: String,
    val chapterId: String,
    val chapterOrderIndex: Int,
    val note: String? = null,
)

// ── Model / API configuration ─────────────────────────────────

@Serializable
data class TextModelConfig(
    val provider: String = "deepseek",
    val apiKey: String = "",
    val apiUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
)

@Serializable
data class TextModelProfile(
    val id: String,
    val name: String,
    val provider: String,
    val apiKey: String = "",
    val apiUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val builtIn: Boolean = false,
    val keyUrl: String? = null,
)

@Serializable
data class EmbeddingConfig(
    val apiKey: String = "",
    val apiUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val model: String = "text-embedding-v3",
    val dimensions: Int? = 1024,
)