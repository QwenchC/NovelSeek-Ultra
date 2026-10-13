package com.example.novelseek_ultra.data.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real OkHttp calls use only a deterministic loopback server, never a real public tunnel. */
class ComfyUIConnectionContractTest {
    private var server: HttpServer? = null

    @After
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun bareTcpAddressAndProxyPrefixReachActualSystemStats() = runBlocking {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val address = startServer { exchange ->
            paths += exchange.requestURI.path
            exchange.respond(200, SYSTEM_STATS)
        }
        val result = withTimeout(5_000) {
            AiService().diagnoseComfyUIConnection("${address.removePrefix("http://")}/proxy/comfy")
        }
        assertTrue(result.success)
        assertEquals("$address/proxy/comfy", result.normalizedUrl)
        assertEquals(listOf("/proxy/comfy/system_stats"), paths)
    }

    @Test
    fun htmlAndGenericJson200AreNotReportedAsComfyConnections() = runBlocking {
        for (body in listOf("<html>tunnel login</html>", """{"message":"ok"}""")) {
            val address = startServer { it.respond(200, body) }
            val service = AiService()
            val result = withTimeout(5_000) { service.diagnoseComfyUIConnection(address) }
            assertFalse(result.success)
            assertTrue(result.message.contains("不是 ComfyUI"))
            assertFalse(service.testComfyUIConnection(address))
            stopServer()
        }
    }

    @Test
    fun tunnelAuthRouteAndGatewayHttpErrorsRemainDistinguishableAndDoNotEchoBodies() = runBlocking {
        for (code in listOf(401, 403, 404, 502)) {
            val address = startServer { it.respond(code, "secret-gateway-login-token") }
            val result = withTimeout(5_000) { AiService().diagnoseComfyUIConnection(address) }
            assertFalse(result.success)
            assertTrue(result.message.contains("HTTP $code"))
            assertFalse(result.message.contains("secret-gateway-login-token"))
            stopServer()
        }
    }

    @Test
    fun invalidCredentialsAreRejectedBeforeAnyNetworkCall() = runBlocking {
        val result = AiService().diagnoseComfyUIConnection("https://user:secret@tunnel.example/")
        assertFalse(result.success)
        assertEquals(null, result.normalizedUrl)
        assertTrue(result.message.contains("凭据"))
        assertFalse(result.message.contains("secret"))
    }

    @Test
    fun redirectedGatewayLoginIsNotMistakenForComfyApi() = runBlocking {
        val address = startServer { exchange ->
            if (exchange.requestURI.path == "/system_stats") {
                exchange.responseHeaders.add("Location", "/login")
                exchange.respond(302, "login required")
            } else {
                exchange.respond(200, "<html>Login required</html>")
            }
        }
        val result = withTimeout(5_000) { AiService().diagnoseComfyUIConnection(address) }
        assertFalse(result.success)
        assertTrue(result.message.contains("登录页"))
    }

    @Test
    fun unexpectedGatewayErrorJsonFailsWithoutCrashingHttpCallback() = runBlocking {
        val address = startServer { it.respond(400, """{"error":"gateway rejected request","node_errors":"not an object"}""") }
        val failure = withTimeout(5_000) {
            runCatching { AiService().generateImageComfyUI("封面", 512, 512, address) }.exceptionOrNull()
        }
        assertTrue(failure is IOException)
    }

    @Test
    fun automaticHttps501SuggestsHttpsWithoutAutomaticallyRequestingIt() = runBlocking {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val address = startServer { exchange ->
            requests.incrementAndGet()
            exchange.respond(501, "<html><title>501 Not Implemented</title>您已为本隧道启用 <b>自动 HTTPS</b><script>secret-gateway-script</script></html>")
        }
        val result = withTimeout(5_000) { AiService().diagnoseComfyUIConnection("$address/proxy/comfy") }
        assertFalse(result.success)
        assertEquals("$address/proxy/comfy", result.normalizedUrl)
        assertTrue(result.message.contains("自动 HTTPS"))
        assertTrue(result.message.contains("${address.replace("http://", "https://")}/proxy/comfy"))
        assertFalse(result.message.contains("secret-gateway-script"))
        assertEquals(1, requests.get())
    }

    @Test
    fun generic501DoesNotSuggestAProtocolChange() = runBlocking {
        val address = startServer { it.respond(501, "<html>Not implemented</html>") }
        val result = withTimeout(5_000) { AiService().diagnoseComfyUIConnection(address) }
        assertFalse(result.success)
        assertTrue(result.message.contains("HTTP 501"))
        assertFalse(result.message.contains("自动 HTTPS"))
    }

    @Test
    fun generationPreservesProxyPrefixAcrossSubmitHistoryAndImageDownload() = runBlocking {
        val routes = Collections.synchronizedList(mutableListOf<String>())
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x01, 0x02)
        val address = startServer { exchange ->
            routes += "${exchange.requestMethod} ${exchange.requestURI.path}"
            when (exchange.requestURI.path) {
                "/proxy/comfy/prompt" -> {
                    val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(body.contains("\"prompt\""))
                    exchange.respond(200, """{"prompt_id":"task-1"}""")
                }
                "/proxy/comfy/history/task-1" -> exchange.respond(200, """{"task-1":{"status":{"completed":true,"status_str":"success"},"outputs":{"9":{"images":[{"filename":"封面 a&b?.png","subfolder":"卷一/章节 #1","type":"output"}]}}}}""")
                "/proxy/comfy/view" -> {
                    val url = "http://unused${exchange.requestURI.toASCIIString()}".toHttpUrl()
                    assertEquals("封面 a&b?.png", url.queryParameter("filename"))
                    assertEquals("卷一/章节 #1", url.queryParameter("subfolder"))
                    assertEquals("output", url.queryParameter("type"))
                    exchange.respond(200, pngBytes)
                }
                else -> exchange.respond(404, "wrong proxy route")
            }
        }
        val result = withTimeout(5_000) {
            AiService().generateImageComfyUI("封面", 512, 512, "$address/proxy/comfy")
        }
        assertArrayEquals(pngBytes, result)
        assertEquals(listOf("POST /proxy/comfy/prompt", "GET /proxy/comfy/history/task-1", "GET /proxy/comfy/view"), routes)
    }

    @Test
    fun cancellingConnectionDiagnosisPropagatesCancellationInsteadOfFalseResult() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val address = startServer { exchange ->
            requestStarted.countDown()
            releaseRequest.await(10, TimeUnit.SECONDS)
            runCatching { exchange.respond(200, SYSTEM_STATS) }
        }
        try {
            val diagnosis = async(Dispatchers.Default) { AiService().diagnoseComfyUIConnection(address) }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            diagnosis.cancel()
            val failure = withTimeout(5_000) { runCatching { diagnosis.await() }.exceptionOrNull() }
            assertTrue(failure is CancellationException)
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test
    fun cancellingImageDownloadDoesNotStartAnotherAttempt() = runBlocking {
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val downloadRequests = java.util.concurrent.atomic.AtomicInteger()
        val address = startServer { exchange ->
            when (exchange.requestURI.path) {
                "/prompt" -> exchange.respond(200, """{"prompt_id":"task-1"}""")
                "/history/task-1" -> exchange.respond(200, """{"task-1":{"status":{"completed":true,"status_str":"success"},"outputs":{"9":{"images":[{"filename":"image.png","subfolder":"","type":"output"}]}}}}""")
                "/view" -> {
                    downloadRequests.incrementAndGet()
                    downloadStarted.countDown()
                    releaseDownload.await(10, TimeUnit.SECONDS)
                    runCatching { exchange.respond(200, byteArrayOf(1, 2)) }
                }
                else -> exchange.respond(404, "wrong route")
            }
        }
        try {
            val generation = async(Dispatchers.Default) { AiService().generateImageComfyUI("封面", 512, 512, address) }
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS))
            generation.cancel()
            val failure = withTimeout(5_000) { runCatching { generation.await() }.exceptionOrNull() }
            assertTrue(failure is CancellationException)
            assertEquals(1, downloadRequests.get())
        } finally {
            releaseDownload.countDown()
        }
    }

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/", handler)
        instance.start()
        server = instance
        return "http://127.0.0.1:${instance.address.port}"
    }

    private fun HttpExchange.respond(status: Int, body: String) = respond(status, body.toByteArray(StandardCharsets.UTF_8))

    private fun HttpExchange.respond(status: Int, body: ByteArray) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
        close()
    }

    companion object {
        private const val SYSTEM_STATS = """{"system":{"os":"win32","python_version":"3.12","pytorch_version":"2.8"},"devices":[]}"""
    }
}
