package com.example.novelseek_ultra.data.backup

import com.example.novelseek_ultra.data.model.BackupBundle
import com.example.novelseek_ultra.data.writing.WritingArchive
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.Project as NovelProject
import com.example.novelseek_ultra.data.model.Chapter
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exports an actual Java DEFLATED archive; PC tests read it for cross-runtime verification. */
class CrossPlatformBackupTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun fixture(): BackupBundle = json.decodeFromString(
        javaClass.getResource("/backup-cross-platform.json")!!.readText())

    private fun assertDomainRecords(bundle: BackupBundle) {
        val project = json.decodeFromJsonElement<NovelProject>(bundle.data.getValue("projects").jsonArray.single())
        assertEquals("interop-project", project.id)
        assertEquals("两端互通测试", project.title)
        assertEquals(30_000, project.target_word_count)
        assertEquals("2026-10-07T10:00:00Z", project.created_at)
        val metadata = bundle.data.getValue("chaptersByProject").jsonObject.getValue(project.id).jsonArray.single()
        val chapter = json.decodeFromJsonElement<Chapter>(metadata)
        assertEquals("interop-chapter", chapter.id)
        assertEquals(project.id, chapter.project_id)
        assertEquals(3, chapter.order_index)
        assertEquals("寻找线索", chapter.outline_goal)
        assertEquals("不能暴露秘密", chapter.conflict)
        assertEquals("残页指向故人", chapter.twist)
        assertEquals("守卫突然回头", chapter.cliffhanger)
        assertEquals(12, chapter.word_count)
        assertEquals("interop-arc", chapter.arcId)
        assertEquals("2026-10-07T10:00:00Z", chapter.created_at)
        assertEquals("2026-10-07T10:00:00Z", chapter.updated_at)
    }

    @Test fun sharedWireFixtureRoundTripsAndDecodesDomainRecords() {
        val fixture = fixture()
        assertDomainRecords(fixture)
        json.decodeFromString<WritingArchive>(fixture.data.getValue("sceneWritingByProject").jsonObject.getValue("interop-project").toString())
        json.decodeFromString<AgentSession>(fixture.data.getValue("agentSessions").jsonObject.getValue("interop-session").toString())
        val bytes = ByteArrayOutputStream().also { BackupArchiveCodec.write(it, fixture) }.toByteArray()
        val decoded = BackupArchiveCodec.read(bytes.inputStream())
        assertEquals(fixture, decoded)
        assertDomainRecords(decoded)
        val output = File("build/interoperability/android-backup.zip")
        output.parentFile!!.mkdirs()
        output.writeBytes(bytes)
    }
    @Test fun pcStoredArchiveHasExactlyTheSharedData() {
        val path = System.getenv("NOVELSEEK_PC_BACKUP_FIXTURE") ?: return
        File(path).inputStream().use {
            val decoded = BackupArchiveCodec.read(it)
            assertEquals(fixture(), decoded)
            assertDomainRecords(decoded)
        }
    }
}
