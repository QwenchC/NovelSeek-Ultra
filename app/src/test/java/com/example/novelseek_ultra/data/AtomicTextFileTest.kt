package com.example.novelseek_ultra.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicTextFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeText_createsParentDirectoriesAndPersistsUtf8() {
        val target = File(temporaryFolder.root, "agent/sessions/session.json")

        AtomicTextFile.writeText(target, "第一章 · hello")

        assertTrue(target.isFile)
        assertEquals("第一章 · hello", target.readText(Charsets.UTF_8))
        assertFalse(File(target.parentFile, "session.json.new").exists())
        assertFalse(File(target.parentFile, "session.json.bak").exists())
    }

    @Test
    fun writeText_replacesExistingFile() {
        val target = temporaryFolder.newFile("index.json")
        target.writeText("old", Charsets.UTF_8)

        AtomicTextFile.writeText(target, "new")

        assertEquals("new", AtomicTextFile.readText(target))
    }

    @Test
    fun writeText_commitFailureRestoresLastGoodFile() {
        val target = temporaryFolder.newFile("index.json")
        target.writeText("last-good", Charsets.UTF_8)

        val error = assertThrows(IOException::class.java) {
            AtomicTextFile.writeText(target, "replacement") { source, destination ->
                if (source.name == "index.json.new" && destination == target) {
                    false
                } else {
                    source.renameTo(destination)
                }
            }
        }

        assertTrue(error.message.orEmpty().contains("Unable to commit"))
        assertEquals("last-good", target.readText(Charsets.UTF_8))
        assertFalse(File(target.parentFile, "index.json.new").exists())
        assertFalse(File(target.parentFile, "index.json.bak").exists())
    }

    @Test
    fun readText_recoversInterruptedReplacementFromBackup() {
        val target = File(temporaryFolder.root, "session.json")
        val backup = File(temporaryFolder.root, "session.json.bak")
        val pending = File(temporaryFolder.root, "session.json.new")
        backup.writeText("last-good", Charsets.UTF_8)
        pending.writeText("possibly-incomplete", Charsets.UTF_8)

        assertEquals("last-good", AtomicTextFile.readText(target))

        assertEquals("last-good", target.readText(Charsets.UTF_8))
        assertFalse(backup.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun delete_removesTargetAndRecoverableSidecars() {
        val target = temporaryFolder.newFile("delete.json").apply { writeText("live") }
        File(target.parentFile, "delete.json.new").writeText("pending")
        File(target.parentFile, "delete.json.bak").writeText("backup")

        assertTrue(AtomicTextFile.delete(target))
        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "delete.json.new").exists())
        assertFalse(File(target.parentFile, "delete.json.bak").exists())
    }

    @Test
    fun delete_missingFileUnderMissingDirectoryIsSuccessful() {
        val target = File(temporaryFolder.root, "missing/child.json")

        assertTrue(AtomicTextFile.delete(target))
        assertFalse(target.parentFile?.exists() == true)
    }

    @Test
    fun projectArtifactReadsRecoverChapterIllustrationAndSnapshotFiles() {
        listOf(
            "chapters/chapter-1.json",
            "illustrations/chapter-1.json",
            "versions/project-1/index.json",
            "versions/project-1/snapshot-1.json",
        ).forEach { relativePath ->
            val target = File(temporaryFolder.root, relativePath)
            val parent = requireNotNull(target.parentFile)
            parent.mkdirs()
            val backup = File(parent, "${target.name}.bak")
            val pending = File(parent, "${target.name}.new")
            backup.writeText("last-good-$relativePath", Charsets.UTF_8)
            pending.writeText("partial", Charsets.UTF_8)

            assertEquals("last-good-$relativePath", AtomicTextFile.readText(target))
            assertTrue(target.isFile)
            assertFalse(backup.exists())
            assertFalse(pending.exists())
        }
    }
}
