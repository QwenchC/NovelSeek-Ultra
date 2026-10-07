package com.example.novelseek_ultra.data.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** One deterministic quality finding attached to a completed candidate. */
@Serializable
data class QualityFinding(
    val code: String = "",
    val severity: String = SEVERITY_WARNING,
    val message: String = "",
) {
    companion object {
        const val SEVERITY_INFO = "info"
        const val SEVERITY_WARNING = "warning"
        const val SEVERITY_ERROR = "error"
    }
}

/** Result of the non-LLM checks run after a candidate stream has ended. */
@Serializable
data class ChapterQualityReport(
    val completedStream: Boolean = false,
    val wordCount: Int = 0,
    val blocking: Boolean = false,
    val findings: List<QualityFinding> = emptyList(),
)

/** One immutable category in the prompt-context manifest stored with a generation run. */
@Serializable
data class GenerationContextEntry(
    val key: String = "",
    val fingerprint: String = "",
    val itemCount: Int = 0,
)

/**
 * Versioned fingerprint of every repository source that could affect a chapter prompt.
 *
 * Only hashes are persisted: generation records remain small and never duplicate the project's
 * world-building data, while candidate adoption can still detect context drift.
 */
@Serializable
data class GenerationContextManifest(
    val version: Int = GenerationContextFingerprint.VERSION,
    val scope: String = "",
    val fingerprint: String = "",
    val entries: List<GenerationContextEntry> = emptyList(),
) {
    fun isSelfConsistent(): Boolean = GenerationContextFingerprint.isSelfConsistent(this)
}

/** Sanitized identity of the text model used by one generation run. API credentials are excluded. */
@Serializable
data class GenerationModelSnapshot(
    val provider: String = "",
    val model: String = "",
    /** SHA-256 of a canonical endpoint without user-info, query or fragment. */
    val endpointFingerprint: String = "",
    val temperature: Double? = null,
    val thinkingMode: String? = null,
)

/** Provider-reported token accounting for one request. Null means not reported, not zero. */
@Serializable
data class GenerationTokenUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val cacheHitTokens: Long? = null,
    val cacheMissTokens: Long? = null,
)

/** A bounded, prompt-free trace for one model call within a generation run. */
@Serializable
data class GenerationCallTelemetry(
    val ordinal: Int = 0,
    val purpose: String = "",
    /** Fingerprint of ordered role/content pairs; the prompt itself is never persisted. */
    val promptFingerprint: String = "",
    val firstTokenMillis: Long? = null,
    val durationMillis: Long = 0,
    val usage: GenerationTokenUsage? = null,
    val outcome: String = OUTCOME_COMPLETED,
    val failureCategory: String? = null,
) {
    companion object {
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"
        val OUTCOMES = setOf(OUTCOME_COMPLETED, OUTCOME_FAILED, OUTCOME_CANCELLED)
    }
}

/**
 * Final execution telemetry for a generation run.
 *
 * It deliberately contains neither prompt/output text nor raw provider errors. Missing usage is
 * preserved as unknown so an unsupported provider is never displayed as a zero-token request.
 */
@Serializable
data class GenerationTelemetry(
    val version: Int = VERSION,
    val model: GenerationModelSnapshot = GenerationModelSnapshot(),
    val promptContract: String = "",
    val calls: List<GenerationCallTelemetry> = emptyList(),
    /** Run start to the first user-visible prose delta; internal blueprint replies do not count. */
    val firstOutputMillis: Long? = null,
    /** Monotonic execution duration through completion/failure/cancellation, excluding review time. */
    val totalMillis: Long = 0,
    val outcome: String = OUTCOME_COMPLETED,
    val failureCategory: String? = null,
) {
    val requestCount: Int get() = calls.size

    fun promptTokens(): Long? = tokenTotal { it.promptTokens }
    fun completionTokens(): Long? = tokenTotal { it.completionTokens }
    fun totalTokens(): Long? = tokenTotal { it.totalTokens }
    fun cacheHitTokens(): Long? = tokenTotal { it.cacheHitTokens }
    fun cacheMissTokens(): Long? = tokenTotal { it.cacheMissTokens }
    fun usageReportedRequests(): Int = calls.count { it.usage != null }
    fun promptTokensReportedRequests(): Int = calls.count { it.usage?.promptTokens != null }
    fun completionTokensReportedRequests(): Int = calls.count { it.usage?.completionTokens != null }
    fun totalTokensReportedRequests(): Int = calls.count { it.usage?.totalTokens != null }
    fun cacheReportedRequests(): Int = calls.count {
        it.usage?.cacheHitTokens != null && it.usage.cacheMissTokens != null
    }
    fun failedRequestCount(): Int = calls.count {
        it.outcome == GenerationCallTelemetry.OUTCOME_FAILED
    }

    /** Hit/miss totals over the exact same fully-observed request subset. */
    fun cacheObservedTokens(): Pair<Long, Long>? {
        val observed = calls.mapNotNull { call ->
            val usage = call.usage ?: return@mapNotNull null
            val hit = usage.cacheHitTokens ?: return@mapNotNull null
            val miss = usage.cacheMissTokens ?: return@mapNotNull null
            hit to miss
        }
        if (observed.isEmpty()) return null
        return observed.fold(0L to 0L) { total, value ->
            saturatedAdd(total.first, value.first) to saturatedAdd(total.second, value.second)
        }
    }

    /** Weighted cache hit rate over calls that reported both hit and miss counts. */
    fun cacheHitRate(): Double? {
        val (hit, miss) = cacheObservedTokens() ?: return null
        val denominator = hit.toDouble() + miss.toDouble()
        return if (denominator > 0.0) hit.toDouble() / denominator else null
    }

    private fun tokenTotal(selector: (GenerationTokenUsage) -> Long?): Long? {
        val values = calls.mapNotNull { it.usage?.let(selector) }
        if (values.isEmpty()) return null
        return values.fold(0L, ::saturatedAdd)
    }

    companion object {
        const val VERSION = 1
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"
        val OUTCOMES = setOf(OUTCOME_COMPLETED, OUTCOME_FAILED, OUTCOME_CANCELLED)

        const val FAILURE_INVALID_CONFIG = "invalid_config"
        const val FAILURE_ENDPOINT_POLICY = "endpoint_policy"
        const val FAILURE_AUTH = "auth"
        const val FAILURE_QUOTA = "quota"
        const val FAILURE_RATE_LIMIT = "rate_limit"
        const val FAILURE_TIMEOUT = "timeout"
        const val FAILURE_NETWORK = "network"
        const val FAILURE_PROVIDER_4XX = "provider_4xx"
        const val FAILURE_PROVIDER_5XX = "provider_5xx"
        const val FAILURE_MALFORMED_RESPONSE = "malformed_response"
        const val FAILURE_TRUNCATED = "truncated"
        const val FAILURE_CONTENT_FILTER = "content_filter"
        const val FAILURE_EMPTY_OUTPUT = "empty_output"
        const val FAILURE_SOURCE_CONFLICT = "source_conflict"
        const val FAILURE_USER_CANCELLED = "user_cancelled"
        const val FAILURE_SUPERSEDED = "superseded"
        const val FAILURE_PERSISTENCE = "persistence"
        const val FAILURE_UNKNOWN = "unknown"

        val FAILURE_CATEGORIES = setOf(
            FAILURE_INVALID_CONFIG,
            FAILURE_ENDPOINT_POLICY,
            FAILURE_AUTH,
            FAILURE_QUOTA,
            FAILURE_RATE_LIMIT,
            FAILURE_TIMEOUT,
            FAILURE_NETWORK,
            FAILURE_PROVIDER_4XX,
            FAILURE_PROVIDER_5XX,
            FAILURE_MALFORMED_RESPONSE,
            FAILURE_TRUNCATED,
            FAILURE_CONTENT_FILTER,
            FAILURE_EMPTY_OUTPUT,
            FAILURE_SOURCE_CONFLICT,
            FAILURE_USER_CANCELLED,
            FAILURE_SUPERSEDED,
            FAILURE_PERSISTENCE,
            FAILURE_UNKNOWN,
        )
    }
}

private fun saturatedAdd(left: Long, right: Long): Long = when {
    right > 0 && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0 && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

/** Ephemeral source material used to build a [GenerationContextManifest]. */
data class GenerationContextMaterial(
    val key: String,
    val values: List<String>,
)

/**
 * Immutable chapter instructions used by one generation run. The planning fields come from the
 * chapter requested by the editor; they may intentionally differ from the persisted baseline.
 */
@Serializable
data class ChapterSpec(
    val projectId: String = "",
    val chapterId: String = "",
    val title: String = "",
    val orderIndex: Int = 0,
    val outlineGoal: String? = null,
    val conflict: String? = null,
    val twist: String? = null,
    val cliffhanger: String? = null,
    val arcId: String? = null,
    val targetWords: Int = 0,
    val language: String = "zh",
    val mode: String = MODE_ONE_SHOT,
    val beats: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
) {
    companion object {
        const val MODE_ONE_SHOT = "one_shot"
        const val MODE_STEPWISE = "stepwise"

        fun fromChapter(
            projectId: String,
            chapter: Chapter,
            targetWords: Int = 0,
            language: String = "zh",
            mode: String = MODE_ONE_SHOT,
            beats: List<String> = emptyList(),
            constraints: List<String> = emptyList(),
        ): ChapterSpec = ChapterSpec(
            projectId = projectId,
            chapterId = chapter.id,
            title = chapter.title,
            orderIndex = chapter.order_index,
            outlineGoal = chapter.outline_goal,
            conflict = chapter.conflict,
            twist = chapter.twist,
            cliffhanger = chapter.cliffhanger,
            arcId = chapter.arcId,
            targetWords = targetWords,
            language = language,
            mode = mode,
            beats = beats,
            constraints = constraints,
        )
    }

    /**
     * Apply prose-planning fields only. orderIndex/arcId remain part of the generation snapshot,
     * but changing either requires repository-wide reordering/index maintenance and is therefore
     * never smuggled in through candidate adoption.
     */
    fun applyPlanningTo(chapter: Chapter): Chapter = chapter.copy(
        title = title,
        outline_goal = outlineGoal,
        conflict = conflict,
        twist = twist,
        cliffhanger = cliffhanger,
    )
}

/** A reviewable candidate. Streaming deltas are never persisted into [body]. */
@Serializable
data class CandidateChapter(
    val id: String = "",
    /** Stable, one-based presentation order even when candidates finish out of order. */
    val slot: Int = 0,
    val status: String = STATUS_PENDING,
    val body: String = "",
    val wordCount: Int = 0,
    val qualityReport: ChapterQualityReport? = null,
    val completedAt: String? = null,
    val error: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        val TERMINAL_STATUSES = setOf(STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED)
    }

    fun isTerminal(): Boolean = status in TERMINAL_STATUSES
}

/** Durable batch of candidates bound to one exact persisted chapter source. */
@Serializable
data class GenerationRun(
    val id: String = "",
    val projectId: String = "",
    val chapterId: String = "",
    val spec: ChapterSpec = ChapterSpec(),
    /** Hash of the persisted planning baseline and exact draft/final body at run creation. */
    val sourceHash: String = "",
    /** Missing legacy JSON must decode as v1; newly manifested runs are explicitly written as v2. */
    val sourceHashVersion: Int = GenerationSourceFingerprint.LEGACY_VERSION,
    val baselinePlanHash: String = "",
    val baselineBodyHash: String = "",
    /** Full prompt-source version. Null keeps candidates created by older app versions reviewable. */
    val contextManifest: GenerationContextManifest? = null,
    val initiator: String = INITIATOR_EDITOR,
    val agentEngine: String? = null,
    val agentSessionId: String? = null,
    val agentActionId: String? = null,
    val operation: String = OPERATION_GENERATE,
    /** Final, prompt-free execution metrics. Missing legacy JSON decodes as null. */
    val telemetry: GenerationTelemetry? = null,
    val sceneReviewFindings: List<com.example.novelseek_ultra.data.writing.ReviewFinding> = emptyList(),
    val reviewRevisions: List<CandidateReviewRevision> = emptyList(),
    val status: String = STATUS_RUNNING,
    val candidates: List<CandidateChapter> = emptyList(),
    val selectedCandidateId: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val completedAt: String? = null,
    val error: String? = null,
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"

        const val INITIATOR_EDITOR = "editor"
        const val INITIATOR_AGENT = "agent"

        const val OPERATION_GENERATE = "generate"
        const val OPERATION_CONTINUE = "continue"
        const val OPERATION_REVISE = "revise"
        const val OPERATION_REPLACE = "replace"
        const val OPERATION_EDIT_PARAGRAPH = "edit_paragraph"
        const val OPERATION_SET_BODY = "set_body"
        const val CANCEL_REASON_SUPERSEDED = "superseded"

        val TERMINAL_STATUSES = setOf(
            STATUS_COMPLETED,
            STATUS_FAILED,
            STATUS_CANCELLED,
            STATUS_ACCEPTED,
            STATUS_REJECTED,
        )
    }

    fun isTerminal(): Boolean = status in TERMINAL_STATUSES
}

/** Versioned hashes used for compare-and-set adoption of a candidate. */
data class GenerationSourceHashes(
    val planHash: String,
    val bodyHash: String,
    val sourceHash: String,
)

/** Pure JVM fingerprint helper shared by repository adoption and unit tests. */
object GenerationSourceFingerprint {
    const val LEGACY_VERSION = 1
    const val CONTEXT_BOUND_VERSION = 2
    /** Kept as the v1 alias for source compatibility with existing tests and callers. */
    const val VERSION = LEGACY_VERSION

    fun capture(chapter: Chapter, draft: String, final: String): GenerationSourceHashes {
        val planHash = plan(chapter)
        val bodyHash = body(draft, final)
        return GenerationSourceHashes(
            planHash = planHash,
            bodyHash = bodyHash,
            sourceHash = combine(planHash, bodyHash),
        )
    }

    fun plan(chapter: Chapter): String = generationDigest(buildList {
        add("generation-plan")
        add(VERSION.toString())
        add(chapter.project_id)
        add(chapter.id)
        add(chapter.title)
        add(chapter.order_index.toString())
        addNullable(chapter.outline_goal)
        addNullable(chapter.conflict)
        addNullable(chapter.twist)
        addNullable(chapter.cliffhanger)
        addNullable(chapter.arcId)
    })

    fun body(draft: String, final: String): String = generationDigest(
        listOf("generation-body", VERSION.toString(), draft, final),
    )

    fun combine(planHash: String, bodyHash: String): String = generationDigest(
        listOf("generation-source", VERSION.toString(), planHash, bodyHash),
    )

    fun combineWithContext(
        planHash: String,
        bodyHash: String,
        contextFingerprint: String,
    ): String = generationDigest(
        listOf(
            "generation-source",
            CONTEXT_BOUND_VERSION.toString(),
            planHash,
            bodyHash,
            contextFingerprint,
        ),
    )

    private fun MutableList<String>.addNullable(value: String?) {
        add(if (value == null) "0" else "1")
        add(value.orEmpty())
    }

}

@Serializable
data class CandidateReviewRevision(
    val beforeBody: String,
    val instruction: String,
    val createdAt: String,
)

/** Deterministic, order-stable manifest builder shared by runtime code and pure JVM tests. */
object GenerationContextFingerprint {
    const val VERSION = 1

    fun capture(
        scope: String,
        materials: List<GenerationContextMaterial>,
    ): GenerationContextManifest {
        require(scope.isNotBlank()) { "Generation context scope is blank" }
        require(materials.map { it.key }.all { it.isNotBlank() }) {
            "Generation context material key is blank"
        }
        require(materials.map { it.key }.distinct().size == materials.size) {
            "Generation context material keys are not unique"
        }
        val entries = materials.sortedBy { it.key }.map { material ->
            GenerationContextEntry(
                key = material.key,
                fingerprint = generationDigest(
                    buildList {
                        add("generation-context-entry")
                        add(VERSION.toString())
                        add(material.key)
                        add(material.values.size.toString())
                        addAll(material.values)
                    },
                ),
                itemCount = material.values.size,
            )
        }
        return GenerationContextManifest(
            version = VERSION,
            scope = scope,
            fingerprint = manifestFingerprint(VERSION, scope, entries),
            entries = entries,
        )
    }

    fun isSelfConsistent(manifest: GenerationContextManifest): Boolean =
        manifest.version > 0 &&
            manifest.scope.isNotBlank() &&
            manifest.fingerprint.isNotBlank() &&
            manifest.entries.map { it.key }.all { it.isNotBlank() } &&
            manifest.entries.map { it.key }.distinct().size == manifest.entries.size &&
            manifest.entries.all { it.itemCount >= 0 && it.fingerprint.isNotBlank() } &&
            manifest.fingerprint == manifestFingerprint(
                manifest.version,
                manifest.scope,
                manifest.entries.sortedBy { it.key },
            )

    fun changedKeys(
        expected: GenerationContextManifest,
        current: GenerationContextManifest?,
    ): List<String> {
        if (current == null || current.version != expected.version || current.scope != expected.scope) {
            return expected.entries.map { it.key }
        }
        val currentByKey = current.entries.associateBy { it.key }
        return (expected.entries.map { it.key } + current.entries.map { it.key })
            .distinct()
            .filter { key -> expected.entries.firstOrNull { it.key == key } != currentByKey[key] }
            .sorted()
    }

    private fun manifestFingerprint(
        version: Int,
        scope: String,
        entries: List<GenerationContextEntry>,
    ): String = generationDigest(buildList {
        add("generation-context-manifest")
        add(version.toString())
        add(scope)
        add(entries.size.toString())
        entries.forEach { entry ->
            add(entry.key)
            add(entry.itemCount.toString())
            add(entry.fingerprint)
        }
    })
}

private fun generationDigest(parts: List<String>): String {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        md.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        md.update(bytes)
    }
    return md.digest().toHex()
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0x0f])
    }
}

/** Outcome of the only operation allowed to publish a candidate into the official chapter. */
sealed class CandidateAdoptionResult {
    data class Adopted(val run: GenerationRun, val chapter: Chapter) : CandidateAdoptionResult()

    data class SourceChanged(
        val planChanged: Boolean,
        val bodyChanged: Boolean,
        val contextChanged: Boolean = false,
        val contextKeys: List<String> = emptyList(),
    ) : CandidateAdoptionResult()

    data class Unavailable(val reason: String) : CandidateAdoptionResult()

    companion object {
        const val RUN_NOT_FOUND = "run_not_found"
        const val RUN_NOT_READY = "run_not_ready"
        const val CANDIDATE_NOT_FOUND = "candidate_not_found"
        const val CANDIDATE_NOT_READY = "candidate_not_ready"
        const val STREAM_INCOMPLETE = "stream_incomplete"
        const val QUALITY_BLOCKED = "quality_blocked"
        const val EMPTY_BODY = "empty_body"
        const val CHAPTER_NOT_FOUND = "chapter_not_found"
        const val ALREADY_ACCEPTED = "already_accepted"
    }
}
