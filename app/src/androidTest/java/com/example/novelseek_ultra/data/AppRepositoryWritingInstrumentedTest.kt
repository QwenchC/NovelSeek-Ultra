package com.example.novelseek_ultra.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.novelseek_ultra.data.model.BackupBundle
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.Chapter
import com.example.novelseek_ultra.data.model.ChapterQualityReport
import com.example.novelseek_ultra.data.model.ChapterSpec
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationSourceFingerprint
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.data.writing.ChapterScenePlan
import com.example.novelseek_ultra.data.writing.CompletedScene
import com.example.novelseek_ultra.data.writing.ManuscriptImporter
import com.example.novelseek_ultra.data.writing.SceneSpec
import com.example.novelseek_ultra.data.writing.StoryNote
import com.example.novelseek_ultra.data.writing.WritingMode
import com.example.novelseek_ultra.data.writing.WritingStatus
import com.example.novelseek_ultra.data.writing.WritingWorkspace
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android file/crypto integration; no model configuration or network calls are required. */
@RunWith(AndroidJUnit4::class)
class AppRepositoryWritingInstrumentedTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun writingBackupRestoresWorkspaceBodyCandidateAndResumableSceneTogether() = fixture { _, sourceContext ->
        val source = AppRepository(sourceContext)
        seed(source, "原正文", "原文风")
        val originalRun = generationRun(source, "run-restored", GenerationRun.STATUS_RUNNING)
        assertTrue(generationStore(sourceContext).create(originalRun))
        val plan = plan("original-source")
        val current = source.sceneWritingStore.begin(plan, WritingMode.SCENES,
            chapterTask = "续写原任务", baselineText = "原正文", language = "zh")
        assertTrue(source.sceneWritingStore.compareAndSet(current, current.copy(revision = current.revision + 1,
            completedScenes = listOf(CompletedScene("s1", "已完成第一场", "得到线索", 2)))))
        val backup = source.buildBackupBundle()
        assertTrue(backup.data.containsKey("generationRunsByProject"))
        assertTrue(backup.data.containsKey("sceneWritingByProject"))
        assertTrue(backup.data.containsKey("writingWorkspaceByProject"))

        fixture { _, targetContext ->
            val target = AppRepository(targetContext)
            target.importBackup(backup, includeAppSettings = false)
            assertEquals("原正文", target.chapterBody(CHAPTER_ID).final)
            assertEquals(source.writingWorkspace(PROJECT_ID), target.writingWorkspace(PROJECT_ID))
            val restoredRun = target.getGenerationRun(PROJECT_ID, originalRun.id)!!
            assertEquals(GenerationRun.STATUS_CANCELLED, restoredRun.status)
            assertEquals(CandidateChapter.STATUS_CANCELLED, restoredRun.candidates.single().status)
            val restored = target.sceneWritingStore.load(PROJECT_ID, CHAPTER_ID)!!
            assertEquals(WritingStatus.INTERRUPTED, restored.status)
            assertNotEquals(current.runId, restored.runId)
            assertEquals("已完成第一场", restored.completedScenes.single().body)
            assertEquals("原正文", restored.baselineText)
            assertEquals("续写原任务", restored.chapterTask)
            assertFalse(target.state.value.containsKey("generationRunsByProject"))
            assertFalse(target.state.value.containsKey("sceneWritingByProject"))
            assertFalse(BackupImportJournal.hasActiveJournal(targetContext.filesDir))
        }
    }

    @Test
    fun failedWritingImportRecoversOldFilesAndMetadataAfterRepositoryReopen() = fixture { targetRoot, targetContext ->
        val target = AppRepository(targetContext)
        seed(target, "原正文", "原文风")
        val oldRun = generationRun(target, "old-run", GenerationRun.STATUS_CANCELLED)
        assertTrue(generationStore(targetContext).create(oldRun))
        val oldScene = target.sceneWritingStore.begin(plan("old-source"), WritingMode.SCENES)
        val paused = oldScene.copy(revision = oldScene.revision + 1, status = WritingStatus.INTERRUPTED)
        assertTrue(target.sceneWritingStore.compareAndSet(oldScene, paused))
        val oldWorkspace = target.writingWorkspace(PROJECT_ID)
        val oldState = target.state.value

        fixture { _, sourceContext ->
            val source = AppRepository(sourceContext)
            seed(source, "导入的新正文", "导入的新文风")
            assertTrue(generationStore(sourceContext).create(generationRun(source, "new-run", GenerationRun.STATUS_COMPLETED)))
            source.sceneWritingStore.savePlan(plan("new-source"))
            val backup = source.buildBackupBundle()
            // Import commits generation/scene targets before chapterBodies. This persistent .new
            // obstruction injects a real later write failure and a rollback failure, exercising
            // the durable recovery journal instead of relying on an in-memory mock.
            val obstruction = File(targetRoot, "chapters/$CHAPTER_ID.json.new")
            assertTrue(obstruction.mkdirs())
            File(obstruction, "blocker").writeText("fault injection")
            try {
                try {
                    target.importBackup(backup, includeAppSettings = false)
                    fail("Expected the injected chapter-body write failure")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message!!.contains("导入失败"))
                }
                assertTrue(BackupImportJournal.hasActiveJournal(targetRoot))
            } finally {
                check(obstruction.canonicalFile.parentFile == File(targetRoot, "chapters").canonicalFile)
                assertTrue(obstruction.deleteRecursively())
            }
            val reopened = AppRepository(targetContext)
            assertTrue(reopened.recoveredInterruptedBackupImport)
            assertEquals("原正文", reopened.chapterBody(CHAPTER_ID).final)
            assertEquals(oldWorkspace, reopened.writingWorkspace(PROJECT_ID))
            assertEquals(oldState, reopened.state.value)
            assertEquals(oldRun, reopened.getGenerationRun(PROJECT_ID, oldRun.id))
            assertNull(reopened.getGenerationRun(PROJECT_ID, "new-run"))
            assertEquals(paused, reopened.sceneWritingStore.load(PROJECT_ID, CHAPTER_ID))
            assertFalse(BackupImportJournal.hasActiveJournal(targetRoot))
        }
    }

    @Test
    fun invalidWritingArchiveIsRejectedBeforeAnyProjectWrite() = fixture { root, context ->
        val repository = AppRepository(context)
        seed(repository, "不可丢失正文", "原风格")
        repository.sceneWritingStore.savePlan(plan("safe-source"))
        val original = repository.buildBackupBundle()
        val oldState = repository.state.value
        val oldPlan = repository.sceneWritingStore.loadPlan(PROJECT_ID, CHAPTER_ID)
        val archives = original.data.getValue("sceneWritingByProject") as JsonObject
        val archive = archives.getValue(PROJECT_ID) as JsonObject
        val invalidArchive = JsonObject(archive + ("version" to kotlinx.serialization.json.JsonPrimitive(999)))
        val invalid = original.copy(data = JsonObject(original.data +
            ("sceneWritingByProject" to JsonObject(archives + (PROJECT_ID to invalidArchive)))))
        try {
            repository.importBackup(invalid, includeAppSettings = false)
            fail("Expected unsupported writing archive rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(oldState, repository.state.value)
        assertEquals("不可丢失正文", repository.chapterBody(CHAPTER_ID).final)
        assertEquals(oldPlan, repository.sceneWritingStore.loadPlan(PROJECT_ID, CHAPTER_ID))
        assertFalse(BackupImportJournal.hasActiveJournal(root))
    }

    @Test
    fun manuscriptAppendPersistsNewChapterBodiesWithoutReplacingExistingText() = fixture { root, context ->
        val repository = AppRepository(context)
        seed(repository, "保留的原章节", "原文风")
        val preview = ManuscriptImporter.preview("第一章相逢\n新书稿第一章正文\n第二章追踪\n新书稿第二章正文")
        assertEquals(2, repository.importManuscript(PROJECT_ID, preview))
        val chapters = repository.chapters(PROJECT_ID).sortedBy { it.order_index }
        assertEquals(3, chapters.size)
        assertEquals("保留的原章节", repository.chapterBody(CHAPTER_ID).final)
        assertEquals(listOf("新书稿第一章正文", "新书稿第二章正文"),
            chapters.drop(1).map { repository.chapterBody(it.id).final })
        assertEquals(listOf(1, 2, 3), chapters.map { it.order_index })
        assertFalse(BackupImportJournal.hasActiveJournal(root))
    }

    private fun seed(repository: AppRepository, body: String, style: String) {
        repository.createProject(Project(PROJECT_ID, "测试小说"))
        repository.setChapters(PROJECT_ID, listOf(chapter()))
        repository.saveChapterBody(CHAPTER_ID, AppRepository.ChapterBody(draft = body, final = body))
        repository.saveWritingWorkspace(PROJECT_ID, WritingWorkspace(style = style,
            notes = listOf(StoryNote("fact-card", "fact", text = "已有正文的事实", sourceChapterId = CHAPTER_ID))))
    }

    private fun chapter() = Chapter(CHAPTER_ID, PROJECT_ID, "原章节", 1)
    private fun plan(source: String) = ChapterScenePlan(PROJECT_ID, CHAPTER_ID, source,
        listOf(SceneSpec("s1", "调查", goal = "取得线索", exitState = "得到线索"),
            SceneSpec("s2", "追踪", goal = "追上药商", entryState = "得到线索")), 1)

    private fun generationRun(repository: AppRepository, id: String, status: String): GenerationRun {
        val chapter = chapter()
        val body = repository.chapterBody(CHAPTER_ID)
        val hashes = GenerationSourceFingerprint.capture(chapter, body.draft, body.final)
        return GenerationRun(id = id, projectId = PROJECT_ID, chapterId = CHAPTER_ID,
            spec = ChapterSpec.fromChapter(PROJECT_ID, chapter), sourceHash = hashes.sourceHash,
            baselinePlanHash = hashes.planHash, baselineBodyHash = hashes.bodyHash,
            status = status, candidates = listOf(CandidateChapter("candidate-$id", 1,
                status = if (status == GenerationRun.STATUS_RUNNING) CandidateChapter.STATUS_RUNNING
                    else if (status == GenerationRun.STATUS_COMPLETED) CandidateChapter.STATUS_COMPLETED else CandidateChapter.STATUS_CANCELLED,
                body = if (status == GenerationRun.STATUS_COMPLETED) "完整候选正文" else "",
                qualityReport = if (status == GenerationRun.STATUS_COMPLETED) ChapterQualityReport(true, 6, false) else null)),
            createdAt = "2026-10-07T01:00:00Z", updatedAt = "2026-10-07T01:00:00Z")
    }

    private fun generationStore(context: Context) = GenerationRunStore(File(context.filesDir, "generation_runs"), json)

    private fun fixture(block: (File, Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().context
        val root = File(base.filesDir, "writing-integration-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try { block(root, IsolatedRepositoryContext(base, root)) }
        finally {
            check(root.canonicalFile.parentFile == base.filesDir.canonicalFile)
            check(!root.exists() || root.deleteRecursively())
        }
    }

    private class IsolatedRepositoryContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    private companion object {
        const val PROJECT_ID = "writing-test-project"
        const val CHAPTER_ID = "writing-test-chapter"
    }
}
