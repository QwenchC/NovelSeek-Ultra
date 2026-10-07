package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.model.TextModelConfig
import kotlinx.serialization.Serializable

/** Author-editable scene constraints, separate from the generated prose. */
@Serializable
data class SceneSpec(
    val id: String,
    val title: String,
    val pov: String = "",
    val time: String = "",
    val location: String = "",
    val goal: String = "",
    val conflict: String = "",
    val turn: String = "",
    val entryState: String = "",
    val exitState: String = "",
    val requiredEvents: List<String> = emptyList(),
    val forbiddenEvents: List<String> = emptyList(),
    val targetWords: Int = 1_500,
)

@Serializable
data class ChapterScenePlan(
    val projectId: String,
    val chapterId: String,
    val sourceFingerprint: String,
    val scenes: List<SceneSpec>,
    val updatedAt: Long,
)

@Serializable
enum class WritingMode { FAST, SCENES, POLISHED }

@Serializable
enum class WritingStatus { PLANNED, RUNNING, INTERRUPTED, FAILED, COMPLETED }

@Serializable
data class CompletedScene(
    val sceneId: String,
    val body: String,
    /** Expected plan constraint for continuation, never an extracted or verified story fact. */
    val exitState: String,
    val completedAt: Long,
)

@Serializable
enum class ReviewCategory { FACT_CONFLICT, KNOWLEDGE_LEAK, MISSING_EVENT, STYLE, PACING, OTHER }

@Serializable
enum class ReviewSeverity { BLOCKING, WARNING, SUGGESTION }

/** Offsets are UTF-16 offsets within the identified scene body, not within the assembled chapter. */
@Serializable
data class ReviewFinding(
    val sceneId: String,
    val category: ReviewCategory,
    val severity: ReviewSeverity,
    val quote: String,
    val startOffset: Int,
    val endOffset: Int,
    val constraint: String = "",
    val explanation: String,
    val suggestion: String,
)

/** A durable draft. The pipeline never writes this content into the official chapter body. */
@Serializable
data class WritingCheckpoint(
    val runId: String,
    val revision: Long,
    val sourceFingerprint: String,
    val plan: ChapterScenePlan,
    val completedScenes: List<CompletedScene> = emptyList(),
    val status: WritingStatus = WritingStatus.PLANNED,
    val error: String? = null,
    val mode: WritingMode = WritingMode.SCENES,
    val requestCount: Int = 0,
    val reviewFindings: List<ReviewFinding> = emptyList(),
    val reviewCompleted: Boolean = false,
    val updatedAt: Long,
    /** Original task and language survive app restarts, including continuation and draft hints. */
    val chapterTask: String = "",
    val language: String = "zh",
    /** Existing prose prefix for a continuation; separate from generated scene bodies. */
    val baselineText: String = "",
) {
    val body: String get() = completedScenes.joinToString("\n\n") { it.body }
}

data class WritingRequest(
    val projectId: String,
    val chapterId: String,
    val sourceFingerprint: String,
    val chapterTask: String,
    val stableContext: String,
    val config: TextModelConfig,
    val mode: WritingMode = WritingMode.SCENES,
    val plan: ChapterScenePlan? = null,
    val resumeRunId: String? = null,
    val optionalReview: Boolean = mode == WritingMode.POLISHED,
    val targetWords: Int = 3_000,
    val language: String = "zh",
    val maxRequests: Int = 32,
    val maxRetries: Int = 1,
    val maxContinuationsPerScene: Int = 2,
    /** Rechecked before requests and commits when the host supplies a live source reader. */
    val currentSourceFingerprint: (() -> String)? = null,
    val baselineText: String = "",
)

data class WritingProgress(
    val stage: String,
    val checkpoint: WritingCheckpoint? = null,
    val sceneId: String? = null,
    /** Replace-style preview of the current scene only. */
    val preview: String = "",
)

data class WritingResult(val checkpoint: WritingCheckpoint) {
    val body: String get() = checkpoint.body
    val fullBody: String get() = listOf(checkpoint.baselineText, body).filter { it.isNotBlank() }.joinToString("\n\n")
    val findings: List<ReviewFinding> get() = checkpoint.reviewFindings
}

@Serializable
data class WritingArchive(
    val version: Int = 1,
    val plans: List<ChapterScenePlan> = emptyList(),
    val checkpoints: List<WritingCheckpoint> = emptyList(),
)

class WritingSourceChangedException(message: String) : IllegalStateException(message)
class WritingStaleRunException(message: String) : IllegalStateException(message)
class WritingQuotaException(message: String) : IllegalStateException(message)
