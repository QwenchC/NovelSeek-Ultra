package com.example.novelseek_ultra.data.backup

import com.example.novelseek_ultra.data.model.BackupBundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveCodecTest {
    private val bundle = BackupBundle(
        exportedAt = "2026-10-07T08:00:00Z", appVersion = "1.6.0",
        data = buildJsonObject {
            put("projects", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("p1")) })))
            put("futurePcField", buildJsonObject { put("nested", JsonArray(listOf(JsonPrimitive("保留未知字段")))) })
            put("chapterBodies", buildJsonObject {
                put("c1", buildJsonObject { put("draft", JsonPrimitive("雪😀".repeat(3_000))); put("final", JsonPrimitive("正式正文")) })
            })
            put("chapterIllustrations", buildJsonObject { put("c1", JsonArray(listOf(
                buildJsonObject { put("id", JsonPrimitive("i1")); put("imageBase64", JsonPrimitive("QUJD")) },
                buildJsonObject { put("id", JsonPrimitive("i2")); put("imageBase64", JsonPrimitive("REVG")) },
            ))) })
            put("generationRunsByProject", buildJsonObject { put("p1", JsonArray(listOf(
                buildJsonObject { put("id", JsonPrimitive("g1")); put("body", JsonPrimitive("候选稿一")) },
                buildJsonObject { put("id", JsonPrimitive("g2")); put("body", JsonPrimitive("候选稿二")) },
            ))) })
            put("sceneWritingByProject", buildJsonObject { put("p1", buildJsonObject {
                put("version", JsonPrimitive(1))
                put("plans", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("plan1")); put("chapterId", JsonPrimitive("c1")) })))
                put("checkpoints", buildJsonObject { put("c1", buildJsonObject { put("body", JsonPrimitive("场景进度")) }) })
            }) })
        },
    )

    @Test fun archiveRoundTripSplitsTextImagesGenerationAndScenesAndPreservesUnknownFields() {
        val bytes = write()
        assertEquals(bundle, BackupArchiveCodec.read(ByteArrayInputStream(bytes)))
        val entries = unpack(bytes)
        assertEquals("manifest.json", entries.first().first)
        val metadata = Json.parseToJsonElement(entries.first { it.first == "backup.json" }.second.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, metadata.getValue("version").toString().toInt())
        assertFalse(metadata.toString().contains("雪😀"))
        assertFalse(metadata.toString().contains("场景进度"))
        val manifest = Json.parseToJsonElement(entries.first().second.toString(Charsets.UTF_8)).jsonObject
        val pointers = manifest.getValue("entries").jsonArray.mapNotNull { it.jsonObject["jsonPath"] }
        assertTrue(pointers.any { it.toString() == "[\"data\",\"generationRunsByProject\",\"p1\",\"1\"]" })
        assertTrue(pointers.any { it.toString() == "[\"data\",\"sceneWritingByProject\",\"p1\",\"checkpoints\",\"c1\"]" })
    }

    @Test fun legacyJsonAndBomJsonRoundTripWithoutChangingBundleVersion() {
        val legacy = Json.encodeToString(bundle).toByteArray(Charsets.UTF_8)
        assertEquals(bundle, BackupArchiveCodec.read(ByteArrayInputStream(legacy)))
        assertEquals(bundle, BackupArchiveCodec.read(ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + legacy)))
        val future = Json.encodeToString(bundle.copy(version = 2)).toByteArray()
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(future)) }
        val streamed = ByteArrayOutputStream().also { BackupArchiveCodec.writeLegacyJson(it, bundle) }.toByteArray()
        assertEquals(bundle, BackupArchiveCodec.read(ByteArrayInputStream(streamed)))
    }

    @Test fun changedPayloadWithValidZipCrcIsRejectedBySha256() {
        val entries = unpack(write()).toMutableList()
        val index = entries.indexOfFirst { it.first == "attachments/000001.json" }
        entries[index] = entries[index].first to entries[index].second.toString(Charsets.UTF_8).replace("雪", "风").toByteArray(Charsets.UTF_8)
        val failure = assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(pack(entries))) }
        assertTrue(failure.message.orEmpty().contains("校验失败"))
    }

    @Test fun zipTraversalAbsoluteBackslashAndUndeclaredPathsAreRejected() {
        for (path in listOf("../backup.json", "/backup.json", "C:/backup.json", "attachments\\000001.json", "extra.json", "attachments/../backup.json")) {
            val entries = unpack(write()).toMutableList()
            entries.add(1, path to "{}".toByteArray())
            assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(pack(entries))) }
        }
    }

    @Test fun duplicateZipEntryNamesAreRejectedBeforeTheyCanOverwriteVerifiedData() {
        val entries = unpack(write()).toMutableList()
        entries += "backupXjson" to entries.first { it.first == "backup.json" }.second
        val bytes = pack(entries)
        val from = "backupXjson".toByteArray()
        val to = "backup.json".toByteArray()
        for (index in 0..bytes.size - from.size) if (from.indices.all { bytes[index + it] == from[it] }) {
            to.copyInto(bytes, index)
        }
        val failure = assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(bytes)) }
        assertTrue(failure.message.orEmpty().contains("重复路径"))
    }

    @Test fun manifestDuplicatePathsOverlappingPointersAndUnsupportedVersionsAreRejected() {
        val entries = unpack(write())
        val original = Json.parseToJsonElement(entries.first().second.toString(Charsets.UTF_8)).jsonObject
        fun changedManifest(manifest: JsonObject): ByteArray = pack(listOf("manifest.json" to manifest.toString().toByteArray()) + entries.drop(1))
        val descriptors = original.getValue("entries").jsonArray
        val duplicate = JsonObject(original + ("entries" to JsonArray(descriptors + descriptors.first())))
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(changedManifest(duplicate))) }
        val mutated = descriptors.mapIndexed { index, value ->
            if (index != 2) value else JsonObject(value.jsonObject + ("jsonPath" to descriptors[1].jsonObject.getValue("jsonPath")))
        }
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(changedManifest(JsonObject(original + ("entries" to JsonArray(mutated)))))) }
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(changedManifest(JsonObject(original + ("archiveVersion" to JsonPrimitive(2)))))) }
    }

    @Test fun missingFilesAndNonPlaceholderAttachmentTargetsAreRejected() {
        val entries = unpack(write())
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(pack(entries.filterNot { it.first == "attachments/000001.json" }))) }
        // An updated hash cannot legitimize an attachment whose target is not an explicit placeholder.
        val empty = bundle.copy(data = buildJsonObject { put("chapterBodies", buildJsonObject { put("c1", JsonPrimitive("body")) }) })
        val emptyEntries = unpack(write(empty)).toMutableList()
        val manifest = Json.parseToJsonElement(emptyEntries[0].second.toString(Charsets.UTF_8)).jsonObject
        val descriptors = manifest.getValue("entries").jsonArray.mapIndexed { index, value ->
            if (index != 1) value else JsonObject(value.jsonObject + ("jsonPath" to JsonArray(listOf(JsonPrimitive("data"), JsonPrimitive("chapterBodies"), JsonPrimitive("missing")))))
        }
        emptyEntries[0] = "manifest.json" to JsonObject(manifest + ("entries" to JsonArray(descriptors))).toString().toByteArray()
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) { BackupArchiveCodec.read(ByteArrayInputStream(pack(emptyEntries))) }
    }

    @Test fun entryTotalCompressedInputAndEntryCountLimitsAreEnforced() {
        val bytes = write()
        val output = ByteArrayOutputStream()
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) {
            BackupArchiveCodec.write(output, bundle, BackupArchiveCodec.Limits(maxEntryBytes = 100))
        }
        assertEquals(0, output.size())
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) {
            BackupArchiveCodec.read(ByteArrayInputStream(bytes), BackupArchiveCodec.Limits(maxEntryBytes = 100))
        }
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) {
            BackupArchiveCodec.read(ByteArrayInputStream(bytes), BackupArchiveCodec.Limits(maxEntryBytes = 5_000, maxTotalBytes = 5_000))
        }
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) {
            BackupArchiveCodec.read(ByteArrayInputStream(bytes), BackupArchiveCodec.Limits(maxInputBytes = bytes.size.toLong() - 1))
        }
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) {
            BackupArchiveCodec.read(ByteArrayInputStream(bytes), BackupArchiveCodec.Limits(maxEntries = 2))
        }
        assertThrows(IllegalArgumentException::class.java) { BackupArchiveCodec.Limits(maxTotalBytes = BackupArchiveCodec.MAX_ARCHIVE_BYTES + 1) }
    }

    @Test fun trailingBytesCannotBypassCompressedInputLimitAndCallerStreamsStayOpen() {
        val bytes = write()
        assertThrows(BackupArchiveCodec.ArchiveException::class.java) {
            BackupArchiveCodec.read(ByteArrayInputStream(bytes + ByteArray(20_000)), BackupArchiveCodec.Limits(maxInputBytes = bytes.size.toLong() + 10))
        }
        val input = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        assertEquals(bundle, BackupArchiveCodec.read(input))
        assertFalse(input.closed)
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        BackupArchiveCodec.write(output, bundle)
        assertFalse(output.closed)
    }

    private fun write(value: BackupBundle = bundle): ByteArray = ByteArrayOutputStream()
        .also { BackupArchiveCodec.write(it, value) }.toByteArray()
    private fun unpack(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name to zip.readBytes())
            }
        }
    }
    private fun pack(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
}
