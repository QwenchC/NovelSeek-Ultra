package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.ChapterQualityReport
import com.example.novelseek_ultra.data.model.QualityFinding

/**
 * Deterministic, side-effect-free checks applied after a chapter candidate stream terminates.
 *
 * The validator deliberately does not inspect mutable repository state. Its caller supplies the
 * exact baseline that was bound to the generation run, so the same inputs always produce the same
 * report and can be exercised by ordinary JVM tests.
 */
object ChapterCandidateValidator {
    const val CODE_STREAM_INCOMPLETE = "stream_incomplete"
    const val CODE_EMPTY_BODY = "empty_body"
    const val CODE_CONTINUATION_WITHOUT_NEW_BODY = "continuation_without_new_body"
    const val CODE_RESIDUAL_BEAT_MARKER = "residual_beat_marker"
    const val CODE_BELOW_TARGET_WORDS = "below_target_words"

    /** A candidate below 60% of its target is conspicuously short, but remains reviewable. */
    private const val MIN_TARGET_PERCENT = 60

    fun validate(
        candidateText: String,
        baselineText: String,
        completedStream: Boolean,
        targetWords: Int,
        requireNetNewBody: Boolean = true,
    ): ChapterQualityReport {
        val candidate = canonicalText(candidateText)
        val baseline = canonicalText(baselineText)
        val wordCount = countWords(candidate)
        val findings = buildList {
            if (!completedStream) {
                add(blockingFinding(CODE_STREAM_INCOMPLETE, "流式响应未正常完成，候选正文可能被截断"))
            }
            if (candidate.isEmpty()) {
                add(blockingFinding(CODE_EMPTY_BODY, "候选正文为空"))
            }
            if (
                requireNetNewBody &&
                baseline.isNotEmpty() &&
                candidate.isNotEmpty() &&
                candidate.length <= baseline.length
            ) {
                add(
                    blockingFinding(
                        CODE_CONTINUATION_WITHOUT_NEW_BODY,
                        "续写候选没有在基线正文之后产生新增内容",
                    ),
                )
            }
            if (candidate.contains(Prompts.BEAT_DELIM)) {
                add(
                    blockingFinding(
                        CODE_RESIDUAL_BEAT_MARKER,
                        "候选正文仍包含分步生成标记 ${Prompts.BEAT_DELIM}",
                    ),
                )
            }
            if (
                candidate.isNotEmpty() &&
                targetWords > 0 &&
                wordCount.toLong() * 100L < targetWords.toLong() * MIN_TARGET_PERCENT
            ) {
                add(
                    QualityFinding(
                        code = CODE_BELOW_TARGET_WORDS,
                        severity = QualityFinding.SEVERITY_WARNING,
                        message = "候选正文约 $wordCount 字，明显低于目标 $targetWords 字",
                    ),
                )
            }
        }
        return ChapterQualityReport(
            completedStream = completedStream,
            wordCount = wordCount,
            blocking = findings.any { it.severity == QualityFinding.SEVERITY_ERROR },
            findings = findings,
        )
    }

    /** Mirrors the editor's mixed-script approximation: CJK characters + Latin word runs. */
    internal fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (character in text) {
            if (character.code in 0x4E00..0x9FFF) {
                count += 1
                inWord = false
            } else if (character.isLetterOrDigit()) {
                if (!inWord) count += 1
                inWord = true
            } else {
                inWord = false
            }
        }
        return count
    }

    private fun canonicalText(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    private fun blockingFinding(code: String, message: String) = QualityFinding(
        code = code,
        severity = QualityFinding.SEVERITY_ERROR,
        message = message,
    )
}
