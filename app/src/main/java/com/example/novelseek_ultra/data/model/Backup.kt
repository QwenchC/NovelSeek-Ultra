package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Mirrors the JSON shape produced by NovelSeek-Pro-PC `SettingsPage.handleExportBackup`. The PC
 * side writes:
 *   { version: 1, exportedAt, appVersion, data: { ... } }
 *
 * `data` is kept as a raw [JsonObject] so that:
 *   1. fields we have not yet ported on Android survive round-trip,
 *   2. import semantics can be implemented as a key-wise merge identical to the PC side.
 */
@Serializable
data class BackupBundle(
    val version: Int = BACKUP_VERSION,
    val exportedAt: String,
    val appVersion: String? = null,
    val data: JsonObject,
) {
    companion object {
        const val BACKUP_VERSION = 1
    }
}

/** Mirrors `PROJECT_MAP_FIELDS` in SettingsPage.tsx — per-project metadata maps. */
val PROJECT_MAP_FIELDS = listOf(
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
    "promoByChapter",
)

/** Mirrors `APP_SETTINGS_FIELDS` in SettingsPage.tsx — global application settings. */
val APP_SETTINGS_FIELDS = listOf(
    "textModelProfiles",
    "activeTextModelProfileId",
    "textModelConfig",
    "pollinationsKey",
    "imageEngine",
    "comfyUIUrl",
    "embeddingConfig",
    "knowledgeBaseEnabled",
    "summariesEnabled",
    "entitiesEnabled",
    "theme",
    "uiLanguage",
)

data class BackupSummary(
    val projectIdsInBackup: Int,
    val projectIdsInStore: Int,
    val projectIdsOverlap: Int,
    val chapterPromosInBackup: Int,
    val hasAppSettings: Boolean,
)

/** Collect distinct project ids that appear as keys in any of the given xxxByProject maps. */
fun collectProjectIds(maps: List<JsonElement?>): Set<String> {
    val ids = mutableSetOf<String>()
    for (m in maps) {
        if (m is JsonObject) ids += m.keys
    }
    return ids
}