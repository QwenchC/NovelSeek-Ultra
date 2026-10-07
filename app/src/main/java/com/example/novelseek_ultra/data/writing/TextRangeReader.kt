package com.example.novelseek_ultra.data.writing

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Offsets are UTF-16 character indexes, matching Kotlin String and exact chapter replacements. */
object TextRangeReader {
    const val MAX_READ_LENGTH = 12_000
    const val MAX_SEARCH_RESULTS = 30
    const val MAX_CONTEXT_LENGTH = 300

    @Serializable
    data class Range(
        val offset: Int,
        val endOffset: Int,
        val total: Int,
        val nextOffset: Int?,
        val sourceHash: String,
        val text: String,
        val unit: String = "UTF-16",
    )

    data class Source(val id: String, val title: String, val kind: String, val text: String)

    @Serializable
    data class Hit(
        val sourceId: String,
        val title: String,
        val kind: String,
        val offset: Int,
        val endOffset: Int,
        val contextOffset: Int,
        val context: String,
        val total: Int,
        val sourceHash: String,
    )

    @Serializable
    data class SearchPage(
        val query: String,
        val offset: Int,
        val total: Int,
        val nextOffset: Int?,
        val hits: List<Hit>,
        val unit: String = "UTF-16; search offset/nextOffset count matches",
    )

    fun read(text: String, offset: Int = 0, limit: Int = 8_000): Range {
        require(offset in 0..text.length) { "offset must be within 0..${text.length}" }
        require(limit in 1..MAX_READ_LENGTH) { "limit must be within 1..$MAX_READ_LENGTH" }
        val start = safeStart(text, offset)
        var end = safeStart(text, (start.toLong() + limit).coerceAtMost(text.length.toLong()).toInt())
        // A one-character request must still advance across a supplementary code point.
        if (end == start && start < text.length) end = safeEnd(text, start + 1)
        return Range(start, end, text.length, end.takeIf { it < text.length }, hash(text), text.substring(start, end))
    }

    /** Match pagination is stable while sourceHash remains unchanged. Reads sources lazily. */
    fun search(
        sources: Iterable<Source>,
        query: String,
        offset: Int = 0,
        limit: Int = 10,
        contextLength: Int = 120,
        ignoreCase: Boolean = false,
    ): SearchPage {
        require(query.isNotBlank() && query.length <= 500) { "query must contain 1..500 characters" }
        require(query.indices.all { index ->
            val char = query[index]
            when {
                char.isHighSurrogate() -> index + 1 < query.length && query[index + 1].isLowSurrogate()
                char.isLowSurrogate() -> index > 0 && query[index - 1].isHighSurrogate()
                else -> true
            }
        }) { "query must contain complete Unicode characters" }
        require(offset >= 0) { "offset must be non-negative" }
        require(limit in 1..MAX_SEARCH_RESULTS) { "limit must be within 1..$MAX_SEARCH_RESULTS" }
        require(contextLength in 0..MAX_CONTEXT_LENGTH) { "contextLength must be within 0..$MAX_CONTEXT_LENGTH" }
        val hits = mutableListOf<Hit>()
        var total = 0
        for (source in sources) {
            var cursor = 0
            var sourceHash: String? = null
            while (cursor <= source.text.length) {
                val index = source.text.indexOf(query, cursor, ignoreCase)
                if (index < 0) break
                val end = index + query.length
                if (total >= offset && hits.size < limit) {
                    val contextStart = safeStart(source.text, (index - contextLength).coerceAtLeast(0))
                    val contextEnd = safeEnd(source.text, (end.toLong() + contextLength).coerceAtMost(source.text.length.toLong()).toInt())
                    if (sourceHash == null) sourceHash = hash(source.text)
                    hits += Hit(source.id, source.title, source.kind, index, end, contextStart,
                        source.text.substring(contextStart, contextEnd), source.text.length, sourceHash)
                }
                total++
                cursor = end
            }
        }
        val next = offset.toLong() + hits.size
        return SearchPage(query, offset, total, next.toInt().takeIf { next < total }, hits)
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun safeStart(text: String, offset: Int): Int =
        if (offset > 0 && offset < text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset - 1 else offset

    private fun safeEnd(text: String, offset: Int): Int =
        if (offset > 0 && offset < text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset + 1 else offset
}
