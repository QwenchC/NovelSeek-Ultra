package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.AtomicTextFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

/** One atomic record per chapter. A run lease and revision prevent late tasks replacing new work. */
class SceneWritingStore(private val root: File) {
    @Serializable
    private data class Record(val plan: ChapterScenePlan, val checkpoint: WritingCheckpoint? = null)

    /** Hosts coordinate multi-file journals while all instances continue to use the same lock. */
    fun <T> withExclusiveAccess(block: () -> T): T = synchronized(storeLock) { block() }

    fun load(projectId: String, chapterId: String): WritingCheckpoint? = synchronized(storeLock) {
        read(projectId, chapterId)?.checkpoint
    }

    fun loadPlan(projectId: String, chapterId: String): ChapterScenePlan? = synchronized(storeLock) {
        read(projectId, chapterId)?.plan
    }

    fun list(projectId: String): List<WritingCheckpoint> = synchronized(storeLock) {
        records(projectId).mapNotNull { it.checkpoint }.sortedByDescending { it.updatedAt }
    }

    fun listPlans(projectId: String): List<ChapterScenePlan> = synchronized(storeLock) {
        records(projectId).map { it.plan }.sortedByDescending { it.updatedAt }
    }

    /** Author edits invalidate old partial drafts; a running lease must first be interrupted. */
    fun savePlan(plan: ChapterScenePlan): Boolean = synchronized(storeLock) {
        ScenePlanProtocol.validate(plan)
        val current = read(plan.projectId, plan.chapterId)
        if (current?.checkpoint?.status == WritingStatus.RUNNING) return@synchronized false
        if (current?.plan == plan) return@synchronized true
        write(Record(plan))
        true
    }

    /** Explicit resume transfers the lease, making every in-flight write from the old run stale. */
    fun begin(
        plan: ChapterScenePlan,
        mode: WritingMode,
        resumeRunId: String? = null,
        initialRequestCount: Int = 0,
        now: Long = System.currentTimeMillis(),
        chapterTask: String = "",
        language: String = "zh",
        baselineText: String = "",
    ): WritingCheckpoint = synchronized(storeLock) {
        ScenePlanProtocol.validate(plan)
        require(initialRequestCount >= 0)
        val previous = read(plan.projectId, plan.chapterId)?.checkpoint
        val next = if (resumeRunId != null) {
            if (previous?.runId != resumeRunId) throw WritingStaleRunException("恢复任务已被替换，请重新读取任务状态")
            if (previous.sourceFingerprint != plan.sourceFingerprint) {
                throw WritingSourceChangedException("章节或设定已变更，不能恢复旧场景草稿；请重新规划")
            }
            require(previous.plan == plan && previous.mode == mode) { "恢复时场景计划和写作模式必须保持不变" }
            if (previous.status == WritingStatus.COMPLETED) return@synchronized previous
            previous.copy(
                runId = UUID.randomUUID().toString(), revision = previous.revision + 1,
                status = WritingStatus.RUNNING, error = null, updatedAt = now,
            )
        } else {
            if (previous?.status == WritingStatus.RUNNING) {
                throw WritingStaleRunException("本章已有运行中的场景任务，请先暂停或选择恢复")
            }
            WritingCheckpoint(
                runId = UUID.randomUUID().toString(), revision = 0,
                sourceFingerprint = plan.sourceFingerprint, plan = plan, mode = mode,
                status = WritingStatus.RUNNING, requestCount = initialRequestCount, updatedAt = now,
                chapterTask = chapterTask, language = language, baselineText = baselineText,
            )
        }
        validate(next)
        write(Record(plan, next))
        next
    }

    /** Both lease and revision must match; identity, plan and completed prefix cannot be changed. */
    fun compareAndSet(expected: WritingCheckpoint, next: WritingCheckpoint): Boolean = synchronized(storeLock) {
        val current = read(expected.plan.projectId, expected.plan.chapterId)?.checkpoint
            ?: return@synchronized false
        if (current.runId != expected.runId || current.revision != expected.revision) return@synchronized false
        require(current == expected) { "Expected checkpoint does not match durable state" }
        validate(next)
        require(next.runId == current.runId && next.revision == current.revision + 1) { "Invalid run lease or revision" }
        require(next.plan == current.plan && next.sourceFingerprint == current.sourceFingerprint && next.mode == current.mode) {
            "Cannot mutate a running task's source, plan or mode"
        }
        require(next.chapterTask == current.chapterTask && next.language == current.language && next.baselineText == current.baselineText) {
            "Cannot mutate a task's original prompt, language or continuation prefix"
        }
        require(next.requestCount >= current.requestCount) { "Request count cannot decrease" }
        require(next.completedScenes.take(current.completedScenes.size) == current.completedScenes) {
            "Completed scene drafts cannot be replaced by a later task"
        }
        require(current.status != WritingStatus.COMPLETED || next == current) { "Completed task is immutable" }
        require(!current.reviewCompleted || (next.reviewCompleted && next.reviewFindings == current.reviewFindings)) {
            "Completed review cannot be replaced"
        }
        write(Record(next.plan, next))
        true
    }

    fun deleteChapter(projectId: String, chapterId: String): Boolean = synchronized(storeLock) {
        val target = chapterFile(projectId, chapterId)
        // Verify identities before removing a recoverable record.
        if (AtomicTextFile.exists(target)) read(projectId, chapterId)
        AtomicTextFile.delete(target)
    }

    fun deleteProject(projectId: String): Boolean = synchronized(storeLock) {
        val existing = records(projectId)
        existing.forEach { record ->
            if (!AtomicTextFile.delete(chapterFile(projectId, record.plan.chapterId))) {
                throw IllegalStateException("Unable to remove chapter writing checkpoint")
            }
        }
        val directory = projectDirectory(projectId)
        if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
        existing.isNotEmpty()
    }

    fun clearCheckpoint(projectId: String, chapterId: String, expectedRunId: String? = null): Boolean = synchronized(storeLock) {
        val current = read(projectId, chapterId) ?: return@synchronized false
        val checkpoint = current.checkpoint ?: return@synchronized true
        if (checkpoint.status == WritingStatus.RUNNING ||
            (expectedRunId != null && checkpoint.runId != expectedRunId)) return@synchronized false
        write(current.copy(checkpoint = null))
        true
    }

    fun exportProject(projectId: String): WritingArchive = synchronized(storeLock) {
        val current = records(projectId)
        WritingArchive(plans = current.map { it.plan }, checkpoints = current.mapNotNull { it.checkpoint })
    }

    /** Validate the whole payload before writing. Imported running work is resumable, not live. */
    fun importProject(projectId: String, archive: WritingArchive, replace: Boolean = false) = synchronized(storeLock) {
        validatedImportFiles(projectId, archive, replace).forEach { (file, text) ->
            AtomicTextFile.writeText(file, text)
        }
    }

    /** Read-only preflight: hosts can include these exact writes in their durable backup journal. */
    fun validatedImportFiles(projectId: String, archive: WritingArchive, replace: Boolean = false): Map<File, String> = synchronized(storeLock) {
        require(archive.version == 1) { "Unsupported scene writing backup version" }
        require(archive.plans.map { it.chapterId }.distinct().size == archive.plans.size) { "Duplicate scene plan" }
        require(archive.checkpoints.map { it.plan.chapterId }.distinct().size == archive.checkpoints.size) { "Duplicate scene checkpoint" }
        val checkpoints = archive.checkpoints.associateBy { it.plan.chapterId }
        val plans = archive.plans.associateBy { it.chapterId }
        require(checkpoints.keys.all { it in plans }) { "Checkpoint is missing its scene plan" }
        val imported = archive.plans.map { plan ->
            ScenePlanProtocol.validate(plan)
            require(plan.projectId == projectId) { "Scene plan belongs to another project" }
            val checkpoint = checkpoints[plan.chapterId]?.also {
                validate(it)
                require(it.plan == plan) { "Scene checkpoint plan mismatch" }
            }?.let {
                it.copy(runId = UUID.randomUUID().toString(), revision = it.revision + 1,
                    status = if (it.status == WritingStatus.RUNNING) WritingStatus.INTERRUPTED else it.status,
                    error = if (it.status == WritingStatus.RUNNING) "从备份恢复，等待继续写作" else it.error)
            }
            val existing = read(projectId, plan.chapterId)
            require(existing?.checkpoint?.status != WritingStatus.RUNNING) { "Cannot import over a running writing task" }
            require(replace || existing == null) { "Scene plan already exists" }
            Record(plan, checkpoint)
        }
        imported.associate { record -> chapterFile(projectId, record.plan.chapterId) to
            ScenePlanProtocol.json.encodeToString(Record.serializer(), record) }
    }

    /** Targets for the host's project replacement transaction; no files are deleted here. */
    fun filesForProject(projectId: String): List<File> = synchronized(storeLock) {
        records(projectId).map { chapterFile(projectId, it.plan.chapterId) }
    }

    private fun records(projectId: String): List<Record> {
        val directory = projectDirectory(projectId)
        if (!directory.isDirectory) return emptyList()
        directory.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".json.bak") -> AtomicTextFile.exists(File(directory, file.name.removeSuffix(".bak")))
                file.name.endsWith(".json.new") -> AtomicTextFile.exists(File(directory, file.name.removeSuffix(".new")))
            }
        }
        return directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
            .map { file -> decode(file, projectId, null) }
    }

    private fun read(projectId: String, chapterId: String): Record? {
        val file = chapterFile(projectId, chapterId)
        return if (AtomicTextFile.exists(file)) decode(file, projectId, chapterId) else null
    }

    private fun decode(file: File, projectId: String, chapterId: String?): Record {
        try {
            val record = ScenePlanProtocol.json.decodeFromString(Record.serializer(), AtomicTextFile.readText(file))
            ScenePlanProtocol.validate(record.plan)
            require(record.plan.projectId == projectId && (chapterId == null || record.plan.chapterId == chapterId)) {
                "Scene record identity mismatch"
            }
            require(file.nameWithoutExtension == hash(record.plan.chapterId)) { "Scene record filename mismatch" }
            record.checkpoint?.let { validate(it); require(it.plan == record.plan) }
            return record
        } catch (error: Exception) {
            throw IllegalStateException("Unable to read scene writing record ${file.absolutePath}", error)
        }
    }

    private fun write(record: Record) {
        AtomicTextFile.writeText(chapterFile(record.plan.projectId, record.plan.chapterId),
            ScenePlanProtocol.json.encodeToString(Record.serializer(), record))
    }

    private fun projectDirectory(projectId: String): File {
        require(projectId.isNotBlank())
        return File(root, hash(projectId))
    }

    private fun chapterFile(projectId: String, chapterId: String): File {
        require(chapterId.isNotBlank())
        return File(projectDirectory(projectId), "${hash(chapterId)}.json")
    }

    companion object {
        // Instances pointing at the same directory share CAS serialization in this process.
        private val storeLock = Any()
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun validate(checkpoint: WritingCheckpoint) {
            ScenePlanProtocol.validate(checkpoint.plan)
            require(checkpoint.runId.isNotBlank() && checkpoint.revision >= 0 && checkpoint.requestCount >= 0)
            require(checkpoint.sourceFingerprint == checkpoint.plan.sourceFingerprint) { "Checkpoint source mismatch" }
            require(checkpoint.completedScenes.size <= checkpoint.plan.scenes.size)
            checkpoint.completedScenes.forEachIndexed { index, scene ->
                val expected = checkpoint.plan.scenes[index]
                require(scene.sceneId == expected.id && scene.body.isNotBlank() && scene.body.length <= 256_000) {
                    "Completed scenes must be a nonempty consecutive plan prefix"
                }
                require(scene.exitState == expected.exitState) { "Completed scene state differs from the plan" }
            }
            require(checkpoint.completedScenes.sumOf { it.body.length.toLong() } <= 1_000_000) { "Chapter draft is too large" }
            require(checkpoint.status != WritingStatus.COMPLETED || checkpoint.completedScenes.size == checkpoint.plan.scenes.size) {
                "Completed task has unfinished scenes"
            }
            require(checkpoint.error == null || checkpoint.error.length <= 2_000)
            require(checkpoint.chapterTask.length <= 128_000 && checkpoint.language.isNotBlank() && checkpoint.language.length <= 32)
            require(checkpoint.baselineText.length <= 1_000_000) { "Continuation prefix is too large" }
            require(checkpoint.reviewCompleted || checkpoint.reviewFindings.isEmpty()) { "Unfinished review contains findings" }
            ReviewProtocol.validate(checkpoint.reviewFindings, checkpoint.plan, checkpoint.completedScenes)
        }
    }
}
