package com.example.novelseek_ultra.data.writing

data class ImportedChapter(val title: String, val body: String)
data class ManuscriptPreview(val chapters: List<ImportedChapter>, val totalCharacters: Int)

/** Only plain text/Markdown: never executes HTML or interprets a local path from input. */
object ManuscriptImporter {
    const val MAX_CHARACTERS = 5_000_000
    private val chapterHeading = Regex("^(?:#{1,3}\\s+(.{1,250})|((?:第[零〇一二三四五六七八九十百千万两0-9]+[章节回卷部].{0,200}|Chapter\\s+\\d+(?:[\\s：:、.．].{0,200})?)))$", RegexOption.IGNORE_CASE)

    fun preview(text: String): ManuscriptPreview {
        require(text.length <= MAX_CHARACTERS) { "书稿超过 500 万字符，请分卷导入" }
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        require(normalized.isNotBlank()) { "书稿为空" }
        val chapters = mutableListOf<ImportedChapter>()
        var title = "序章"
        val lines = mutableListOf<String>()
        fun flush() {
            val body = lines.joinToString("\n").trim('\n')
            if (body.isNotBlank()) chapters += ImportedChapter(title, body)
            lines.clear()
        }
        for (line in normalized.lineSequence()) {
            val match = chapterHeading.matchEntire(line.trim())
            if (match != null) {
                flush()
                title = match.groupValues.drop(1).first { it.isNotBlank() }.trim()
            } else lines += line
        }
        flush()
        require(chapters.isNotEmpty()) { "没有可导入的正文" }
        require(chapters.size <= 2_000) { "章节超过 2,000 个，请分卷导入" }
        return ManuscriptPreview(chapters, normalized.length)
    }
}
