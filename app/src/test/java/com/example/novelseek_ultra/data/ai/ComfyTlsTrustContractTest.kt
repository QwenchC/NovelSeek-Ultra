package com.example.novelseek_ultra.data.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * Real TLS + real OkHttp, entirely offline. Resource private keys were generated solely for these
 * mock loopback servers and never belong to the user's ComfyUI or public tunnel. The matching SAN
 * fixture expires in 2044; the expired fixture in 2020; a sibling cert covers only an invalid host.
 */
class ComfyTlsTrustContractTest {
    private val servers = mutableListOf<HttpsServer>()
    private val executors = mutableListOf<ExecutorService>()

    @After
    fun stopServers() {
        servers.forEach { it.stop(0) }
        executors.forEach { it.shutdownNow() }
        servers.clear()
        executors.clear()
    }

    @Test
    fun ordinaryConnectionRejectsSelfSignedWithoutSendingHttp() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val result = withTimeout(5_000) { AiService().diagnoseComfyUIConnection(address) }
        assertFalse(result.success)
        assertTrue(result.message.contains("HTTPS") || result.message.contains("证书"))
        assertEquals(0, requests.get())
    }

    @Test
    fun explicitlyPinnedMatchingSanCertificateAllowsComfyApiWithoutChangingGlobalTls() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val result = withTimeout(5_000) {
            AiService().diagnoseComfyUIConnection(address, trustFor(address))
        }
        assertTrue(result.message, result.success)
        assertEquals(1, requests.get())
        // A private approved client must not install a permissive SSLContext or system trust.
        val ordinaryFailure = runCatching {
            OkHttpClient().newCall(Request.Builder().url("$address/system_stats").build()).execute().close()
        }.exceptionOrNull()
        assertTrue(ordinaryFailure is SSLException)
        assertEquals(1, requests.get())
    }

    @Test
    fun exactCertificatePinStillRejectsHostnameMismatch() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer(WRONG_HOST_CERT) { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val result = withTimeout(5_000) {
            AiService().diagnoseComfyUIConnection(address, trustFor(address, WRONG_HOST_CERT))
        }
        assertFalse(result.success)
        assertEquals(0, requests.get())
    }

    @Test
    fun wrongFingerprintCannotAuthorizeEvenTheRightCertificateDer() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val badTrust = trustFor(address).copy(certificateSha256 = "00".repeat(32))
        val result = AiService().diagnoseComfyUIConnection(address, badTrust)
        assertFalse(result.success)
        assertEquals(0, requests.get())
    }

    @Test
    fun approvingDifferentValidCertificateDoesNotPermitPeerWithSamePrivateKey() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val result = withTimeout(5_000) {
            AiService().diagnoseComfyUIConnection(address, trustFor(address, WRONG_HOST_CERT))
        }
        assertFalse(result.success)
        assertEquals(0, requests.get())
    }

    @Test
    fun trustCannotMoveToAnotherHostPortOrPlainHttp() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val trust = trustFor(address)
        val otherPort = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        for (differentAddress in listOf(
            address.replace("127.0.0.1", "localhost"),
            otherPort,
            address.replace("https://", "http://"),
        )) {
            val result = withTimeout(5_000) {
                AiService().diagnoseComfyUIConnection(differentAddress, trust)
            }
            assertFalse(differentAddress, result.success)
            assertFalse(ComfyTlsTrust.isTrustForOrigin(differentAddress, trust))
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun approvalCannotSendRequestToAnotherOriginEvenViaDirectClientReuse() {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val otherAddress = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val client = ComfyTlsTrust.clientFor(address, trustFor(address))
        try {
            for (url in listOf(otherAddress, address.replace("127.0.0.1", "localhost"), address.replace("https://", "http://"))) {
                val failure = runCatching {
                    client.newCall(Request.Builder().url("$url/system_stats").build()).execute().close()
                }.exceptionOrNull()
                assertTrue(url, failure is SSLException)
            }
            assertEquals(0, requests.get())
        } finally { client.closeResources() }
    }

    @Test
    fun expiredCertificateRemainsRejectedEvenWithExactPinAndMatchingSan() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer(EXPIRED_CERT) { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val result = AiService().diagnoseComfyUIConnection(address, trustFor(address, EXPIRED_CERT))
        assertFalse(result.success)
        assertEquals(0, requests.get())
    }

    @Test
    fun malformedOrExtraCertificateDerCannotBecomeAValidApproval() {
        val address = "https://127.0.0.1:12345"
        val validTrust = trustFor(address)
        val rawDer = certificate().encoded
        for (badTrust in listOf(
            validTrust.copy(certificateSha256 = "not-a-sha256"),
            validTrust.copy(certificateDerBase64 = "not DER"),
            validTrust.copy(certificateDerBase64 = Base64.getEncoder().encodeToString(rawDer + byteArrayOf(1))),
            validTrust.copy(certificateDerBase64 = Base64.getEncoder().encodeToString(rawDer + rawDer)),
        )) {
            assertTrue(runCatching { ComfyTlsTrust.clientFor(address, badTrust) }.exceptionOrNull() is SSLException)
        }
    }

    @Test
    fun certificateInspectionReturnsUnauthenticatedMetadataButNeverSendsHttp() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val observed = withTimeout(5_000) { AiService().inspectComfyCertificate("$address/proxy/comfy") }
        val expected = certificate()
        assertEquals(ComfyTlsTrust.originFor(address), observed.origin)
        assertEquals(fingerprint(expected), observed.certificateSha256)
        assertEquals(Base64.getEncoder().encodeToString(expected.encoded), observed.certificateDerBase64)
        assertEquals(expected.subjectX500Principal.name, observed.subject)
        assertEquals(expected.issuerX500Principal.name, observed.issuer)
        assertEquals(expected.notBefore.time, observed.notBeforeEpochMs)
        assertEquals(expected.notAfter.time, observed.notAfterEpochMs)
        assertEquals(trustFor(address), observed.toTrust())
        assertEquals(0, requests.get())
        // Inspection does not silently persist approval or relax a subsequent default connection.
        assertFalse(AiService().diagnoseComfyUIConnection(address).success)
        assertEquals(0, requests.get())
    }

    @Test
    fun expiredInspectionCanDisplayDatesWithoutAuthorizingOrSendingHttp() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer(EXPIRED_CERT) { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val observed = withTimeout(5_000) { AiService().inspectComfyCertificate(address) }
        assertTrue(observed.notAfterEpochMs < System.currentTimeMillis())
        assertFalse(AiService().diagnoseComfyUIConnection(address, observed.toTrust()).success)
        assertEquals(0, requests.get())
    }

    @Test
    fun inspectionRejectsHttpBeforeConnecting() = runBlocking {
        val failure = runCatching { AiService().inspectComfyCertificate("http://127.0.0.1:12345") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun pinnedClientDoesNotFollowSameOriginRedirects() = runBlocking {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val address = startServer { exchange ->
            paths += exchange.requestURI.path
            if (exchange.requestURI.path == "/system_stats") {
                exchange.responseHeaders.add("Location", "/redirect-target")
                exchange.respond(302, "do not follow")
            } else exchange.respond(200, SYSTEM_STATS)
        }
        val result = AiService().diagnoseComfyUIConnection(address, trustFor(address))
        assertFalse(result.success)
        assertTrue(result.message.contains("302"))
        assertEquals(listOf("/system_stats"), paths)
    }

    @Test
    fun pinnedClientDoesNotFollowCrossOriginOrDowngradeRedirects() = runBlocking {
        val otherRequests = AtomicInteger()
        val otherAddress = startServer { otherRequests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        for (location in listOf("$otherAddress/system_stats", "${otherAddress.replace("https://", "http://")}/system_stats")) {
            val requests = AtomicInteger()
            val address = startServer { exchange ->
                requests.incrementAndGet()
                exchange.responseHeaders.add("Location", location)
                exchange.respond(307, "do not forward")
            }
            val result = AiService().diagnoseComfyUIConnection(address, trustFor(address))
            assertFalse(result.success)
            assertEquals(1, requests.get())
        }
        assertEquals(0, otherRequests.get())
    }

    @Test
    fun approvedOriginWorksThroughPromptHistoryAndBinaryDownloadWithProxyPrefix() = runBlocking {
        val routes = Collections.synchronizedList(mutableListOf<String>())
        val requestBodies = Collections.synchronizedList(mutableListOf<String>())
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x01, 0x02)
        val address = startServer { exchange ->
            routes += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/proxy/comfy/prompt" -> {
                    requestBodies += exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    exchange.respond(200, """{"prompt_id":"tls-job"}""")
                }
                "/proxy/comfy/history/tls-job" -> exchange.respond(200, HISTORY)
                "/proxy/comfy/view" -> { queries += exchange.requestURI.rawQuery; exchange.respond(200, png) }
                else -> exchange.respond(404, "wrong route")
            }
        }
        val image = withTimeout(5_000) {
            AiService().generateImageComfyUI("封面", 512, 512, "$address/proxy/comfy", trustedCertificate = trustFor(address))
        }
        assertArrayEquals(png, image)
        assertEquals(listOf("POST /proxy/comfy/prompt", "GET /proxy/comfy/history/tls-job", "GET /proxy/comfy/view"), routes)
        assertEquals(1, requestBodies.size)
        assertTrue(requestBodies.single().contains("\"prompt\""))
        assertTrue(queries.single().contains("filename=cover.png"))
        assertTrue(queries.single().contains("type=output"))
    }

    @Test
    fun cancellingPinnedDiagnosisPropagatesCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val address = startServer { exchange ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            runCatching { exchange.respond(200, SYSTEM_STATS) }
        }
        try {
            val diagnosis = async(Dispatchers.Default) { AiService().diagnoseComfyUIConnection(address, trustFor(address)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            diagnosis.cancel()
            val failure = withTimeout(5_000) { runCatching { diagnosis.await() }.exceptionOrNull() }
            assertTrue(failure is CancellationException)
        } finally { release.countDown() }
    }

    @Test
    fun cancellingInspectionClosesBlockedTlsSocket() = runBlocking {
        val clientHello = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val socketServer = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = newExecutor()
        executor.submit {
            socketServer.accept().use { peer ->
                peer.soTimeout = 5_000
                val input = peer.getInputStream()
                if (input.read() >= 0) clientHello.countDown()
                try { while (input.read() != -1) { /* Consume ClientHello, never respond. */ } }
                catch (error: java.net.SocketException) { /* cancellation may reset rather than FIN */ }
                peerClosed.countDown()
            }
        }
        try {
            val inspection = async(Dispatchers.Default) { AiService().inspectComfyCertificate("https://127.0.0.1:${socketServer.localPort}") }
            assertTrue(clientHello.await(5, TimeUnit.SECONDS))
            inspection.cancel()
            val failure = withTimeout(5_000) { runCatching { inspection.await() }.exceptionOrNull() }
            assertTrue(failure is CancellationException)
            assertTrue("Cancellation must close the blocked TLS socket", peerClosed.await(5, TimeUnit.SECONDS))
        } finally { socketServer.close() }
    }

    @Test
    fun revocationGuardBlocksPreviouslyPooledConnectionBeforeAnotherHttpExchange() {
        val requests = AtomicInteger()
        val current = AtomicBoolean(true)
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val client = ComfyTlsTrust.clientFor(address, trustFor(address)) { current.get() }
        try {
            client.newCall(Request.Builder().url("$address/system_stats").build()).execute().use {
                assertEquals(200, it.code)
                it.body?.string()
            }
            assertEquals(1, requests.get())
            current.set(false)
            val failure = runCatching {
                client.newCall(Request.Builder().url("$address/system_stats").build()).execute().close()
            }.exceptionOrNull()
            assertTrue(failure is SSLException)
            assertEquals(1, requests.get())
        } finally { client.closeResources() }
    }

    @Test
    fun removingApprovalDoesNotReuseTheSameServicesPreviouslyPinnedSocket() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val service = AiService()
        assertTrue(service.diagnoseComfyUIConnection(address, trustFor(address)).success)
        service.invalidateComfyCertificateTrust()
        assertFalse(service.diagnoseComfyUIConnection(address).success)
        assertEquals(1, requests.get())
    }

    @Test
    fun staleConsentSnapshotCannotCreateANewClientAfterRevocation() = runBlocking {
        val requests = AtomicInteger()
        val address = startServer { requests.incrementAndGet(); it.respond(200, SYSTEM_STATS) }
        val service = AiService()
        val previousRevision = service.currentComfyTrustRevision()
        val previousApproval = trustFor(address)
        service.invalidateComfyCertificateTrust()
        val result = service.diagnoseComfyUIConnection(address, previousApproval, previousRevision)
        assertFalse(result.success)
        assertEquals(0, requests.get())
    }

    @Test
    fun revocationDuringGenerationCannotProceedToImageDownload() = runBlocking {
        val historyStarted = CountDownLatch(1)
        val releaseHistory = CountDownLatch(1)
        val imageRequests = AtomicInteger()
        val service = AiService()
        val address = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/prompt" -> exchange.respond(200, """{"prompt_id":"tls-job"}""")
                "/history/tls-job" -> {
                    historyStarted.countDown()
                    releaseHistory.await(10, TimeUnit.SECONDS)
                    runCatching { exchange.respond(200, HISTORY) }
                }
                "/view" -> { imageRequests.incrementAndGet(); exchange.respond(200, byteArrayOf(1)) }
                else -> exchange.respond(404, "wrong route")
            }
        }
        try {
            val generation = async(Dispatchers.Default) {
                runCatching {
                    service.generateImageComfyUI("封面", 512, 512, address, trustedCertificate = trustFor(address))
                }
            }
            assertTrue(historyStarted.await(5, TimeUnit.SECONDS))
            service.invalidateComfyCertificateTrust()
            releaseHistory.countDown()
            val failure = withTimeout(5_000) { generation.await().exceptionOrNull() }
            assertTrue("Revocation must fail generation rather than use an old connection", failure != null)
            assertEquals(0, imageRequests.get())
        } finally { releaseHistory.countDown() }
    }

    @Test
    fun invalidationCancelsAnInFlightApprovedRequestInsteadOfReturningSuccess() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = AiService()
        val address = startServer { exchange ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            runCatching { exchange.respond(200, SYSTEM_STATS) }
        }
        try {
            val diagnosis = async(Dispatchers.Default) { service.diagnoseComfyUIConnection(address, trustFor(address)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            service.invalidateComfyCertificateTrust()
            release.countDown()
            val outcome = withTimeout(5_000) { runCatching { diagnosis.await() } }
            assertFalse("A revoked in-flight trust must never report connection success", outcome.getOrNull()?.success == true)
            assertTrue(outcome.isFailure || outcome.getOrNull()?.success == false)
        } finally { release.countDown() }
    }

    @Test
    fun originIgnoresProxyPathButNeverHostPortOrScheme() {
        val trust = trustFor("https://localhost:35961")
        assertTrue(ComfyTlsTrust.isTrustForOrigin("https://localhost:35961/proxy/comfy", trust))
        assertEquals("https://localhost:35961", ComfyTlsTrust.originFor("https://LOCALHOST:35961/proxy/comfy"))
        assertFalse(ComfyTlsTrust.isTrustForOrigin("http://localhost:35961", trust))
        assertFalse(ComfyTlsTrust.isTrustForOrigin("https://localhost:35962", trust))
        assertFalse(ComfyTlsTrust.isTrustForOrigin("https://127.0.0.1:35961", trust))
    }

    private fun startServer(certResource: String = VALID_CERT, handler: (HttpExchange) -> Unit): String {
        val certificate = certificate(certResource)
        val keyPem = resource("mock-private-key.pem").toString(StandardCharsets.US_ASCII)
        val keyDer = Base64.getMimeDecoder().decode(keyPem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----"))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyDer))
        val password = "offline-fixture-only".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("mock-comfy", privateKey, password, arrayOf(certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ssl)
            executor = newExecutor()
            createContext("/", handler)
            start()
        }
        servers += server
        return "https://127.0.0.1:${server.address.port}"
    }

    private fun newExecutor(): ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "offline-comfy-tls-test").apply { isDaemon = true }
    }.also { executors += it }

    private fun trustFor(address: String, certResource: String = VALID_CERT): ComfyCertificateTrust {
        val certificate = certificate(certResource)
        return ComfyCertificateTrust(
            ComfyTlsTrust.originFor(address),
            fingerprint(certificate),
            Base64.getEncoder().encodeToString(certificate.encoded),
        )
    }

    private fun certificate(resourceName: String = VALID_CERT): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(resource(resourceName).inputStream()) as X509Certificate

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/comfy-tls/$name")!!.use { it.readBytes() }

    private fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun HttpExchange.respond(code: Int, body: String) = respond(code, body.toByteArray(StandardCharsets.UTF_8))

    private fun HttpExchange.respond(code: Int, bytes: ByteArray) {
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun OkHttpClient.closeResources() {
        dispatcher.cancelAll()
        connectionPool.evictAll()
        dispatcher.executorService.shutdownNow()
    }

    companion object {
        private const val VALID_CERT = "valid-tunnel-certificate.pem"
        private const val EXPIRED_CERT = "expired-tunnel-certificate.pem"
        private const val WRONG_HOST_CERT = "wrong-host-tunnel-certificate.pem"
        private const val SYSTEM_STATS = """{"system":{"os":"win32","python_version":"3.12","pytorch_version":"2.8"},"devices":[]}"""
        private const val HISTORY = """{"tls-job":{"status":{"completed":true,"status_str":"success"},"outputs":{"9":{"images":[{"filename":"cover.png","subfolder":"","type":"output"}]}}}}"""
    }
}
