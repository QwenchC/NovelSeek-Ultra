package com.example.novelseek_ultra.ui.components

/** Exact, lossless paragraph groups used by the review UI. No whitespace is normalised. */
data class ChapterReviewDiffBlock(
    val id: Int,
    val baselineText: String,
    val candidateText: String,
) {
    val changed: Boolean get() = baselineText != candidateText
}

/**
 * Uses unique paragraph anchors and an increasing subsequence, never an N × M edit matrix.
 * Repeated/reordered paragraphs are grouped into a larger change so selecting a change can
 * never duplicate or silently discard a paragraph. The two full versions remain recoverable.
 */
fun chapterReviewDiff(
    baselineText: String,
    candidateText: String,
    maxParagraphs: Int = 1_200,
    maxParagraphChars: Int = 2_400,
): List<ChapterReviewDiffBlock> {
    require(maxParagraphs > 0)
    require(maxParagraphChars >= 2)
    if (baselineText == candidateText) {
        return if (baselineText.isEmpty()) emptyList()
        else listOf(ChapterReviewDiffBlock(0, baselineText, candidateText))
    }
    val before = boundedReviewParagraphs(baselineText, maxParagraphs, maxParagraphChars)
    val after = boundedReviewParagraphs(candidateText, maxParagraphs, maxParagraphChars)
    val beforeOccurrences = before.groupingBy { it }.eachCount()
    val afterOccurrences = after.groupingBy { it }.eachCount()
    val afterIndices = after.withIndex().associate { it.value to it.index }
    val possibleAnchors = before.mapIndexedNotNull { index, paragraph ->
        if (beforeOccurrences[paragraph] == 1 && afterOccurrences[paragraph] == 1) {
            index to afterIndices.getValue(paragraph)
        } else null
    }
    val anchors = increasingReviewAnchors(possibleAnchors)
    val blocks = mutableListOf<ChapterReviewDiffBlock>()
    var beforeStart = 0
    var afterStart = 0
    fun appendBlock(left: String, right: String) {
        if (left.isNotEmpty() || right.isNotEmpty()) {
            blocks += ChapterReviewDiffBlock(blocks.size, left, right)
        }
    }
    for ((beforeIndex, afterIndex) in anchors) {
        appendBlock(
            before.subList(beforeStart, beforeIndex).joinToString(""),
            after.subList(afterStart, afterIndex).joinToString(""),
        )
        appendBlock(before[beforeIndex], after[afterIndex])
        beforeStart = beforeIndex + 1
        afterStart = afterIndex + 1
    }
    appendBlock(before.subList(beforeStart, before.size).joinToString(""), after.subList(afterStart, after.size).joinToString(""))
    return blocks
}

/** Adopted change IDs use candidate text; all other changes retain original text. */
fun assembleChapterReviewText(
    blocks: List<ChapterReviewDiffBlock>,
    adoptedChangeIds: Set<Int>,
): String = buildString {
    blocks.forEach { block ->
        append(if (!block.changed || block.id in adoptedChangeIds) block.candidateText else block.baselineText)
    }
}

/** Lazy previews preserve every character and never split a UTF-16 surrogate pair. */
fun chapterReviewTextChunks(text: String, maxChars: Int = 2_400): List<String> {
    require(maxChars >= 2)
    if (text.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxChars, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        // Prefer a nearby paragraph boundary without making one enormous Text composable.
        if (end < text.length) {
            val lineEnd = text.lastIndexOf('\n', end - 1)
            if (lineEnd >= start + (end - start) / 2) end = lineEnd + 1
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

private fun boundedReviewParagraphs(text: String, maxParagraphs: Int, maxParagraphChars: Int): List<String> {
    if (text.isEmpty()) return emptyList()
    // Bound working memory even for hundreds of thousands of tiny paragraphs.
    val minimumGroupChars = maxOf(1, (text.length + maxParagraphs - 1) / maxParagraphs)
    val paragraphs = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val nextLine = text.indexOf('\n', start)
        var end = if (nextLine < 0) text.length else nextLine + 1
        while (end - start < minimumGroupChars && end < text.length) {
            val followingLine = text.indexOf('\n', end)
            end = if (followingLine < 0) text.length else followingLine + 1
        }
        // A very long single paragraph also has bounded anchors.
        end = minOf(end, start + maxOf(maxParagraphChars, minimumGroupChars))
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        paragraphs += text.substring(start, end)
        start = end
    }
    if (paragraphs.size <= maxParagraphs) return paragraphs
    val groupSize = (paragraphs.size + maxParagraphs - 1) / maxParagraphs
    return paragraphs.chunked(groupSize).map { it.joinToString("") }
}

private fun increasingReviewAnchors(anchors: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    if (anchors.isEmpty()) return emptyList()
    val tails = IntArray(anchors.size)
    val predecessors = IntArray(anchors.size) { -1 }
    var length = 0
    anchors.forEachIndexed { index, (_, rightIndex) ->
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (anchors[tails[middle]].second < rightIndex) low = middle + 1 else high = middle
        }
        if (low > 0) predecessors[index] = tails[low - 1]
        tails[low] = index
        if (low == length) length++
    }
    val result = ArrayList<Pair<Int, Int>>(length)
    var cursor = tails[length - 1]
    while (cursor >= 0) {
        result += anchors[cursor]
        cursor = predecessors[cursor]
    }
    result.reverse()
    return result
}
