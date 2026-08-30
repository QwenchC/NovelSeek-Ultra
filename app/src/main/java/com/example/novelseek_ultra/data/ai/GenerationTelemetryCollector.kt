package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.GenerationCallTelemetry
import com.example.novelseek_ultra.data.model.GenerationModelSnapshot
import com.example.novelseek_ultra.data.model.GenerationTelemetry
import com.example.novelseek_ultra.data.model.GenerationTokenUsage
import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelThinkingModes
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Per-run telemetry collector. It receives usage callbacks from OkHttp threads and deltas from a
 * coroutine collector, so every mutation is protected by [lock].
 *
 * Only fixed contracts, sanitized model identity, fingerprints and numeric measurements leave this
 * class. Prompts, generated prose, API keys and raw provider errors are never retained.
 */
internal class GenerationTelemetryCollector private constructor(
    private val modelSnapshot: GenerationModelSnapshot,
    private val promptContract: String,
    private val nanoTime: () -> Long,
) {
    private val lock = Any()
    private val runStartedNanos = nanoTime()
    private val promptSalt = ByteArray(PROMPT_SALT_BYTES).also(SecureRandom()::nextBytes)
    private val calls = mutableListOf<MutableCall>()
    private var firstVisibleOutputNanos: Long? = null
    private var terminalSnapshot: GenerationTelemetry? = null

    init {
        require(promptContract.isNotBlank()) { "Generation prompt contract is blank" }
    }

    /** Begin one actual provider request. Internal calls can opt out of run-level first-output time. */
    fun beginRequest(
        purpose: String,
        messages: List<ChatMessage>,
        visibleOutput: Boolean,
    ): RequestTrace = synchronized(lock) {
        check(terminalSnapshot == null) { "Generation telemetry is already final" }
        require(purpose.isNotBlank()) { "Generation request purpose is blank" }
        check(calls.size < MAX_CALLS_PER_RUN) {
            "Generation request limit exceeded"
        }
        val call = MutableCall(
            ordinal = calls.size + 1,
            purpose = purpose,
            promptFingerprint = promptFingerprint(messages),
            startedNanos = nanoTime(),
            visibleOutput = visibleOutput,
        )
        calls += call
        RequestTrace(call)
    }

    fun finishCompleted(): GenerationTelemetry =
        sealCompleted(completedSnapshot())

    /**
     * Build the success payload before the repository CAS, without irreversibly sealing this
     * collector. If persistence loses a race, the same collector can still finish as failed/cancelled.
     */
    fun completedSnapshot(): GenerationTelemetry = synchronized(lock) {
        terminalSnapshot?.let { return@synchronized it }
        check(calls.all { it.terminal }) {
            "Generation has an unfinished provider request"
        }
        buildSnapshot(
            now = nanoTime(),
            outcome = GenerationTelemetry.OUTCOME_COMPLETED,
            failureCategory = null,
        )
    }

    /** Seal a success snapshot only after the repository confirms the atomic terminal write. */
    fun sealCompleted(snapshot: GenerationTelemetry): GenerationTelemetry = synchronized(lock) {
        terminalSnapshot?.let { return@synchronized it }
        require(snapshot.outcome == GenerationTelemetry.OUTCOME_COMPLETED)
        require(snapshot.promptContract == promptContract && snapshot.model == modelSnapshot)
        require(snapshot.calls == calls.map { it.snapshot() })
        snapshot.also { terminalSnapshot = it }
    }

    fun finishFailed(category: String): GenerationTelemetry =
        finish(GenerationTelemetry.OUTCOME_FAILED, normalizeFailureCategory(category))

    fun finishCancelled(
        category: String = GenerationTelemetry.FAILURE_USER_CANCELLED,
    ): GenerationTelemetry =
        finish(GenerationTelemetry.OUTCOME_CANCELLED, normalizeFailureCategory(category))

    private fun finish(outcome: String, failureCategory: String?): GenerationTelemetry =
        synchronized(lock) {
            terminalSnapshot?.let { return@synchronized it }
            val now = nanoTime()
            calls.filterNot { it.terminal }.forEach { call ->
                call.durationMillis = elapsedMillis(call.startedNanos, now)
                call.outcome = when (outcome) {
                    GenerationTelemetry.OUTCOME_CANCELLED ->
                        GenerationCallTelemetry.OUTCOME_CANCELLED
                    else -> GenerationCallTelemetry.OUTCOME_FAILED
                }
                call.failureCategory = failureCategory
                    ?: GenerationTelemetry.FAILURE_UNKNOWN
                call.terminal = true
            }
            buildSnapshot(now, outcome, failureCategory).also { terminalSnapshot = it }
        }

    private fun buildSnapshot(
        now: Long,
        outcome: String,
        failureCategory: String?,
    ): GenerationTelemetry = GenerationTelemetry(
        model = modelSnapshot,
        promptContract = promptContract,
        calls = calls.map { it.snapshot() },
        firstOutputMillis = firstVisibleOutputNanos?.let {
            elapsedMillis(runStartedNanos, it)
        },
        totalMillis = elapsedMillis(runStartedNanos, now),
        outcome = outcome,
        failureCategory = failureCategory,
    )

    private fun promptFingerprint(messages: List<ChatMessage>): String = digest(
        buildList {
            add("generation-prompt")
            add(messages.size.toString())
            messages.forEach {
                add(it.role)
                add(it.content)
            }
        },
        prefix = promptSalt,
    )

    inner class RequestTrace internal constructor(
        private val call: MutableCall,
    ) {
        /** Record a non-empty response chunk. Whitespace-only protocol frames are not first output. */
        fun onContent(text: String) {
            if (text.isBlank()) return
            synchronized(lock) {
                if (call.terminal || terminalSnapshot != null) return
                val now = nanoTime()
                if (call.firstTokenNanos == null) call.firstTokenNanos = now
                if (call.visibleOutput && firstVisibleOutputNanos == null) {
                    firstVisibleOutputNanos = now
                }
            }
        }

        /** Keep the latest usage frame; providers may emit usage more than once in one stream. */
        fun onUsage(usage: StreamUsage) {
            synchronized(lock) {
                if (call.terminal || terminalSnapshot != null) return
                usage.toGenerationUsage()?.let { call.usage = it }
            }
        }

        fun complete() = finishCall(GenerationCallTelemetry.OUTCOME_COMPLETED, null)

        fun fail(category: String) = finishCall(
            GenerationCallTelemetry.OUTCOME_FAILED,
            normalizeFailureCategory(category),
        )

        fun cancel(
            category: String = GenerationTelemetry.FAILURE_USER_CANCELLED,
        ) = finishCall(
            GenerationCallTelemetry.OUTCOME_CANCELLED,
            normalizeFailureCategory(category),
        )

        private fun finishCall(outcome: String, category: String?) {
            synchronized(lock) {
                if (call.terminal || terminalSnapshot != null) return
                call.durationMillis = elapsedMillis(call.startedNanos, nanoTime())
                call.outcome = outcome
                call.failureCategory = category
                call.terminal = true
            }
        }
    }

    internal data class MutableCall(
        val ordinal: Int,
        val purpose: String,
        val promptFingerprint: String,
        val startedNanos: Long,
        val visibleOutput: Boolean,
        var firstTokenNanos: Long? = null,
        var durationMillis: Long = 0,
        var usage: GenerationTokenUsage? = null,
        var outcome: String = GenerationCallTelemetry.OUTCOME_COMPLETED,
        var failureCategory: String? = null,
        var terminal: Boolean = false,
    ) {
        fun snapshot(): GenerationCallTelemetry = GenerationCallTelemetry(
            ordinal = ordinal,
            purpose = purpose,
            promptFingerprint = promptFingerprint,
            firstTokenMillis = firstTokenNanos?.let {
                elapsedMillis(startedNanos, it)
            },
            durationMillis = durationMillis,
            usage = usage,
            outcome = outcome,
            failureCategory = failureCategory,
        )
    }

    companion object {
        fun forModel(
            config: TextModelConfig,
            promptContract: String,
            nanoTime: () -> Long = System::nanoTime,
        ): GenerationTelemetryCollector = GenerationTelemetryCollector(
            modelSnapshot = GenerationModelSnapshot(
                provider = providerFamily(config.provider, config.apiUrl),
                model = config.model.trim().take(MAX_MODEL_LABEL_LENGTH),
                endpointFingerprint = endpointFingerprint(config.apiUrl),
                temperature = config.temperature.takeUnless {
                    isDirectDeepSeek(config) &&
                        TextModelThinkingModes.normalize(config.thinkingMode) ==
                        TextModelThinkingModes.ENABLED
                },
                thinkingMode = TextModelThinkingModes.normalize(config.thinkingMode),
            ),
            promptContract = promptContract,
            nanoTime = nanoTime,
        )

        fun local(
            promptContract: String,
            nanoTime: () -> Long = System::nanoTime,
        ): GenerationTelemetryCollector = GenerationTelemetryCollector(
            modelSnapshot = GenerationModelSnapshot(
                provider = PROVIDER_LOCAL,
                model = "deterministic-local",
            ),
            promptContract = promptContract,
            nanoTime = nanoTime,
        )

        fun failureCategory(error: Throwable): String {
            if (error is CancellationException) return GenerationTelemetry.FAILURE_USER_CANCELLED
            if (error is PromptRequestBudgeter.BudgetExceededException) {
                return GenerationTelemetry.FAILURE_INVALID_CONFIG
            }
            val message = error.message.orEmpty()
            val lower = message.lowercase(Locale.ROOT)
            val status = HTTP_STATUS.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (status != null) {
                return when (status) {
                    401, 403 -> GenerationTelemetry.FAILURE_AUTH
                    402 -> GenerationTelemetry.FAILURE_QUOTA
                    408 -> GenerationTelemetry.FAILURE_TIMEOUT
                    429 -> GenerationTelemetry.FAILURE_RATE_LIMIT
                    in 400..499 -> GenerationTelemetry.FAILURE_PROVIDER_4XX
                    in 500..599 -> GenerationTelemetry.FAILURE_PROVIDER_5XX
                    else -> GenerationTelemetry.FAILURE_UNKNOWN
                }
            }
            return when {
                "额度不足" in message -> GenerationTelemetry.FAILURE_QUOTA
                "请求过于频繁" in message -> GenerationTelemetry.FAILURE_RATE_LIMIT
                "鉴权失败" in message -> GenerationTelemetry.FAILURE_AUTH
                "写入失败" in message || "保存失败" in message ->
                    GenerationTelemetry.FAILURE_PERSISTENCE
                "finish_reason=length" in lower || "最大输出长度" in message ||
                    "truncat" in lower -> GenerationTelemetry.FAILURE_TRUNCATED
                "content_filter" in lower || "内容安全策略" in message ->
                    GenerationTelemetry.FAILURE_CONTENT_FILTER
                "insufficient_system_resource" in lower || "资源暂时不足" in message ->
                    GenerationTelemetry.FAILURE_PROVIDER_5XX
                "empty" in lower || "空正文" in message || "空输出" in message ->
                    GenerationTelemetry.FAILURE_EMPTY_OUTPUT
                error is SocketTimeoutException || "timeout" in lower || "timed out" in lower ||
                    "超时" in message ->
                    GenerationTelemetry.FAILURE_TIMEOUT
                error is UnknownHostException || error is ConnectException ->
                    GenerationTelemetry.FAILURE_NETWORK
                "endpoint" in lower || "端点" in message || "cleartext" in lower ->
                    GenerationTelemetry.FAILURE_ENDPOINT_POLICY
                "parse" in lower || "json" in lower || "无法解析" in message ||
                    "返回格式异常" in message ||
                    "未收到 done" in lower || "非正常原因" in message ->
                    GenerationTelemetry.FAILURE_MALFORMED_RESPONSE
                error is java.io.IOException -> GenerationTelemetry.FAILURE_NETWORK
                else -> GenerationTelemetry.FAILURE_UNKNOWN
            }
        }

        /** Safe text for durable run JSON; never persist a raw provider response or exception. */
        fun persistedFailureMessage(category: String): String = when (
            normalizeFailureCategory(category)
        ) {
            GenerationTelemetry.FAILURE_AUTH -> "模型服务鉴权失败"
            GenerationTelemetry.FAILURE_QUOTA -> "模型服务额度不足"
            GenerationTelemetry.FAILURE_RATE_LIMIT -> "模型服务请求过于频繁"
            GenerationTelemetry.FAILURE_TIMEOUT -> "模型服务响应超时"
            GenerationTelemetry.FAILURE_NETWORK -> "模型服务网络连接失败"
            GenerationTelemetry.FAILURE_PROVIDER_4XX -> "模型服务拒绝了请求"
            GenerationTelemetry.FAILURE_PROVIDER_5XX -> "模型服务暂时不可用"
            GenerationTelemetry.FAILURE_MALFORMED_RESPONSE -> "模型服务返回格式异常"
            GenerationTelemetry.FAILURE_TRUNCATED -> "模型输出达到长度上限，候选稿未提交"
            GenerationTelemetry.FAILURE_CONTENT_FILTER -> "模型输出被内容策略中止"
            GenerationTelemetry.FAILURE_EMPTY_OUTPUT -> "模型返回了空正文"
            GenerationTelemetry.FAILURE_SOURCE_CONFLICT -> "章节或生成上下文已变化"
            GenerationTelemetry.FAILURE_USER_CANCELLED -> "用户停止了生成"
            GenerationTelemetry.FAILURE_SUPERSEDED -> "生成任务被新的任务替代"
            GenerationTelemetry.FAILURE_PERSISTENCE -> "生成结果未能安全写入"
            GenerationTelemetry.FAILURE_INVALID_CONFIG -> "文本模型配置无效"
            GenerationTelemetry.FAILURE_ENDPOINT_POLICY -> "模型服务端点不符合安全策略"
            else -> "模型生成失败"
        }

        internal fun endpointFingerprint(apiUrl: String): String {
            val parsed = apiUrl.trim().toHttpUrlOrNull()
            val canonical = if (parsed != null) {
                buildString {
                    append(parsed.scheme.lowercase(Locale.ROOT))
                    append("://")
                    append(parsed.host.lowercase(Locale.ROOT))
                    val defaultPort = when (parsed.scheme.lowercase(Locale.ROOT)) {
                        "http" -> 80
                        "https" -> 443
                        else -> -1
                    }
                    if (parsed.port != defaultPort) append(":${parsed.port}")
                    val path = parsed.encodedPath.trimEnd('/')
                    if (path.isNotEmpty()) append(path)
                }
            } else {
                apiUrl.trim().substringBefore('#').substringBefore('?').trimEnd('/')
            }
            return digest(listOf("generation-endpoint", canonical))
        }

        private fun providerFamily(provider: String, apiUrl: String): String {
            val normalized = provider.trim().lowercase(Locale.ROOT)
            val host = apiUrl.toHttpUrlOrNull()?.host.orEmpty().lowercase(Locale.ROOT)
            return when {
                normalized == "deepseek" || host == "deepseek.com" ||
                    host.endsWith(".deepseek.com") -> "deepseek"
                normalized == "openai" || host == "openai.com" ||
                    host.endsWith(".openai.com") -> "openai"
                normalized == "openrouter" || host == "openrouter.ai" ||
                    host.endsWith(".openrouter.ai") -> "openrouter"
                normalized == "gemini" || normalized == "google" ||
                    host.endsWith(".googleapis.com") -> "gemini"
                else -> "custom"
            }
        }

        private fun StreamUsage.toGenerationUsage(): GenerationTokenUsage? {
            if (
                promptTokens == null &&
                completionTokens == null &&
                totalTokens == null &&
                cacheHitTokens == null &&
                cacheMissTokens == null
            ) return null
            return GenerationTokenUsage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                cacheHitTokens = cacheHitTokens,
                cacheMissTokens = cacheMissTokens,
            )
        }

        private fun normalizeFailureCategory(category: String): String =
            category.takeIf { it in GenerationTelemetry.FAILURE_CATEGORIES }
                ?: GenerationTelemetry.FAILURE_UNKNOWN

        private fun digest(parts: List<String>, prefix: ByteArray? = null): String {
            val md = MessageDigest.getInstance("SHA-256")
            prefix?.let(md::update)
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                md.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                md.update(bytes)
            }
            return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }

        private val HTTP_STATUS = Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
        private const val PROVIDER_LOCAL = "local"
        private const val MAX_MODEL_LABEL_LENGTH = 128
        private const val MAX_CALLS_PER_RUN = 64
        private const val PROMPT_SALT_BYTES = 32
    }
}

private fun elapsedMillis(startNanos: Long, endNanos: Long): Long =
    ((endNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)
