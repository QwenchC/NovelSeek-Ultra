package com.example.novelseek_ultra.data.model

import kotlinx.serialization.Serializable

/**
 * One turn in the per-project "ask the novel" Q&A agent. Persisted to
 * `filesDir/novel_chat/{projectId}.json` so the conversation survives navigation/app restart.
 * Retrieval context is NOT stored here — it is rebuilt fresh from the current project on each
 * question so answers always reflect the latest version of the novel.
 */
@Serializable
data class NovelChatMessage(
    val id: String,
    val role: String,          // "user" | "assistant"
    val content: String,
    val createdAt: String = "",
)
