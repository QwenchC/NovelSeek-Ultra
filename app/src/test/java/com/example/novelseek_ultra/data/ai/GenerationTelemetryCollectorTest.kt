package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.GenerationCallTelemetry
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.TextModelConfig
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTelemetryCollectorTest {
    @Test
    fun `stepwise calls retain coverage while blueprint does not become first visible prose`() {
        var now = 0L
        val collector = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.stepwise.v1",
            nanoTime = { now },
        )

        now = millis(10)
        val blueprint = collector.beginRequest(
            "chapter_blueprint",
            listOf(ChatMessage("system", "秘密系统提示"), ChatMessage("user", "秘密蓝图提示")),
            visibleOutput = false,
        )
        now = millis(40)
        blueprint.onUsage(StreamUsage(promptTokens = 100, completionTokens = 20))
        now = millis(50)
        blueprint.complete()

        now = millis(60)
        val segment = collector.beginRequest(
            "chapter_segment_1",
            listOf(ChatMessage("system", "段落系统提示"), ChatMessage("user", "段落正文提示")),
            visibleOutput = true,
        )
        now = millis(70)
        segment.onContent("   ")
        segment.onUsage(
            StreamUsage(
                promptTokens = 200,
                completionTokens = 50,
                totalTokens = 250,
                cacheHitTokens = 120,
                cacheMissTokens = 80,
            ),
        )
        // A later usage frame replaces rather than double-counts the earlier frame.
        segment.onUsage(
            StreamUsage(
                promptTokens = 210,
                completionTokens = 55,
                totalTokens = 265,
                cacheHitTokens = 140,
                cacheMissTokens = 70,
            ),
        )
        now = millis(90)
        segment.onContent("用户可见正文")
        now = millis(120)
        segment.complete()

        now = millis(150)
        val telemetry = collector.finishCompleted()

        assertEquals(2, telemetry.requestCount)
        assertEquals(90L, telemetry.firstOutputMillis)
        assertEquals(150L, telemetry.totalMillis)
        assertEquals(310L, telemetry.promptTokens())
        assertEquals(75L, telemetry.completionTokens())
        assertEquals(265L, telemetry.totalTokens())
        assertEquals(140L, telemetry.cacheHitTokens())
        assertEquals(70L, telemetry.cacheMissTokens())
        assertEquals(2, telemetry.promptTokensReportedRequests())
        assertEquals(2, telemetry.completionTokensReportedRequests())
        assertEquals(1, telemetry.totalTokensReportedRequests())
        assertEquals(1, telemetry.cacheReportedRequests())
        assertEquals(2, telemetry.usageReportedRequests())
        assertEquals(2.0 / 3.0, telemetry.cacheHitRate()!!, 0.0001)
        assertNull(telemetry.calls[0].firstTokenMillis)
        assertEquals(30L, telemetry.calls[1].firstTokenMillis)
        assertEquals(GenerationCallTelemetry.OUTCOME_COMPLETED, telemetry.calls[1].outcome)
    }

    @Test
    fun `serialized telemetry contains fingerprints but no credentials prompts endpoint or output`() {
        var now = 0L
        val collector = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.one_shot.v1",
            nanoTime = { now },
        )
        val trace = collector.beginRequest(
            "chapter_generate",
            listOf(ChatMessage("user", "PROMPT-SENTINEL-9237")),
            visibleOutput = true,
        )
        now = millis(10)
        trace.onContent("OUTPUT-SENTINEL-4186")
        trace.complete()
        val encoded = Json { encodeDefaults = true }.encodeToString(
            GenerationTelemetry.serializer(),
            collector.finishCompleted(),
        )

        assertFalse(encoded.contains("API-KEY-SENTINEL"))
        assertFalse(encoded.contains("PROMPT-SENTINEL"))
        assertFalse(encoded.contains("OUTPUT-SENTINEL"))
        assertFalse(encoded.contains("private.example"))
        assertFalse(encoded.contains("userinfo-secret"))
        assertFalse(encoded.contains("query-secret"))
        assertTrue(encoded.contains("\"promptFingerprint\""))
        assertTrue(encoded.contains("\"endpointFingerprint\""))
    }

    @Test
    fun `endpoint fingerprint ignores user info query fragment default port and trailing slash`() {
        val privateUrl =
            "https://userinfo-secret:password@private.example:443/v1/?key=query-secret#fragment"
        assertEquals(
            GenerationTelemetryCollector.endpointFingerprint("https://private.example/v1"),
            GenerationTelemetryCollector.endpointFingerprint(privateUrl),
        )
    }

    @Test
    fun `failure is categorized without persisting the raw provider body`() {
        val raw = IOException("HTTP 429: PROVIDER-ERROR-SENTINEL")
        val category = GenerationTelemetryCollector.failureCategory(raw)

        assertEquals(GenerationTelemetry.FAILURE_RATE_LIMIT, category)
        assertEquals("模型服务请求过于频繁", GenerationTelemetryCollector.persistedFailureMessage(category))
        assertFalse(GenerationTelemetryCollector.persistedFailureMessage(category).contains("SENTINEL"))
    }

    @Test
    fun `budget overflow is categorized as invalid model configuration`() {
        val raw = PromptRequestBudgeter.BudgetExceededException("PROMPT-SENTINEL 超出上下文")
        val category = GenerationTelemetryCollector.failureCategory(raw)

        assertEquals(GenerationTelemetry.FAILURE_INVALID_CONFIG, category)
        assertEquals("文本模型配置无效", GenerationTelemetryCollector.persistedFailureMessage(category))
        assertFalse(GenerationTelemetryCollector.persistedFailureMessage(category).contains("SENTINEL"))
    }

    @Test
    fun `local telemetry has no provider request or first output`() {
        var now = 0L
        val collector = GenerationTelemetryCollector.local(
            "chapter.agent_local.v1",
            nanoTime = { now },
        )
        now = millis(3)
        val telemetry = collector.finishCompleted()

        assertEquals("local", telemetry.model.provider)
        assertEquals(0, telemetry.requestCount)
        assertNull(telemetry.firstOutputMillis)
        assertEquals(3L, telemetry.totalMillis)
    }

    @Test
    fun `completed snapshot does not seal collector and persistence failure can still win`() {
        var now = 0L
        val collector = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.one_shot.v1",
            nanoTime = { now },
        )
        val trace = collector.beginRequest(
            purpose = "chapter_generate",
            messages = listOf(ChatMessage("user", "正文提示")),
            visibleOutput = true,
        )
        now = millis(10)
        trace.onContent("候选正文")
        now = millis(20)
        trace.complete()

        now = millis(25)
        val successPreview = collector.completedSnapshot()
        now = millis(40)
        val failed = collector.finishFailed(GenerationTelemetry.FAILURE_PERSISTENCE)

        assertEquals(GenerationTelemetry.OUTCOME_COMPLETED, successPreview.outcome)
        assertEquals(25L, successPreview.totalMillis)
        assertEquals(GenerationTelemetry.OUTCOME_FAILED, failed.outcome)
        assertEquals(GenerationTelemetry.FAILURE_PERSISTENCE, failed.failureCategory)
        assertEquals(40L, failed.totalMillis)
        assertEquals(GenerationCallTelemetry.OUTCOME_COMPLETED, failed.calls.single().outcome)
    }

    @Test
    fun `same prompt gets a different fingerprint in each collector`() {
        val messages = listOf(
            ChatMessage("system", "固定系统提示"),
            ChatMessage("user", "固定用户提示"),
        )
        val first = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.one_shot.v1",
        )
        val second = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.one_shot.v1",
        )

        val firstTrace = first.beginRequest("chapter_generate", messages, visibleOutput = true)
        val secondTrace = second.beginRequest("chapter_generate", messages, visibleOutput = true)
        firstTrace.complete()
        secondTrace.complete()

        assertNotEquals(
            first.finishCompleted().calls.single().promptFingerprint,
            second.finishCompleted().calls.single().promptFingerprint,
        )
    }

    @Test
    fun `collector retains at most sixty four provider calls`() {
        val collector = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.stepwise.v1",
        )
        val messages = listOf(ChatMessage("user", "固定提示"))

        repeat(64) { index ->
            collector.beginRequest(
                purpose = "chapter_segment_${index + 1}",
                messages = messages,
                visibleOutput = true,
            ).complete()
        }
        val overflow = runCatching {
            collector.beginRequest(
                purpose = "chapter_segment_65",
                messages = messages,
                visibleOutput = true,
            )
        }.exceptionOrNull()
        val telemetry = collector.finishCompleted()

        assertTrue(overflow is IllegalStateException)
        assertEquals(64, telemetry.requestCount)
        assertEquals((1..64).toList(), telemetry.calls.map { it.ordinal })
    }

    @Test
    fun `empty usage frame does not erase last reported token and cache coverage`() {
        val collector = GenerationTelemetryCollector.forModel(
            config = config(),
            promptContract = "chapter.one_shot.v1",
        )
        val trace = collector.beginRequest(
            purpose = "chapter_generate",
            messages = listOf(ChatMessage("user", "正文提示")),
            visibleOutput = true,
        )
        trace.onUsage(
            StreamUsage(
                promptTokens = 120,
                completionTokens = 30,
                totalTokens = 150,
                cacheHitTokens = 90,
                cacheMissTokens = 30,
            ),
        )
        trace.onUsage(StreamUsage())
        trace.complete()

        val telemetry = collector.finishCompleted()

        assertEquals(120L, telemetry.promptTokens())
        assertEquals(30L, telemetry.completionTokens())
        assertEquals(150L, telemetry.totalTokens())
        assertEquals(1, telemetry.usageReportedRequests())
        assertEquals(1, telemetry.cacheReportedRequests())
        assertEquals(0.75, telemetry.cacheHitRate()!!, 0.0001)
    }

    private fun config() = TextModelConfig(
        provider = "deepseek",
        apiKey = "API-KEY-SENTINEL",
        apiUrl =
            "https://userinfo-secret:password@private.example:443/v1/?key=query-secret#fragment",
        model = "deepseek-chat",
        temperature = 0.25,
    )

    private fun millis(value: Long): Long = value * 1_000_000L
}
