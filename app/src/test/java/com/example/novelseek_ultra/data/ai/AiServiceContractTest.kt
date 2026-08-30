package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the real OkHttp/OpenAI-compatible boundary against a loopback server.
 * No external network, provider account, or API key is used by these tests.
 */
class AiServiceContractTest {

    private var server: HttpServer? = null

    @After
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun chatSendsCompatibleRequestAndParsesCompletion() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(
                200,
                """{"choices":[{"message":{"content":"生成成功"}}]}""",
            )
        }

        val reply = AiService().chat(
            config = config(apiUrl),
            messages = listOf(
                ChatMessage("system", "你是小说编辑"),
                ChatMessage("user", "写第一章"),
            ),
        )

        assertEquals("生成成功", reply)
        val request = captured.get()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer local-test-key", request.authorization)

        val payload = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("local-model", payload.getValue("model").jsonPrimitive.content)
        assertEquals(0.25, payload.getValue("temperature").jsonPrimitive.double, 0.0)
        assertEquals(8_000, payload.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertFalse(payload.getValue("stream").jsonPrimitive.boolean)
        val messages = payload.getValue("messages").jsonArray
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("你是小说编辑", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("写第一章", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun oversizedRequestFailsBeforeAnyHttpCall() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(200, """{"choices":[{"message":{"content":"不应到达"}}]}""")
        }

        val failure = runCatching {
            AiService().chat(
                config(apiUrl).copy(contextWindowTokens = 4_096, maxOutputTokens = 512),
                listOf(ChatMessage("user", "超长必需正文".repeat(10_000))),
            )
        }.exceptionOrNull()

        assertTrue(failure is PromptRequestBudgeter.BudgetExceededException)
        assertNull(captured.get())
    }

    @Test
    fun deepSeekV4NonThinkingModeIsExplicitInPayload() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(200, """{"choices":[{"message":{"content":"完成"}}]}""")
        }

        AiService().chat(
            config(apiUrl).copy(
                provider = "deepseek",
                model = "deepseek-v4-flash",
                thinkingMode = com.example.novelseek_ultra.data.model.TextModelThinkingModes.DISABLED,
            ),
            listOf(ChatMessage("user", "开始")),
        )

        val payload = Json.parseToJsonElement(captured.get().body).jsonObject
        assertEquals(
            "disabled",
            payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertTrue("temperature" in payload)
    }

    @Test
    fun deepSeekV4OutputLimitIsCappedAtRequestBoundary() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(200, """{"choices":[{"message":{"content":"完成"}}]}""")
        }

        AiService().chat(
            config(apiUrl).copy(
                provider = "deepseek",
                contextWindowTokens = 1_000_000,
                maxOutputTokens = 900_000,
            ),
            listOf(ChatMessage("user", "开始")),
        )

        val payload = Json.parseToJsonElement(captured.get().body).jsonObject
        assertEquals(
            TextModelRequestPolicy.DEEPSEEK_V4_MAX_OUTPUT_TOKENS,
            payload.getValue("max_tokens").jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun invalidTemperatureFailsBeforeAnyHttpCall() = runBlocking {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 2.01).forEach { invalid ->
            val captured = AtomicReference<CapturedRequest>()
            val apiUrl = startServer { exchange ->
                captured.set(exchange.capture())
                exchange.respondJson(200, """{"choices":[{"message":{"content":"不应到达"}}]}""")
            }

            val failure = runCatching {
                AiService().chat(
                    config(apiUrl).copy(temperature = invalid),
                    listOf(ChatMessage("user", "开始")),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertNull(captured.get())
            server?.stop(0)
            server = null
        }
    }

    @Test
    fun temperatureBoundaryValuesAreAccepted() = runBlocking {
        listOf(0.0, 2.0).forEach { boundary ->
            val captured = AtomicReference<CapturedRequest>()
            val apiUrl = startServer { exchange ->
                captured.set(exchange.capture())
                exchange.respondJson(200, """{"choices":[{"message":{"content":"完成"}}]}""")
            }

            AiService().chat(
                config(apiUrl).copy(temperature = boundary),
                listOf(ChatMessage("user", "开始")),
            )

            val payload = Json.parseToJsonElement(captured.get().body).jsonObject
            assertEquals(boundary, payload.getValue("temperature").jsonPrimitive.double, 0.0)
            server?.stop(0)
            server = null
        }
    }

    @Test
    fun chatReportsPerRequestUsageAndDerivesOpenAiCacheMisses() = runBlocking {
        val observed = AtomicReference<StreamUsage>()
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondJson(
                200,
                """
                    {
                      "choices":[{"message":{"content":"完成"},"finish_reason":"stop"}],
                      "usage":{
                        "prompt_tokens":100,
                        "completion_tokens":20,
                        "prompt_tokens_details":{"cached_tokens":75}
                      }
                    }
                """.trimIndent(),
            )
        }

        val service = AiService()
        val reply = service.chat(
            config(apiUrl),
            listOf(ChatMessage("user", "开始")),
            onUsage = observed::set,
        )

        assertEquals("完成", reply)
        assertEquals(StreamUsage(100, 20, 120, 75, 25), observed.get())
        assertEquals(75, service.usageStats.value.cacheHitTokens)
        assertEquals(25, service.usageStats.value.cacheMissTokens)
    }

    @Test
    fun chatRejectsNonSuccessfulFinishReasons() = runBlocking {
        for (reason in listOf("length", "content_filter", "insufficient_system_resource")) {
            val apiUrl = startServer { exchange ->
                exchange.capture()
                exchange.respondJson(
                    200,
                    """{"choices":[{"message":{"content":"不完整"},"finish_reason":"$reason"}]}""",
                )
            }

            val failure = runCatching {
                AiService().chat(config(apiUrl), listOf(ChatMessage("user", "开始")))
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("finish_reason=$reason"))
            stopServer()
        }
    }

    @Test
    fun chatHttpErrorDoesNotExposeProviderBody() = runBlocking {
        val sentinel = "PROVIDER-ERROR-SENTINEL secret-query=abc"
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondJson(429, """{"error":{"message":"$sentinel"}}""")
        }

        val failure = runCatching {
            AiService().chat(config(apiUrl), listOf(ChatMessage("user", "开始")))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("HTTP 429"))
        assertFalse(failure?.message.orEmpty().contains(sentinel))
        assertFalse(failure?.message.orEmpty().contains("local-test-key"))
    }

    @Test
    fun chatDerivesCacheHitsFromDeepSeekMissesAndDropsInconsistentCacheCounts() = runBlocking {
        var body = """
            {
              "choices":[{"message":{"content":"完成"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_miss_tokens":40}
            }
        """.trimIndent()
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondJson(200, body)
        }
        val service = AiService()
        val observed = AtomicReference<StreamUsage>()

        service.chat(config(apiUrl), listOf(ChatMessage("user", "开始")), observed::set)
        assertEquals(StreamUsage(100, 20, 120, 60, 40), observed.get())

        body = """
            {
              "choices":[{"message":{"content":"完成"},"finish_reason":"stop"}],
              "usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_miss_tokens":101}
            }
        """.trimIndent()
        service.chat(config(apiUrl), listOf(ChatMessage("user", "继续")), observed::set)
        assertEquals(StreamUsage(100, 20, 120, null, null), observed.get())
        assertEquals(1, service.usageStats.value.cacheObservedRequests)
        assertEquals(60, service.usageStats.value.cacheHitTokens)
        assertEquals(40, service.usageStats.value.cacheMissTokens)
    }

    @Test
    fun keylessLoopbackRequestOmitsAuthorizationHeader() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }

        val reply = AiService().chat(
            config = config(apiUrl).copy(apiKey = ""),
            messages = listOf(ChatMessage("user", "ping")),
        )

        assertEquals("ok", reply)
        assertEquals(null, captured.get().authorization)
    }

    @Test
    fun streamChatParsesSseDeltasAndDoneMarker() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondSse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"第一\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"章\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            )
        }

        val events = withTimeout(5_000) {
            AiService().streamChat(
                config = config(apiUrl),
                messages = listOf(ChatMessage("user", "开始")),
            ).toList()
        }

        assertEquals(
            listOf(
                AiService.StreamEvent.Delta("第一"),
                AiService.StreamEvent.Delta("章"),
                AiService.StreamEvent.Done,
            ),
            events,
        )
        val payload = Json.parseToJsonElement(captured.get().body).jsonObject
        assertTrue(payload.getValue("stream").jsonPrimitive.boolean)
        assertFalse("stream_options" in payload)
    }

    @Test
    fun streamChatAcceptsExplicitStopBeforeCleanEof() = runBlocking {
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondSse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"完整正文\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            )
        }

        val events = withTimeout(5_000) {
            AiService().streamChat(config(apiUrl), listOf(ChatMessage("user", "开始"))).toList()
        }

        assertEquals(
            listOf(AiService.StreamEvent.Delta("完整正文"), AiService.StreamEvent.Done),
            events,
        )
    }

    @Test
    fun streamChatRejectsEofWithoutTerminalMarker() = runBlocking {
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondSse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"半章\"}}]}\n\n",
            )
        }
        val events = mutableListOf<AiService.StreamEvent>()

        val failure = withTimeout(5_000) {
            runCatching {
                AiService().streamChat(config(apiUrl), listOf(ChatMessage("user", "开始")))
                    .collect { events += it }
            }.exceptionOrNull()
        }

        assertTrue(failure is IOException)
        assertEquals(AiService.StreamEvent.Delta("半章"), events.first())
        assertTrue(events.last() is AiService.StreamEvent.Error)
        assertFalse(events.contains(AiService.StreamEvent.Done))
    }

    @Test
    fun streamChatRejectsProviderErrorFrame() = runBlocking {
        val sentinel = "PROVIDER-ERROR-SENTINEL insufficient balance"
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondSse(
                "data: {\"error\":{\"message\":\"$sentinel\",\"type\":\"insufficient_quota\"}}\n\n",
            )
        }
        val events = mutableListOf<AiService.StreamEvent>()

        val failure = withTimeout(5_000) {
            runCatching {
                AiService().streamChat(config(apiUrl), listOf(ChatMessage("user", "开始")))
                    .collect { events += it }
            }.exceptionOrNull()
        }

        assertTrue(failure is IOException)
        assertEquals(
            "模型服务额度不足",
            (events.last() as AiService.StreamEvent.Error).message,
        )
        assertEquals("模型服务额度不足", failure?.message)
        assertFalse(failure?.message.orEmpty().contains(sentinel))
        assertFalse(events.contains(AiService.StreamEvent.Done))
    }

    @Test
    fun streamHttpErrorKeepsStatusButDoesNotExposeProviderBody() = runBlocking {
        val sentinel = "SSE-PROVIDER-ERROR-SENTINEL secret-query=abc"
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondJson(429, """{"error":{"message":"$sentinel"}}""")
        }
        val events = mutableListOf<AiService.StreamEvent>()

        val failure = withTimeout(5_000) {
            runCatching {
                AiService().streamChat(config(apiUrl), listOf(ChatMessage("user", "开始")))
                    .collect { events += it }
            }.exceptionOrNull()
        }

        assertTrue(failure is IOException)
        val safeMessage = (events.last() as AiService.StreamEvent.Error).message
        assertTrue(safeMessage.contains("HTTP 429"))
        assertFalse(safeMessage.contains(sentinel))
        assertFalse(failure?.message.orEmpty().contains(sentinel))
    }

    @Test
    fun streamChatRejectsTruncatedOrFilteredFinishReasons() = runBlocking {
        for (reason in listOf("length", "content_filter")) {
            val apiUrl = startServer { exchange ->
                exchange.capture()
                exchange.respondSse(
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n",
                )
            }
            val events = mutableListOf<AiService.StreamEvent>()

            val failure = withTimeout(5_000) {
                runCatching {
                    AiService().streamChat(config(apiUrl), listOf(ChatMessage("user", "开始")))
                        .collect { events += it }
                }.exceptionOrNull()
            }

            assertTrue(failure is IOException)
            assertTrue((events.last() as AiService.StreamEvent.Error).message.contains("finish_reason=$reason"))
            assertFalse(events.contains(AiService.StreamEvent.Done))
            stopServer()
        }
    }

    @Test
    fun deepSeekStreamingRequestsUsageAndRecordsCacheHits() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val observedUsage = AtomicReference<StreamUsage>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondSse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}}\n\n" +
                    "data: [DONE]\n\n",
            )
        }
        val service = AiService()

        val events = withTimeout(5_000) {
            service.streamChat(
                config = config(apiUrl).copy(provider = "deepseek"),
                messages = listOf(ChatMessage("user", "开始")),
                onUsage = observedUsage::set,
            ).toList()
        }

        assertEquals(listOf(AiService.StreamEvent.Delta("正文"), AiService.StreamEvent.Done), events)
        assertEquals(StreamUsage(100, 20, 120, 80, 20), observedUsage.get())
        assertEquals(80, service.usageStats.value.cacheHitTokens)
        assertEquals(20, service.usageStats.value.cacheMissTokens)
        val payload = Json.parseToJsonElement(captured.get().body).jsonObject
        assertTrue(
            payload.getValue("stream_options").jsonObject
                .getValue("include_usage").jsonPrimitive.boolean,
        )
    }

    @Test
    fun openAiStreamingRequestsUsageAndDerivesNestedCacheMisses() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val observedUsage = AtomicReference<StreamUsage>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondSse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":90,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":60}}}\n\n" +
                    "data: [DONE]\n\n",
            )
        }

        val events = AiService().streamChat(
            config = config(apiUrl).copy(provider = "openai"),
            messages = listOf(ChatMessage("user", "开始")),
            onUsage = observedUsage::set,
        ).toList()

        assertEquals(listOf(AiService.StreamEvent.Delta("正文"), AiService.StreamEvent.Done), events)
        assertEquals(StreamUsage(90, 10, 100, 60, 30), observedUsage.get())
        val payload = Json.parseToJsonElement(captured.get().body).jsonObject
        assertTrue(
            payload.getValue("stream_options").jsonObject
                .getValue("include_usage").jsonPrimitive.boolean,
        )
    }

    @Test
    fun testConnectionUsesMinimumOutputAllowance() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val apiUrl = startServer { exchange ->
            captured.set(exchange.capture())
            exchange.respondJson(200, """{"choices":[{"message":{"content":"pong"}}]}""")
        }

        assertTrue(
            AiService().testConnection(
                config(apiUrl).copy(
                    provider = " deepseek ",
                    thinkingMode = com.example.novelseek_ultra.data.model.TextModelThinkingModes.ENABLED,
                ),
            ),
        )
        val payload = Json.parseToJsonElement(captured.get().body).jsonObject
        assertEquals(256, payload.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertEquals(0.0, payload.getValue("temperature").jsonPrimitive.double, 0.0)
        assertEquals(
            "disabled",
            payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun testConnectionReturnsFalseForProviderError() = runBlocking {
        val apiUrl = startServer { exchange ->
            exchange.capture()
            exchange.respondJson(401, """{"error":{"message":"invalid key"}}""")
        }

        assertFalse(AiService().testConnection(config(apiUrl)))
    }

    @Test
    fun directDeepSeekDetectionUsesTrimmedProviderOrOfficialHostButNotModelAlias() {
        assertTrue(isDirectDeepSeek(config("http://127.0.0.1:1/v1").copy(provider = " DeepSeek ")))
        assertTrue(
            isDirectDeepSeek(
                config("https://api.deepseek.com/v1").copy(provider = "custom"),
            ),
        )
        assertFalse(
            isDirectDeepSeek(
                config("https://proxy.example/v1").copy(
                    provider = "custom",
                    model = "deepseek-v4-flash",
                ),
            ),
        )
    }

    private fun config(apiUrl: String) = TextModelConfig(
        provider = "local-test",
        apiKey = "local-test-key",
        apiUrl = apiUrl,
        model = "local-model",
        temperature = 0.25,
    )

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/v1/chat/completions", handler)
        instance.start()
        server = instance
        return "http://127.0.0.1:${instance.address.port}/v1"
    }

    private fun HttpExchange.capture(): CapturedRequest {
        val requestBody = requestBody.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
        return CapturedRequest(
            method = requestMethod,
            path = requestURI.path,
            authorization = requestHeaders.getFirst("Authorization"),
            body = requestBody,
        )
    }

    private fun HttpExchange.respondJson(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        respond(status, body)
    }

    private fun HttpExchange.respondSse(body: String) {
        responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        respond(200, body)
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )
}
