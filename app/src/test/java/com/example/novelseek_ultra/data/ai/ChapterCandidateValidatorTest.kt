package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.QualityFinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterCandidateValidatorTest {
    @Test
    fun `incomplete stream blocks an otherwise valid candidate`() {
        val report = ChapterCandidateValidator.validate(
            candidateText = "这是完整度未知的候选正文。",
            baselineText = "",
            completedStream = false,
            targetWords = 0,
        )

        assertTrue(report.blocking)
        assertFalse(report.completedStream)
        assertEquals(
            listOf(ChapterCandidateValidator.CODE_STREAM_INCOMPLETE),
            report.findings.map { it.code },
        )
        assertEquals(QualityFinding.SEVERITY_ERROR, report.findings.single().severity)
    }

    @Test
    fun `blank candidate is blocked without a redundant length warning`() {
        val report = ChapterCandidateValidator.validate(
            candidateText = " \r\n\t ",
            baselineText = "",
            completedStream = true,
            targetWords = 3_000,
        )

        assertTrue(report.blocking)
        assertEquals(0, report.wordCount)
        assertEquals(
            listOf(ChapterCandidateValidator.CODE_EMPTY_BODY),
            report.findings.map { it.code },
        )
    }

    @Test
    fun `continuation with no net new body is blocked`() {
        val baseline = "第一段。\r\n第二段。"

        val unchanged = ChapterCandidateValidator.validate(
            candidateText = "第一段。\n第二段。\n\n",
            baselineText = baseline,
            completedStream = true,
            targetWords = 0,
        )
        val shorterRewrite = ChapterCandidateValidator.validate(
            candidateText = "第一段。",
            baselineText = baseline,
            completedStream = true,
            targetWords = 0,
        )

        assertTrue(unchanged.blocking)
        assertTrue(shorterRewrite.blocking)
        assertTrue(
            unchanged.findings.any {
                it.code == ChapterCandidateValidator.CODE_CONTINUATION_WITHOUT_NEW_BODY
            },
        )
        assertTrue(
            shorterRewrite.findings.any {
                it.code == ChapterCandidateValidator.CODE_CONTINUATION_WITHOUT_NEW_BODY
            },
        )
    }

    @Test
    fun `continuation with appended prose passes hard gates`() {
        val report = ChapterCandidateValidator.validate(
            candidateText = "旧正文。\n\n这是新增正文。",
            baselineText = "旧正文。",
            completedStream = true,
            targetWords = 0,
        )

        assertFalse(report.blocking)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `non continuation rewrite may be shorter while all other hard gates remain active`() {
        val rewrite = ChapterCandidateValidator.validate(
            candidateText = "精简改稿。",
            baselineText = "这是明显更长的旧正文，用于整章修订。",
            completedStream = true,
            targetWords = 0,
            requireNetNewBody = false,
        )

        assertFalse(rewrite.blocking)
        assertFalse(
            rewrite.findings.any {
                it.code == ChapterCandidateValidator.CODE_CONTINUATION_WITHOUT_NEW_BODY
            },
        )

        val incomplete = ChapterCandidateValidator.validate(
            candidateText = "精简改稿。",
            baselineText = "这是明显更长的旧正文，用于整章修订。",
            completedStream = false,
            targetWords = 0,
            requireNetNewBody = false,
        )
        assertTrue(incomplete.blocking)
        assertTrue(
            incomplete.findings.any {
                it.code == ChapterCandidateValidator.CODE_STREAM_INCOMPLETE
            },
        )
    }

    @Test
    fun `residual beat delimiter is a hard blocker`() {
        val report = ChapterCandidateValidator.validate(
            candidateText = "开场。\n${Prompts.BEAT_DELIM}\n转折。",
            baselineText = "",
            completedStream = true,
            targetWords = 0,
        )

        assertTrue(report.blocking)
        assertTrue(
            report.findings.any {
                it.code == ChapterCandidateValidator.CODE_RESIDUAL_BEAT_MARKER &&
                    it.severity == QualityFinding.SEVERITY_ERROR
            },
        )
    }

    @Test
    fun `candidate below sixty percent of target gets only a warning`() {
        val report = ChapterCandidateValidator.validate(
            candidateText = "甲乙丙丁戊",
            baselineText = "",
            completedStream = true,
            targetWords = 10,
        )

        assertFalse(report.blocking)
        assertEquals(5, report.wordCount)
        assertEquals(
            listOf(ChapterCandidateValidator.CODE_BELOW_TARGET_WORDS),
            report.findings.map { it.code },
        )
        assertEquals(QualityFinding.SEVERITY_WARNING, report.findings.single().severity)
    }

    @Test
    fun `sixty percent threshold is accepted and mixed scripts share editor word count semantics`() {
        val report = ChapterCandidateValidator.validate(
            candidateText = "甲乙 hello world",
            baselineText = "",
            completedStream = true,
            targetWords = 6,
        )

        assertEquals(4, report.wordCount)
        assertFalse(report.blocking)
        assertFalse(
            report.findings.any { it.code == ChapterCandidateValidator.CODE_BELOW_TARGET_WORDS },
        )
    }
}
