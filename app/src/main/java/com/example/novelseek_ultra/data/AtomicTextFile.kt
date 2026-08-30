package com.example.novelseek_ultra.data

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

/**
 * Small, Android-compatible atomic text-file helper.
 *
 * The replacement uses sibling `.new` and `.bak` files rather than truncating [target]. A
 * completed old file therefore remains available until the new file has been fully written and
 * synced. Reads repair an interrupted replacement before returning any content.
 */
internal object AtomicTextFile {
    private fun interface RenameOperation {
        fun rename(source: File, destination: File): Boolean
    }

    private val defaultRename = RenameOperation { source, destination ->
        source.renameTo(destination)
    }

    @Synchronized
    fun writeText(target: File, text: String) {
        writeText(target, text, defaultRename)
    }

    /** Visible to JVM tests so replacement failures can be injected deterministically. */
    @Synchronized
    internal fun writeText(
        target: File,
        text: String,
        rename: (source: File, destination: File) -> Boolean,
    ) {
        writeText(target, text, RenameOperation(rename))
    }

    @Synchronized
    fun readText(target: File): String = recoverForRead(target).readText(Charsets.UTF_8)

    @Synchronized
    fun exists(target: File): Boolean = recoverForRead(target).exists()

    @Synchronized
    fun delete(target: File): Boolean {
        val pending = pendingFile(target)
        val backup = backupFile(target)
        val pendingExisted = pending.exists()
        val backupExisted = backup.exists()
        val targetExisted = target.exists()
        val deletedPending = !pendingExisted || pending.delete()
        val deletedBackup = !backupExisted || backup.delete()
        // Keep the committed target until every recoverable sidecar is gone. Otherwise a failed
        // backup deletion could resurrect an older session after the target itself was deleted.
        if (!deletedPending || !deletedBackup) return false
        val deletedTarget = !target.exists() || target.delete()
        if (deletedTarget && (pendingExisted || backupExisted || targetExisted)) {
            target.absoluteFile.parentFile?.takeIf { it.isDirectory }?.let(::syncDirectory)
        }
        return deletedTarget
    }

    private fun writeText(target: File, text: String, rename: RenameOperation) {
        ensureParentDirectory(target)
        prepareForWrite(target, rename)

        val pending = pendingFile(target)
        try {
            FileOutputStream(pending, false).use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                writer.write(text)
                writer.flush()
                output.fd.sync()
            }
            replace(target, pending, rename)
            syncDirectory(checkNotNull(target.absoluteFile.parentFile))
        } finally {
            // A committed file has already been renamed away; otherwise this is incomplete data.
            if (pending.exists()) pending.delete()
        }
    }

    private fun ensureParentDirectory(target: File) {
        val parent = target.absoluteFile.parentFile
            ?: throw IOException("Atomic file has no parent directory: ${target.absolutePath}")
        val missingDirectories = generateSequence(parent) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create atomic file directory: ${parent.absolutePath}")
        }
        if (!parent.isDirectory) {
            throw IOException("Atomic file parent is not a directory: ${parent.absolutePath}")
        }
        // Persist newly-created directory entries from the existing ancestor downwards.
        missingDirectories.asReversed().forEach { created ->
            created.parentFile?.let(::syncDirectory)
        }
    }

    private fun prepareForWrite(target: File, rename: RenameOperation) {
        val backup = backupFile(target)
        if (backup.exists()) {
            if (target.exists()) {
                // The new target was committed and only backup cleanup was interrupted.
                if (!backup.delete()) {
                    throw IOException("Unable to remove stale atomic backup: ${backup.absolutePath}")
                }
            } else if (!rename.rename(backup, target)) {
                // The old target was backed up but the new target was never committed.
                throw IOException("Unable to restore atomic backup: ${backup.absolutePath}")
            }
        }

        val pending = pendingFile(target)
        if (pending.exists() && !pending.delete()) {
            throw IOException("Unable to remove stale atomic pending file: ${pending.absolutePath}")
        }
    }

    private fun replace(target: File, pending: File, rename: RenameOperation) {
        if (!target.exists()) {
            if (!rename.rename(pending, target)) {
                throw IOException("Unable to commit atomic file: ${target.absolutePath}")
            }
            return
        }

        val backup = backupFile(target)
        if (!rename.rename(target, backup)) {
            throw IOException("Unable to back up atomic file: ${target.absolutePath}")
        }

        if (rename.rename(pending, target)) {
            // Failure to delete is harmless: a later read/write recognizes target as committed.
            backup.delete()
            return
        }

        val restored = rename.rename(backup, target)
        if (!restored) {
            throw IOException(
                "Unable to commit atomic file and restore its backup; last good data remains at " +
                    backup.absolutePath,
            )
        }
        throw IOException("Unable to commit atomic file: ${target.absolutePath}")
    }

    private fun recoverForRead(target: File): File {
        val backup = backupFile(target)
        val pending = pendingFile(target)

        if (target.exists()) {
            // Target + backup means commit succeeded but cleanup did not.
            var changed = false
            if (backup.exists()) changed = backup.delete() || changed
            if (pending.exists()) changed = pending.delete() || changed
            if (changed) target.absoluteFile.parentFile?.let(::syncDirectory)
            return target
        }

        if (backup.exists()) {
            // Target missing + backup means replacement stopped between its two renames.
            if (defaultRename.rename(backup, target)) {
                if (pending.exists()) pending.delete()
                target.absoluteFile.parentFile?.let(::syncDirectory)
                return target
            }
            // Even if repair is blocked, callers can still consume the last known-good file.
            return backup
        }

        if (pending.exists() && pending.delete()) {
            target.absoluteFile.parentFile?.let(::syncDirectory)
        }
        return target
    }

    /**
     * Persist directory-entry changes on Android/Linux. Host-side JVM tests intentionally no-op;
     * Android's libcore Os calls are unavailable outside an Android runtime.
     */
    internal fun syncDirectory(directory: File) {
        if (!IS_ANDROID_RUNTIME) return
        val descriptor = try {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
        } catch (error: Exception) {
            throw IOException("Unable to open directory for sync: ${directory.absolutePath}", error)
        }
        try {
            Os.fsync(descriptor)
        } catch (error: Exception) {
            throw IOException("Unable to sync directory: ${directory.absolutePath}", error)
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    private fun pendingFile(target: File): File = File(target.parentFile, "${target.name}.new")

    private fun backupFile(target: File): File = File(target.parentFile, "${target.name}.bak")

    private val IS_ANDROID_RUNTIME: Boolean =
        System.getProperty("java.vm.name").equals("Dalvik", ignoreCase = true) ||
            System.getProperty("java.runtime.name").equals("Android Runtime", ignoreCase = true)
}
