package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.ai.ComfyCertificateTrust
import com.example.novelseek_ultra.data.ai.ComfyTlsTrust
import com.example.novelseek_ultra.data.ai.ComfyUIEndpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Device-local authority, deliberately separate from app state and all portable backup data. */
internal class ComfyCertificateTrustStore(private val root: File) {
    @Serializable
    private data class SavedTrusts(val version: Int = 1, val certificates: List<ComfyCertificateTrust> = emptyList())

    private val lock = Any()
    // A failed disk revocation must not re-enable the old record within this process.
    private val deniedOrigins = mutableSetOf<String>()
    private val file = File(root, "certificates.json")
    private val json = Json { ignoreUnknownKeys = false }

    fun find(baseUrl: String): ComfyCertificateTrust? = synchronized(lock) {
        val normalized = ComfyUIEndpoint.normalize(baseUrl)
        if (!normalized.startsWith("https://")) return@synchronized null
        val origin = ComfyTlsTrust.originFor(normalized)
        if (origin in deniedOrigins) return@synchronized null
        read().certificates.singleOrNull { it.origin == origin }
    }

    fun approve(trust: ComfyCertificateTrust) = synchronized(lock) {
        validate(trust)
        val previous = read()
        val updated = previous.certificates.filterNot { it.origin == trust.origin } + trust
        require(updated.size <= MAX_CERTIFICATES) { "受信任服务器已达上限，请先移除不再使用的记录。" }
        AtomicTextFile.writeText(file, json.encodeToString(SavedTrusts.serializer(), SavedTrusts(certificates = updated)))
        deniedOrigins.remove(trust.origin)
    }

    fun forget(baseUrl: String) = synchronized(lock) {
        val normalized = ComfyUIEndpoint.normalize(baseUrl)
        if (!normalized.startsWith("https://")) return@synchronized
        val origin = ComfyTlsTrust.originFor(normalized)
        deniedOrigins.add(origin)
        val previous = read()
        val updated = previous.certificates.filterNot { it.origin == origin }
        if (updated != previous.certificates) {
            // Persist the revoked state before reporting success; never resurrect an old .bak.
            AtomicTextFile.writeText(file, json.encodeToString(SavedTrusts.serializer(), SavedTrusts(certificates = updated)))
        }
    }

    private fun read(): SavedTrusts {
        if (!AtomicTextFile.exists(file)) return SavedTrusts()
        require(file.length() <= MAX_FILE_BYTES) { "证书信任记录超出限制，请检查本机数据。" }
        val saved = json.decodeFromString(SavedTrusts.serializer(), AtomicTextFile.readText(file))
        require(saved.version == 1 && saved.certificates.size <= MAX_CERTIFICATES &&
            saved.certificates.map { it.origin }.distinct().size == saved.certificates.size) { "证书信任记录无效。" }
        saved.certificates.forEach { validate(it, checkTime = false) }
        return saved
    }

    private fun validate(trust: ComfyCertificateTrust, checkTime: Boolean = true) {
        require(trust.origin.startsWith("https://") && ComfyTlsTrust.originFor(trust.origin) == trust.origin) { "只允许信任确定的 HTTPS 主机和端口。" }
        require(trust.certificateSha256.matches(Regex("[a-f0-9]{64}"))) { "证书 SHA-256 格式无效。" }
        val bytes = trust.certificateDerBase64.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("证书数据无效。")
        require(bytes.size in 1..16_384) { "证书数据超出限制。" }
        val input = ByteArrayInputStream(bytes)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        require(input.available() == 0 && certificate.encoded.contentEquals(bytes)) { "证书不是完整的单个 DER 证书。" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == trust.certificateSha256) { "证书与核对的指纹不一致。" }
        if (checkTime) certificate.checkValidity()
    }

    companion object {
        private const val MAX_CERTIFICATES = 32
        private const val MAX_FILE_BYTES = 1_048_576L
    }
}
