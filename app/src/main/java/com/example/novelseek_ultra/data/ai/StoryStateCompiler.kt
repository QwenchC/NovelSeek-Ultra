package com.example.novelseek_ultra.data.ai

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministically compiles the app's existing durable truth sources into one bounded prompt block.
 *
 * This is deliberately not another database. Character growth, entities, summaries, containers and
 * chapter bodies remain owned by their existing stores. The compiler gives every chapter-writing
 * engine one ordered, versioned view of those sources, which also keeps slow-changing prompt prefixes
 * byte-stable for providers with prefix caching.
 */
internal object StoryStateCompiler {
    const val CONTRACT = "story_state.v2"
    const val MAX_PROMPT_CHARS = 16_000

    data class ArcState(
        val id: String,
        val title: String,
        val summary: String,
        val status: String,
        val order: Int,
    )

    data class SummaryState(
        val scope: String,
        val scopeId: String,
        val text: String,
        val stale: Boolean = false,
    )

    data class CommitmentState(
        val id: String,
        val type: String,
        val name: String,
        val summary: String,
        val firstSeenChapterId: String? = null,
    )

    data class EvidenceState(
        val entityId: String? = null,
        val type: String,
        val subject: String,
        val claim: String,
        val statusAfter: String,
        val sourceChapterId: String,
        val sourceChapterOrder: Int,
        val sourceChapterTitle: String,
        val evidenceText: String = "",
    )

    data class CharacterState(
        val characterId: String,
        val name: String,
        val value: String,
        val chapterOrder: Int? = null,
    )

    data class TrackedState(
        val containerId: String,
        val containerName: String,
        val blockKey: String,
        val blockLabel: String,
        val value: String,
    )

    data class RecentChapterState(
        val chapterId: String,
        val order: Int,
        val title: String,
        val goal: String? = null,
        val summary: String? = null,
        val bodyTail: String? = null,
    )

    data class MemoryState(
        val rank: Int,
        val sourceId: String,
        val sourceTitle: String,
        val chunkIndex: Int,
        val text: String,
    )

    data class Input(
        val language: String,
        val arcs: List<ArcState> = emptyList(),
        val summaries: List<SummaryState> = emptyList(),
        val commitments: List<CommitmentState> = emptyList(),
        val evidence: List<EvidenceState> = emptyList(),
        val characters: List<CharacterState> = emptyList(),
        val trackedState: List<TrackedState> = emptyList(),
        val recentChapters: List<RecentChapterState> = emptyList(),
        val retrievedMemory: List<MemoryState> = emptyList(),
    )

    data class Compilation(
        val contract: String = CONTRACT,
        val prompt: String,
        val fingerprint: String,
        val sourceCounts: Map<String, Int>,
        val truncatedSections: List<String>,
    )

    private data class Section(
        val id: String,
        val titleZh: String,
        val titleEn: String,
        val body: String,
        val budget: Int,
        val preserveLatestWholeLines: Boolean = false,
    )

    fun compile(input: Input): Compilation {
        val english = input.language.equals("en", ignoreCase = true)
        val sections = buildList {
            renderArcs(input.arcs, english)?.let(::add)
            renderSummaries(input.summaries, english)?.let(::add)
            renderCommitments(input.commitments, english)?.let(::add)
            renderEvidence(input.evidence, english)?.let(::add)
            renderCharacters(input.characters, english)?.let(::add)
            renderTrackedState(input.trackedState, english)?.let(::add)
            renderRecentChapters(input.recentChapters, english)?.let(::add)
            renderMemory(input.retrievedMemory, english)?.let(::add)
        }

        val truncated = mutableListOf<String>()
        val blocks = mutableListOf<String>()
        var remaining = MAX_PROMPT_CHARS
        sections.forEach { section ->
            if (remaining <= MIN_SECTION_CHARS) {
                truncated += section.id
                return@forEach
            }
            val heading = if (english) "[${section.titleEn}]" else "【${section.titleZh}】"
            val sectionLimit = minOf(section.budget, remaining)
            val normalized = normalize(section.body)
            val availableBody = (sectionLimit - heading.length - 1).coerceAtLeast(0)
            val clipped = if (section.preserveLatestWholeLines) {
                clipLatestWholeLines(normalized, availableBody)
            } else {
                clip(normalized, availableBody)
            }
            if (clipped.length < normalized.length) truncated += section.id
            val block = "$heading\n$clipped".trimEnd()
            if (block.length <= remaining && clipped.isNotBlank()) {
                blocks += block
                remaining -= block.length + SECTION_SEPARATOR.length
            }
        }
        val prompt = blocks.joinToString(SECTION_SEPARATOR)
        val counts = linkedMapOf(
            "arcs" to input.arcs.size,
            "summaries" to input.summaries.size,
            "commitments" to input.commitments.size,
            "fact_evidence" to input.evidence.size,
            "characters" to input.characters.size,
            "tracked_state" to input.trackedState.size,
            "recent_chapters" to input.recentChapters.size,
            "retrieved_memory" to input.retrievedMemory.size,
        )
        return Compilation(
            prompt = prompt,
            fingerprint = fingerprint(CONTRACT, prompt),
            sourceCounts = counts,
            truncatedSections = truncated.distinct(),
        )
    }

    private fun renderArcs(values: List<ArcState>, english: Boolean): Section? {
        val ordered = values
            .filter { it.title.isNotBlank() }
            .sortedWith(
                compareBy<ArcState>(
                    { arcStatusRank(it.status) },
                    { it.order },
                    { it.id },
                    { it.title },
                    { it.summary },
                    { it.status },
                ),
            )
        if (ordered.isEmpty()) return null
        val separator = if (english) ": " else "："
        val body = ordered.joinToString("\n") { arc ->
            val status = if (english) arc.status.ifBlank { "unknown" } else when (arc.status) {
                "active" -> "进行中"
                "ending" -> "收束中"
                "completed" -> "已完成"
                "upcoming" -> "待展开"
                else -> arc.status.ifBlank { "未知" }
            }
            "- ${normalizeInline(arc.title)} [$status]" +
                arc.summary.takeIf { it.isNotBlank() }?.let { "$separator${normalizeInline(it)}" }.orEmpty()
        }
        return Section("arc_progress", "剧情弧线状态", "Story arc state", body, 1_200)
    }

    private fun renderSummaries(values: List<SummaryState>, english: Boolean): Section? {
        // A stale roll-up is not a weaker fact; it is derived from text that no longer matches the
        // project. Omitting it is safer than placing it under an authoritative continuity heading.
        val ordered = values.filter { it.text.isNotBlank() && !it.stale }
            .sortedWith(
                compareBy<SummaryState>(
                    { summaryScopeRank(it.scope) },
                    { it.scopeId },
                    { it.stale },
                    { it.text },
                ),
            )
        if (ordered.isEmpty()) return null
        val body = ordered.joinToString("\n\n") { summary ->
            val label = when (summary.scope) {
                "book" -> if (english) "Book" else "全书"
                "arc" -> if (english) "Arc" else "弧线"
                "chapter" -> if (english) "Chapter" else "章节"
                else -> summary.scope
            }
            "$label ${normalizeInline(summary.scopeId)}\n${normalize(summary.text)}"
        }
        return Section("narrative_summaries", "叙事摘要", "Narrative summaries", body, 1_800)
    }

    private fun renderCommitments(values: List<CommitmentState>, english: Boolean): Section? {
        val ordered = values.filter { it.name.isNotBlank() }
            .sortedWith(
                compareBy<CommitmentState>(
                    { commitmentTypeRank(it.type) },
                    { it.name },
                    { it.id },
                    { it.firstSeenChapterId.orEmpty() },
                    { it.summary },
                ),
            )
        if (ordered.isEmpty()) return null
        val separator = if (english) ": " else "："
        val body = ordered.joinToString("\n") { item ->
            val type = if (english) item.type else when (item.type) {
                "foreshadowing" -> "伏笔"
                "event" -> "事件"
                "item" -> "物品"
                "location" -> "地点"
                "character_ref" -> "角色事实"
                else -> item.type
            }
            "- [$type] ${normalizeInline(item.name)}" +
                item.summary.takeIf { it.isNotBlank() }?.let { "$separator${normalizeInline(it)}" }.orEmpty()
        }
        return Section("open_commitments", "未闭合剧情承诺与事实", "Open story commitments and facts", body, 1_800)
    }

    private fun renderEvidence(values: List<EvidenceState>, english: Boolean): Section? {
        val ordered = values.filter { it.subject.isNotBlank() && it.claim.isNotBlank() }
            .sortedWith(
                compareBy<EvidenceState>(
                    { it.sourceChapterOrder },
                    { commitmentTypeRank(it.type) },
                    { it.subject },
                    { it.entityId.orEmpty() },
                    { it.claim },
                    { it.evidenceText },
                ),
            )
        if (ordered.isEmpty()) return null
        val body = ordered.joinToString("\n") { evidence ->
            val source = if (english) {
                "Ch.${evidence.sourceChapterOrder} ${normalizeInline(evidence.sourceChapterTitle)}"
            } else {
                "第${evidence.sourceChapterOrder}章《${normalizeInline(evidence.sourceChapterTitle)}》"
            }
            val status = evidence.statusAfter.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
            val excerpt = evidence.evidenceText.takeIf { it.isNotBlank() }?.let {
                if (english) " Evidence: “${normalizeInline(it)}”" else " 原文证据：“${normalizeInline(it)}”"
            }.orEmpty()
            "- [$source] ${normalizeInline(evidence.subject)}$status: ${normalizeInline(evidence.claim)}$excerpt"
        }
        return Section(
            "fact_evidence",
            "逐章事实证据",
            "Chapter fact evidence",
            body,
            2_400,
            preserveLatestWholeLines = true,
        )
    }

    private fun renderCharacters(values: List<CharacterState>, english: Boolean): Section? {
        val ordered = values.filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(
                compareBy<CharacterState>(
                    { it.name },
                    { it.characterId },
                    { it.chapterOrder ?: Int.MIN_VALUE },
                    { it.value },
                ),
            )
        if (ordered.isEmpty()) return null
        val separator = if (english) ": " else "："
        val body = ordered.joinToString("\n") { item ->
            val chapter = item.chapterOrder?.let {
                if (english) " (through chapter $it)" else "（截至第${it}章）"
            }.orEmpty()
            "- ${normalizeInline(item.name)}$chapter$separator${normalizeInline(item.value)}"
        }
        return Section("character_state", "角色当前状态", "Current character state", body, 1_600)
    }

    private fun renderTrackedState(values: List<TrackedState>, english: Boolean): Section? {
        val ordered = values.filter { it.containerName.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(
                compareBy<TrackedState>(
                    { it.containerName },
                    { it.containerId },
                    { it.blockLabel },
                    { it.blockKey },
                    { it.value },
                ),
            )
        if (ordered.isEmpty()) return null
        val separator = if (english) ": " else "："
        val body = ordered.joinToString("\n") { item ->
            "- ${normalizeInline(item.containerName)} / ${normalizeInline(item.blockLabel)}$separator${normalizeInline(item.value)}"
        }
        return Section("tracked_state", "结构化追踪状态", "Structured tracked state", body, 1_400)
    }

    private fun renderRecentChapters(values: List<RecentChapterState>, english: Boolean): Section? {
        val ordered = values.filter { it.title.isNotBlank() }
            .sortedWith(
                compareBy<RecentChapterState>(
                    { it.order },
                    { it.chapterId },
                    { it.title },
                    { it.goal.orEmpty() },
                    { it.summary.orEmpty() },
                    { it.bodyTail.orEmpty() },
                ),
            )
            .takeLast(MAX_RECENT_CHAPTERS)
        if (ordered.isEmpty()) return null
        val body = ordered.joinToString("\n\n") { chapter ->
            val heading = if (english) {
                "Chapter ${chapter.order}: ${normalizeInline(chapter.title)}"
            } else {
                "第${chapter.order}章《${normalizeInline(chapter.title)}》"
            }
            val goal = chapter.goal?.takeIf { it.isNotBlank() }?.let {
                if (english) "\nGoal: ${normalizeInline(it)}" else "\n目标：${normalizeInline(it)}"
            }.orEmpty()
            val consequence = chapter.summary?.takeIf { it.isNotBlank() }
                ?: chapter.bodyTail?.takeIf { it.isNotBlank() }
                ?: if (english) "No committed consequence recorded." else "暂无已记录后果。"
            "$heading$goal\n${normalize(consequence)}"
        }
        return Section("recent_consequences", "最近章节已发生事实", "Recent committed consequences", body, 3_200)
    }

    private fun renderMemory(values: List<MemoryState>, english: Boolean): Section? {
        val ordered = values.filter { it.text.isNotBlank() }
            .sortedWith(
                compareBy<MemoryState>(
                    { it.rank },
                    { it.sourceId },
                    { it.chunkIndex },
                    { it.sourceTitle },
                    { it.text },
                ),
            )
        if (ordered.isEmpty()) return null
        val body = ordered.joinToString("\n\n") { memory ->
            val source = if (english) {
                "From ${normalizeInline(memory.sourceTitle)} (chunk ${memory.chunkIndex + 1})"
            } else {
                "摘自《${normalizeInline(memory.sourceTitle)}》（片段 ${memory.chunkIndex + 1}）"
            }
            "$source\n${normalize(memory.text)}"
        }
        return Section("retrieved_memory", "长程相关记忆", "Long-range relevant memory", body, 1_200)
    }

    private fun normalize(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map(String::trimEnd)
        .joinToString("\n")
        .trim()

    private fun normalizeInline(value: String): String = normalize(value)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun clip(value: String, limit: Int): String {
        if (limit <= 0) return ""
        if (value.length <= limit) return value
        if (limit <= TRUNCATION_MARK.length) return value.take(limit)
        return value.take(limit - TRUNCATION_MARK.length).trimEnd() + TRUNCATION_MARK
    }

    private fun clipLatestWholeLines(value: String, limit: Int): String {
        if (limit <= 0) return ""
        if (value.length <= limit) return value
        val marker = "[earlier records truncated]"
        if (marker.length > limit) return ""
        val selected = mutableListOf<String>()
        var used = marker.length
        for (line in value.lineSequence().toList().asReversed()) {
            val extra = 1 + line.length
            if (used + extra <= limit) {
                selected += line
                used += extra
            } else {
                break
            }
        }
        return buildString {
            append(marker)
            selected.asReversed().forEach { line ->
                append('\n')
                append(line)
            }
        }
    }

    private fun arcStatusRank(status: String): Int = when (status) {
        "active" -> 0
        "ending" -> 1
        "upcoming" -> 2
        "completed" -> 3
        else -> 4
    }

    private fun summaryScopeRank(scope: String): Int = when (scope) {
        "book" -> 0
        "arc" -> 1
        "chapter" -> 2
        else -> 3
    }

    private fun commitmentTypeRank(type: String): Int = when (type) {
        "foreshadowing" -> 0
        "event" -> 1
        "item" -> 2
        "location" -> 3
        "character_ref" -> 4
        else -> 5
    }

    private fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }

    private const val MAX_RECENT_CHAPTERS = 3
    private const val MIN_SECTION_CHARS = 64
    private const val SECTION_SEPARATOR = "\n\n"
    private const val TRUNCATION_MARK = "\n… [truncated]"
}
