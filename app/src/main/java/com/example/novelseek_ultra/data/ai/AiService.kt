package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import com.example.novelseek_ultra.data.model.TextModelThinkingModes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(val role: String, val content: String)

data class StreamUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val cacheHitTokens: Long? = null,
    val cacheMissTokens: Long? = null,
)

data class TextUsageStats(
    val requests: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheObservedRequests: Long = 0,
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
)

/**
 * OkHttp-based replacement for the PC Tauri AI invoke commands. Supports OpenAI-compatible
 * `/chat/completions` endpoints (DeepSeek / OpenAI / OpenRouter / Gemini-OpenAI-compat / custom).
 */
internal fun isDirectDeepSeek(config: TextModelConfig): Boolean {
    val host = config.apiUrl.toHttpUrlOrNull()?.host.orEmpty()
    return config.provider.trim().equals("deepseek", ignoreCase = true) ||
        host == "api.deepseek.com"
}

class AiService {

    private val usageLock = Any()
    private val _usageStats = MutableStateFlow(TextUsageStats())
    val usageStats: StateFlow<TextUsageStats> = _usageStats.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        // Never let a configured HTTPS endpoint downgrade a request (and its bearer token) to
        // cleartext through a redirect. Same-scheme redirects remain available.
        .followSslRedirects(false)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    /** Streamed chat completion: each emission is a delta `String` (a token or token chunk). */
    fun streamChat(
        config: TextModelConfig,
        messages: List<ChatMessage>,
        onUsage: (StreamUsage) -> Unit = {},
    ): Flow<StreamEvent> = callbackFlow {
        ApiEndpointPolicy.requireAllowed(config.apiUrl, config.apiKey)
        val payload = buildChatPayload(config, messages, stream = true).toString()
        val request = Request.Builder()
            .url("${config.apiUrl.trimEnd('/')}/chat/completions")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val terminated = AtomicBoolean(false)
        val sawStop = AtomicBoolean(false)
        val usageRecorded = AtomicBoolean(false)
        var latestUsage: StreamUsage? = null

        fun recordLatestUsage() {
            if (usageRecorded.compareAndSet(false, true)) {
                latestUsage?.let(::recordUsage)
            }
        }

        fun succeed() {
            if (!terminated.compareAndSet(false, true)) return
            recordLatestUsage()
            trySend(StreamEvent.Done)
            close()
        }

        fun fail(message: String, cause: Throwable? = null) {
            if (!terminated.compareAndSet(false, true)) return
            recordLatestUsage()
            // The original cause may contain an endpoint/query string or a provider response.
            // Keep it only as a nested diagnostic cause; collectors and UI see the safe message.
            val error = IOException(message, cause)
            trySend(StreamEvent.Error(message))
            // Closing exceptionally is deliberate: current collectors commit their buffer after a
            // normal completion, so an Error event alone would still allow a truncated chapter.
            close(error)
        }

        val source = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (terminated.get()) return
                val raw = data.trim()
                if (raw == "[DONE]") {
                    succeed()
                    return
                }

                parseSseError(raw, type)?.let {
                    fail(it)
                    return
                }
                val frame = parseSseFrame(raw)
                if (frame == null) {
                    fail("模型服务返回格式异常")
                    return
                }
                frame.usage?.let { usage ->
                    latestUsage = usage
                    runCatching { onUsage(usage) }
                }
                frame.delta?.takeIf { it.isNotEmpty() }?.let {
                    trySend(StreamEvent.Delta(it))
                }
                when (val reason = frame.finishReason?.takeIf { it.isNotBlank() }) {
                    null -> Unit
                    "stop" -> sawStop.set(true)
                    "length" -> fail("生成内容因达到模型最大输出长度而被截断（finish_reason=length）")
                    "content_filter" -> fail("生成内容被模型的内容安全策略中止（finish_reason=content_filter）")
                    "insufficient_system_resource" ->
                        fail("模型服务资源暂时不足（finish_reason=insufficient_system_resource）")
                    else -> fail("模型以非正常原因结束生成（finish_reason=$reason）")
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (sawStop.get()) {
                    succeed()
                    return
                }
                val msg = when {
                    response != null -> "HTTP ${response.code}: 模型服务流请求失败"
                    t is SocketTimeoutException -> "模型服务流响应超时"
                    t is UnknownHostException || t is ConnectException -> "模型服务流连接失败"
                    else -> "模型服务流连接失败"
                }
                fail(msg, t)
            }

            override fun onClosed(eventSource: EventSource) {
                if (sawStop.get()) succeed()
                else fail("流式响应提前结束：未收到 [DONE] 或 finish_reason=stop")
            }
        })

        awaitClose { source.cancel() }
    }

    /** Non-streaming chat completion — full text reply. */
    suspend fun chat(
        config: TextModelConfig,
        messages: List<ChatMessage>,
        onUsage: (StreamUsage) -> Unit = {},
    ): String {
        ApiEndpointPolicy.requireAllowed(config.apiUrl, config.apiKey)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val payload = buildChatPayload(config, messages, stream = false).toString()
            val request = Request.Builder()
                .url("${config.apiUrl.trimEnd('/')}/chat/completions")
                .apply {
                    if (config.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${config.apiKey}")
                    }
                }
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            // Per-call hard deadline — client.callTimeout stays at 0 to keep SSE streaming
            // long-lived, so the bound has to be applied here per-request. Without this the
            // call could hang forever if the provider queues / rate-limits us (Pollinations
            // does this, which is exactly how the "always generating…" bug surfaced).
            call.timeout().timeout(CHAT_ONESHOT_TIMEOUT_SEC, TimeUnit.SECONDS)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val message = when (e) {
                        is SocketTimeoutException -> "模型服务请求超时"
                        is UnknownHostException, is ConnectException -> "模型服务网络连接失败"
                        else -> "模型服务网络请求失败"
                    }
                    cont.resumeWith(Result.failure(IOException(message, e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            cont.resumeWith(
                                Result.failure(
                                    IOException("HTTP ${resp.code}: 模型服务请求失败"),
                                ),
                            )
                            return
                        }
                        runCatching {
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                            (root["usage"] as? JsonObject)?.let(::parseUsage)?.let { usage ->
                                runCatching { onUsage(usage) }
                                recordUsage(usage)
                            }
                            val choice = root["choices"]?.jsonArray?.get(0)?.jsonObject
                            val finishReason = (choice?.get("finish_reason") as? JsonPrimitive)
                                ?.contentOrNull
                            requireSuccessfulFinish(finishReason)
                            choice?.get("message")?.jsonObject
                                ?.get("content")?.jsonPrimitive?.contentOrNull
                                ?: ""
                        }.onSuccess { cont.resumeWith(Result.success(it)) }
                            .onFailure { error ->
                                // Preserve deliberate, already-sanitized finish_reason failures;
                                // only parser/shape errors should become malformed-response errors.
                                val safeError = if (error is IOException) {
                                    error
                                } else {
                                    IOException("模型服务返回格式异常", error)
                                }
                                cont.resumeWith(
                                    Result.failure(safeError),
                                )
                            }
                    }
                }
            })
        }
    }

    /**
     * Pollinations image generation — uses the NEW unified gateway at `gen.pollinations.ai`
     * which:
     *   - defaults to `zimage` (the high-quality model — old anonymous `image.pollinations.ai`
     *     defaulted to `flux` which produced visibly worse output)
     *   - requires `Authorization: Bearer <key>` for proper quotas (sk_/pk_ from
     *     https://auth.pollinations.ai/). Anonymous calls still get a response on this host
     *     but are heavily throttled
     *   - supports `enhance=true` which has the platform rewrite the prompt before generation
     *     — usually noticeably better composition / detail for short prompts
     *
     * Returns raw PNG/JPEG bytes.
     */
    suspend fun generateImage(
        prompt: String,
        width: Int,
        height: Int,
        model: String = "zimage",
        seed: Int? = null,
        nologo: Boolean = true,
        enhance: Boolean = true,
        transparent: Boolean = false,
        safe: Boolean = false,
        pollinationsKey: String? = null,
    ): ByteArray = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
        val params = buildList {
            add("width=$width")
            add("height=$height")
            add("model=$model")
            if (nologo) add("nologo=true")
            if (enhance) add("enhance=true")
            if (transparent) add("transparent=true")
            if (safe) add("safe=true")
            seed?.let { add("seed=$it") }
        }.joinToString("&")
        val url = "https://gen.pollinations.ai/image/$encoded?$params"
        val req = Request.Builder().url(url).apply {
            if (!pollinationsKey.isNullOrBlank()) addHeader("Authorization", "Bearer $pollinationsKey")
        }.build()
        val call = client.newCall(req)
        // Per-call deadline — see chat(): the client-wide callTimeout must stay at 0 so SSE
        // streaming works, but image gen MUST have a hard bound or Pollinations' queue can
        // wedge the request forever. 120s is generous (free tier usually responds in 5-30s).
        call.timeout().timeout(IMAGE_TIMEOUT_SEC, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Translate generic IO/timeout into something the user can actually act on.
                val msg = when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Pollinations 超时（${IMAGE_TIMEOUT_SEC}s）。可能是限流或队列拥堵，请稍后再试。"
                    else -> e.message.orEmpty().ifBlank { "网络异常" }
                }
                cont.resumeWith(Result.failure(IOException(msg, e)))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        // Surface Pollinations' actual body (e.g. "Rate limit exceeded") so the
                        // user sees the real reason instead of just a status code.
                        val body = runCatching { r.body?.string().orEmpty() }.getOrNull().orEmpty()
                        val detail = if (body.isNotBlank()) "：${body.take(200)}" else ""
                        cont.resumeWith(Result.failure(IOException("Pollinations HTTP ${r.code}$detail")))
                        return
                    }
                    val bytes = r.body?.bytes()
                    if (bytes == null) cont.resumeWith(Result.failure(IOException("empty image body")))
                    else cont.resumeWith(Result.success(bytes))
                }
            }
        })
    }

    // ── ComfyUI image generation ──────────────────────────────────────────────────────────
    //
    // Port of the PC `src-tauri/src/api/comfyui.rs` client. Submits the hardcoded z-image-turbo
    // workflow (`t2i-lumicreate.json`) in ComfyUI API/`prompt` format — the LoRA node (48) is
    // skipped, so UNETLoader (46) feeds ModelSamplingAuraFlow (47) directly. Flow:
    //   1. POST /prompt           → returns a prompt_id
    //   2. poll GET /history/{id} → until status.completed, reading SaveImage (node 9) outputs
    //   3. GET /view              → download the PNG bytes
    // ComfyUI runs on the local network with no auth, so there's no API key here — only a base URL
    // (default http://localhost:8188, configurable in Settings for a LAN host like 192.168.x.x).

    /** Health check — ComfyUI exposes GET /system_stats. */
    suspend fun testComfyUIConnection(baseUrl: String): Boolean = try {
        val url = "${baseUrl.trimEnd('/')}/system_stats"
        val req = Request.Builder().url(url).get().build()
        executeForString(req, COMFY_REQUEST_TIMEOUT_SEC).let { true }
    } catch (_: Throwable) {
        false
    }

    /**
     * Generate an image via ComfyUI and return the raw PNG/JPEG bytes (parity with
     * [generateImage]'s return type so callers can stay engine-agnostic).
     */
    suspend fun generateImageComfyUI(
        prompt: String,
        width: Int,
        height: Int,
        baseUrl: String,
        negativePrompt: String = COMFY_DEFAULT_NEGATIVE,
    ): ByteArray {
        val base = baseUrl.trimEnd('/')
        val promptId = submitComfyPrompt(base, prompt, negativePrompt, width, height)
        val image = pollComfyHistory(base, promptId)
        return downloadComfyImage(base, image)
    }

    private data class ComfyImageRef(val filename: String, val subfolder: String, val type: String)

    /** Build the ComfyUI API-format prompt. LoRA node (48) omitted — 46 → 47 directly. */
    private fun buildComfyPrompt(
        positive: String,
        negative: String,
        width: Int,
        height: Int,
    ): JsonObject = buildJsonObject {
        // Step 1 – Load models
        put("46", buildJsonObject {
            put("class_type", "UNETLoader")
            put("inputs", buildJsonObject {
                put("unet_name", "z_image_turbo_bf16.safetensors")
                put("weight_dtype", "default")
            })
        })
        put("39", buildJsonObject {
            put("class_type", "CLIPLoader")
            put("inputs", buildJsonObject {
                put("clip_name", "qwen_3_4b.safetensors")
                put("type", "lumina2")
                put("device", "default")
            })
        })
        put("40", buildJsonObject {
            put("class_type", "VAELoader")
            put("inputs", buildJsonObject { put("vae_name", "ae.safetensors") })
        })
        // Step 2 – Sampling config (shift for AuraFlow-style scheduling)
        put("47", buildJsonObject {
            put("class_type", "ModelSamplingAuraFlow")
            put("inputs", buildJsonObject {
                put("model", nodeRef("46", 0))
                put("shift", 3.0)
            })
        })
        // Step 3 – Prompts
        put("45", buildJsonObject {
            put("class_type", "CLIPTextEncode")
            put("inputs", buildJsonObject {
                put("clip", nodeRef("39", 0))
                put("text", positive)
            })
        })
        put("61", buildJsonObject {
            put("class_type", "CLIPTextEncode")
            put("inputs", buildJsonObject {
                put("clip", nodeRef("39", 0))
                put("text", negative)
            })
        })
        // Step 4 – Latent canvas
        put("41", buildJsonObject {
            put("class_type", "EmptySD3LatentImage")
            put("inputs", buildJsonObject {
                put("width", width)
                put("height", height)
                put("batch_size", 1)
            })
        })
        // KSampler
        put("44", buildJsonObject {
            put("class_type", "KSampler")
            put("inputs", buildJsonObject {
                put("model", nodeRef("47", 0))
                put("positive", nodeRef("45", 0))
                put("negative", nodeRef("61", 0))
                put("latent_image", nodeRef("41", 0))
                put("seed", kotlin.random.Random.nextLong(0, Long.MAX_VALUE))
                put("steps", 6)
                put("cfg", 1.0)
                put("sampler_name", "dpmpp_2m_sde_gpu")
                put("scheduler", "simple")
                put("denoise", 1.0)
            })
        })
        // Decode + Save
        put("43", buildJsonObject {
            put("class_type", "VAEDecode")
            put("inputs", buildJsonObject {
                put("samples", nodeRef("44", 0))
                put("vae", nodeRef("40", 0))
            })
        })
        put("9", buildJsonObject {
            put("class_type", "SaveImage")
            put("inputs", buildJsonObject {
                put("images", nodeRef("43", 0))
                put("filename_prefix", "novelseek")
            })
        })
    }

    private fun nodeRef(nodeId: String, slot: Int): JsonArray =
        JsonArray(listOf(JsonPrimitive(nodeId), JsonPrimitive(slot)))

    /** POST /prompt → prompt_id (surfaces ComfyUI's node-validation error if the submit fails). */
    private suspend fun submitComfyPrompt(
        base: String,
        positive: String,
        negative: String,
        width: Int,
        height: Int,
    ): String {
        val payload = buildJsonObject {
            put("prompt", buildComfyPrompt(positive, negative, width, height))
        }.toString()
        val req = Request.Builder()
            .url("$base/prompt")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = executeForString(req, COMFY_REQUEST_TIMEOUT_SEC, friendlyComfyError = true)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        return root["prompt_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("ComfyUI 未返回 prompt_id：${body.take(200)}")
    }

    /** Poll GET /history/{id} until the job completes; returns the first SaveImage output. */
    private suspend fun pollComfyHistory(base: String, promptId: String): ComfyImageRef {
        val deadline = System.currentTimeMillis() + COMFY_JOB_TIMEOUT_SEC * 1000
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException("ComfyUI 任务超时（${COMFY_JOB_TIMEOUT_SEC / 60} 分钟），请检查 ComfyUI 是否在生成。")
            }
            val req = Request.Builder().url("$base/history/$promptId").get().build()
            val body = executeForString(req, COMFY_REQUEST_TIMEOUT_SEC)
            val history = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            val entry = history[promptId]?.jsonObject
            if (entry != null) {
                val status = entry["status"]?.jsonObject
                val completed = status?.get("completed")?.jsonPrimitive?.contentOrNull == "true"
                if (completed) {
                    val statusStr = status?.get("status_str")?.jsonPrimitive?.contentOrNull ?: "success"
                    if (statusStr == "error") {
                        throw IOException(extractComfyExecError(status) ?: "ComfyUI 任务执行失败")
                    }
                    val images = entry["outputs"]?.jsonObject
                        ?.get("9")?.jsonObject
                        ?.get("images")?.jsonArray
                        ?.mapNotNull { it.jsonObject.toComfyImageRef() }
                        .orEmpty()
                    if (images.isEmpty()) {
                        throw IOException("ComfyUI 任务完成但无输出图片，请检查 SaveImage 节点（id=9）")
                    }
                    return images.first()
                }
            }
            kotlinx.coroutines.delay(COMFY_POLL_INTERVAL_MS)
        }
    }

    private fun JsonObject.toComfyImageRef(): ComfyImageRef? {
        val filename = this["filename"]?.jsonPrimitive?.contentOrNull ?: return null
        val subfolder = this["subfolder"]?.jsonPrimitive?.contentOrNull ?: ""
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: "output"
        return ComfyImageRef(filename, subfolder, type)
    }

    /** Pull the `execution_error` exception message out of the history `status.messages` array. */
    private fun extractComfyExecError(status: JsonObject?): String? {
        val messages = status?.get("messages")?.jsonArray ?: return null
        for (msg in messages) {
            val arr = (msg as? JsonArray) ?: continue
            if (arr.firstOrNull()?.jsonPrimitive?.contentOrNull != "execution_error") continue
            val details = arr.getOrNull(1)?.jsonObject ?: continue
            val nodeType = details["node_type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val exc = details["exception_message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            return "节点 $nodeType 执行失败：$exc"
        }
        return null
    }

    /** GET /view → image bytes. Retries to ride out stale keep-alive connections. */
    private suspend fun downloadComfyImage(base: String, img: ComfyImageRef): ByteArray {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$base/view?filename=${enc(img.filename)}&subfolder=${enc(img.subfolder)}&type=${enc(img.type)}"
        val req = Request.Builder().url(url).get().build()
        var lastErr: Throwable? = null
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(800L * attempt)
            try {
                return executeForBytes(req, COMFY_REQUEST_TIMEOUT_SEC)
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        throw IOException("ComfyUI /view 下载失败：${lastErr?.message}", lastErr)
    }

    /** Run a request with a per-call deadline and return the body as a String (throws on non-2xx). */
    private suspend fun executeForString(
        req: Request,
        timeoutSec: Long,
        friendlyComfyError: Boolean = false,
    ): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (!r.isSuccessful) {
                        val msg = if (friendlyComfyError) comfyValidationError(body)
                            else "ComfyUI HTTP ${r.code}：${body.take(200)}"
                        cont.resumeWith(Result.failure(IOException(msg)))
                    } else {
                        cont.resumeWith(Result.success(body))
                    }
                }
            }
        })
    }

    private suspend fun executeForBytes(
        req: Request,
        timeoutSec: Long,
    ): ByteArray = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        cont.resumeWith(Result.failure(IOException("ComfyUI HTTP ${r.code}")))
                        return
                    }
                    val bytes = r.body?.bytes()
                    if (bytes == null) cont.resumeWith(Result.failure(IOException("empty image body")))
                    else cont.resumeWith(Result.success(bytes))
                }
            }
        })
    }

    /** Turn ComfyUI's node-validation JSON into a readable message; fall back to the raw body. */
    private fun comfyValidationError(body: String): String {
        val parsed = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: return "ComfyUI /prompt 错误：${body.take(200)}"
        val mainMsg = parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        val nodeDetail = parsed["node_errors"]?.jsonObject?.values?.firstOrNull()
            ?.jsonObject?.get("errors")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("details")?.jsonPrimitive?.contentOrNull
        return when {
            mainMsg.isNullOrBlank() -> "ComfyUI /prompt 错误：${body.take(200)}"
            nodeDetail.isNullOrBlank() -> "ComfyUI 节点验证失败：$mainMsg"
            else -> "ComfyUI 节点验证失败：$mainMsg（$nodeDetail）"
        }
    }

    /** Verify credentials with a tiny non-thinking completion capped at 256 output tokens. */
    suspend fun testConnection(config: TextModelConfig): Boolean = try {
        chat(
            config = config.copy(
                temperature = 0.0,
                maxOutputTokens = 256,
                thinkingMode = if (isDirectDeepSeek(config)) {
                    TextModelThinkingModes.DISABLED
                } else {
                    config.thinkingMode
                },
            ),
            messages = listOf(ChatMessage("user", "ping")),
        ).isNotEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private fun buildChatPayload(
        config: TextModelConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        val requestConfig = TextModelRequestPolicy.normalizeForRequest(config)
        val budgeted = PromptRequestBudgeter.validate(requestConfig, messages)
        val thinkingMode = TextModelThinkingModes.normalize(requestConfig.thinkingMode)
        val isDeepSeek = isDirectDeepSeek(requestConfig)
        put("model", requestConfig.model)
        if (!(isDeepSeek && thinkingMode == TextModelThinkingModes.ENABLED)) {
            put("temperature", requestConfig.temperature)
        }
        put("max_tokens", budgeted.maxOutputTokens)
        put("stream", stream)
        if (isDeepSeek && thinkingMode != TextModelThinkingModes.AUTO) {
            put("thinking", buildJsonObject { put("type", thinkingMode) })
        }
        if (stream && supportsStreamUsage(requestConfig)) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        put("messages", JsonArray(budgeted.messages.map {
            buildJsonObject {
                put("role", it.role)
                put("content", it.content)
            }
        }))
    }

    private data class SseFrame(
        val delta: String?,
        val finishReason: String?,
        val usage: StreamUsage?,
    )

    private fun parseSseFrame(data: String): SseFrame? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull() as? JsonObject
        SseFrame(
            delta = (choice?.get("delta") as? JsonObject)
                ?.get("content")?.let { it as? JsonPrimitive }?.contentOrNull,
            finishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull,
            usage = (root["usage"] as? JsonObject)?.let(::parseUsage),
        )
    }.getOrNull()

    private fun parseSseError(data: String, eventType: String?): String? {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject
        }.getOrNull()
        val error = root?.get("error")
        if (error != null && error !is JsonNull) {
            val code = (error as? JsonObject)?.let { errorObject ->
                (errorObject["type"] as? JsonPrimitive)?.contentOrNull
                    ?: (errorObject["code"] as? JsonPrimitive)?.contentOrNull
            }
            return safeProviderError(code)
        }
        if (eventType.equals("error", ignoreCase = true)) {
            return "模型服务返回错误事件"
        }
        return null
    }

    private fun safeProviderError(code: String?): String {
        val normalized = code.orEmpty().lowercase()
        return when {
            "quota" in normalized || "balance" in normalized -> "模型服务额度不足"
            "rate" in normalized || "too_many" in normalized -> "模型服务请求过于频繁"
            "auth" in normalized || "key" in normalized || "permission" in normalized ->
                "模型服务鉴权失败"
            else -> "模型服务返回错误事件"
        }
    }

    private fun parseUsage(obj: JsonObject): StreamUsage? {
        fun long(name: String): Long? =
            (obj[name] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }
        val prompt = long("prompt_tokens") ?: long("input_tokens")
        val completion = long("completion_tokens") ?: long("output_tokens")
        val details = (obj["prompt_tokens_details"] as? JsonObject)
            ?: (obj["input_tokens_details"] as? JsonObject)
        val rawCached = long("prompt_cache_hit_tokens")
            ?: (details?.get("cached_tokens") as? JsonPrimitive)
                ?.longOrNull?.takeIf { it >= 0L }
        val rawCacheMiss = long("prompt_cache_miss_tokens")
        // A cache rate is only meaningful when hit and miss refer to the same complete prompt.
        // DeepSeek normally returns both; OpenAI returns cached tokens and lets us derive misses.
        val cachePair = when {
            rawCached != null && rawCacheMiss != null -> {
                val sumIsSafe = rawCached <= Long.MAX_VALUE - rawCacheMiss
                val sum = if (sumIsSafe) rawCached + rawCacheMiss else null
                if (sum != null && (prompt == null || sum == prompt)) {
                    rawCached to rawCacheMiss
                } else {
                    null
                }
            }
            prompt != null && rawCached != null && rawCached <= prompt ->
                rawCached to (prompt - rawCached)
            prompt != null && rawCacheMiss != null && rawCacheMiss <= prompt ->
                (prompt - rawCacheMiss) to rawCacheMiss
            else -> null
        }
        val usage = StreamUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = long("total_tokens")
                ?: if (prompt != null && completion != null) {
                    if (Long.MAX_VALUE - prompt < completion) Long.MAX_VALUE
                    else prompt + completion
                } else {
                    null
                },
            cacheHitTokens = cachePair?.first,
            cacheMissTokens = cachePair?.second,
        )
        return usage.takeIf {
            it.promptTokens != null || it.completionTokens != null || it.totalTokens != null ||
                it.cacheHitTokens != null || it.cacheMissTokens != null
        }
    }

    private fun requireSuccessfulFinish(finishReason: String?) {
        when (finishReason?.takeIf { it.isNotBlank() }) {
            null, "stop" -> Unit
            "length" -> throw IOException(
                "生成内容因达到模型最大输出长度而被截断（finish_reason=length）",
            )
            "content_filter" -> throw IOException(
                "生成内容被模型的内容安全策略中止（finish_reason=content_filter）",
            )
            "insufficient_system_resource" -> throw IOException(
                "模型服务资源暂时不足（finish_reason=insufficient_system_resource）",
            )
            else -> throw IOException("模型以非正常原因结束生成")
        }
    }

    private fun recordUsage(usage: StreamUsage) {
        synchronized(usageLock) {
            val current = _usageStats.value
            val observedCache = usage.cacheHitTokens != null && usage.cacheMissTokens != null
            _usageStats.value = current.copy(
                requests = saturatedAdd(current.requests, 1),
                promptTokens = saturatedAdd(current.promptTokens, usage.promptTokens ?: 0),
                completionTokens = saturatedAdd(
                    current.completionTokens,
                    usage.completionTokens ?: 0,
                ),
                cacheObservedRequests = saturatedAdd(
                    current.cacheObservedRequests,
                    if (observedCache) 1 else 0,
                ),
                cacheHitTokens = saturatedAdd(
                    current.cacheHitTokens,
                    if (observedCache) usage.cacheHitTokens ?: 0 else 0,
                ),
                cacheMissTokens = saturatedAdd(
                    current.cacheMissTokens,
                    if (observedCache) usage.cacheMissTokens ?: 0 else 0,
                ),
            )
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun supportsStreamUsage(config: TextModelConfig): Boolean {
        val host = config.apiUrl.toHttpUrlOrNull()?.host.orEmpty()
        return isDirectDeepSeek(config) ||
            config.provider.trim().equals("openai", ignoreCase = true) ||
            host == "openai.com" || host.endsWith(".openai.com")
    }

    sealed class StreamEvent {
        data class Delta(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }

    private companion object {
        // Per-call hard deadlines applied via `call.timeout()` (NOT client.callTimeout — that
        // would also kill long-lived SSE streams). Generous enough for slow providers, short
        // enough that a hung request surfaces an error instead of an infinite spinner.
        const val CHAT_ONESHOT_TIMEOUT_SEC = 120L
        const val IMAGE_TIMEOUT_SEC = 120L
        const val EMBED_TIMEOUT_SEC = 60L

        // ComfyUI: each individual HTTP call (submit / poll / view) gets this bound; the overall
        // job can take much longer, so it's gated by COMFY_JOB_TIMEOUT_SEC + a 1.5s poll interval.
        const val COMFY_REQUEST_TIMEOUT_SEC = 60L
        const val COMFY_JOB_TIMEOUT_SEC = 300L
        const val COMFY_POLL_INTERVAL_MS = 1500L
        const val COMFY_DEFAULT_NEGATIVE =
            "low quality, worst quality, deformed, mutated hands, mutated fingers, " +
                "extra limbs, missing arms, signature, watermark, username, logo"
    }
}
