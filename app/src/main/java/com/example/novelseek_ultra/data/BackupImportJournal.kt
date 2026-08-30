package com.example.novelseek_ultra.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Encrypted rollback-value boundary used by the crash-recovery journal. */
internal interface BackupImportSecretRollbackStore {
    fun stageRollbackValues(secretKeys: List<String>)
    fun validateRollbackValues(secretKeys: List<String>)
    fun restoreRollbackValues(secretKeys: List<String>)
    fun clearRollbackValues()
}

internal fun validateBackupImportSecretKeys(secretKeys: List<String>) {
    check(secretKeys.size == secretKeys.distinct().size) {
        "备份导入恢复日志包含重复的密钥标识"
    }
    secretKeys.forEach { key ->
        val valid = key == SecureStore.TEXT_MODEL_CONFIG_KEY ||
            key == SecureStore.EMBEDDING_CONFIG_KEY ||
            key == SecureStore.POLLINATIONS_KEY ||
            (key.startsWith(SecureStore.TEXT_MODEL_PROFILE_KEY_PREFIX) &&
                key.removePrefix(SecureStore.TEXT_MODEL_PROFILE_KEY_PREFIX).isNotBlank())
        check(valid) { "备份导入恢复日志包含不受支持的密钥标识" }
    }
}

/**
 * Write-ahead rollback journal for a multi-file backup import.
 *
 * The manifest is written last during preparation and deleted first during commit. Its presence
 * therefore has one unambiguous meaning: target files may have changed and startup must restore
 * every snapshot before opening the repository. Recovery is idempotent, so a second process death
 * while rolling back simply retries the same journal on the next launch.
 */
internal object BackupImportJournal {
    private const val JOURNAL_VERSION = 2
    private const val JOURNAL_DIR = "backup_import_transaction"
    private const val MANIFEST_FILE = "manifest.json"
    private const val STATE_SNAPSHOT_FILE = "previous_state.json"
    private const val ROLLBACK_DIR = "rollback"

    @Serializable
    private data class Manifest(
        val version: Int = JOURNAL_VERSION,
        val stateSnapshot: String = STATE_SNAPSHOT_FILE,
        val files: List<FileEntry> = emptyList(),
        val secretKeys: List<String> = emptyList(),
    )

    @Serializable
    private data class FileEntry(
        val targetRelativePath: String,
        val existed: Boolean,
        val rollbackFile: String? = null,
    )

    data class SnapshotInput(
        val target: File,
        val existed: Boolean,
        val text: String?,
    )

    class Handle internal constructor(internal val manifest: File)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun prepare(
        filesDir: File,
        previousStateJson: String,
        snapshots: List<SnapshotInput>,
        secretKeys: List<String>,
        secrets: BackupImportSecretRollbackStore,
    ): Handle {
        val normalizedSecretKeys = secretKeys.sorted()
        validateBackupImportSecretKeys(normalizedSecretKeys)
        val journalDir = File(filesDir, JOURNAL_DIR)
        val manifestFile = File(journalDir, MANIFEST_FILE)
        check(!AtomicTextFile.exists(manifestFile)) {
            "存在尚未恢复的备份导入事务，拒绝开始新的导入"
        }
        check(deleteTree(journalDir)) { "无法清理旧的备份导入事务目录" }
        check(journalDir.mkdirs() || journalDir.isDirectory) { "无法创建备份导入事务目录" }
        AtomicTextFile.syncDirectory(filesDir)
        if (snapshots.any { it.existed }) {
            val rollbackDir = File(journalDir, ROLLBACK_DIR)
            check(rollbackDir.mkdirs() || rollbackDir.isDirectory) { "无法创建备份导入回滚目录" }
            AtomicTextFile.syncDirectory(journalDir)
        }

        return try {
            AtomicTextFile.writeText(File(journalDir, STATE_SNAPSHOT_FILE), previousStateJson)
            val entries = snapshots.mapIndexed { index, snapshot ->
                val relativeTarget = relativePathWithin(filesDir, snapshot.target)
                val rollbackRelative = if (snapshot.existed) {
                    val rollbackName = index.toString().padStart(5, '0') + ".json"
                    val rollbackFile = File(File(journalDir, ROLLBACK_DIR), rollbackName)
                    AtomicTextFile.writeText(rollbackFile, checkNotNull(snapshot.text))
                    relativePathWithin(journalDir, rollbackFile)
                } else {
                    null
                }
                FileEntry(relativeTarget, snapshot.existed, rollbackRelative)
            }
            // Values are staged in EncryptedSharedPreferences, never in the plaintext journal.
            secrets.stageRollbackValues(normalizedSecretKeys)
            val manifest = Manifest(files = entries, secretKeys = normalizedSecretKeys)
            AtomicTextFile.writeText(
                manifestFile,
                json.encodeToString(Manifest.serializer(), manifest),
            )
            Handle(manifestFile)
        } catch (error: Throwable) {
            runCatching { secrets.clearRollbackValues() }
            runCatching { deleteTree(journalDir) }
            throw error
        }
    }

    /** Delete the marker first: after this succeeds the new state is the durable committed state. */
    fun commit(handle: Handle, secrets: BackupImportSecretRollbackStore) {
        check(AtomicTextFile.delete(handle.manifest)) { "无法提交备份导入事务日志" }
        runCatching { secrets.clearRollbackValues() }
        handle.manifest.parentFile?.let { journalDir ->
            runCatching { deleteTree(journalDir) }
        }
    }

    /** Called only after an in-process rollback has restored every target successfully. */
    fun resolveAfterRollback(handle: Handle, secrets: BackupImportSecretRollbackStore) {
        check(AtomicTextFile.delete(handle.manifest)) { "无法清除已回滚的备份导入事务日志" }
        runCatching { secrets.clearRollbackValues() }
        handle.manifest.parentFile?.let { journalDir ->
            runCatching { deleteTree(journalDir) }
        }
    }

    /** Restore an interrupted import before AppRepository loads app_state.json. */
    fun recoverIfNeeded(
        filesDir: File,
        stateFile: File,
        secrets: BackupImportSecretRollbackStore,
    ): Boolean {
        val journalDir = File(filesDir, JOURNAL_DIR)
        val manifestFile = File(journalDir, MANIFEST_FILE)
        if (!AtomicTextFile.exists(manifestFile)) {
            // Preparation can die before the manifest marker is written. No live target has been
            // touched in that phase, so only orphan snapshots/encrypted rollback values remain.
            runCatching { secrets.clearRollbackValues() }
            runCatching { deleteTree(journalDir) }
            return false
        }

        val manifest = try {
            json.decodeFromString(Manifest.serializer(), AtomicTextFile.readText(manifestFile))
        } catch (error: Throwable) {
            throw IllegalStateException("备份导入恢复日志损坏；为保护原数据，应用拒绝继续启动", error)
        }
        check(manifest.version == JOURNAL_VERSION) {
            "不支持的备份导入恢复日志版本：${manifest.version}"
        }
        validateBackupImportSecretKeys(manifest.secretKeys)
        check(manifest.secretKeys == manifest.secretKeys.sorted()) {
            "备份导入恢复日志中的密钥标识顺序无效"
        }
        // Validate the encrypted side of the journal before changing any live file.
        secrets.validateRollbackValues(manifest.secretKeys)

        // Every operation below is idempotent. Keep the marker until all restoration has finished.
        manifest.files.asReversed().forEach { entry ->
            val target = resolveWithin(filesDir, entry.targetRelativePath)
            if (entry.existed) {
                val rollbackRelative = checkNotNull(entry.rollbackFile) {
                    "备份导入恢复日志缺少文件快照：${entry.targetRelativePath}"
                }
                val rollback = resolveWithin(journalDir, rollbackRelative)
                AtomicTextFile.writeText(target, AtomicTextFile.readText(rollback))
            } else {
                check(AtomicTextFile.delete(target)) {
                    "无法删除中断导入创建的文件：${target.absolutePath}"
                }
            }
        }
        secrets.restoreRollbackValues(manifest.secretKeys)
        val stateSnapshot = resolveWithin(journalDir, manifest.stateSnapshot)
        AtomicTextFile.writeText(stateFile, AtomicTextFile.readText(stateSnapshot))
        resolveAfterRollback(Handle(manifestFile), secrets)
        return true
    }

    internal fun hasActiveJournal(filesDir: File): Boolean =
        AtomicTextFile.exists(File(File(filesDir, JOURNAL_DIR), MANIFEST_FILE))

    private fun relativePathWithin(root: File, target: File): String {
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(canonicalTarget.path.startsWith(prefix)) {
            "事务目标越过应用私有目录：${target.absolutePath}"
        }
        return canonicalTarget.path.removePrefix(prefix)
    }

    private fun resolveWithin(root: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "事务日志包含无效相对路径" }
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, relative).canonicalFile
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(resolved.path.startsWith(prefix)) { "事务日志路径越过应用私有目录" }
        return resolved
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (isSymbolicLink(file)) return file.delete() || !file.exists()
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            children.forEach { child -> if (!deleteTree(child)) return false }
        }
        return file.delete() || !file.exists()
    }

    /** Java 11-compatible no-follow check; Android API 24 cannot rely on java.nio.file.Files. */
    private fun isSymbolicLink(file: File): Boolean = runCatching {
        val absolute = file.absoluteFile
        val parent = absolute.parentFile ?: return@runCatching false
        val candidate = File(parent.canonicalFile, absolute.name)
        candidate.canonicalFile != candidate.absoluteFile
    }.getOrDefault(true)
}
