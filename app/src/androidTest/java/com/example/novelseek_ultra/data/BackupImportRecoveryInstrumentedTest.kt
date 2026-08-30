package com.example.novelseek_ultra.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android-runtime coverage for encrypted rollback values and directory durability barriers. */
@RunWith(AndroidJUnit4::class)
class BackupImportRecoveryInstrumentedTest {

    @Test
    fun interruptedImportRecoversAfterRuntimeComponentsAreReopened() {
        resetFixture()
        try {
            prepareInterruptedFixture()

            val reopenedSecrets = SecureStore(testContext())
            assertTrue(BackupImportJournal.recoverIfNeeded(testRoot(), stateFile(), reopenedSecrets))
            assertRecoveredFixture(reopenedSecrets)
        } finally {
            cleanupFixture()
        }
    }

    @Test
    fun committedImportRemainsCommittedAfterRuntimeComponentsAreReopened() {
        resetFixture()
        try {
            val secrets = SecureStore(testContext())
            val handle = prepareJournal(secrets)
            writeImportedFixture(secrets)
            BackupImportJournal.commit(handle, secrets)

            val reopenedSecrets = SecureStore(testContext())
            assertFalse(BackupImportJournal.recoverIfNeeded(testRoot(), stateFile(), reopenedSecrets))
            assertEquals(NEW_BODY, AtomicTextFile.readText(bodyFile()))
            assertEquals(NEW_STATE, AtomicTextFile.readText(stateFile()))
            assertEquals(NEW_KEY, reopenedSecrets.get(SECRET_KEY))
        } finally {
            cleanupFixture()
        }
    }

    /** Host-script phase 1. The runner is intentionally killed with an active durable journal. */
    @Test
    fun prepareAndKillProcessWithActiveJournal() {
        assumeTrue(processDeathPhase() == PHASE_PREPARE)
        resetFixture()
        val secrets = SecureStore(testContext())
        val handle = prepareJournal(secrets)
        when (requiredCheckpoint()) {
            CHECKPOINT_MANIFEST_ONLY -> Unit
            CHECKPOINT_PARTIAL_FILES -> writeImportedFiles()
            CHECKPOINT_SECRETS_WRITTEN -> {
                writeImportedFiles()
                secrets.putAllDurably(mapOf(SECRET_KEY to NEW_KEY))
            }
            CHECKPOINT_STATE_WRITTEN -> writeImportedFixture(secrets)
            CHECKPOINT_COMMITTED -> {
                writeImportedFixture(secrets)
                BackupImportJournal.commit(handle, secrets)
            }
            else -> error("Unsupported process-death checkpoint")
        }

        Process.killProcess(Process.myPid())
        Thread.sleep(5_000)
        fail("Instrumentation process survived Process.killProcess")
    }

    /** Host-script phase 2. A new instrumentation process verifies and resolves that journal. */
    @Test
    fun verifyRecoveryAfterProcessDeath() {
        assumeTrue(processDeathPhase() == PHASE_VERIFY)
        try {
            val checkpoint = requiredCheckpoint()
            val repository = AppRepository.get(IsolatedRepositoryContext(testContext(), testRoot()))
            val reopenedSecrets = SecureStore(testContext())
            if (checkpoint == CHECKPOINT_COMMITTED) {
                assertFalse(repository.recoveredInterruptedBackupImport)
                assertCommittedFixture(reopenedSecrets)
            } else {
                assertTrue(repository.recoveredInterruptedBackupImport)
                assertRecoveredFixture(reopenedSecrets)
            }
        } finally {
            cleanupFixture()
        }
    }

    private fun prepareInterruptedFixture() {
        val secrets = SecureStore(testContext())
        prepareJournal(secrets)
        writeImportedFixture(secrets)
        assertTrue(BackupImportJournal.hasActiveJournal(testRoot()))
    }

    private fun prepareJournal(secrets: SecureStore): BackupImportJournal.Handle {
        AtomicTextFile.writeText(stateFile(), OLD_STATE)
        AtomicTextFile.writeText(bodyFile(), OLD_BODY)
        secrets.putAllDurably(mapOf(SECRET_KEY to OLD_KEY))
        return BackupImportJournal.prepare(
            filesDir = testRoot(),
            previousStateJson = OLD_STATE,
            snapshots = listOf(
                BackupImportJournal.SnapshotInput(bodyFile(), existed = true, text = OLD_BODY),
                BackupImportJournal.SnapshotInput(createdFile(), existed = false, text = null),
            ),
            secretKeys = listOf(SECRET_KEY),
            secrets = secrets,
        )
    }

    private fun writeImportedFixture(secrets: SecureStore) {
        writeImportedFiles()
        secrets.putAllDurably(mapOf(SECRET_KEY to NEW_KEY))
        AtomicTextFile.writeText(stateFile(), NEW_STATE)
    }

    private fun writeImportedFiles() {
        AtomicTextFile.writeText(bodyFile(), NEW_BODY)
        AtomicTextFile.writeText(createdFile(), NEW_FILE)
    }

    private fun assertRecoveredFixture(secrets: SecureStore) {
        assertEquals(OLD_BODY, AtomicTextFile.readText(bodyFile()))
        assertFalse(createdFile().exists())
        assertEquals(OLD_STATE, AtomicTextFile.readText(stateFile()))
        assertEquals(OLD_KEY, secrets.get(SECRET_KEY))
        assertFalse(BackupImportJournal.hasActiveJournal(testRoot()))
    }

    private fun assertCommittedFixture(secrets: SecureStore) {
        assertEquals(NEW_BODY, AtomicTextFile.readText(bodyFile()))
        assertEquals(NEW_FILE, AtomicTextFile.readText(createdFile()))
        assertEquals(NEW_STATE, AtomicTextFile.readText(stateFile()))
        assertEquals(NEW_KEY, secrets.get(SECRET_KEY))
        assertFalse(BackupImportJournal.hasActiveJournal(testRoot()))
    }

    private fun resetFixture() {
        cleanupRoot()
        assertTrue(testRoot().mkdirs() || testRoot().isDirectory)
        AtomicTextFile.syncDirectory(requireNotNull(testRoot().parentFile))
        SecureStore(testContext()).apply {
            clearRollbackValues()
            putAllDurably(mapOf(SECRET_KEY to OLD_KEY))
        }
    }

    private fun cleanupFixture() {
        runCatching {
            SecureStore(testContext()).apply {
                clearRollbackValues()
                remove(SECRET_KEY)
            }
        }
        cleanupRoot()
    }

    private fun cleanupRoot() {
        val root = testRoot().canonicalFile
        val expectedParent = testContext().filesDir.canonicalFile
        check(root.parentFile == expectedParent) { "Refusing to delete outside instrumentation filesDir" }
        check(!root.exists() || root.deleteRecursively()) { "Unable to clean instrumentation fixture" }
    }

    private fun processDeathPhase(): String? =
        InstrumentationRegistry.getArguments().getString(PROCESS_DEATH_PHASE_ARG)

    private fun requiredCheckpoint(): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString(CHECKPOINT_ARG)) {
            "Missing $CHECKPOINT_ARG instrumentation argument"
        }

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().context
    private fun testRoot(): File = File(testContext().filesDir, TEST_ROOT_NAME)
    private fun stateFile(): File = File(testRoot(), "app_state.json")
    private fun bodyFile(): File = File(testRoot(), "chapters/existing.json")
    private fun createdFile(): File = File(testRoot(), "chapters/created.json")

    private class IsolatedRepositoryContext(base: Context, private val root: File) :
        ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    private companion object {
        const val TEST_ROOT_NAME = "backup-import-runtime-test"
        const val SECRET_KEY = "textModelProfile:instrumentation-crash-test"
        const val OLD_STATE = "{\"textModelProfiles\":[],\"fixtureMarker\":\"old\"}"
        const val NEW_STATE = "{\"textModelProfiles\":[],\"fixtureMarker\":\"new\"}"
        const val OLD_BODY = "old-body"
        const val NEW_BODY = "new-body"
        const val NEW_FILE = "new-file"
        const val OLD_KEY = "old-key"
        const val NEW_KEY = "new-key"
        const val PROCESS_DEATH_PHASE_ARG = "backupImportProcessDeathPhase"
        const val CHECKPOINT_ARG = "backupImportCheckpoint"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val CHECKPOINT_MANIFEST_ONLY = "MANIFEST_ONLY"
        const val CHECKPOINT_PARTIAL_FILES = "PARTIAL_FILES"
        const val CHECKPOINT_SECRETS_WRITTEN = "SECRETS_WRITTEN"
        const val CHECKPOINT_STATE_WRITTEN = "STATE_WRITTEN"
        const val CHECKPOINT_COMMITTED = "COMMITTED"
    }
}
