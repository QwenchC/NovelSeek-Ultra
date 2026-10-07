package com.example.novelseek_ultra.data.writing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SceneWritingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plan() = ChapterScenePlan("p", "c", "source", listOf(SceneSpec("s1", "场景", goal = "调查")), 1)

    @Test
    fun atomicReopenPreservesDraftAndRejectsOldLeaseAfterResume() {
        val directory = temporary.newFolder()
        val store = SceneWritingStore(directory)
        val first = store.begin(plan(), WritingMode.SCENES, now = 2)
        val next = first.copy(revision = 1, requestCount = 1,
            completedScenes = listOf(CompletedScene("s1", "调查正文", "", 3)), updatedAt = 3)
        assertTrue(store.compareAndSet(first, next))
        val reopened = SceneWritingStore(directory)
        assertEquals(next, reopened.load("p", "c"))
        val resumed = reopened.begin(plan(), WritingMode.SCENES, next.runId, now = 4)
        assertNotEquals(next.runId, resumed.runId)
        assertEquals(next.completedScenes, resumed.completedScenes)
        assertFalse(store.compareAndSet(next, next.copy(revision = 2, status = WritingStatus.COMPLETED)))
        assertEquals(resumed, store.load("p", "c"))
    }

    @Test
    fun rejectsChangedSourceAndConcurrentPlanEdits() {
        val store = SceneWritingStore(temporary.newFolder())
        val checkpoint = store.begin(plan(), WritingMode.SCENES)
        assertFalse(store.savePlan(plan().copy(sourceFingerprint = "changed")))
        assertFalse(store.clearCheckpoint("p", "c", checkpoint.runId))
        assertTrue(runCatching { store.begin(plan().copy(sourceFingerprint = "changed"), WritingMode.SCENES,
            checkpoint.runId) }.exceptionOrNull() is WritingSourceChangedException)
        assertTrue(runCatching { store.begin(plan(), WritingMode.SCENES) }.exceptionOrNull() is WritingStaleRunException)
        assertEquals(checkpoint, store.load("p", "c"))
    }

    @Test
    fun invalidCompletedPrefixDoesNotDamageCommittedFile() {
        val store = SceneWritingStore(temporary.newFolder())
        val checkpoint = store.begin(plan(), WritingMode.SCENES)
        val invalid = checkpoint.copy(revision = 1, completedScenes = listOf(CompletedScene("wrong", "正文", "", 3)))
        assertTrue(runCatching { store.compareAndSet(checkpoint, invalid) }.isFailure)
        assertEquals(checkpoint, store.load("p", "c"))
    }

    @Test
    fun importPreflightDoesNotWriteAndRestoresRunningAsInterruptedWithNewLease() {
        val source = SceneWritingStore(temporary.newFolder())
        val checkpoint = source.begin(plan(), WritingMode.SCENES)
        val archive = source.exportProject("p")
        val target = SceneWritingStore(temporary.newFolder())
        val files = target.validatedImportFiles("p", archive)
        assertEquals(1, files.size)
        assertTrue(target.list("p").isEmpty())
        target.importProject("p", archive)
        val restored = target.load("p", "c")!!
        assertEquals(WritingStatus.INTERRUPTED, restored.status)
        assertNotEquals(checkpoint.runId, restored.runId)
        assertEquals(plan(), restored.plan)
        assertTrue(target.clearCheckpoint("p", "c", restored.runId))
        assertEquals(plan(), target.loadPlan("p", "c"))
        assertEquals(null, target.load("p", "c"))
    }

    @Test
    fun hashedPathsAndWholeArchiveValidationPreventCrossProjectWrites() {
        val root = temporary.newFolder()
        val store = SceneWritingStore(root)
        val special = plan().copy(projectId = "../../project", chapterId = "../chapter")
        assertTrue(store.savePlan(special))
        assertTrue(store.filesForProject(special.projectId).all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) })
        val other = plan().copy(chapterId = "other")
        assertTrue(runCatching { store.importProject("p", WritingArchive(plans = listOf(plan(), other.copy(projectId = "wrong")))) }.isFailure)
        assertTrue(store.listPlans("p").isEmpty())
    }

    @Test
    fun malformedRecordIsNotSilentlyReplaced() {
        val store = SceneWritingStore(temporary.newFolder())
        store.savePlan(plan())
        val target = store.filesForProject("p").single()
        target.writeText("broken")
        assertTrue(runCatching { store.load("p", "c") }.isFailure)
        assertEquals("broken", target.readText())
    }
}
