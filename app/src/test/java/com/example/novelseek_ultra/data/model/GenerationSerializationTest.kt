package com.example.novelseek_ultra.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `generation run chapter spec candidate and quality report round trip`() {
        val original = GenerationRun(
            id = "run-1",
            projectId = "project-1",
            chapterId = "chapter-1",
            spec = ChapterSpec(
                projectId = "project-1",
                chapterId = "chapter-1",
                title = "第一章",
                orderIndex = 1,
                outlineGoal = "踏入山门",
                conflict = "守门人阻拦",
                targetWords = 3_000,
                mode = ChapterSpec.MODE_STEPWISE,
                beats = listOf("抵达", "交锋"),
                constraints = listOf("第一人称"),
            ),
            sourceHash = "source-hash",
            baselinePlanHash = "plan-hash",
            baselineBodyHash = "body-hash",
            telemetry = GenerationTelemetry(
                model = GenerationModelSnapshot(
                    provider = "deepseek",
                    model = "deepseek-chat",
                    endpointFingerprint = "a".repeat(64),
                    temperature = 0.7,
                ),
                promptContract = "chapter.stepwise.v1",
                calls = listOf(
                    GenerationCallTelemetry(
                        ordinal = 1,
                        purpose = "chapter_blueprint",
                        promptFingerprint = "b".repeat(64),
                        firstTokenMillis = 100,
                        durationMillis = 120,
                        usage = GenerationTokenUsage(
                            promptTokens = 1_000,
                            completionTokens = 100,
                            totalTokens = 1_100,
                            cacheHitTokens = 800,
                            cacheMissTokens = 200,
                        ),
                    ),
                ),
                firstOutputMillis = 100,
                totalMillis = 120,
            ),
            status = GenerationRun.STATUS_COMPLETED,
            candidates = listOf(
                CandidateChapter(
                    id = "candidate-1",
                    slot = 1,
                    status = CandidateChapter.STATUS_COMPLETED,
                    body = "新的正文",
                    wordCount = 4,
                    qualityReport = ChapterQualityReport(
                        completedStream = true,
                        wordCount = 4,
                        findings = listOf(
                            QualityFinding(
                                code = "below_target_words",
                                severity = QualityFinding.SEVERITY_WARNING,
                                message = "正文偏短",
                            ),
                        ),
                    ),
                    completedAt = "2026-08-30T12:01:00Z",
                ),
            ),
            selectedCandidateId = "candidate-1",
            createdAt = "2026-08-30T12:00:00Z",
            updatedAt = "2026-08-30T12:01:00Z",
            completedAt = "2026-08-30T12:01:00Z",
        )

        val encoded = json.encodeToString(GenerationRun.serializer(), original)
        val decoded = json.decodeFromString(GenerationRun.serializer(), encoded)

        assertEquals(original, decoded)
        assertTrue(decoded.isTerminal())
        assertEquals(QualityFinding.SEVERITY_WARNING, decoded.candidates.single().qualityReport
            ?.findings?.single()?.severity)
    }

    @Test
    fun `missing evolved fields retain backward compatible defaults`() {
        val legacy = json.decodeFromString(
            GenerationRun.serializer(),
            """
                {
                  "id":"run-legacy",
                  "projectId":"project-1",
                  "chapterId":"chapter-1",
                  "spec":{"projectId":"project-1","chapterId":"chapter-1"},
                  "sourceHash":"source-hash"
                }
            """.trimIndent(),
        )

        assertEquals(GenerationRun.STATUS_RUNNING, legacy.status)
        assertEquals(GenerationSourceFingerprint.VERSION, legacy.sourceHashVersion)
        assertTrue(legacy.candidates.isEmpty())
        assertNull(legacy.selectedCandidateId)
        assertNull(legacy.completedAt)
        assertNull(legacy.error)
        assertNull(legacy.telemetry)
        assertEquals(ChapterSpec.MODE_ONE_SHOT, legacy.spec.mode)
        assertTrue(legacy.spec.beats.isEmpty())
        assertFalse(legacy.isTerminal())
    }

    @Test
    fun `v2 run persists context hashes and provenance without source material`() {
        val rawWorld = "不可落盘的世界观原文-9f31"
        val manifest = GenerationContextFingerprint.capture(
            scope = "prompt:CHAPTER",
            materials = listOf(
                GenerationContextMaterial("world", listOf(rawWorld)),
            ),
        )
        val run = GenerationRun(
            id = "run-v2",
            projectId = "project-1",
            chapterId = "chapter-1",
            spec = ChapterSpec(projectId = "project-1", chapterId = "chapter-1"),
            sourceHash = GenerationSourceFingerprint.combineWithContext(
                "plan-hash",
                "body-hash",
                manifest.fingerprint,
            ),
            sourceHashVersion = GenerationSourceFingerprint.CONTEXT_BOUND_VERSION,
            baselinePlanHash = "plan-hash",
            baselineBodyHash = "body-hash",
            contextManifest = manifest,
            initiator = GenerationRun.INITIATOR_AGENT,
            agentEngine = "dual",
            agentSessionId = "session-1",
            agentActionId = "action-1",
            operation = GenerationRun.OPERATION_REVISE,
        )

        val encoded = json.encodeToString(GenerationRun.serializer(), run)
        val decoded = json.decodeFromString(GenerationRun.serializer(), encoded)

        assertEquals(run, decoded)
        assertTrue(encoded.contains(manifest.fingerprint))
        assertFalse(encoded.contains(rawWorld))
    }

    @Test
    fun `unknown fields are ignored at every generation model level`() {
        val decoded = json.decodeFromString(
            GenerationRun.serializer(),
            """
                {
                  "id":"run-future",
                  "projectId":"project-1",
                  "chapterId":"chapter-1",
                  "spec":{
                    "projectId":"project-1",
                    "chapterId":"chapter-1",
                    "futureSpecField":true
                  },
                  "sourceHash":"source-hash",
                  "futureRunField":{"version":2},
                  "candidates":[{
                    "id":"candidate-1",
                    "slot":1,
                    "status":"completed",
                    "body":"正文",
                    "futureCandidateField":"kept by a newer app",
                    "qualityReport":{
                      "completedStream":true,
                      "wordCount":2,
                      "futureReportField":1,
                      "findings":[{
                        "code":"future_check",
                        "severity":"info",
                        "message":"提示",
                        "futureFindingField":true
                      }]
                    }
                  }]
                }
            """.trimIndent(),
        )

        assertEquals("run-future", decoded.id)
        assertEquals("正文", decoded.candidates.single().body)
        assertEquals("future_check", decoded.candidates.single().qualityReport
            ?.findings?.single()?.code)
    }

    @Test
    fun `source fingerprint changes for plan and body inputs`() {
        val baseline = chapter()
        val original = GenerationSourceFingerprint.capture(baseline, "草稿", "定稿")
        val planVariants = listOf(
            baseline.copy(title = "新标题"),
            baseline.copy(order_index = 2),
            baseline.copy(outline_goal = "新目标"),
            baseline.copy(conflict = "新冲突"),
            baseline.copy(twist = "新反转"),
            baseline.copy(cliffhanger = "新悬念"),
            baseline.copy(arcId = "arc-2"),
        )

        planVariants.forEach { changed ->
            assertNotEquals(original.planHash, GenerationSourceFingerprint.plan(changed))
            assertNotEquals(
                original.sourceHash,
                GenerationSourceFingerprint.capture(changed, "草稿", "定稿").sourceHash,
            )
        }
        assertNotEquals(
            original.bodyHash,
            GenerationSourceFingerprint.capture(baseline, "新草稿", "定稿").bodyHash,
        )
        assertNotEquals(
            original.bodyHash,
            GenerationSourceFingerprint.capture(baseline, "草稿", "新定稿").bodyHash,
        )
    }

    @Test
    fun `requested chapter spec does not mutate or rebind baseline fingerprint`() {
        val baseline = chapter()
        val before = GenerationSourceFingerprint.capture(baseline, "草稿", "定稿")
        val requested = ChapterSpec.fromChapter("project-1", baseline)
            .copy(title = "用户请求的新标题", outlineGoal = "用户请求的新目标")

        val plannedChapter = requested.applyPlanningTo(baseline)
        val after = GenerationSourceFingerprint.capture(baseline, "草稿", "定稿")

        assertEquals(before, after)
        assertEquals("第一章", baseline.title)
        assertNotEquals(before.planHash, GenerationSourceFingerprint.plan(plannedChapter))
    }

    private fun chapter() = Chapter(
        id = "chapter-1",
        project_id = "project-1",
        title = "第一章",
        order_index = 1,
        outline_goal = "旧目标",
        conflict = "旧冲突",
        twist = "旧反转",
        cliffhanger = "旧悬念",
        arcId = "arc-1",
    )
}
