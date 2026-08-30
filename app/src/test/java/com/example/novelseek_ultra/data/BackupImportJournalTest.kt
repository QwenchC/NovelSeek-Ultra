package com.example.novelseek_ultra.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupImportJournalTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun startupRecoveryRestoresFilesStateAndEncryptedSecrets() {
        val root = temp.newFolder("files")
        val state = File(root, "app_state.json").apply { writeText("old-state") }
        val existing = File(root, "chapters/a.json").apply {
            parentFile?.mkdirs()
            writeText("old-body")
        }
        val created = File(root, "chapters/new.json")
        val secrets = FakeSecrets(mutableMapOf("pollinationsKey" to "old-key"))

        BackupImportJournal.prepare(
            filesDir = root,
            previousStateJson = "old-state",
            snapshots = listOf(
                BackupImportJournal.SnapshotInput(existing, existed = true, text = "old-body"),
                BackupImportJournal.SnapshotInput(created, existed = false, text = null),
            ),
            secretKeys = listOf("pollinationsKey"),
            secrets = secrets,
        )
        assertTrue(BackupImportJournal.hasActiveJournal(root))

        AtomicTextFile.writeText(existing, "new-body")
        AtomicTextFile.writeText(created, "new-file")
        AtomicTextFile.writeText(state, "new-state")
        secrets.live["pollinationsKey"] = "new-key"

        assertTrue(BackupImportJournal.recoverIfNeeded(root, state, secrets))
        assertEquals("old-body", existing.readText())
        assertFalse(created.exists())
        assertEquals("old-state", state.readText())
        assertEquals("old-key", secrets.live["pollinationsKey"])
        assertFalse(BackupImportJournal.hasActiveJournal(root))
        assertTrue(secrets.staged.isEmpty())
    }

    @Test
    fun committedTransactionIsNeverRolledBack() {
        val root = temp.newFolder("committed")
        val state = File(root, "app_state.json").apply { writeText("old-state") }
        val target = File(root, "chapters/a.json").apply {
            parentFile?.mkdirs()
            writeText("old-body")
        }
        val secrets = FakeSecrets(mutableMapOf("pollinationsKey" to "old-key"))
        val handle = BackupImportJournal.prepare(
            root,
            "old-state",
            listOf(BackupImportJournal.SnapshotInput(target, true, "old-body")),
            listOf("pollinationsKey"),
            secrets,
        )

        AtomicTextFile.writeText(target, "new-body")
        AtomicTextFile.writeText(state, "new-state")
        secrets.live["pollinationsKey"] = "new-key"
        BackupImportJournal.commit(handle, secrets)

        assertFalse(BackupImportJournal.recoverIfNeeded(root, state, secrets))
        assertEquals("new-body", target.readText())
        assertEquals("new-state", state.readText())
        assertEquals("new-key", secrets.live["pollinationsKey"])
    }

    @Test
    fun failedRecoveryKeepsMarkerAndCanRetryIdempotently() {
        val root = temp.newFolder("retry")
        val state = File(root, "app_state.json").apply { writeText("old-state") }
        val target = File(root, "body.json").apply { writeText("old") }
        val secrets = FakeSecrets(mutableMapOf("pollinationsKey" to "old-key"))
        BackupImportJournal.prepare(
            root,
            "old-state",
            listOf(BackupImportJournal.SnapshotInput(target, true, "old")),
            listOf("pollinationsKey"),
            secrets,
        )
        AtomicTextFile.writeText(target, "new")
        AtomicTextFile.writeText(state, "new-state")
        secrets.live["pollinationsKey"] = "new-key"
        secrets.failNextRestore = true

        assertThrows(IllegalStateException::class.java) {
            BackupImportJournal.recoverIfNeeded(root, state, secrets)
        }
        assertTrue(BackupImportJournal.hasActiveJournal(root))

        assertTrue(BackupImportJournal.recoverIfNeeded(root, state, secrets))
        assertEquals("old", target.readText())
        assertEquals("old-state", state.readText())
        assertEquals("old-key", secrets.live["pollinationsKey"])
        assertFalse(BackupImportJournal.hasActiveJournal(root))
    }

    @Test
    fun incompletePreparationWithoutMarkerOnlyCleansOrphans() {
        val root = temp.newFolder("preparation")
        val state = File(root, "app_state.json").apply { writeText("current-state") }
        val journalDir = File(root, "backup_import_transaction").apply { mkdirs() }
        File(journalDir, "previous_state.json").writeText("stale-state")
        val secrets = FakeSecrets(mutableMapOf("pollinationsKey" to "current-key")).apply {
            staged["pollinationsKey"] = "stale-key"
            stagedPresence["pollinationsKey"] = true
        }

        assertFalse(BackupImportJournal.recoverIfNeeded(root, state, secrets))
        assertEquals("current-state", state.readText())
        assertEquals("current-key", secrets.live["pollinationsKey"])
        assertTrue(secrets.staged.isEmpty())
        assertFalse(journalDir.exists())
    }

    @Test
    fun recoveryRemovesSecretThatWasAbsentBeforeImport() {
        val root = temp.newFolder("absent-secret")
        val state = File(root, "app_state.json").apply { writeText("old-state") }
        val secrets = FakeSecrets(mutableMapOf())
        BackupImportJournal.prepare(
            root,
            "old-state",
            emptyList(),
            listOf("pollinationsKey"),
            secrets,
        )
        secrets.live["pollinationsKey"] = "imported-key"

        assertTrue(BackupImportJournal.recoverIfNeeded(root, state, secrets))
        assertFalse(secrets.live.containsKey("pollinationsKey"))
    }

    @Test
    fun unsupportedSecretKeyIsRejectedBeforeJournalActivation() {
        val root = temp.newFolder("invalid-secret")
        val secrets = FakeSecrets(mutableMapOf())

        assertThrows(IllegalStateException::class.java) {
            BackupImportJournal.prepare(root, "state", emptyList(), listOf("unknown"), secrets)
        }
        assertFalse(BackupImportJournal.hasActiveJournal(root))
    }

    @Test
    fun missingEncryptedRollbackSlotFailsBeforeAnyLiveFileChanges() {
        val root = temp.newFolder("missing-secret-slot")
        val state = File(root, "app_state.json").apply { writeText("old-state") }
        val target = File(root, "body.json").apply { writeText("old-body") }
        val secrets = FakeSecrets(mutableMapOf("pollinationsKey" to "old-key"))
        BackupImportJournal.prepare(
            root,
            "old-state",
            listOf(BackupImportJournal.SnapshotInput(target, true, "old-body")),
            listOf("pollinationsKey"),
            secrets,
        )
        AtomicTextFile.writeText(target, "new-body")
        AtomicTextFile.writeText(state, "new-state")
        secrets.live["pollinationsKey"] = "new-key"
        secrets.staged.clear()
        secrets.stagedPresence.clear()

        assertThrows(IllegalStateException::class.java) {
            BackupImportJournal.recoverIfNeeded(root, state, secrets)
        }
        assertEquals("new-body", target.readText())
        assertEquals("new-state", state.readText())
        assertEquals("new-key", secrets.live["pollinationsKey"])
        assertTrue(BackupImportJournal.hasActiveJournal(root))
    }

    private class FakeSecrets(
        val live: MutableMap<String, String>,
    ) : BackupImportSecretRollbackStore {
        val staged = mutableMapOf<String, String>()
        val stagedPresence = mutableMapOf<String, Boolean>()
        var failNextRestore = false

        override fun stageRollbackValues(secretKeys: List<String>) {
            staged.clear()
            stagedPresence.clear()
            secretKeys.forEach { key ->
                val present = live.containsKey(key)
                stagedPresence[key] = present
                if (present) staged[key] = live[key].orEmpty()
            }
        }

        override fun restoreRollbackValues(secretKeys: List<String>) {
            if (failNextRestore) {
                failNextRestore = false
                error("injected secret restore failure")
            }
            secretKeys.forEach { key ->
                check(stagedPresence.containsKey(key))
                if (stagedPresence.getValue(key)) live[key] = staged[key].orEmpty() else live.remove(key)
            }
        }

        override fun validateRollbackValues(secretKeys: List<String>) {
            secretKeys.forEach { key ->
                check(stagedPresence.containsKey(key))
                if (stagedPresence.getValue(key)) check(staged.containsKey(key))
            }
        }

        override fun clearRollbackValues() {
            staged.clear()
            stagedPresence.clear()
        }
    }
}
