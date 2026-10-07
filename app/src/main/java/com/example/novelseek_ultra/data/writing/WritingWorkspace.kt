package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Project preferences contain profile identifiers only; credentials remain in SecureStore. */
@Serializable
data class WritingWorkspace(
    val version: Int = 1,
    val mode: String = "scene",
    val style: String = "",
    val perspective: String = "",
    val forbiddenExpressions: String = "",
    val sampleProse: String = "",
    val maxRequestsPerRun: Int = 16,
    val planningProfileId: String? = null,
    val writingProfileId: String? = null,
    val reviewProfileId: String? = null,
    val extractionProfileId: String? = null,
    val notes: List<StoryNote> = emptyList(),
) {
    fun validated(): WritingWorkspace {
        require(mode in setOf("quick", "scene", "polish")) { "未知写作模式" }
        require(maxRequestsPerRun in 1..64) { "单次请求上限应在 1–64 之间" }
        require(listOf(style, perspective, forbiddenExpressions, sampleProse).all { it.length <= 12_000 }) {
            "写作偏好单项不能超过 12,000 字符"
        }
        require(notes.size <= 2_000 && notes.map { it.id }.distinct().size == notes.size) { "故事卡片过多或 ID 重复" }
        require(notes.sumOf { it.subject.length.toLong() + it.text.length } <= 300_000) { "故事卡片总量超过 30 万字符，请精简或分项目保存" }
        notes.forEach { it.validated() }
        return this
    }

    fun profileId(role: String): String? = when (role) {
        "planning" -> planningProfileId
        "review" -> reviewProfileId
        "extraction" -> extractionProfileId
        else -> writingProfileId
    }

    /** Operational settings can change for a retry without changing the story source. */
    fun contentFingerprintMaterial(): List<String> = listOf(style, perspective, forbiddenExpressions, sampleProse,
        Json.encodeToString(ListSerializer(StoryNote.serializer()), notes.sortedBy { it.id }))

    fun preferencePrompt(): String = buildString {
        if (style.isNotBlank()) appendLine("文风与节奏：$style")
        if (perspective.isNotBlank()) appendLine("叙述人称与视角：$perspective")
        if (forbiddenExpressions.isNotBlank()) appendLine("避免的表达：$forbiddenExpressions")
        if (sampleProse.isNotBlank()) appendLine("作者认可的文风样例（仅学习表达，不复制剧情）：\n$sampleProse")
    }
}

@Serializable
data class StoryNote(
    val id: String,
    /** canon / plan / fact / belief / foreshadowing. These meanings must never be conflated. */
    val kind: String = "canon",
    val subject: String = "",
    val text: String = "",
    val sourceChapterId: String? = null,
    /** Exact effective source-body digest; editing the source makes this card stale. */
    val sourceBodyHash: String? = null,
    val knownByCharacterIds: List<String> = emptyList(),
    val payoffChapterId: String? = null,
    val resolved: Boolean = false,
    val importance: Int = 1,
) {
    fun validated(): StoryNote {
        require(id.isNotBlank() && id.length <= 128)
        require(kind in setOf("canon", "plan", "fact", "belief", "foreshadowing"))
        require(subject.length <= 256 && text.isNotBlank() && text.length <= 12_000)
        require(importance in 1..3 && knownByCharacterIds.size <= 100)
        require(kind != "fact" || !sourceChapterId.isNullOrBlank()) { "已发生事实必须关联来源章节" }
        require(sourceBodyHash == null || sourceBodyHash.matches(Regex("[a-f0-9]{64}"))) { "故事卡片来源摘要无效" }
        return this
    }
}

data class SelectedStoryNotes(val prompt: String, val includedIds: List<String>, val excludedIds: List<String>,
    val exclusionReasons: Map<String, String> = emptyMap())

/** Select chapter-relevant cards before rendering, without truncating a card's meaning. */
object StoryNoteSelector {
    fun select(workspace: WritingWorkspace, chapter: Chapter, chapters: List<Chapter>, maxChars: Int = 12_000,
        sourceHashes: Map<String, String>? = null, characterNames: Map<String, String>? = null): SelectedStoryNotes {
        val byId = chapters.associateBy { it.id }
        val selected = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        val reasons = linkedMapOf<String, String>()
        val prompt = StringBuilder()
        val ranked = workspace.notes.sortedWith(
            compareByDescending<StoryNote> { it.payoffChapterId == chapter.id }
                .thenByDescending { it.importance }.thenBy { it.id },
        )
        for (note in ranked) {
            val source = note.sourceChapterId?.let(byId::get)
            val historicallyVisible = when (note.kind) {
                "fact", "belief" -> source != null && source.order_index < chapter.order_index
                "foreshadowing" -> !note.resolved && (note.sourceChapterId == null ||
                    (source != null && source.order_index < chapter.order_index))
                else -> true
            }
            if (!historicallyVisible) { excluded += note.id; reasons[note.id] = "尚未发生、来源缺失或已回收"; continue }
            if (characterNames != null && note.knownByCharacterIds.any { it !in characterNames }) {
                excluded += note.id; reasons[note.id] = "知情角色已删除，需重新核对"; continue
            }
            if (sourceHashes != null && note.sourceChapterId != null &&
                (note.sourceBodyHash == null || note.sourceBodyHash != sourceHashes[note.sourceChapterId])) {
                excluded += note.id; reasons[note.id] = "来源正文已变化，需重新核对卡片"; continue
            }
            val label = when (note.kind) {
                "canon" -> "作者设定（不代表角色已知）"
                "plan" -> "未来计划（不能当作已发生事实）"
                "fact" -> "已发生事实，来源：${source?.title}"
                "belief" -> "角色的认知/误解，不代表客观事实"
                else -> "未回收伏笔"
            }
            val rendered = buildString {
                appendLine("[$label] ${note.subject}: ${note.text}")
                if (note.knownByCharacterIds.isNotEmpty()) appendLine("知情角色：${note.knownByCharacterIds.joinToString { characterNames?.get(it) ?: it }}；其他角色不可直接获知")
                if (note.payoffChapterId != null) appendLine("计划回收章节：${byId[note.payoffChapterId]?.title ?: "未知章节"}")
                if (note.payoffChapterId == chapter.id) appendLine("本章应处理此伏笔")
            }
            // Canon and cards explicitly scheduled for this chapter are hard requirements.
            if (prompt.length + rendered.length > maxChars) {
                if (note.kind == "canon" || note.payoffChapterId == chapter.id) {
                    throw IllegalArgumentException("本章必需故事卡片超过上下文预算，请精简设定或扩大上下文")
                }
                excluded += note.id
                reasons[note.id] = "本次可选上下文预算不足"
            } else { prompt.append(rendered); selected += note.id }
        }
        return SelectedStoryNotes(prompt.toString(), selected, excluded, reasons)
    }
}

@Serializable
data class WritingUsageEntry(
    val runId: String,
    val model: String,
    val completedAt: String,
    val requestCount: Int,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val cacheHitTokens: Long? = null,
    val failedRequests: Int = 0,
    val chapterId: String? = null,
    val parentRunId: String? = null,
)

fun GenerationTelemetry.toWritingUsage(runId: String, completedAt: String, chapterId: String? = null,
    parentRunId: String? = null) = WritingUsageEntry(
    runId, model.model, completedAt, requestCount, promptTokens(), completionTokens(), cacheHitTokens(), failedRequestCount(), chapterId, parentRunId,
)
