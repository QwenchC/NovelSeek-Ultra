package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.GenerationCallTelemetry
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.GenerationTokenUsage
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Atomic, bounded storage for chapter-generation review runs.
 *
 * User-controlled ids never become path segments. The embedded ids are verified after every read,
 * so even a theoretical filename-hash collision cannot return another project or run's payload.
 */
internal class GenerationRunStore(
    private val root: File,
    private val json: Json,
    private val maxTerminalRunsPerChapter: Int = DEFAULT_TERMINAL_RETENTION,
) {
    private val lock = Any()

    init {
        require(maxTerminalRunsPerChapter >= 0)
    }

    fun list(projectId: String): List<GenerationRun> = synchronized(lock) {
        listLocked(projectId).sortedWith(RUN_ORDER)
    }

    fun latest(projectId: String, chapterId: String): GenerationRun? = synchronized(lock) {
        listLocked(projectId)
            .asSequence()
            .filter { it.chapterId == chapterId }
            .sortedWith(RUN_ORDER)
            .firstOrNull()
    }

    fun get(projectId: String, runId: String): GenerationRun? = synchronized(lock) {
        getLocked(projectId, runId)
    }

    /** Hashed target used by the repository's durable multi-file adoption journal. */
    internal fun targetFile(projectId: String, runId: String): File = runFile(projectId, runId)

    internal fun filesForProject(projectId: String): List<File> = synchronized(lock) {
        listLocked(projectId).map { runFile(projectId, it.id) }
    }

    /** Read-only preflight. All actual writes join the repository rollback journal. */
    internal fun validatedImportFiles(projectId: String, runs: List<GenerationRun>, chapterIds: Set<String>): Map<File, String> = synchronized(lock) {
        require(runs.size <= 10_000 && runs.map { it.id }.distinct().size == runs.size) { "Duplicate or excessive generation records" }
        require(listLocked(projectId).none { !it.isTerminal() }) { "请先停止项目中的生成任务再导入备份" }
        val normalized = runs.map { run ->
            validate(run)
            require(run.status in GenerationRun.TERMINAL_STATUSES + GenerationRun.STATUS_RUNNING) { "Unknown generation status" }
            require(run.projectId == projectId && run.chapterId in chapterIds) { "Generation record belongs to a missing chapter" }
            val knownCandidateStatuses = com.example.novelseek_ultra.data.model.CandidateChapter.TERMINAL_STATUSES +
                setOf(com.example.novelseek_ultra.data.model.CandidateChapter.STATUS_PENDING,
                    com.example.novelseek_ultra.data.model.CandidateChapter.STATUS_RUNNING)
            require(run.candidates.all { it.status in knownCandidateStatuses }) { "Unknown candidate status" }
            if (run.status == GenerationRun.STATUS_COMPLETED) {
                require(run.candidates.any { it.status == com.example.novelseek_ultra.data.model.CandidateChapter.STATUS_COMPLETED }) {
                    "Completed generation record has no completed review candidate"
                }
            }
            if (run.status == GenerationRun.STATUS_ACCEPTED) {
                require(run.selectedCandidateId != null && run.candidates.any {
                    it.id == run.selectedCandidateId && it.status == com.example.novelseek_ultra.data.model.CandidateChapter.STATUS_COMPLETED && it.body.isNotBlank()
                }) { "Accepted generation record is missing its selected completed candidate" }
            }
            val safe = if (!run.isTerminal()) run.copy(status = GenerationRun.STATUS_CANCELLED,
                error = "从备份恢复，原任务已中断", telemetry = null,
                candidates = run.candidates.map { if (it.isTerminal()) it else it.copy(status = com.example.novelseek_ultra.data.model.CandidateChapter.STATUS_CANCELLED) }) else run
            validate(safe)
            safe
        }
        require(normalized.filter { it.status == GenerationRun.STATUS_COMPLETED }.groupBy { it.chapterId }.values.all { it.size <= 1 }) {
            "同一章节存在重复待审核稿"
        }
        normalized.associate { run -> runFile(projectId, run.id) to json.encodeToString(GenerationRun.serializer(), run) }
    }

    /** Returns false for a duplicate id or when this chapter already has active/reviewable work. */
    fun create(run: GenerationRun): Boolean = synchronized(lock) {
        validate(run)
        val existing = listLocked(run.projectId)
        if (!run.isTerminal() && existing.any {
                it.chapterId == run.chapterId &&
                    (!it.isTerminal() || it.status == GenerationRun.STATUS_COMPLETED)
            }
        ) {
            return@synchronized false
        }
        val target = runFile(run.projectId, run.id)
        if (AtomicTextFile.exists(target)) {
            val stored = decode(target, run.projectId)
            if (stored.id != run.id) {
                throw GenerationRunStorageException("Generation run filename hash collision")
            }
            return@synchronized false
        }
        writeLocked(run)
        pruneLocked(run.projectId, run.chapterId)
        true
    }

    /** The transform runs exactly once under the store lock; identities and source binding are immutable. */
    fun update(
        projectId: String,
        runId: String,
        transform: (GenerationRun) -> GenerationRun,
    ): GenerationRun? = synchronized(lock) {
        val current = getLocked(projectId, runId) ?: return@synchronized null
        val next = transform(current)
        validate(next)
        require(next.id == current.id) { "Generation run id cannot change" }
        require(next.projectId == current.projectId) { "Generation run project cannot change" }
        require(next.chapterId == current.chapterId) { "Generation run chapter cannot change" }
        require(next.spec == current.spec) { "Generation run chapter spec cannot change" }
        require(next.sourceHash == current.sourceHash) { "Generation run source hash cannot change" }
        require(next.sourceHashVersion == current.sourceHashVersion) {
            "Generation run source hash version cannot change"
        }
        require(next.baselinePlanHash == current.baselinePlanHash) {
            "Generation run plan baseline cannot change"
        }
        require(next.baselineBodyHash == current.baselineBodyHash) {
            "Generation run body baseline cannot change"
        }
        require(next.contextManifest == current.contextManifest) {
            "Generation run context manifest cannot change"
        }
        require(next.initiator == current.initiator) {
            "Generation run initiator cannot change"
        }
        require(next.agentEngine == current.agentEngine) {
            "Generation run agent engine cannot change"
        }
        require(next.agentSessionId == current.agentSessionId) {
            "Generation run agent session cannot change"
        }
        require(next.agentActionId == current.agentActionId) {
            "Generation run agent action cannot change"
        }
        require(next.operation == current.operation) {
            "Generation run operation cannot change"
        }
        require(current.telemetry == null || next.telemetry == current.telemetry) {
            "Final generation telemetry cannot change"
        }
        require(next.telemetry == null || next.isTerminal()) {
            "Generation telemetry can only be attached to a terminal run"
        }
        if (!next.isTerminal()) {
            val otherActive = listLocked(projectId).any {
                it.id != runId && it.chapterId == next.chapterId && !it.isTerminal()
            }
            require(!otherActive) { "Chapter already has a non-terminal generation run" }
        }
        if (next != current) writeLocked(next)
        pruneLocked(projectId, next.chapterId)
        next
    }

    fun deleteChapter(projectId: String, chapterId: String): Boolean = synchronized(lock) {
        val matches = listLocked(projectId).filter { it.chapterId == chapterId }
        matches.fold(false) { changed, run ->
            AtomicTextFile.delete(runFile(projectId, run.id)) || changed
        }.also { if (it) deleteEmptyProjectDirectory(projectId) }
    }

    fun deleteProject(projectId: String): Boolean = synchronized(lock) {
        val directory = projectDirectory(projectId)
        if (!directory.exists()) return@synchronized false
        if (!directory.deleteRecursively()) {
            throw GenerationRunStorageException(
                "Unable to delete generation runs for project $projectId",
            )
        }
        if (root.isDirectory && root.list().isNullOrEmpty()) root.delete()
        true
    }

    private fun listLocked(projectId: String): List<GenerationRun> {
        val directory = projectDirectory(projectId)
        if (!directory.isDirectory) return emptyList()
        recoverAtomicSidecars(directory)
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == FILE_EXTENSION }
            .sortedBy { it.name }
            .map { decode(it, projectId) }
            .toList()
    }

    /**
     * AtomicTextFile repairs a target when it is addressed directly. Directory discovery must do
     * the same first, otherwise a crash after target -> .bak would temporarily hide an active run.
     */
    private fun recoverAtomicSidecars(directory: File) {
        directory.listFiles().orEmpty().forEach { sidecar ->
            val targetName = when {
                sidecar.name.endsWith(".$FILE_EXTENSION.bak") -> sidecar.name.removeSuffix(".bak")
                sidecar.name.endsWith(".$FILE_EXTENSION.new") -> sidecar.name.removeSuffix(".new")
                else -> null
            } ?: return@forEach
            AtomicTextFile.exists(File(directory, targetName))
        }
    }

    private fun getLocked(projectId: String, runId: String): GenerationRun? {
        val file = runFile(projectId, runId)
        if (!AtomicTextFile.exists(file)) return null
        val run = decode(file, projectId)
        if (run.id != runId) {
            throw GenerationRunStorageException("Generation run filename does not match embedded id")
        }
        return run
    }

    private fun decode(file: File, expectedProjectId: String): GenerationRun {
        val run = try {
            json.decodeFromString(GenerationRun.serializer(), AtomicTextFile.readText(file))
        } catch (error: Exception) {
            // Never treat malformed durable candidates as an empty store: that would allow a later
            // write to silently hide or overwrite user-visible review work.
            throw GenerationRunStorageException(
                "Unable to decode generation run ${file.absolutePath}",
                error,
            )
        }
        try {
            validate(run)
            require(run.projectId == expectedProjectId) {
                "Embedded project id does not match generation-run directory"
            }
            require(file.nameWithoutExtension == hash(run.id)) {
                "Embedded run id does not match generation-run filename"
            }
        } catch (error: IllegalArgumentException) {
            throw GenerationRunStorageException(
                "Invalid generation run ${file.absolutePath}: ${error.message}",
                error,
            )
        }
        return run
    }

    private fun writeLocked(run: GenerationRun) {
        AtomicTextFile.writeText(
            runFile(run.projectId, run.id),
            json.encodeToString(GenerationRun.serializer(), run),
        )
    }

    private fun pruneLocked(projectId: String, chapterId: String) {
        val runs = listLocked(projectId).filter { it.chapterId == chapterId }
        val active = runs.filterNot { it.isTerminal() }
        if (active.size > 1) {
            throw GenerationRunStorageException("Chapter has more than one non-terminal generation run")
        }
        // STATUS_COMPLETED is terminal for execution but not for the user: it still represents an
        // unresolved accept/reject decision and must never disappear through history retention.
        runs.filter { it.isTerminal() && it.status != GenerationRun.STATUS_COMPLETED }
            .sortedWith(TERMINAL_RETENTION_ORDER)
            .drop(maxTerminalRunsPerChapter)
            .forEach { old -> AtomicTextFile.delete(runFile(projectId, old.id)) }
        deleteEmptyProjectDirectory(projectId)
    }

    private fun deleteEmptyProjectDirectory(projectId: String) {
        val directory = projectDirectory(projectId)
        if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
        if (root.isDirectory && root.list().isNullOrEmpty()) root.delete()
    }

    private fun validate(run: GenerationRun) {
        require(run.id.isNotBlank()) { "Generation run id is blank" }
        require(run.projectId.isNotBlank()) { "Generation run project id is blank" }
        require(run.chapterId.isNotBlank()) { "Generation run chapter id is blank" }
        require(run.spec.projectId == run.projectId) { "Chapter spec project id does not match run" }
        require(run.spec.chapterId == run.chapterId) { "Chapter spec chapter id does not match run" }
        require(run.sourceHashVersion > 0) { "Generation run source hash version is invalid" }
        require(run.sourceHash.isNotBlank()) { "Generation run source hash is blank" }
        require(run.baselinePlanHash.isNotBlank()) { "Generation run plan baseline is blank" }
        require(run.baselineBodyHash.isNotBlank()) { "Generation run body baseline is blank" }
        require(run.initiator.isNotBlank()) { "Generation run initiator is blank" }
        require(run.operation.isNotBlank()) { "Generation run operation is blank" }
        require(run.agentEngine == null || run.agentEngine.isNotBlank()) {
            "Generation run agent engine is blank"
        }
        require(run.agentSessionId == null || run.agentSessionId.isNotBlank()) {
            "Generation run agent session is blank"
        }
        require(run.agentActionId == null || run.agentActionId.isNotBlank()) {
            "Generation run agent action is blank"
        }
        require(run.contextManifest?.isSelfConsistent() != false) {
            "Generation run context manifest is invalid"
        }
        run.telemetry?.let { telemetry ->
            validateTelemetry(telemetry)
            val expectedOutcome = when (run.status) {
                GenerationRun.STATUS_COMPLETED,
                GenerationRun.STATUS_ACCEPTED,
                GenerationRun.STATUS_REJECTED -> GenerationTelemetry.OUTCOME_COMPLETED
                GenerationRun.STATUS_FAILED -> GenerationTelemetry.OUTCOME_FAILED
                GenerationRun.STATUS_CANCELLED -> GenerationTelemetry.OUTCOME_CANCELLED
                else -> null
            }
            require(expectedOutcome == telemetry.outcome) {
                "Generation telemetry outcome does not match run status"
            }
        }
        if (run.sourceHashVersion ==
            com.example.novelseek_ultra.data.model.GenerationSourceFingerprint.CONTEXT_BOUND_VERSION
        ) {
            require(run.contextManifest != null) {
                "Context-bound generation run is missing its manifest"
            }
        }
        require(run.candidates.map { it.id }.all { it.isNotBlank() }) { "Candidate id is blank" }
        require(run.candidates.map { it.id }.distinct().size == run.candidates.size) {
            "Candidate ids are not unique"
        }
        require(run.candidates.all { it.slot > 0 }) { "Candidate slot must be one-based" }
        require(run.candidates.map { it.slot }.distinct().size == run.candidates.size) {
            "Candidate slots are not unique"
        }
    }

    private fun validateTelemetry(telemetry: GenerationTelemetry) {
        require(telemetry.version > 0) { "Generation telemetry version is invalid" }
        require(telemetry.model.provider.isNotBlank() && telemetry.model.provider.length <= 32) {
            "Generation telemetry provider is invalid"
        }
        require(telemetry.model.model.isNotBlank() && telemetry.model.model.length <= 128) {
            "Generation telemetry model is invalid"
        }
        require(
            telemetry.model.endpointFingerprint.isBlank() ||
                telemetry.model.endpointFingerprint.matches(SHA256_HEX),
        ) { "Generation telemetry endpoint fingerprint is invalid" }
        require(telemetry.model.temperature?.isFinite() != false) {
            "Generation telemetry temperature is invalid"
        }
        require(telemetry.promptContract.matches(CONTRACT_ID)) {
            "Generation telemetry prompt contract is invalid"
        }
        require(telemetry.calls.size <= MAX_TELEMETRY_CALLS) {
            "Generation telemetry has too many calls"
        }
        require(telemetry.calls.map { it.ordinal } == (1..telemetry.calls.size).toList()) {
            "Generation telemetry call ordinals are invalid"
        }
        telemetry.calls.forEach(::validateCallTelemetry)
        require(telemetry.firstOutputMillis == null || telemetry.firstOutputMillis >= 0) {
            "Generation telemetry first-output time is invalid"
        }
        require(telemetry.totalMillis >= 0) { "Generation telemetry duration is invalid" }
        require(
            telemetry.firstOutputMillis == null ||
                telemetry.firstOutputMillis <= telemetry.totalMillis,
        ) { "Generation telemetry first-output time exceeds total duration" }
        require(telemetry.outcome in GenerationTelemetry.OUTCOMES) {
            "Generation telemetry outcome is invalid"
        }
        require(
            telemetry.failureCategory == null ||
                telemetry.failureCategory in GenerationTelemetry.FAILURE_CATEGORIES,
        ) { "Generation telemetry failure category is invalid" }
        require(
            (telemetry.outcome == GenerationTelemetry.OUTCOME_COMPLETED) ==
                (telemetry.failureCategory == null),
        ) { "Generation telemetry failure category does not match outcome" }
    }

    private fun validateCallTelemetry(call: GenerationCallTelemetry) {
        require(call.purpose.matches(CONTRACT_ID)) {
            "Generation telemetry call purpose is invalid"
        }
        require(call.promptFingerprint.matches(SHA256_HEX)) {
            "Generation telemetry prompt fingerprint is invalid"
        }
        require(call.firstTokenMillis == null || call.firstTokenMillis >= 0) {
            "Generation telemetry call first-token time is invalid"
        }
        require(call.durationMillis >= 0) { "Generation telemetry call duration is invalid" }
        require(call.firstTokenMillis == null || call.firstTokenMillis <= call.durationMillis) {
            "Generation telemetry call first-token time exceeds duration"
        }
        call.usage?.let(::validateUsage)
        require(call.outcome in GenerationCallTelemetry.OUTCOMES) {
            "Generation telemetry call outcome is invalid"
        }
        require(
            call.failureCategory == null ||
                call.failureCategory in GenerationTelemetry.FAILURE_CATEGORIES,
        ) { "Generation telemetry call failure category is invalid" }
        require(
            (call.outcome == GenerationCallTelemetry.OUTCOME_COMPLETED) ==
                (call.failureCategory == null),
        ) { "Generation telemetry call failure category does not match outcome" }
    }

    private fun validateUsage(usage: GenerationTokenUsage) {
        listOf(
            usage.promptTokens,
            usage.completionTokens,
            usage.totalTokens,
            usage.cacheHitTokens,
            usage.cacheMissTokens,
        ).filterNotNull().forEach {
            require(it >= 0) { "Generation telemetry token count is negative" }
        }
        val prompt = usage.promptTokens
        val hit = usage.cacheHitTokens
        val miss = usage.cacheMissTokens
        if (prompt != null) {
            require(hit == null || hit <= prompt) {
                "Generation telemetry cache-hit count exceeds prompt tokens"
            }
            require(miss == null || miss <= prompt) {
                "Generation telemetry cache-miss count exceeds prompt tokens"
            }
        }
        if (hit != null && miss != null) {
            require(hit <= Long.MAX_VALUE - miss) {
                "Generation telemetry cache counts overflow"
            }
            require(prompt == null || hit + miss == prompt) {
                "Generation telemetry cache counts do not cover the prompt"
            }
        }
    }

    private fun projectDirectory(projectId: String): File = File(root, hash(projectId))

    private fun runFile(projectId: String, runId: String): File =
        File(projectDirectory(projectId), "${hash(runId)}.$FILE_EXTENSION")

    companion object {
        const val DEFAULT_TERMINAL_RETENTION = 3
        private const val FILE_EXTENSION = "json"
        private const val MAX_TELEMETRY_CALLS = 64
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val CONTRACT_ID = Regex("[a-z0-9][a-z0-9._-]{0,95}")

        private val RUN_ORDER = compareByDescending<GenerationRun> { it.createdAt }
            .thenByDescending { it.updatedAt }
            .thenByDescending { it.id }

        private val TERMINAL_RETENTION_ORDER =
            compareByDescending<GenerationRun> { it.completedAt.orEmpty() }
                .thenByDescending { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }

        internal fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return buildString(bytes.size * 2) {
                for (byte in bytes) {
                    val number = byte.toInt() and 0xff
                    append("0123456789abcdef"[number ushr 4])
                    append("0123456789abcdef"[number and 0x0f])
                }
            }
        }
    }
}

internal class GenerationRunStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
