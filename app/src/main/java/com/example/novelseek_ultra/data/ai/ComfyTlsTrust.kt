package com.example.novelseek_ultra.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.EmptyCoroutineContext

/** Device-local, explicitly approved trust. Never obtain an approval from imported app state. */
@Serializable
data class ComfyCertificateTrust(
    val origin: String,
    val certificateSha256: String,
    val certificateDerBase64: String,
)

/** Observed, NOT authenticated certificate metadata; inspecting it is not approving its identity. */
data class ComfyCertificateInfo(
    val origin: String,
    val certificateSha256: String,
    val certificateDerBase64: String,
    val subject: String,
    val issuer: String,
    val notBeforeEpochMs: Long,
    val notAfterEpochMs: Long,
) {
    fun toTrust(): ComfyCertificateTrust = ComfyCertificateTrust(origin, certificateSha256, certificateDerBase64)
}

internal class ComfyCertificateTrustException(message: String, cause: Throwable? = null) :
    SSLException(message, cause)

/**
 * A private ComfyUI client can trust ONE explicitly approved HTTPS origin and leaf certificate.
 * This never modifies any global SSL defaults, text API clients, or Pollinations clients.
 * A user-approved leaf is an additional local trust anchor, not a hostname-verification bypass.
 */
object ComfyTlsTrust {
    fun originFor(baseUrl: String): String = originOf(ComfyUIEndpoint.parse(baseUrl).route("system_stats"))

    fun isTrustForOrigin(baseUrl: String, trust: ComfyCertificateTrust): Boolean = runCatching {
        val url = ComfyUIEndpoint.parse(baseUrl).route("system_stats")
        url.isHttps && trust.origin == originOf(url)
    }.getOrDefault(false)

    internal fun clientFor(
        baseUrl: String,
        trustedCertificate: ComfyCertificateTrust?,
        isTrustCurrent: () -> Boolean = { true },
    ): OkHttpClient {
        val base = ComfyUIEndpoint.parse(baseUrl).route("system_stats")
        val expectedOrigin = originOf(base)
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)

        // Guard the initial URL before connecting; network interceptors also guard retries/reuse.
        builder.addInterceptor { chain ->
            requireOrigin(chain.request().url, expectedOrigin)
            if (trustedCertificate != null && !isTrustCurrent()) throw revokedTrust()
            chain.proceed(chain.request())
        }
        if (trustedCertificate == null) {
            // No saved approval means ordinary system PKI and ordinary hostname verification.
            return builder.build()
        }
        if (!base.isHttps || trustedCertificate.origin != expectedOrigin) {
            throw ComfyCertificateTrustException("证书授权仅适用于原 HTTPS 主机和端口；请重新核对当前地址。(Certificate approval is restricted to its exact HTTPS origin.)")
        }
        val approved = decodeApprovedCertificate(trustedCertificate)
        verifyPinnedLeaf(approved, trustedCertificate)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("comfy-approved-leaf", approved)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val platformTrust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val pinnedTrust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not supported by ComfyUI trust")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!isTrustCurrent()) throw CertificateException("ComfyUI certificate approval was revoked")
                val leaf = chain?.firstOrNull() ?: throw CertificateException("No ComfyUI server certificate")
                try {
                    verifyPinnedLeaf(leaf, trustedCertificate)
                } catch (error: SSLException) {
                    throw CertificateException(error.message, error)
                }
                // Keep the platform's cryptographic chain checks; only this leaf is a trust anchor.
                platformTrust.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = platformTrust.acceptedIssuers
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinnedTrust), null) }
        builder.sslSocketFactory(context.socketFactory, pinnedTrust)
        // Intentionally DO NOT override hostnameVerifier: the approved cert must cover this host.
        builder.addNetworkInterceptor { chain ->
            requireOrigin(chain.request().url, expectedOrigin)
            if (!isTrustCurrent()) throw revokedTrust()
            val connection = chain.connection() ?: throw ComfyCertificateTrustException("未建立安全连接。(No secure connection.)")
            requireOrigin(connection.route().address.url, expectedOrigin)
            val tls = connection.socket() as? SSLSocket
                ?: throw ComfyCertificateTrustException("证书授权不允许 HTTP 明文连接。(Certificate approval never permits HTTP.)")
            val leaf = tls.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw ComfyCertificateTrustException("服务器未提供证书。(No server certificate.)")
            // A pooled socket can outlive certificate validity or UI revocation. Recheck before
            // every HTTP exchange, not only when the TLS connection was first established.
            verifyPinnedLeaf(leaf, trustedCertificate)
            if (!isTrustCurrent()) throw revokedTrust()
            chain.proceed(chain.request())
        }
        return builder.build()
    }

    /**
     * Observe only the TLS certificate. The trust manager ALWAYS rejects the handshake after
     * copying its leaf: no completed TLS session, HTTP request, credentials or prompt is sent.
     * Cancellation closes the socket even while a connect/handshake is blocked.
     */
    internal suspend fun inspect(baseUrl: String): ComfyCertificateInfo {
        val url = ComfyUIEndpoint.parse(baseUrl).route("system_stats")
        require(url.isHttps) { "证书检查仅支持 HTTPS，请先将地址改为 https:// 开头。(Certificate inspection requires HTTPS.)" }
        return withTimeoutOrNull(INSPECTION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val socketRef = AtomicReference<Socket?>()
                continuation.invokeOnCancellation { runCatching { socketRef.get()?.close() } }
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    val observed = AtomicReference<X509Certificate?>()
                    try {
                        val observer = object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                                throw CertificateException("Certificate inspection cannot authenticate clients")
                            }
                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                                observed.set(chain?.firstOrNull())
                                throw CertificateException("Inspection only: never authenticate or complete this handshake")
                            }
                            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        }
                        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(observer), null) }
                        val tcp = Socket()
                        socketRef.set(tcp)
                        if (!continuation.isActive) return@dispatch
                        // DNS resolution can remain blocked in the platform. The coroutine itself
                        // remains bounded; after cancellation no late DNS result starts a handshake.
                        val address = InetSocketAddress(url.host, url.port)
                        if (!continuation.isActive) return@dispatch
                        tcp.connect(address, INSPECTION_SOCKET_TIMEOUT_MS)
                        val tls = context.socketFactory.createSocket(tcp, url.host, url.port, true) as SSLSocket
                        socketRef.set(tls)
                        if (!continuation.isActive) return@dispatch
                        tls.soTimeout = INSPECTION_SOCKET_TIMEOUT_MS
                        tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                        val parameters = tls.sslParameters
                        if (':' !in url.host && !url.host.matches(Regex("[0-9.]+"))) {
                            parameters.serverNames = listOf(SNIHostName(url.host))
                        }
                        tls.sslParameters = parameters
                        try {
                            tls.startHandshake()
                            throw SSLException("Inspection must never complete a trusted TLS handshake")
                        } catch (error: IOException) {
                            val certificate = observed.get() ?: throw error
                            val info = ComfyCertificateInfo(
                                origin = originOf(url),
                                certificateSha256 = fingerprint(certificate),
                                certificateDerBase64 = certificate.encoded.toByteString().base64(),
                                subject = certificate.subjectX500Principal.name,
                                issuer = certificate.issuerX500Principal.name,
                                notBeforeEpochMs = certificate.notBefore.time,
                                notAfterEpochMs = certificate.notAfter.time,
                            )
                            if (continuation.isActive) continuation.resumeWith(Result.success(info))
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    } finally {
                        runCatching { socketRef.get()?.close() }
                    }
                }
            }
        } ?: throw SocketTimeoutException("证书检查超时，请检查手机网络和隧道是否在线。(Certificate inspection timed out.)")
    }

    internal fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeApprovedCertificate(trust: ComfyCertificateTrust): X509Certificate = try {
        if (!trust.certificateSha256.matches(Regex("[0-9a-fA-F]{64}")) || trust.certificateDerBase64.length > 32_768) {
            throw CertificateException("Invalid ComfyUI certificate approval")
        }
        val bytes = trust.certificateDerBase64.decodeBase64()?.toByteArray()
            ?: throw CertificateException("Invalid certificate base64")
        val input = ByteArrayInputStream(bytes)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        if (input.available() != 0 || !MessageDigest.isEqual(bytes, certificate.encoded)) {
            throw CertificateException("Approval must contain exactly one DER certificate")
        }
        certificate
    } catch (error: Exception) {
        throw ComfyCertificateTrustException("已保存的证书授权无效，请删除后重新核对。(Invalid saved certificate approval.)", error)
    }

    private fun verifyPinnedLeaf(leaf: X509Certificate, trust: ComfyCertificateTrust) {
        val actual = fingerprint(leaf).toByteArray(Charsets.US_ASCII)
        val expected = trust.certificateSha256.lowercase().toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(actual, expected)) {
            throw ComfyCertificateTrustException("服务器证书已改变，已阻止连接；请先在电脑端核对新指纹再重新授权。(Server certificate changed.)")
        }
        try {
            leaf.checkValidity()
        } catch (error: CertificateException) {
            throw ComfyCertificateTrustException("服务器证书已过期或尚未生效，请修复隧道证书并检查设备时间。(Certificate expired or not yet valid.)", error)
        }
    }

    private fun originOf(url: HttpUrl): String = HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port).build().toString().removeSuffix("/")

    private fun requireOrigin(url: HttpUrl, expectedOrigin: String) {
        if (originOf(url) != expectedOrigin || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ComfyCertificateTrustException("已阻止跨主机、端口或协议的请求；证书授权不会随跳转传播。(Request is outside the approved origin.)")
        }
    }

    private fun revokedTrust() = ComfyCertificateTrustException("证书授权已撤销，请重新测试或核对后授权。(Certificate approval was revoked.)")
    private const val INSPECTION_TIMEOUT_MS = 15_000L
    private const val INSPECTION_SOCKET_TIMEOUT_MS = 10_000
}
