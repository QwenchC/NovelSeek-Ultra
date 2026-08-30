package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.ChapterSpec
import com.example.novelseek_ultra.data.model.GenerationContextFingerprint
import com.example.novelseek_ultra.data.model.GenerationContextMaterial
import com.example.novelseek_ultra.data.model.GenerationCallTelemetry
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationModelSnapshot
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.GenerationSourceFingerprint
import com.example.novelseek_ultra.data.model.GenerationTokenUsage
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenerationRunStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `project and run ids are hashed instead of becoming path segments`() {
        val root = temporaryFolder.newFolder("generation-runs")
        val store = GenerationRunStore(root, json)
        val run = run(
            id = "../../outside-run",
            projectId = "../outside-project",
            chapterId = "chapter-1",
        )

        assertTrue(store.create(run))
        assertEquals(run, store.get(run.projectId, run.id))

        val projectDirectories = root.listFiles().orEmpty()
        assertEquals(1, projectDirectories.size)
        assertTrue(projectDirectories.single().name.matches(Regex("[0-9a-f]{64}")))
        val files = projectDirectories.single().listFiles().orEmpty()
        assertEquals(1, files.size)
        assertEquals("${GenerationRunStore.hash(run.id)}.json", files.single().name)
        assertTrue(files.single().canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
    }

    @Test
    fun `only one non terminal run is accepted per chapter`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("single-active"), json)

        assertTrue(store.create(run("active-1")))
        assertFalse(store.create(run("active-2")))
        assertTrue(
            store.create(
                run(
                    id = "terminal-1",
                    status = GenerationRun.STATUS_COMPLETED,
                    completedAt = "2026-08-30T12:00:00Z",
                ),
            ),
        )
        assertTrue(store.create(run("other-chapter", chapterId = "chapter-2")))
        assertEquals(setOf("active-1", "terminal-1", "other-chapter"), store.list("project-1").map { it.id }.toSet())
    }

    @Test
    fun `completed review blocks a new active run until the review is resolved`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("pending-review"), json)
        val pendingReview = run(
            id = "review-1",
            status = GenerationRun.STATUS_COMPLETED,
            completedAt = "2026-08-30T12:00:00Z",
        )
        assertTrue(store.create(pendingReview))

        assertFalse(store.create(run("active-before-review")))
        assertTrue(
            store.update("project-1", "review-1") {
                it.copy(status = GenerationRun.STATUS_REJECTED)
            } != null,
        )
        assertTrue(store.create(run("active-after-review")))
    }

    @Test
    fun `directory listing recovers an active run hidden behind atomic backup`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("backup-recovery"), json)
        val active = run("active-1")
        assertTrue(store.create(active))

        val target = store.targetFile(active.projectId, active.id)
        val backup = File(target.parentFile, "${target.name}.bak")
        assertTrue(target.renameTo(backup))
        assertFalse(target.exists())
        assertTrue(backup.exists())

        assertEquals(listOf(active), store.list(active.projectId))
        assertTrue(target.exists())
        assertFalse(backup.exists())
        assertFalse(store.create(run("active-2")))
    }

    @Test
    fun `terminal retention keeps the newest three runs for each chapter`() {
        val store = GenerationRunStore(
            temporaryFolder.newFolder("retention"),
            json,
            maxTerminalRunsPerChapter = 3,
        )
        (1..4).forEach { index ->
            assertTrue(
                store.create(
                    run(
                        id = "run-$index",
                        status = GenerationRun.STATUS_REJECTED,
                        createdAt = "2026-08-30T0$index:00:00Z",
                        updatedAt = "2026-08-30T0$index:00:00Z",
                        completedAt = "2026-08-30T0$index:00:00Z",
                    ),
                ),
            )
        }

        assertNull(store.get("project-1", "run-1"))
        assertEquals(setOf("run-2", "run-3", "run-4"), store.list("project-1").map { it.id }.toSet())
    }

    @Test
    fun `terminal retention never prunes an unresolved completed review`() {
        val store = GenerationRunStore(
            temporaryFolder.newFolder("review-retention"),
            json,
            maxTerminalRunsPerChapter = 1,
        )
        assertTrue(
            store.create(
                run(
                    id = "pending-review",
                    status = GenerationRun.STATUS_COMPLETED,
                    completedAt = "2026-08-30T01:00:00Z",
                ),
            ),
        )
        (1..3).forEach { index ->
            assertTrue(
                store.create(
                    run(
                        id = "history-$index",
                        status = GenerationRun.STATUS_REJECTED,
                        createdAt = "2026-08-30T0${index + 1}:00:00Z",
                        updatedAt = "2026-08-30T0${index + 1}:00:00Z",
                        completedAt = "2026-08-30T0${index + 1}:00:00Z",
                    ),
                ),
            )
        }

        assertTrue(store.get("project-1", "pending-review") != null)
        assertEquals(
            setOf("pending-review", "history-3"),
            store.list("project-1").map { it.id }.toSet(),
        )
    }

    @Test
    fun `update persists terminal result and rejects source rebinding`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("update"), json)
        val initial = run("run-1")
        assertTrue(store.create(initial))

        val completed = store.update("project-1", "run-1") { current ->
            current.copy(
                status = GenerationRun.STATUS_COMPLETED,
                candidates = listOf(
                    CandidateChapter(
                        id = "candidate-1",
                        slot = 1,
                        status = CandidateChapter.STATUS_COMPLETED,
                        body = "正文",
                    ),
                ),
                updatedAt = "2026-08-30T12:01:00Z",
                completedAt = "2026-08-30T12:01:00Z",
            )
        }

        assertEquals(completed, store.get("project-1", "run-1"))
        assertTrue(requireNotNull(completed).isTerminal())
        assertEquals("正文", completed.candidates.single().body)
        assertThrows(IllegalArgumentException::class.java) {
            store.update("project-1", "run-1") { it.copy(sourceHash = "different-source") }
        }
        assertEquals("source-run-1", store.get("project-1", "run-1")?.sourceHash)
    }

    @Test
    fun `telemetry is attached only at terminal transition and is immutable afterwards`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("telemetry"), json)
        val initial = run("telemetry-run")
        val metrics = telemetry()
        assertTrue(store.create(initial))

        assertThrows(IllegalArgumentException::class.java) {
            store.update(initial.projectId, initial.id) { it.copy(telemetry = metrics) }
        }

        val completed = requireNotNull(
            store.update(initial.projectId, initial.id) {
                it.copy(
                    status = GenerationRun.STATUS_COMPLETED,
                    telemetry = metrics,
                    completedAt = "2026-08-30T12:01:00Z",
                )
            },
        )
        assertEquals(metrics, completed.telemetry)

        val accepted = requireNotNull(
            store.update(initial.projectId, initial.id) {
                it.copy(status = GenerationRun.STATUS_ACCEPTED)
            },
        )
        assertEquals(metrics, accepted.telemetry)
        assertThrows(IllegalArgumentException::class.java) {
            store.update(initial.projectId, initial.id) {
                it.copy(telemetry = metrics.copy(totalMillis = metrics.totalMillis + 1))
            }
        }
        assertEquals(metrics, store.get(initial.projectId, initial.id)?.telemetry)
    }

    @Test
    fun `terminal run can attach matching late telemetry exactly once`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("late-telemetry"), json)
        val cancelled = run(
            id = "cancelled-run",
            status = GenerationRun.STATUS_CANCELLED,
            completedAt = "2026-08-30T12:01:00Z",
        )
        val metrics = telemetry().copy(
            outcome = GenerationTelemetry.OUTCOME_CANCELLED,
            failureCategory = GenerationTelemetry.FAILURE_SUPERSEDED,
        )
        assertTrue(store.create(cancelled))

        val attached = requireNotNull(
            store.update(cancelled.projectId, cancelled.id) { it.copy(telemetry = metrics) },
        )

        assertEquals(metrics, attached.telemetry)
        assertThrows(IllegalArgumentException::class.java) {
            store.update(cancelled.projectId, cancelled.id) {
                it.copy(telemetry = metrics.copy(totalMillis = metrics.totalMillis + 1))
            }
        }
        assertEquals(metrics, store.get(cancelled.projectId, cancelled.id)?.telemetry)
    }

    @Test
    fun `invalid telemetry is rejected before persistence`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("invalid-telemetry"), json)
        val invalid = run(
            id = "invalid-telemetry-run",
            status = GenerationRun.STATUS_FAILED,
            completedAt = "2026-08-30T12:01:00Z",
        ).copy(
            telemetry = telemetry().copy(
                outcome = GenerationTelemetry.OUTCOME_FAILED,
                failureCategory = GenerationTelemetry.FAILURE_TIMEOUT,
                totalMillis = -1,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) { store.create(invalid) }
        val invalidCache = run(
            id = "invalid-cache-telemetry",
            status = GenerationRun.STATUS_COMPLETED,
            completedAt = "2026-08-30T12:01:00Z",
        ).copy(
            telemetry = telemetry().copy(
                calls = listOf(
                    GenerationCallTelemetry(
                        ordinal = 1,
                        purpose = "chapter_generate",
                        promptFingerprint = "b".repeat(64),
                        usage = GenerationTokenUsage(
                            promptTokens = 100,
                            cacheHitTokens = 80,
                            cacheMissTokens = 30,
                        ),
                    ),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { store.create(invalidCache) }
        assertTrue(store.list("project-1").isEmpty())
    }

    @Test
    fun `chapter and project deletion remain scoped`() {
        val root = temporaryFolder.newFolder("deletion")
        val store = GenerationRunStore(root, json)
        assertTrue(store.create(run("chapter-1-a")))
        assertTrue(store.create(run("chapter-2-a", chapterId = "chapter-2")))
        assertTrue(store.create(run("other-project", projectId = "project-2")))

        assertTrue(store.deleteChapter("project-1", "chapter-1"))
        assertNull(store.get("project-1", "chapter-1-a"))
        assertTrue(store.get("project-1", "chapter-2-a") != null)
        assertTrue(store.get("project-2", "other-project") != null)

        assertTrue(store.deleteProject("project-1"))
        assertTrue(store.list("project-1").isEmpty())
        assertTrue(store.get("project-2", "other-project") != null)
        assertFalse(store.deleteProject("missing-project"))
    }

    @Test
    fun `v2 context and agent provenance remain immutable across updates`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("v2-immutable"), json)
        val manifest = contextManifest("旧世界")
        val replacementManifest = contextManifest("新世界")
        val initial = run("v2-run").copy(
            sourceHashVersion = GenerationSourceFingerprint.CONTEXT_BOUND_VERSION,
            sourceHash = GenerationSourceFingerprint.combineWithContext(
                "plan-v2-run",
                "body-v2-run",
                manifest.fingerprint,
            ),
            contextManifest = manifest,
            initiator = GenerationRun.INITIATOR_AGENT,
            agentEngine = "dual",
            agentSessionId = "session-1",
            agentActionId = "action-1",
            operation = GenerationRun.OPERATION_REVISE,
        )
        assertTrue(store.create(initial))

        val mutations: List<(GenerationRun) -> GenerationRun> = listOf(
            { it.copy(sourceHashVersion = GenerationSourceFingerprint.LEGACY_VERSION) },
            { it.copy(contextManifest = replacementManifest) },
            { it.copy(initiator = GenerationRun.INITIATOR_EDITOR) },
            { it.copy(agentEngine = "classic") },
            { it.copy(agentSessionId = "session-2") },
            { it.copy(agentActionId = "action-2") },
            { it.copy(operation = GenerationRun.OPERATION_GENERATE) },
        )

        mutations.forEach { mutation ->
            assertThrows(IllegalArgumentException::class.java) {
                store.update(initial.projectId, initial.id, mutation)
            }
            assertEquals(initial, store.get(initial.projectId, initial.id))
        }
    }

    @Test
    fun `v2 run requires a self consistent context manifest`() {
        val store = GenerationRunStore(temporaryFolder.newFolder("v2-validation"), json)
        val missing = run("missing-manifest").copy(
            sourceHashVersion = GenerationSourceFingerprint.CONTEXT_BOUND_VERSION,
        )
        val manifest = contextManifest("旧世界")
        val invalid = run("invalid-manifest").copy(
            sourceHashVersion = GenerationSourceFingerprint.CONTEXT_BOUND_VERSION,
            contextManifest = manifest.copy(fingerprint = "tampered"),
        )

        assertThrows(IllegalArgumentException::class.java) { store.create(missing) }
        assertThrows(IllegalArgumentException::class.java) { store.create(invalid) }
        assertTrue(store.list("project-1").isEmpty())
    }

    private fun run(
        id: String,
        projectId: String = "project-1",
        chapterId: String = "chapter-1",
        status: String = GenerationRun.STATUS_RUNNING,
        createdAt: String = "2026-08-30T12:00:00Z",
        updatedAt: String = createdAt,
        completedAt: String? = null,
    ) = GenerationRun(
        id = id,
        projectId = projectId,
        chapterId = chapterId,
        spec = ChapterSpec(projectId = projectId, chapterId = chapterId, title = "第一章"),
        sourceHash = "source-$id",
        baselinePlanHash = "plan-$id",
        baselineBodyHash = "body-$id",
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    private fun contextManifest(world: String) = GenerationContextFingerprint.capture(
        scope = "chapter_prompt",
        materials = listOf(GenerationContextMaterial("world", listOf(world))),
    )

    private fun telemetry() = GenerationTelemetry(
        model = GenerationModelSnapshot(
            provider = "deepseek",
            model = "deepseek-chat",
            endpointFingerprint = "a".repeat(64),
            temperature = 0.7,
        ),
        promptContract = "chapter.one_shot.v1",
        totalMillis = 120,
    )
}
