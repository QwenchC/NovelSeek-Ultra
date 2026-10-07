package com.example.novelseek_ultra.data.backup

import com.example.novelseek_ultra.data.model.BackupBundle
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * A checksummed ZIP wrapper around the existing v1 backup, not a new repository schema.
 * No file is extracted: decoded, bounded entries are assembled only after every hash is verified.
 * The caller owns both input and output streams.
 */
@OptIn(ExperimentalSerializationApi::class)
object BackupArchiveCodec {
    const val FORMAT = "novelseek-backup"
    const val ARCHIVE_VERSION = 1
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

    data class Limits(
        val maxEntryBytes: Long = 64L * 1024 * 1024,
        val maxTotalBytes: Long = MAX_ARCHIVE_BYTES,
        val maxInputBytes: Long = MAX_ARCHIVE_BYTES,
        val maxEntries: Int = 50_000,
    ) {
        init {
            require(maxTotalBytes in 1..MAX_ARCHIVE_BYTES)
            require(maxInputBytes in 1..MAX_ARCHIVE_BYTES)
            require(maxEntryBytes in 1..maxTotalBytes)
            require(maxEntries in 2..50_000)
        }
    }

    class ArchiveException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    @Serializable
    private data class Manifest(
        val format: String = FORMAT,
        val archiveVersion: Int = ARCHIVE_VERSION,
        val bundleVersion: Int = BackupBundle.BACKUP_VERSION,
        val entries: List<ManifestEntry>,
    )

    @Serializable
    private data class ManifestEntry(
        val path: String,
        val bytes: Long,
        val sha256: String,
        val jsonPath: List<String>? = null,
    )

    private data class Payload(val path: String, val value: JsonElement, val jsonPath: List<String>? = null)
    private class PatchNode {
        var payload: JsonElement? = null
        var hasPayload = false
        val children = linkedMapOf<String, PatchNode>()
    }
    private class ByteBudget(val maximum: Long) {
        var used = 0L
        fun add(count: Int) {
            if (count.toLong() > maximum - used) reject("备份超过总解压大小上限 $maximum 字节")
            used += count
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val attachmentFields = setOf(
        "chapterBodies", "chapterIllustrations", "novelChats", "agentSessions",
        "coversByProject", "coverImagesByProject", "promoByChapter", "generationRuns",
        "generationRunsByProject", "chapterGenerationRunsByProject", "sceneWritingByProject",
        "sceneWritingPlansByProject", "writingWorkspaceByProject",
    )
    private val nestedMapFields = setOf(
        "generationRuns", "generationRunsByProject", "chapterGenerationRunsByProject",
        "sceneWritingByProject", "sceneWritingPlansByProject", "writingWorkspaceByProject",
    )

    /** Streaming serialization avoids a second String containing all chapter text and base64. */
    fun write(output: OutputStream, bundle: BackupBundle, limits: Limits = Limits()) {
        requireVersion(bundle)
        val payloads = split(bundle, limits.maxEntries)
        if (payloads.size + 1 > limits.maxEntries) reject("备份文件项超过 ${limits.maxEntries} 项")
        val measured = payloads.map { payload ->
            val digest = CountingOutput(null, limits.maxEntryBytes)
            json.encodeToStream(JsonElement.serializer(), payload.value, digest)
            ManifestEntry(payload.path, digest.count, digest.hash(), payload.jsonPath)
        }
        val manifest = Manifest(entries = measured)
        val manifestCount = CountingOutput(null, limits.maxEntryBytes)
        json.encodeToStream(Manifest.serializer(), manifest, manifestCount)
        var total = manifestCount.count
        measured.forEach {
            if (it.bytes > limits.maxTotalBytes - total) reject("备份超过总解压大小上限 ${limits.maxTotalBytes} 字节")
            total += it.bytes
        }
        // Limits are checked before writing any bytes; attachments refer to the existing JSON tree.
        val boundedOutput = CountingOutput(output, limits.maxInputBytes)
        ZipOutputStream(object : FilterOutputStream(boundedOutput) {
            override fun close() { flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
        }).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            json.encodeToStream(Manifest.serializer(), manifest, zip)
            zip.closeEntry()
            payloads.forEachIndexed { index, payload ->
                zip.putNextEntry(ZipEntry(payload.path))
                val encoded = CountingOutput(zip, limits.maxEntryBytes)
                json.encodeToStream(JsonElement.serializer(), payload.value, encoded)
                if (encoded.count != measured[index].bytes || encoded.hash() != measured[index].sha256) reject("备份内容在导出期间发生变化")
                zip.closeEntry()
            }
        }
    }

    /** PC-compatible v1 JSON, also streamed instead of constructing one large backup String. */
    fun writeLegacyJson(output: OutputStream, bundle: BackupBundle, limits: Limits = Limits()) {
        requireVersion(bundle)
        val maximum = minOf(limits.maxInputBytes, limits.maxTotalBytes)
        val measured = CountingOutput(null, maximum)
        json.encodeToStream(BackupBundle.serializer(), bundle, measured)
        json.encodeToStream(BackupBundle.serializer(), bundle, CountingOutput(output, maximum))
    }

    /** Auto-detect ZIP or legacy UTF-8 JSON (including a UTF-8 BOM). */
    fun read(input: InputStream, limits: Limits = Limits()): BackupBundle {
        try {
            val boundedInput = CountingInput(input, limits.maxInputBytes)
            val source = PushbackInputStream(boundedInput, 4)
            val signature = ByteArray(4)
            var count = 0
            while (count < signature.size) {
                val next = source.read(signature, count, signature.size - count)
                if (next < 0) break
                count += next
            }
            if (count == 0) reject("备份文件为空")
            val bom = count >= 3 && signature[0] == 0xEF.toByte() && signature[1] == 0xBB.toByte() && signature[2] == 0xBF.toByte()
            source.unread(signature, if (bom) 3 else 0, count - if (bom) 3 else 0)
            val isZip = !bom && count == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
            val bundle = if (isZip) readZip(source, limits) else {
                json.decodeFromStream(BackupBundle.serializer(), CountingInput(source, limits.maxTotalBytes))
                    .also(::requireVersion)
            }
            // Count central-directory/trailing bytes as well, rather than only bytes ZipInputStream consumed.
            val buffer = ByteArray(8_192)
            while (boundedInput.read(buffer) >= 0) Unit
            return bundle
        } catch (error: ArchiveException) {
            throw error
        } catch (error: Exception) {
            throw ArchiveException("无法读取备份：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun readZip(input: InputStream, limits: Limits): BackupBundle {
        val totalBudget = ByteBudget(limits.maxTotalBytes)
        ZipInputStream(object : FilterInputStream(input) { override fun close() = Unit }).use { zip ->
            val first = zip.nextEntry ?: reject("备份 ZIP 没有文件项")
            validateEntry(first)
            if (first.name != "manifest.json") reject("备份 ZIP 首项必须是 manifest.json")
            val manifestInput = CountingInput(zip, limits.maxEntryBytes, totalBudget)
            val manifest = json.decodeFromStream(Manifest.serializer(), manifestInput)
            if (manifest.format != FORMAT || manifest.archiveVersion != ARCHIVE_VERSION || manifest.bundleVersion != BackupBundle.BACKUP_VERSION) reject("不支持的备份格式或版本")
            if (manifest.entries.size + 1 > limits.maxEntries) reject("备份文件项超过 ${limits.maxEntries} 项")
            val expected = linkedMapOf<String, ManifestEntry>()
            val patchRoot = PatchNode()
            var declaredTotal = manifestInput.count
            manifest.entries.forEach { descriptor ->
                validatePath(descriptor.path)
                if (descriptor.path == "manifest.json" || expected.put(descriptor.path, descriptor) != null) reject("备份清单含重复路径")
                if (descriptor.bytes !in 1..limits.maxEntryBytes) reject("备份单项声明大小超过上限")
                if (!descriptor.sha256.matches(Regex("[0-9a-f]{64}"))) reject("备份清单 SHA-256 无效")
                if (descriptor.bytes > limits.maxTotalBytes - declaredTotal) reject("备份声明的总大小超过上限")
                declaredTotal += descriptor.bytes
                if (descriptor.path == "backup.json") {
                    if (descriptor.jsonPath != null) reject("backup.json 不能作为附件")
                } else {
                    val pointer = descriptor.jsonPath ?: reject("附件缺少 JSON 路径")
                    if (pointer.size !in 3..8 || pointer.first() != "data" || pointer.any { it.length > 1_000 }) reject("附件 JSON 路径无效")
                    patchNode(patchRoot, pointer, create = true)
                }
            }
            if ("backup.json" !in expected) reject("备份缺少 backup.json")
            val seen = mutableSetOf("manifest.json")
            var base: JsonElement? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                validateEntry(entry)
                if (!seen.add(entry.name)) reject("备份 ZIP 含重复路径")
                if (seen.size > limits.maxEntries) reject("备份文件项超过上限")
                val descriptor = expected[entry.name] ?: reject("备份 ZIP 存在清单外文件项")
                val payloadInput = CountingInput(zip, minOf(limits.maxEntryBytes, descriptor.bytes), totalBudget)
                val value = json.decodeFromStream(JsonElement.serializer(), payloadInput)
                if (payloadInput.count != descriptor.bytes || payloadInput.hash() != descriptor.sha256) reject("备份文件校验失败：${entry.name}")
                if (entry.name == "backup.json") base = value
                else patchNode(patchRoot, checkNotNull(descriptor.jsonPath), create = false).apply { payload = value; hasPayload = true }
            }
            if (seen.size != expected.size + 1 || expected.keys.any { it !in seen }) reject("备份 ZIP 缺少清单中的文件项")
            val restored = restore(base ?: reject("备份缺少正文数据"), patchRoot)
            return json.decodeFromJsonElement(BackupBundle.serializer(), restored).also(::requireVersion)
        }
    }

    private fun split(bundle: BackupBundle, maxEntries: Int): List<Payload> {
        val attachments = mutableListOf<Payload>()
        fun detach(value: JsonElement, pointer: List<String>): JsonElement {
            if (attachments.size + 3 > maxEntries) reject("备份文件项超过 $maxEntries 项")
            attachments += Payload("attachments/${(attachments.size + 1).toString().padStart(6, '0')}.json", value, pointer)
            return JsonNull
        }
        val data = JsonObject(bundle.data.mapValues { (field, value) ->
            if (field !in attachmentFields) value
            else when (value) {
                is JsonObject -> JsonObject(value.mapValues { (key, item) ->
                    val pointer = listOf("data", field, key)
                    when {
                        item is JsonArray -> JsonArray(item.mapIndexed { index, child -> detach(child, pointer + index.toString()) })
                        item is JsonObject && field in nestedMapFields -> JsonObject(item.mapValues { (id, child) ->
                            when {
                                field == "sceneWritingByProject" && child is JsonArray -> JsonArray(child.mapIndexed { index, scene -> detach(scene, pointer + id + index.toString()) })
                                field == "sceneWritingByProject" && child is JsonObject -> JsonObject(child.mapValues { (chapterId, checkpoint) -> detach(checkpoint, pointer + id + chapterId) })
                                else -> detach(child, pointer + id)
                            }
                        })
                        else -> detach(item, pointer)
                    }
                })
                is JsonArray -> JsonArray(value.mapIndexed { index, child -> detach(child, listOf("data", field, index.toString())) })
                else -> value
            }
        })
        val base = JsonObject(linkedMapOf(
            "version" to JsonPrimitive(bundle.version), "exportedAt" to JsonPrimitive(bundle.exportedAt),
            "appVersion" to (bundle.appVersion?.let(::JsonPrimitive) ?: JsonNull), "data" to data,
        ))
        return listOf(Payload("backup.json", base)) + attachments
    }

    private fun patchNode(root: PatchNode, pointer: List<String>, create: Boolean): PatchNode {
        var node = root
        for (segment in pointer) {
            if (node.hasPayload) reject("附件 JSON 路径重叠")
            node = if (create) node.children.getOrPut(segment) { PatchNode() }
                else node.children[segment] ?: reject("附件 JSON 路径未登记")
        }
        if (create) {
            if (node.hasPayload || node.children.isNotEmpty()) reject("附件 JSON 路径重复或重叠")
            node.hasPayload = true // Reserve the leaf before any unverified attachment is read.
        }
        return node
    }

    private fun restore(value: JsonElement, patch: PatchNode): JsonElement {
        if (patch.hasPayload) {
            if (value != JsonNull) reject("附件目标必须是空占位")
            return patch.payload ?: reject("附件数据缺失")
        }
        if (patch.children.isEmpty()) return value
        return when (value) {
            is JsonObject -> {
                if (patch.children.keys.any { it !in value }) reject("附件 JSON 对象路径不存在")
                JsonObject(value.mapValues { (key, child) -> patch.children[key]?.let { restore(child, it) } ?: child })
            }
            is JsonArray -> {
                val indexes = patch.children.mapKeys { (key, _) ->
                    val index = key.toIntOrNull() ?: reject("附件 JSON 数组索引无效")
                    if (index !in value.indices || index.toString() != key) reject("附件 JSON 数组索引越界")
                    index
                }
                JsonArray(value.mapIndexed { index, child -> indexes[index]?.let { restore(child, it) } ?: child })
            }
            else -> reject("附件 JSON 路径不能指向非容器")
        }
    }

    private fun requireVersion(bundle: BackupBundle) {
        if (bundle.version != BackupBundle.BACKUP_VERSION) reject("不支持的 BackupBundle 版本 ${bundle.version}")
    }
    private fun validateEntry(entry: ZipEntry) {
        if (entry.isDirectory) reject("备份 ZIP 不允许目录项")
        validatePath(entry.name)
    }
    private fun validatePath(path: String) {
        if (path != "manifest.json" && path != "backup.json" && !path.matches(Regex("attachments/[0-9]{6}\\.json"))) reject("备份 ZIP 路径无效或越界：$path")
    }
    private fun reject(message: String): Nothing = throw ArchiveException(message)

    private class CountingOutput(private val delegate: OutputStream?, private val maximum: Long) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        override fun write(value: Int) {
            check(1); delegate?.write(value); digest.update(value.toByte()); count++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length); delegate?.write(bytes, offset, length); digest.update(bytes, offset, length); count += length
        }
        override fun flush() { delegate?.flush() }
        fun hash(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun check(length: Int) { if (length.toLong() > maximum - count) reject("备份单项超过字节上限 $maximum") }
    }

    private class CountingInput(
        private val delegate: InputStream,
        private val maximum: Long,
        private val total: ByteBudget? = null,
    ) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) { account(1); digest.update(value.toByte()) }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val allowed = minOf(length.toLong(), (maximum - count + 1).coerceAtLeast(1)).toInt()
            val read = delegate.read(bytes, offset, allowed)
            if (read > 0) { account(read); digest.update(bytes, offset, read) }
            return read
        }
        private fun account(length: Int) {
            if (length.toLong() > maximum - count) reject("备份输入或单项超过字节上限 $maximum")
            total?.add(length); count += length
        }
        fun hash(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
